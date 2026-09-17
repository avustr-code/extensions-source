package eu.kanade.tachiyomi.extension.en.comick

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.utils.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Comick :
    KeiSource(),
    ConfigurableSource {

    override val name = "Comick"
    override val baseUrl = "https://comick.dev"
    private val apiBase = "https://api.comick.dev"
    private val proxyApi = "https://comick-api-proxy.notaspider.dev/api"

    private val preferences: SharedPreferences = getPreferences()

    private fun effectiveBaseUrl(): String {
        val custom = preferences.getString(PREF_CUSTOM_BASEURL, null)?.trim()
        return if (!custom.isNullOrBlank()) custom.trimEnd('/') else baseUrl
    }

    private fun effectiveApiBase(): String {
        val custom = preferences.getString(PREF_CUSTOM_BASEURL, null)?.trim()
        return if (!custom.isNullOrBlank()) {
            // if custom is full comic site, api still separate; keep canonical api
            apiBase
        } else {
            apiBase
        }
    }

    override fun okhttp3.OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3, 1.seconds) { it.host == apiBase.toHttpUrl().host }
        rateLimit(2, 1.seconds) { it.host == proxyApi.toHttpUrl().host }
        rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = searchInternal(page, "", FilterList(), sortOverride = "user_follow_count")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val raw = mutableListOf<ComickBrowseItem>()
        val base = searchInternal(page, "", FilterList(), sortOverride = "uploaded", outItems = raw)
        val result = ArrayList<SManga>()
        val emitted = HashSet<String>()
        raw.forEachIndexed { idx, item ->
            if (!emitted.add(item.hid)) return@forEachIndexed
            val lc = item.lastChapter
            if (lc != null) {
                val prev = latestSeen[item.hid]
                if (prev != null && lc <= prev + 0.001f) return@forEachIndexed
                latestSeen[item.hid] = lc
            }
            base.mangas.getOrNull(idx)?.let(result::add)
        }
        return if (result.isNotEmpty() || base.mangas.isEmpty()) MangasPage(result, base.hasNextPage) else base
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = searchInternal(page, query, filters, sortOverride = null)

    private val latestSeen = java.util.concurrent.ConcurrentHashMap<String, Float>()

    private suspend fun searchInternal(page: Int, query: String, filters: FilterList, sortOverride: String? = null, outItems: MutableList<ComickBrowseItem>? = null): MangasPage {
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val sortValue = sortOverride ?: sortFilter?.apiValue ?: "user_follow_count"

        val demographicFilter = filters.filterIsInstance<DemographicFilter>().firstOrNull()
        val contentRatingFilter = filters.filterIsInstance<ContentRatingFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val genreText = filters.filterIsInstance<GenreTextFilter>().firstOrNull()?.state?.toString()
        val tagText = filters.filterIsInstance<TagTextFilter>().firstOrNull()?.state?.toString()
        val yearFrom = filters.filterIsInstance<YearFromFilter>().firstOrNull()?.selectedValue
        val yearTo = filters.filterIsInstance<YearToFilter>().firstOrNull()?.selectedValue
        val ignoredTags = preferences.getString(PREF_IGNORED_TAGS, "")
            ?.split("\n")
            ?.map { it.trim().lowercase().replace(Regex("[ /]"), "-").removePrefix("-") }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val url = effectiveApiBase().let { "$it/v1.0/search".toHttpUrl().newBuilder() }.apply {
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("tachiyomi", "true")
            addQueryParameter("sort", sortValue)
            if (query.isNotBlank()) {
                if (query.trim().length < 2) throw Exception("Query must be at least 2 characters")
                addQueryParameter("q", query.trim())
            }
            demographicFilter?.checkedValues?.forEach { addQueryParameter("demographic", it) }
            contentRatingFilter?.selectedValue?.let { addQueryParameter("content_rating", it) }
            statusFilter?.selectedValue?.let { addQueryParameter("status", it) }
            typeFilter?.checkedValues?.forEach { addQueryParameter("country", it) }
            yearFrom?.let { if (it.isNotEmpty()) addQueryParameter("from", it) }
            yearTo?.let { if (it.isNotEmpty()) addQueryParameter("to", it) }
            genreText?.let { txt ->
                txt.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                    val v = it.lowercase().replace(Regex("[ /]"), "-")
                    if (v.startsWith("-")) addQueryParameter("excludes", v.removePrefix("-")) else addQueryParameter("genres", v)
                }
            }
            tagText?.let { txt ->
                txt.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                    val v = it.lowercase().replace(Regex("[ /]"), "-")
                    if (v.startsWith("-")) addQueryParameter("excluded_tags", v.removePrefix("-")) else addQueryParameter("tags", v)
                }
            }
            ignoredTags.forEach { v ->
                addQueryParameter("excludes", v)
                addQueryParameter("excluded_tags", v)
            }
        }.build()

        val response = client.get(url)
        val list = response.parseAs<List<ComickBrowseItem>>()

        val allowedRatings = when (preferences.getString(PREF_CONTENT_LEVEL, "suggestive")) {
            "safe" -> setOf("safe")
            "erotica" -> setOf("safe", "suggestive", "erotica")
            "pornographic" -> setOf("safe", "suggestive", "erotica", "pornographic")
            else -> setOf("safe", "suggestive")
        }
        val filteredItems = list.filter { item ->
            item.contentRating == null || item.contentRating in allowedRatings
        }
        outItems?.addAll(filteredItems)
        val filtered = filteredItems.map { it.toSManga() }

        val hasNext = list.size >= PAGE_LIMIT
        return MangasPage(filtered, hasNext)
    }

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.isEmpty()) return null
        // expecting /comic/{slug}
        if (segments[0] == "comic" && segments.size >= 2) {
            val slug = segments[1]
            val tmp = SManga.create().apply {
                this.url = "$slug|"
                this.title = slug
            }
            return try {
                fetchMangaUpdate(tmp, emptyList(), fetchDetails = true, fetchChapters = false).manga
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.substringBefore("|").substringBefore("#")
        return if (slug.isNotBlank()) "${effectiveBaseUrl()}/comic/$slug" else super.getMangaUrl(manga)
    }

    override fun getChapterUrl(chapter: SChapter): String = effectiveBaseUrl() + chapter.url

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (slug, hid) = parseMangaUrl(manga.url)
        var updatedManga = manga
        var chapterList: List<SChapter> = chapters

        if (fetchDetails) {
            updatedManga = fetchDetails(slug, hid, manga)
        }
        if (fetchChapters) {
            chapterList = fetchChapters(slug, hid)
        }
        return SMangaUpdate(updatedManga, chapterList)
    }

    private fun parseMangaUrl(url: String): Pair<String, String> = if (url.contains("|")) {
        val parts = url.split("|", limit = 2)
        parts[0] to parts[1]
    } else {
        url to ""
    }

    private fun extractNote(parsedHtml: String?): String? {
        if (parsedHtml.isNullOrBlank()) return null
        val idx = parsedHtml.indexOf("<hr")
        if (idx < 0) return null
        val tail = parsedHtml.substring(idx)
        if (!tail.contains("Note:", ignoreCase = true)) return null
        return org.jsoup.Jsoup.parseBodyFragment(tail).text().trim().ifEmpty { null }
    }

    private suspend fun fetchDetails(slug: String, hid: String, fallback: SManga): SManga {
        // Try proxy API first (faster; the HTML page is normally behind Cloudflare)
        if (hid.isNotBlank()) {
            try {
                val apiUrl = "$proxyApi/comic/$hid".toHttpUrl()
                client.get(apiUrl).use { resp ->
                    val root = resp.parseAs<ComicDetailRoot>()
                    val c = root.comic
                    return SManga.create().apply {
                        url = "${c.slug}|${c.hid}"
                        title = c.mdTitles.firstOrNull { it.lang == "en" && it.isDefault == true }?.title
                            ?: c.mdTitles.firstOrNull { it.lang == "en" }?.title
                            ?: c.title
                        thumbnail_url = if (preferences.getBoolean(PREF_UPDATE_COVER, false)) {
                            c.mdCovers?.firstOrNull()?.let { "https://meo.comick.pictures/${it.b2key}" } ?: fallback.thumbnail_url
                        } else {
                            fallback.thumbnail_url ?: c.mdCovers?.firstOrNull()?.let { "https://meo.comick.pictures/${it.b2key}" }
                        }
                        status = when (c.status) {
                            1 -> SManga.ONGOING
                            2 -> if (c.translationCompleted == true) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
                            3 -> SManga.CANCELLED
                            4 -> SManga.ON_HIATUS
                            else -> SManga.UNKNOWN
                        }
                        author = root.authors.joinToString { it.name }
                        artist = root.artists.joinToString { it.name }
                        description = buildString {
                            val ratingVal = c.bayesianRating ?: c.rating
                            val ratingLine = if (ratingVal != null) "★ $ratingVal" + (c.ratingCount?.let { " ($it votes)" } ?: "") else null
                            val yearLine = c.year?.toString()?.let { "Year: $it" }
                            if (ratingLine != null || yearLine != null) {
                                append(listOfNotNull(yearLine, ratingLine).joinToString(" • "))
                                append("\n\n")
                            }
                            c.desc?.let { append(org.jsoup.Jsoup.parseBodyFragment(it).wholeText().replace(Regex("\\s+"), " ").trim()) }
                            extractNote(c.parsed)?.let {
                                append("\n\n———\n\n")
                                append(it)
                            }
                            if (c.mdTitles.isNotEmpty()) {
                                append("\n\nAlternative Titles:\n")
                                c.mdTitles.forEach { append("- ${it.title}\n") }
                            }
                        }.trim()
                        genre = buildList {
                            when (c.country) {
                                "jp" -> add("Manga")
                                "ko", "kr" -> add("Manhwa")
                                "cn" -> add("Manhua")
                            }
                            c.contentRating?.let { add("Content: $it") }
                            addAll(c.mdGenres.mapNotNull { it.mdGenres?.name })
                            if (preferences.getString(PREF_TAG_DISPLAY, "full") == "full") {
                                addAll(muTagTitles(c.muComics))
                            }
                        }.distinct().joinToString()
                        initialized = true
                    }
                }
            } catch (_: Exception) { }
        }

        // Fallback to HTML #comic-data (canonical)
        try {
            val htmlUrl = "${effectiveBaseUrl()}/comic/$slug"
            client.get(htmlUrl.toHttpUrl()).use { resp ->
                if (resp.isSuccessful) {
                    val doc = resp.asJsoup()
                    val dataEl = doc.selectFirst("#comic-data")
                    if (dataEl != null) {
                        val json = dataEl.data()
                        val data = try {
                            json.parseAs<HtmlComicData>()
                        } catch (_: Exception) {
                            null
                        }
                        if (data != null) {
                            val htmlManga = SManga.create().apply {
                                url = "$slug|${hid.ifEmpty { slug }}"
                                title = data.titles.firstOrNull { it.lang == "en" && it.isDefault == true }?.title
                                    ?: data.titles.firstOrNull { it.lang == "en" }?.title
                                    ?: data.title
                                thumbnail_url = data.thumbnail ?: fallback.thumbnail_url
                                status = when (data.status) {
                                    1 -> SManga.ONGOING
                                    2 -> if (data.translationCompleted) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
                                    3 -> SManga.CANCELLED
                                    4 -> SManga.ON_HIATUS
                                    else -> SManga.UNKNOWN
                                }
                                author = data.authors.joinToString { it.name }
                                artist = data.artists.joinToString { it.name }
                                description = buildString {
                                    val ratingVal = data.bayesianRating ?: data.rating
                                    val ratingLine = if (ratingVal != null) "★ $ratingVal" + (data.ratingCount?.let { " ($it votes)" } ?: "") else null
                                    val yearLine = data.year?.toString()?.let { "Year: $it" }
                                    if (ratingLine != null || yearLine != null) {
                                        append(listOfNotNull(yearLine, ratingLine).joinToString(" • "))
                                        append("\n\n")
                                    }
                                    data.desc?.let {
                                        append(
                                            org.jsoup.Jsoup.parseBodyFragment(it).wholeText()
                                                .replace(Regex("\\s+"), " ")
                                                .trim(),
                                        )
                                    }
                                    extractNote(data.parsed)?.let {
                                        append("\n\n———\n\n")
                                        append(it)
                                    }
                                    if (data.titles.isNotEmpty()) {
                                        append("\n\nAlternative Titles:\n")
                                        data.titles.forEach { append("- ${it.title.trim()}\n") }
                                    }
                                }.trim()
                                genre = buildList {
                                    when (data.country) {
                                        "jp" -> add("Manga")
                                        "kr" -> add("Manhwa")
                                        "cn" -> add("Manhua")
                                    }
                                    data.contentRating?.let { add("Content: $it") }
                                    addAll(data.genres.mapNotNull { it.mdGenres?.name })
                                }.distinct().joinToString()
                                initialized = true
                            }
                            // Site-style tags live in MU categories, not in #comic-data — enrich via proxy
                            if (preferences.getString(PREF_TAG_DISPLAY, "full") == "full") {
                                val muTags = fetchMuTags(data.slug.ifEmpty { hid.ifEmpty { slug } })
                                if (muTags.isNotEmpty()) {
                                    htmlManga.genre = listOfNotNull(htmlManga.genre?.takeIf { it.isNotBlank() }, muTags.joinToString())
                                        .filter { it.isNotBlank() }
                                        .joinToString()
                                }
                            }
                            if (!preferences.getBoolean(PREF_UPDATE_COVER, false)) {
                                htmlManga.thumbnail_url = fallback.thumbnail_url ?: htmlManga.thumbnail_url
                            }
                            return htmlManga
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // As last resort try search result cache: fetch via search by slug
        try {
            val searchUrl = "${effectiveApiBase()}/v1.0/search?limit=5&q=$slug&tachiyomi=true".toHttpUrl()
            client.get(searchUrl).use { resp ->
                val list = resp.parseAs<List<ComickBrowseItem>>()
                val found = list.firstOrNull { it.slug == slug || it.hid == hid }
                if (found != null) {
                    return SManga.create().apply {
                        url = "${found.slug}|${found.hid}"
                        title = found.mdTitles.firstOrNull { it.lang == "en" && it.isDefault == true }?.title
                            ?: found.mdTitles.firstOrNull { it.lang == "en" }?.title
                            ?: found.title
                        val firstCover = found.mdCovers?.firstOrNull()?.let { "https://meo.comick.pictures/${it.b2key}" }
                        thumbnail_url = if (preferences.getBoolean(PREF_UPDATE_COVER, false)) {
                            found.coverUrl ?: firstCover
                        } else {
                            fallback.thumbnail_url ?: found.coverUrl ?: firstCover
                        }
                        description = found.desc
                        status = when (found.status) {
                            1 -> SManga.ONGOING
                            2 -> SManga.COMPLETED
                            3 -> SManga.CANCELLED
                            4 -> SManga.ON_HIATUS
                            else -> SManga.UNKNOWN
                        }
                        initialized = true
                    }
                }
            }
        } catch (_: Exception) { }

        return fallback.apply { initialized = true }
    }

    private fun muTagTitles(muComics: MuComics?): List<String> = muComics?.muComicCategories
        ?.filter { it.muCategories != null && it.positiveVote >= it.negativeVote }
        ?.sortedByDescending { it.positiveVote - it.negativeVote }
        ?.mapNotNull { it.muCategories?.title }
        .orEmpty()

    private suspend fun fetchMuTags(targetHid: String): List<String> = try {
        client.get("$proxyApi/comic/$targetHid".toHttpUrl()).use { resp ->
            if (!resp.isSuccessful) emptyList() else muTagTitles(resp.parseAs<ComicDetailRoot>().comic.muComics)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun resolveHid(slug: String): String? {
        if (slug.isBlank()) return null
        preferences.getString("hid_cache_$slug", null)?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val url = "${effectiveApiBase()}/v1.0/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", slug)
                .addQueryParameter("limit", "5")
                .addQueryParameter("tachiyomi", "true")
                .build()
            client.get(url).use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    resp.parseAs<List<ComickBrowseItem>>()
                        .firstOrNull { it.slug == slug }?.hid
                        ?.also { resolved -> preferences.edit().putString("hid_cache_$slug", resolved).apply() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchChapters(slug: String, hid: String): List<SChapter> {
        val targetHid = hid.ifEmpty { resolveHid(slug).orEmpty() }
        // Try proxy API first
        val chapters = mutableListOf<ComickChapter>()
        var page = 1
        try {
            while (true) {
                val url = "$proxyApi/comic/$targetHid/chapters?lang=en&limit=100&page=$page".toHttpUrl()
                client.get(url).use { resp ->
                    if (!resp.isSuccessful) break
                    val data = resp.parseAs<ChapterListResponse>()
                    if (data.chapters.isEmpty()) break
                    chapters.addAll(data.chapters)
                    if (data.chapters.size < 100) break
                    page++
                    if (page > 20) break
                }
            }
        } catch (_: Exception) { }

        // If proxy gave results, map (dedup per chap if enabled — 15 duplicate from other group won't bump as latest)
        if (chapters.isNotEmpty()) {
            return chapters.map { ch ->
                SChapter.create().apply {
                    url = "/comic/$slug/${ch.hid}-chapter-${ch.chap ?: "0"}-${ch.lang ?: "en"}"
                    name = buildString {
                        ch.vol?.takeIf { it.isNotBlank() }?.let { append("Vol. $it ") }
                        append("Ch. ${ch.chap ?: "?"}")
                        ch.title?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                    }
                    date_upload = (ch.publishAt ?: ch.createdAt)?.tryParseDate() ?: 0L
                    scanlator = ch.groupName.joinToString()
                    chapter_number = ch.chap?.toFloatOrNull() ?: -1f
                }
            }
        }

        // Fallback: try direct api (maybe without proxy)
        try {
            val apiUrl = "${effectiveApiBase()}/comic/$targetHid/chapters?lang=en&limit=100".toHttpUrl()
            client.get(apiUrl).use { resp ->
                if (resp.isSuccessful) {
                    val data = resp.parseAs<ChapterListResponse>()
                    if (data.chapters.isNotEmpty()) {
                        return data.chapters.map { c ->
                            SChapter.create().apply {
                                this.url = "/comic/$slug/${c.hid}-chapter-${c.chap ?: "0"}-${c.lang ?: "en"}"
                                name = buildString {
                                    c.vol?.takeIf { it.isNotBlank() }?.let { append("Vol. $it ") }
                                    append("Ch. ${c.chap ?: "?"}")
                                    c.title?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                                }
                                date_upload = (c.publishAt ?: c.createdAt)?.tryParseDate() ?: 0L
                                scanlator = c.groupName.joinToString()
                                chapter_number = c.chap?.toFloatOrNull() ?: -1f
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return emptyList()
    }

    // ISO 8601 offset parser — handles every observed shape ('.SSS'Z'', '.SSSSSS'Z'', 'Z', '.SSS'+01:00'', '+01:00') regardless of fraction digit count
    private fun String.tryParseDate(): Long = runCatching {
        val normalized = if (endsWith("Z")) dropLast(1) + "+00:00" else this
        java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Try HTML #sv-data first
        try {
            val fullUrl = effectiveBaseUrl() + chapter.url
            client.get(fullUrl.toHttpUrl()).use { resp ->
                if (resp.isSuccessful) {
                    val doc = resp.asJsoup()
                    val el = doc.selectFirst("#sv-data")
                    if (el != null) {
                        val json = el.data()
                        val data = json.parseAs<HtmlPageData>()
                        val dataSaver = preferences.getBoolean(PREF_DATA_SAVER, false)
                        return data.chapter.images.mapIndexed { idx, img ->
                            var url = img.url
                            // Data saver: prefer compressed? html urls already optimized; if datasaver, replace with smaller variant if exists
                            if (dataSaver && url.contains("meo.comick.pictures")) {
                                // keep as is, server already provides optimized; optionally add query
                            }
                            Page(idx, imageUrl = url)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        // Fallback to API chapter detail md_images
        try {
            val hid = chapter.url.substringAfter("/").substringAfter("-chapter-").let {
                // url = /comic/slug/HID-chapter-5-en -> extract HID
                chapter.url.substringAfter("/comic/").substringAfter("/").substringBefore("-chapter-")
            }
            // alternative extraction
            val chapterHid = chapter.url.substringAfterLast("/").substringBefore("-chapter-")
            val apiUrl = "$proxyApi/chapter/$chapterHid".toHttpUrl()
            client.get(apiUrl).use { resp ->
                if (resp.isSuccessful) {
                    val root = resp.parseAs<ChapterDetailRoot>()
                    val images = root.chapter.mdImages
                    if (images.isNotEmpty()) {
                        val useDataSaver = preferences.getBoolean(PREF_DATA_SAVER, false)
                        // Probe once per chapter: -s.jpg variant may 404 (e.g. News chapters)
                        var dsWorks = false
                        if (useDataSaver) {
                            val probeKey = images.first().b2key.substringBeforeLast(".")
                            dsWorks = try {
                                client.newCall(
                                    Request.Builder()
                                        .head()
                                        .url("https://meo.comick.pictures/$probeKey-s.jpg")
                                        .build(),
                                ).execute().use { it.isSuccessful }
                            } catch (_: Exception) {
                                false
                            }
                        }
                        return images.mapIndexed { idx, img ->
                            val url = if (dsWorks) "https://meo.comick.pictures/${img.b2key.substringBeforeLast(".")}-s.jpg" else "https://meo.comick.pictures/${img.b2key}"
                            Page(idx, imageUrl = url)
                        }
                    }
                }
            }
            // try direct api
            val directUrl = "${effectiveApiBase()}/chapter/$chapterHid".toHttpUrl()
            client.get(directUrl).use { resp ->
                if (resp.isSuccessful) {
                    val root = resp.parseAs<ChapterDetailRoot>()
                    val images = root.chapter.mdImages
                    if (images.isNotEmpty()) {
                        return images.mapIndexed { idx, img -> Page(idx, imageUrl = "https://meo.comick.pictures/${img.b2key}") }
                    }
                }
            }
        } catch (_: Exception) { }

        return emptyList()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Comick.dev — canonical (only en)"),
        Filter.Separator(),
        SortFilter(),
        Filter.Separator(),
        DemographicFilter(),
        TypeFilter(),
        StatusFilter(),
        ContentRatingFilter(),
        Filter.Separator(),
        GenreTextFilter(),
        TagTextFilter(),
        Filter.Separator(),
        YearFromFilter(),
        YearToFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_CONTENT_LEVEL
            title = "Content rating"
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf("safe", "suggestive", "erotica", "pornographic")
            summary = "%s"
            setDefaultValue("suggestive")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DATA_SAVER
            title = "Data Saver"
            summaryOn = "Compressed images"
            summaryOff = "Original quality"
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_UPDATE_COVER
            title = "Update covers"
            summaryOn = "Covers follow site changes"
            summaryOff = "First cover kept"
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_IGNORED_TAGS
            title = "Ignored Tags"
            summary = "Manga with these tags won't show up when browsing"
            dialogTitle = "Ignored Tags"
            dialogMessage = "One tag per line (case-insensitive)"
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.setSingleLine(false)
                editText.setMinLines(4)
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TAG_DISPLAY
            title = "Tag display"
            entries = arrayOf("Kısa", "Tam")
            entryValues = arrayOf("short", "full")
            summary = "%s"
            setDefaultValue("full")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_CUSTOM_BASEURL
            title = "Custom base URL"
            summary = "Empty = comick.dev"
            setDefaultValue("")
            dialogTitle = "Custom base URL"
        }.also(screen::addPreference)
    }

    companion object {
        const val PAGE_LIMIT = 50
        const val PREF_CONTENT_LEVEL = "pref_content_level"
        const val PREF_DATA_SAVER = "pref_data_saver"
        const val PREF_UPDATE_COVER = "pref_update_cover"
        const val PREF_IGNORED_TAGS = "pref_ignored_tags"

        const val PREF_TAG_DISPLAY = "pref_tag_display"
        const val PREF_CUSTOM_BASEURL = "pref_custom_baseurl"
    }
}
