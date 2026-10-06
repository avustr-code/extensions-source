package eu.kanade.tachiyomi.extension.en.comick

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Comick :
    KeiSource(),
    ConfigurableSource {
    private val preferences by getPreferencesLazy()

    // ListPreference persists its entry values as strings.
    private val tagDisplay: TagDisplay
        get() = TagDisplay.from(preferences.getString(KEY_TAG_DISPLAY, null)?.toIntOrNull() ?: -1)

    /**
     * The API allows 200 requests per minute; covers come from a different host and must not be
     * throttled.
     */
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 1.seconds) { it.host == API_URL.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage {
        // The site's own "Hot" list (`/top` → `rank`) is a flat 50 entries with no offset, so the
        // catalogue is browsed through the most-followed ranking instead.
        val url = "$API_URL/v1.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")
            .addQueryParameter("limit", "$SEARCH_LIMIT")
            .addQueryParameter("tachiyomi", "true")
            .addQueryParameter("sort", "user_follow_count")
            .freshen()
            .build()

        val entries = client.get(url, cacheControl = freshCache).parseAs<List<MangaEntry>>()
        return MangasPage(entries.toSMangaList(), hasNextPage = page < POPULAR_LAST_PAGE)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$API_URL/v1.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")
            .addQueryParameter("limit", "$SEARCH_LIMIT")
            .addQueryParameter("tachiyomi", "true")
            .addQueryParameter("sort", "uploaded")
            .freshen()
            .build()

        val entries = client.get(url, cacheControl = freshCache).parseAs<List<MangaEntry>>()
        return MangasPage(entries.toSMangaList(), hasNextPage = page < POPULAR_LAST_PAGE)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // Sort defaults to the most-followed ranking, so the sort parameter is part of the baseline the
        // filtered URL is compared against; otherwise the default sort alone would look like an
        // active filter and suppress the overview supplement on every search.
        val base = "$API_URL/v1.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", "$page")
            .addQueryParameter("limit", "$PROBE_LIMIT")
            .addQueryParameter("tachiyomi", "true")
            .addQueryParameter("sort", "user_follow_count")
            .apply { if (query.isNotBlank()) addQueryParameter("q", query.trim()) }
            .build()

        val url = filters.addSearchParams(base.newBuilder()).build()

        // The last page can hold exactly SEARCH_LIMIT entries with nothing behind it, so one extra
        // row is asked for to tell "full page" apart from "end of results".
        val entries = client.get(url, cacheControl = freshCache).parseAs<List<MangaEntry>>()
        val mangas = entries.take(SEARCH_LIMIT).toSMangaList().toMutableList()

        // Mihon hands over the full filter list even when the user changed nothing, so the list is never
        // empty; comparing the parameter sets is what tells a pristine list from an active one (HttpUrl
        // equality is sensitive to parameter order, and filter params are appended, so it cannot be used).
        // The overview endpoint matches alternative titles ("lout" finds Lout of Count's Family) but
        // ignores page, limit and every filter parameter, so it can only add to the first page of an
        // unfiltered query and never replace the ranked list.
        // Order-insensitive comparison of the encoded name=value tokens.
        val pristine = url.query.orEmpty().split('&').sorted() == base.query.orEmpty().split('&').sorted()
        if (page == 1 && query.isNotBlank() && pristine) {
            val seen = mangas.mapTo(HashSet()) { it.url }
            mangas += overviewMatches(query).filterNot { it.url in seen }
        }

        return MangasPage(mangas, hasNextPage = entries.size > SEARCH_LIMIT)
    }

    private suspend fun overviewMatches(query: String): List<SManga> {
        val url = "$API_URL/v1.0/search/overview".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()

        return client.get(url, cacheControl = freshCache).parseAs<OverviewResponse>()
            .results
            .filter { isContentAllowed(it.contentRating, maxContentRating) }
            .mapNotNull(OverviewEntry::toSMangaOrNull)
            .filterNot { !preferences.genreBlocklist().isEmpty() && it.isBlocked() }
    }

    /**
     * The overview payload carries neither genres nor tags, so the entry is looked up by its own
     * title to honour the genre blocklist. An entry that cannot be found that way counts as
     * blocked, because a genre that cannot be checked must not slip past what the user set up.
     */
    private suspend fun SManga.isBlocked(): Boolean {
        val url = "$API_URL/v1.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .addQueryParameter("limit", "$PROBE_LIMIT")
            .addQueryParameter("tachiyomi", "true")
            .build()

        val entry = client.get(url, cacheControl = freshCache).parseAs<List<MangaEntry>>()
            .firstOrNull { it.id == this.url }
        val genres = preferences.genreBlocklist()
        return entry?.genres?.any(genres::contains) != false
    }

    private val maxContentRating: String get() = preferences.maxContentRating()

    // The default 10 minute cache would serve stale lists after a refresh.
    private val freshCache = CacheControl.Builder().noCache().build()

    // Cloudflare may serve a browse query for up to fifteen minutes. Rounding the value to the
    // minute refreshes that cache once per minute instead of on every single request.
    private fun HttpUrl.Builder.freshen() = addQueryParameter("_ts", (System.currentTimeMillis() / 60_000).toString())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        // Both the slug and the hid are accepted by the comic endpoint.
        val id = url.pathSegments.takeIf { it.getOrNull(0) == "comic" }?.getOrNull(1) ?: return null
        val detail = client.get("$API_URL/v1.0/comic/$id/?tachiyomi=true&title_locale=en").parseAs<ComicDetail>()
        return detail.comic.toSManga(detail, tagDisplay)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Details and chapters live behind separate endpoints.
        val details = async { if (fetchDetails) mangaDetails(manga.url) else manga }
        val newChapters = async { if (fetchChapters) chapterList(manga.url) else chapters }
        SMangaUpdate(details.await(), newChapters.await())
    }

    private suspend fun mangaDetails(id: String): SManga {
        val detail = client.get("$API_URL/v1.0/comic/$id/?tachiyomi=true&title_locale=en").parseAs<ComicDetail>()
        return detail.comic.toSManga(detail, tagDisplay)
    }

    private suspend fun chapterList(mangaId: String): List<SChapter> {
        // The endpoint returns every chapter in one response, so paging through it would cost
        // one rate limited request per batch for no benefit.
        val url = "$API_URL/v1.0/comic/$mangaId/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("lang", CHAPTER_LANGUAGE)
            .addQueryParameter("limit", "$CHAPTER_LIMIT")
            .build()

        return client.get(url).parseAs<ChapterList>()
            .chapters
            .map { it.toSChapter(mangaId) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.substringAfterLast('/').substringBefore("-chapter-")
        val images = client.get("$API_URL/chapter/$id/get_images").parseAs<List<ImageDto>>()
        return images.mapIndexed { index, image -> image.toPage(index) }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = fetchAndStoreFilterData().toJsonElement()

    /** Fetches the genre/tag catalog and stores the labels used by the blocklist screens. */
    private suspend fun fetchAndStoreFilterData(): FilterData {
        val genres = client.get("$API_URL/genre/").parseAs<List<Genre>>()
        val tags = client.get("$API_URL/category/popular").parseAs<List<Tag>>()
        preferences.edit()
            .putString(KEY_GENRE_LABELS, genres.joinToString("|") { "${it.id}:${it.slug}:${it.name}" })
            .apply()
        return FilterData(genres, tags)
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: FilterData()
        return FilterList(
            buildList {
                filterData.genres.takeIf { it.isNotEmpty() }?.let { add(GenreFilter(it)) }
                filterData.tags.takeIf { it.isNotEmpty() }?.let { add(TagFilter(it)) }
                addAll(
                    listOf(
                        OriginFilter(),
                        DemographicFilter(),
                        StatusFilter(),
                        ContentRatingFilter(),
                        ExcludedContentRatingFilter(),
                        ViolenceRatingFilter(),
                        ExcludedViolenceRatingFilter(),
                        Filter.Separator(),
                        YearRangeFilter(),
                        MinimumChaptersFilter(),
                        MaximumChaptersFilter(),
                        MinimumRatingFilter(),
                        CompletedFilter(),
                        SortFilter(),
                    ),
                )
            },
        )
    }

    private fun List<MangaEntry>.toSMangaList(): List<SManga> {
        val blocked = preferences.genreBlocklist()
        return filter { isContentAllowed(it.contentRating, maxContentRating) }
            .filter { blocked.isEmpty() || it.genres?.any(blocked::contains) != true }
            .map(MangaEntry::toSManga)
    }

    private fun FilterList.addSearchParams(builder: HttpUrl.Builder): HttpUrl.Builder {
        firstInstanceOrNull<GenreFilter>()?.let { filter ->
            filter.included.take(MAX_GENRES).forEach { builder.addQueryParameter("genres", it) }
            filter.excluded.take(MAX_EXCLUDED_GENRES).forEach { builder.addQueryParameter("excludes", it) }
        }
        firstInstanceOrNull<TagFilter>()?.let { filter ->
            filter.included.take(MAX_TAGS).forEach { builder.addQueryParameter("tags", it) }
            filter.excluded.take(MAX_EXCLUDED_TAGS).forEach { builder.addQueryParameter("excluded-tags", it) }
        }
        firstInstanceOrNull<OriginFilter>()?.selected?.forEach { builder.addQueryParameter("country", it) }
        firstInstanceOrNull<DemographicFilter>()?.selected?.let { builder.addQueryParameter("demographic", it) }
        firstInstanceOrNull<StatusFilter>()?.selected?.let { builder.addQueryParameter("status", it) }
        firstInstanceOrNull<ContentRatingFilter>()?.selected?.let { builder.addQueryParameter("content_rating", it) }
        firstInstanceOrNull<ExcludedContentRatingFilter>()?.selected?.forEach { builder.addQueryParameter("excluded_content_rating", it) }
        firstInstanceOrNull<ViolenceRatingFilter>()?.selected?.let { builder.addQueryParameter("violence_rating", it) }
        firstInstanceOrNull<ExcludedViolenceRatingFilter>()?.selected?.forEach { builder.addQueryParameter("excluded_violence_rating", it) }
        firstInstanceOrNull<SortFilter>()?.selected?.let {
            // The baseline already carries the default sort, so replace rather than append.
            builder.removeAllQueryParameters("sort")
            builder.addQueryParameter("sort", it)
        }
        firstInstanceOrNull<YearRangeFilter>()?.let { filter ->
            filter.from?.let { builder.addQueryParameter("from", it) }
            filter.to?.let { builder.addQueryParameter("to", it) }
        }
        firstInstanceOrNull<MinimumChaptersFilter>()?.value?.let { builder.addQueryParameter("minimum", it) }
        firstInstanceOrNull<MaximumChaptersFilter>()?.value?.let { builder.addQueryParameter("maximum", it) }
        firstInstanceOrNull<MinimumRatingFilter>()?.selected?.let { builder.addQueryParameter("minimum_rating", it) }
        if (firstInstanceOrNull<CompletedFilter>()?.state == true) {
            builder.addQueryParameter("completed", "true")
        }
        return builder
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = KEY_CONTENT_RATING
            title = "Content rating"
            summary = "%s"
            entries = CONTENT_RATINGS.map { it.first }.toTypedArray()
            entryValues = CONTENT_RATINGS.map { it.second }.toTypedArray()
            setDefaultValue("")
        }.also(screen::addPreference)

        val genreLabels = preferences.getString(KEY_GENRE_LABELS, null)
            ?.split("|")
            ?.mapNotNull { entry ->
                val id = entry.substringBefore(":").toIntOrNull()
                id?.let { it to entry.substringAfter(":") }
            }
            .orEmpty()

        val genrePreference = MultiSelectListPreference(screen.context).apply {
            key = KEY_GENRE_BLOCKLIST
            title = "Genre blocklist"
            summary = "Hide titles with the selected genres from browse lists"
            entries = genreLabels.map { it.second.substringAfter(":") }.toTypedArray()
            entryValues = genreLabels.map { it.first.toString() }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        // Labels are only persisted when the filter catalog is fetched (normally when the filter
        // sheet is opened), so fetch it once here if that has never happened.
        if (genreLabels.isEmpty()) {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { fetchAndStoreFilterData() }.onSuccess {
                    withContext(Dispatchers.Main) {
                        val genres = preferences.getString(KEY_GENRE_LABELS, null)
                            ?.split("|")
                            ?.mapNotNull { entry ->
                                val id = entry.substringBefore(":").toIntOrNull()
                                id?.let { it to entry.substringAfter(":") }
                            }
                            .orEmpty()
                        genrePreference.entries = genres.map { it.second.substringAfter(":") }.toTypedArray()
                        genrePreference.entryValues = genres.map { it.first.toString() }.toTypedArray()
                    }
                }
            }
        }

        ListPreference(screen.context).apply {
            key = KEY_TAG_DISPLAY
            title = "Tag display"
            summary = "%s"
            entries = arrayOf("Short", "Full")
            entryValues = arrayOf(
                TagDisplay.SHORT.prefValue.toString(),
                TagDisplay.FULL.prefValue.toString(),
            )
            setDefaultValue(TagDisplay.SHORT.prefValue.toString())
        }.also(screen::addPreference)
    }

    private companion object {
        const val CHAPTER_LANGUAGE = "en"
        const val SEARCH_LIMIT = 50
        const val PROBE_LIMIT = SEARCH_LIMIT + 1
        const val CHAPTER_LIMIT = 99999

        // The API keeps answering past its documented page limit, so the walk is capped here.
        const val POPULAR_LAST_PAGE = 50

        // The search endpoint rejects requests with more items than these.
        const val MAX_GENRES = 10
        const val MAX_EXCLUDED_GENRES = 40
        const val MAX_TAGS = 10
        const val MAX_EXCLUDED_TAGS = 30
    }
}
