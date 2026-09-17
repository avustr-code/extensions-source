package eu.kanade.tachiyomi.extension.ko.goodtoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Goodtoon :
    KeiSource(),
    ConfigurableSource {

    companion object {
        const val PREF_TELEGRAM_URL = "telegram_update_url"
        const val PREF_SHOW_NSFW = "show_nsfw"
        const val PREF_BLOCKED_GENRES = "blocked_genres"
        const val DEFAULT_TELEGRAM_URL = "https://t.me/s/goodtoon_url"

        private const val MAX_AJAX_PAGES = 5
        private const val CHAPTERS_PER_REQUEST = 100
    }

    private val xhrHeaders: Headers
        get() = headersBuilder().set("X-Requested-With", "XMLHttpRequest").build()

    private val adultGenres = setOf(
        "성인", "BL", "GL", "하드코어", "조교", "고수위", "능욕",
        "하렘", "강제", "3P", "후방주의", "백합", "유부녀", "노벨피아",
    )

    private val allGenresForBlocking = listOf(
        "성인", "BL", "GL", "하드코어", "조교", "고수위", "능욕",
        "하렘", "강제", "3P", "후방주의", "백합", "유부녀", "노벨피아",
        "학원", "액션", "SF", "스토리", "판타지", "개그",
        "연애", "드라마", "로맨스", "시대극", "스포츠", "일상",
        "추리", "공포", "옴니버스", "에피소드", "무협",
        "소년", "기타",
    )

    private val preferences: SharedPreferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage {
        // /recommend/ (popular) is a single curated page on the site, no pagination
        if (page > 1) return MangasPage(emptyList(), hasNextPage = false)
        return client.get("$baseUrl/recommend/".toHttpUrl()).asJsoup().let { parseMangaList(it, page) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("pg", page.toString())
            .build()
        return client.get(url).asJsoup().let { parseMangaList(it, page) }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("pg", page.toString())
            .build()
        return client.get(url).asJsoup().let { parseMangaList(it, page) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val doc = client.get(url).asJsoup()
        return parseMangaDetail(doc, url)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = if (fetchDetails || fetchChapters) {
            client.get(manga.url.toHttpUrl()).asJsoup()
        } else {
            null
        }
        val updatedManga = if (fetchDetails && doc != null) {
            parseMangaDetail(doc, manga.url.toHttpUrl())
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters && doc != null) {
            fetchChapters(manga.url)
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * The manga page only renders a loading placeholder for the chapter list;
     * the real list is fetched from the theme's <manga>/ajax/chapters/ endpoint
     * (see wp_manga_load_chapters in madara-core's script.js).
     */
    private suspend fun fetchChapters(mangaUrl: String): List<SChapter> {
        val url = "${mangaUrl.trimEnd('/')}/ajax/chapters/?t=1".toHttpUrl()
        return try {
            client.get(url).asJsoup().let(::parseChapterList)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(chapter.url.toHttpUrl()).asJsoup()
        return parsePageList(doc)
    }

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        val nsfwPref = SwitchPreferenceCompat(context).apply {
            key = PREF_SHOW_NSFW
            title = "성인 콘텐츠 표시"
            summary = "19금/성인 웹툰을 목록에 표시할지 선택 (기본: 숨김)"
            setDefaultValue(false)
        }
        screen.addPreference(nsfwPref)

        val genreBlockPref = MultiSelectListPreference(context).apply {
            key = PREF_BLOCKED_GENRES
            title = "차단할 장르"
            summary = "선택한 장르의 작품을 목록에서 숨김"
            entries = allGenresForBlocking.toTypedArray()
            entryValues = allGenresForBlocking.toTypedArray()
            val blocked = preferences.getStringSet(PREF_BLOCKED_GENRES, emptySet())?.toMutableSet() ?: mutableSetOf()
            values = blocked
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                val selected = newValue as Set<String>
                preferences.edit().putStringSet(PREF_BLOCKED_GENRES, selected).apply()
                this.summary = selected.joinToString()
                true
            }
            this.summary = blocked.joinToString()
            setDefaultValue(emptySet<String>())
        }
        screen.addPreference(genreBlockPref)

        val telegramPref = EditTextPreference(context).apply {
            key = PREF_TELEGRAM_URL
            title = "Telegram 업데이트 채널"
            summary = "사이트 주소 변경 알림 채널 URL"
            dialogTitle = "Telegram 채널 URL"
            setText(preferences.getString(PREF_TELEGRAM_URL, DEFAULT_TELEGRAM_URL))
        }
        screen.addPreference(telegramPref)
    }

    private fun parseMangaList(doc: Document, page: Int): MangasPage {
        val items = doc.select(".card-grid a.card")
        val mangas = mutableListOf<SManga>()

        val showNsfw = preferences.getBoolean(PREF_SHOW_NSFW, false)
        val blockedGenres = preferences.getStringSet(PREF_BLOCKED_GENRES, emptySet()) ?: emptySet()

        for (item in items) {
            val manga = parseMangaFromItem(item, showNsfw, blockedGenres) ?: continue
            mangas.add(manga)
        }

        // Pagination: current page link + next page link with '다음'
        val current = doc.select(".pagination .current").text().trim().toIntOrNull() ?: 1
        val totalPages = doc.select(".pagination .page-numbers").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: 1
        val hasNext = current < totalPages

        return MangasPage(mangas, hasNextPage = hasNext)
    }

    private fun parseMangaFromItem(
        item: Element,
        showNsfw: Boolean,
        blockedGenres: Set<String>,
    ): SManga? {
        // item is the <a class="card"> element
        val url = item.absUrl("href")
        if (!url.contains("/manga/")) return null

        val title = item.select(".subject").first()?.text()?.trim() ?: return null

        // Cover image: .thumb contains platform-icon first, then cover — take the last img
        val coverUrl = item.select(".thumb img")
            .filterNot { it.hasClass("platform-icon") }
            .lastOrNull()
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.let { if (it.startsWith("http")) it else "${baseUrl.trimEnd('/')}${if (it.startsWith("/")) it else "/$it"}" }
            ?: ""

        // Genres: .genre text split by '/'
        val genreText = item.select(".genre").first()?.text() ?: ""
        val genreList = genreText.split("/").map { it.trim() }.filter { it.isNotEmpty() }

        // Adult check: has .adult-badge or genre in adult list
        val isAdult = item.select(".adult-badge").isNotEmpty() || genreList.any { it in adultGenres }
        if (isAdult && !showNsfw) return null
        if (genreList.any { it in blockedGenres }) return null

        return SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = coverUrl
        }
    }

    private fun parseMangaDetail(doc: Document, url: HttpUrl): SManga {
        val title = doc.select("h1.summary-title").first()?.text()?.trim() ?: "Unknown"
        val coverUrl = doc.select(".manga-summary-cover img").first()
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.let { if (it.startsWith("http")) it else "${url.scheme}://${url.host}${if (it.startsWith("/")) it else "/$it"}" }
            ?: ""

        // Status: extract from page text, match Korean status labels
        val pageText = doc.select(".post-status, .summary-meta-row").text()
        val status = when {
            "완결" in pageText -> SManga.COMPLETED
            "연재중" in pageText -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        val genres = doc.select(".manga-summary-genres a").joinToString(", ") { it.text().trim() }
        val author = doc.select(".manga-summary-author").first()?.text()?.trim() ?: ""
        val description = doc.select(".manga-summary-desc").first()?.text()?.trim() ?: ""

        return SManga.create().apply {
            this.url = url.toString()
            this.title = title
            thumbnail_url = coverUrl
            this.status = status
            this.author = author
            this.description = description
        }
    }

    private fun parseChapterList(doc: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        doc.select(".wp-manga-chapter a").forEach { link ->
            val href = link.absUrl("href")
            // Strip "UP" badge span so it doesn't pollute the chapter name
            link.selectFirst(".up-badge-inline")?.remove()
            val name = link.text().trim()
            if (name.isEmpty()) return@forEach

            // Avoid duplicate quick-nav links (.btn-first-ep, .btn-latest-ep)
            if (link.hasClass("btn-first-ep") || link.hasClass("btn-latest-ep")) return@forEach

            // Chapter URLs look like /manga/<slug>/<chapter>/ (3 segments), breadcrumb/nav
            // links are not inside .wp-manga-chapter so this guard is just a sanity check
            val pathSegments = href.toHttpUrl().encodedPathSegments
            if (pathSegments.size < 3) return@forEach

            chapters.add(
                SChapter.create().apply {
                    this.url = href
                    this.name = name
                },
            )
        }

        // Site returns chapters newest-first; keep that order so the latest
        // chapter appears at the top of the list.
        return chapters
    }

    private fun parsePageList(doc: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // .reading-content img: images use data-src (lazy-loaded), src is placeholder
        val images = doc.select(".reading-content img")

        for ((index, img) in images.withIndex()) {
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            val imageUrl = if (src.startsWith("http")) {
                src
            } else if (src.isNotEmpty()) {
                "${baseUrl.trimEnd('/')}${if (src.startsWith("/")) src else "/$src"}"
            } else {
                continue
            }
            pages.add(Page(index + 1, imageUrl = imageUrl))
        }

        return pages
    }
}

object TelegramUrlUpdater {
    suspend fun fetchAndExtractSiteUrl(telegramUrl: String): String? {
        return try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(telegramUrl).build()
            val response = client.newCall(request).execute()
            val html = response.use { it.body?.string() } ?: return null
            val doc = Jsoup.parse(html)

            doc.select("a[href]")
                .map { it.attr("href") }
                .filter { it.startsWith("http") && !it.contains("t.me") }
                .firstOrNull { it.contains("goodtoon") || it.contains("manhwa") }
        } catch (_: Exception) {
            null
        }
    }
}
