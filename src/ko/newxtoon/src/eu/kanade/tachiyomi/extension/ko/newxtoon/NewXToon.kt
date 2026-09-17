package eu.kanade.tachiyomi.extension.ko.newxtoon

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class NewXToon :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val contentFilter: String
        get() = preferences.getString(CONTENT_FILTER_PREF, CONTENT_FILTER_SUGGESTIVE)!!

    private val blockedGenres: Set<String>
        get() = preferences.getStringSet(BLOCKED_GENRES_PREF, emptySet())!!

    private val ajaxHeaders: Headers by lazy {
        headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // ========================= Popular ==========================================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(page, popular = true)

    // ========================= Latest ===========================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(page, popular = false)

    private suspend fun fetchListing(page: Int, popular: Boolean): MangasPage {
        val baseUrl = baseUrl.toHttpUrl()
        val mangas = LinkedHashMap<String, SManga>()
        coroutineScope {
            allowedCategories().map { category ->
                async {
                    val url = baseUrl.newBuilder()
                        .addPathSegment("comics")
                        .addQueryParameter("page", page.toString())
                        .apply {
                            if (popular) addQueryParameter("sort", "popular")
                            if (category.isNotEmpty()) addQueryParameter("category", category)
                        }
                        .build()
                    parseMangaList(client.get(url).asJsoup())
                }
            }.forEach { deferred ->
                deferred.await().mangas.forEach { manga ->
                    mangas.putIfAbsent(manga.url, manga)
                }
            }
        }
        return MangasPage(mangas.values.toList(), mangas.isNotEmpty())
    }

    // ========================= Search ===========================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override fun getFilterList(data: JsonElement?): FilterList = filters()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val comicIndex = segments.indexOf("comics")
        if (comicIndex == -1) return null
        val comicId = segments.getOrNull(comicIndex + 1)?.toLongOrNull() ?: return null
        return fetchDetails(
            SManga.create().apply {
                this.url = comicId.toString()
            },
        )
    }

    // ========================= Details ===========================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            fetchDetails(manga)
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            fetchChaptersList(manga.url.toLong())
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchDetails(manga: SManga): SManga {
        val document = client.get("$baseUrl/comics/${manga.url}").asJsoup()
        return mangaDetailsParse(document).apply {
            url = manga.url
        }
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("p.title-clamp")?.text()
            ?: throw Exception("Title not found")
        thumbnail_url = document.selectFirst("img.mobile-comic-cover")?.absUrl("src")
            ?: document.selectFirst("img.cover-image")?.absUrl("src")
        description = document.selectFirst("p[data-comic-description]")?.text()
        genre = document.select("a[href*=\"genre=\"]").joinToString(", ") { it.text().removePrefix("#") }
        status = document.select("strong.text-ink").firstOrNull { strong ->
            strong.text() in STATUS_NAMES
        }?.let { strong ->
            when (strong.text()) {
                "연재중" -> SManga.ONGOING
                else -> SManga.COMPLETED
            }
        } ?: SManga.UNKNOWN
        initialized = true
    }

    // ========================= Chapters ==========================================

    private suspend fun fetchChaptersList(mangaId: Long): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasMore = true
        while (hasMore && chapters.size < MAX_CHAPTERS) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("comics")
                .addPathSegment(mangaId.toString())
                .addPathSegment("chapters")
                .addQueryParameter("page", page.toString())
                .build()
            val response = client.get(url, ajaxHeaders).parseAs<ChaptersResponse>()
            chapters += response.toSChapters(mangaId.toInt())
            hasMore = response.hasMore
            page = response.nextPage ?: (page + 1)
        }
        return chapters
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comics/${manga.url}"

    // ========================= Pages =============================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        return document
            .select("div[data-reader-page] img[data-reader-image]")
            .mapNotNull { image ->
                image.absUrl("src").takeIf { it.isNotEmpty() && "/ads/" !in it }
            }
            .distinct()
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    // ========================= Preferences =======================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = CONTENT_FILTER_PREF
            title = "콘텐츠 필터"
            summary = "표시할 카테고리를 선택합니다"
            entries = arrayOf(
                "Safe - 일반만화만",
                "Suggestive - 일반만화 + BL·GL",
                "Erotica - 전체",
                "Pornographic - 전체",
            )
            entryValues = arrayOf(
                CONTENT_FILTER_SAFE,
                CONTENT_FILTER_SUGGESTIVE,
                CONTENT_FILTER_EROTICA,
                CONTENT_FILTER_PORNOGRAPHIC,
            )
            setDefaultValue(CONTENT_FILTER_SUGGESTIVE)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = TELEGRAM_PREF
            title = "텔레그램 채널 URL"
            summary = "사이트 공지를 확인할 텔레그램 채널"
            setDefaultValue(TELEGRAM_DEFAULT)
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = BLOCKED_GENRES_PREF
            title = "차단 장르"
            summary = "선택한 장르의 작품을 목록에서 숨깁니다"
            entries = GENRE_NAMES.toTypedArray()
            entryValues = GENRE_NAMES.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)
    }

    // ========================= Utils =============================================

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("a.comic-link").mapNotNull { card ->
            if (card.hasAttr("data-cover-ad")) return@mapNotNull null
            if (card.isBlockedGenre()) return@mapNotNull null
            card.toSManga()
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun Element.toSManga(): SManga? {
        val id = attr("href").substringAfterLast('/').toLongOrNull() ?: return null
        val titleEl = selectFirst("h3[title]") ?: selectFirst("h3") ?: throw Exception("Card title not found")
        return SManga.create().apply {
            url = id.toString()
            title = titleEl.text()
            thumbnail_url = selectFirst("img.cover-image")?.absUrl("src")
            initialized = false
        }
    }

    private fun Element.isBlockedGenre(): Boolean {
        if (blockedGenres.isEmpty()) return false
        val chips = select(".cover-shell span.rounded-full").map { it.text() }
        return chips.any { it in blockedGenres }
    }

    private fun allowedCategories(): List<String> = when (contentFilter) {
        CONTENT_FILTER_SAFE -> CATEGORY_GENERAL // 일반만화 (no category param)
        CONTENT_FILTER_EROTICA, CONTENT_FILTER_PORNOGRAPHIC -> CATEGORY_GENERAL + CATEGORY_BLGL + CATEGORY_ADULT
        else -> CATEGORY_GENERAL + CATEGORY_BLGL
    }

    companion object {
        const val CONTENT_FILTER_SAFE = "safe"
        const val CONTENT_FILTER_SUGGESTIVE = "suggestive"
        const val CONTENT_FILTER_EROTICA = "erotica"
        const val CONTENT_FILTER_PORNOGRAPHIC = "pornographic"

        private const val CONTENT_FILTER_PREF = "contentFilterPref"
        private const val BLOCKED_GENRES_PREF = "blockedGenresPref"
        private const val TELEGRAM_PREF = "telegramPref"
        private const val TELEGRAM_DEFAULT = "https://t.me/newxtoon"
        private const val MAX_CHAPTERS = 500

        // Category query values. An empty string means the default (일반만화) tab.
        private val CATEGORY_GENERAL = listOf("")
        private val CATEGORY_BLGL = listOf("BL·GL")
        private val CATEGORY_ADULT = listOf("성인")

        private val STATUS_NAMES = setOf("연재중", "완결")
    }
}
