package eu.kanade.tachiyomi.extension.en.novatoon

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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.textOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Novatoon : KeiSource() {

    private val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "popular")
            .build()
        return parseMangaPage(client.get(url))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "update")
            .build()
        return parseMangaPage(client.get(url))
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .apply { if (page > 1) addQueryParameter("paged", page.toString()) }
                .build()
        } else {
            buildMangaUrl(page, filters)
        }
        return parseMangaPage(client.get(url))
    }

    private fun buildMangaUrl(page: Int, filters: FilterList): HttpUrl = "$baseUrl/manga/".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
        for (filter in filters) {
            when (filter) {
                is SortFilter -> {
                    val order = filter.sort
                    if (order.isNotEmpty()) addQueryParameter("order", order)
                }
                is StatusFilter -> {
                    val status = filter.value
                    if (status.isNotEmpty()) addQueryParameter("status", status)
                }
                is TypeFilter -> {
                    val type = filter.value
                    if (type.isNotEmpty()) addQueryParameter("type", type)
                }
                is GenreFilter -> filter.state.forEach { genre ->
                    if (!genre.isIgnored()) addQueryParameter("genre[]", genre.id)
                }
                else -> Unit
            }
        }
    }.build()

    override fun getFilterList(data: JsonElement?): FilterList = novatoonFilters()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = if (fetchDetails && fetchChapters) {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        SMangaUpdate(manga = document.toSMangaDetails(manga), chapters = document.parseChapterList())
    } else {
        coroutineScope {
            val newManga = async { if (fetchDetails) getMangaDetails(manga) else manga }
            val newChapters = async { if (fetchChapters) getChapterList(manga) else chapters }
            SMangaUpdate(manga = newManga.await(), chapters = newChapters.await())
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val mangaUrl = when {
            url.encodedPath.startsWith("/manga/") -> url.encodedPath
            "-chapter-" in url.encodedPath -> {
                val document = client.get(url).asJsoup()
                document.selectFirst("div.ts-breadcrumb a[href*='/manga/']")?.attr("href") ?: return null
            }
            else -> return null
        }
        val manga = SManga.create().apply { setUrlWithoutDomain(mangaUrl) }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override val supportsRelatedMangas: Boolean get() = true
    override val supportRelatedMangasBySearch: Boolean get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return document.select(MANGA_CARD).mapNotNull { it.toSManga() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val urls = client.get(getChapterUrl(chapter)).asJsoup()
            .select("div#readerarea img")
            .mapNotNull { it.imageUrl() }
        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private suspend fun getMangaDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return document.toSMangaDetails(manga)
    }

    private suspend fun getChapterList(manga: SManga): List<SChapter> {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return document.parseChapterList()
    }

    private fun Document.parseChapterList(): List<SChapter> {
        return select("div#chapterlist ul li").mapNotNull { li ->
            val link = li.selectFirst("div.chbox div.eph-num a") ?: return@mapNotNull null
            val number = li.attr("data-num").toFloatOrNull()
            SChapter.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                name = link.selectFirst("span.chapternum")?.textOrNull() ?: "Chapter $number"
                date_upload = chapterDateFormat.tryParseDate(link.selectFirst("span.chapterdate")?.text())
                chapter_number = number ?: -1f
            }
        }
    }

    private fun Document.toSMangaDetails(manga: SManga): SManga = SManga.create().apply {
        setUrlWithoutDomain(manga.url)
        title = selectFirst("h1.entry-title")?.textOrNull() ?: manga.title
        thumbnail_url = selectFirst("div.seriestucontl div.thumb img")
            ?.let { it.attrOrNull("data-src") ?: it.attrOrNull("src") }
            ?: manga.thumbnail_url
        description = buildString {
            val released = infoTableValue("Released")
            if (!released.isNullOrBlank()) append("Released: ").append(released)
            val descriptionText = selectFirst("div.entry-content[itemprop='description']")?.text()
            if (!descriptionText.isNullOrBlank()) {
                if (!isEmpty()) append("\n\n")
                append(descriptionText)
            }
            val altNames = infoTableValue("Alternative Names")
            if (!altNames.isNullOrBlank()) {
                if (!isEmpty()) append("\n\n")
                append("Alternative Names:\n")
                append(altNames.split(",", "，").joinToString("\n") { "• ${it.trim()}" })
            }
        }.trimEnd()
        author = infoTableValue("Author")
        artist = infoTableValue("Artist")
        genre = select("div.seriestugenre a[rel='tag']").eachText().joinToString(", ")
        status = parseStatus(infoTableValue("Status"))
    }

    private fun Document.infoTableValue(label: String): String? = select("table.infotable tr").firstOrNull { row ->
        row.selectFirst("td")?.text().orEmpty().trim().equals(label, ignoreCase = true)
    }?.selectFirst("td:eq(1)")?.text()

    private fun parseStatus(status: String?): Int = when {
        status.isNullOrBlank() -> SManga.UNKNOWN
        status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private suspend fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(MANGA_CARD).mapNotNull { it.toSManga() }
        val hasNextPage = document.select("div.hpage a.r[href*='page=']").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga(): SManga? {
        val href = attrOrNull("href") ?: return null
        if (!URL_REGEX.containsMatchIn(href)) return null
        val title = selectFirst("div.bigor div.tt")?.textOrNull() ?: return null
        if (title.isBlank()) return null
        val thumbnail = selectFirst("div.limit img")?.let { it.attrOrNull("data-src") ?: it.attrOrNull("src") }
        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    private fun Element.imageUrl(): String? {
        val src = attrOrNull("data-src") ?: attrOrNull("src")
        return src?.takeIf { it.startsWith("http") && !it.endsWith(".svg") }
    }

    companion object {
        private const val MANGA_CARD = "div.listupd div.bsx a"
        private val URL_REGEX = Regex("/(?:manga|genres)/.+")
    }
}
