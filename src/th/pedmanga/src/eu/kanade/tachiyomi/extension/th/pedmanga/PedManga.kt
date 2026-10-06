package eu.kanade.tachiyomi.extension.th.pedmanga

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class PedManga : MangaThemesia() {

    // Every permalink of the manga post type (/manga/{slug}/) answers 404, the post type is only
    // served through the "manga" query parameter, so series are addressed as "/?manga={slug}".
    private fun seriesUrl(slug: String) = baseUrl.toHttpUrl().newBuilder()
        .addQueryParameter("manga", slug)
        .build()
        .run { "$encodedPath?$query" }

    private fun HttpUrl.seriesSlug(): String? = queryParameter("manga")
        ?: pathSegments.takeIf { it.size > 1 && it[0] == "manga" }?.get(1)
            ?.takeIf { it.isNotEmpty() }

    // The archive form has no "dropped" status option.
    override val statusOptions = arrayOf(
        Pair(intl["status_filter_option_all"], ""),
        Pair(intl["status_filter_option_ongoing"], "ongoing"),
        Pair(intl["status_filter_option_completed"], "completed"),
        Pair(intl["status_filter_option_hiatus"], "hiatus"),
    )

    // The archive uses lowercase type values and also offers novels.
    override val typeFilterOptions = arrayOf(
        Pair(intl["type_filter_option_all"], ""),
        Pair(intl["type_filter_option_manga"], "manga"),
        Pair(intl["type_filter_option_manhwa"], "manhwa"),
        Pair(intl["type_filter_option_manhua"], "manhua"),
        Pair(intl["type_filter_option_comic"], "comic"),
        Pair("Novel", "novel"),
    )

    // The series info lists the uploader as "ผู้อัพเดท" instead of "ผู้แต่ง".
    override val seriesAuthorSelector = listOf(
        "Author",
        "Auteur",
        "autor",
        "المؤلف",
        "Mangaka",
        "seniman",
        "Pengarang",
        "Yazar",
        "ผู้แต่ง",
        "นักเขียน",
        "ผู้อัพเดท",
    ).joinToString(", ") { word ->
        ".infotable tr:contains($word) td:last-child, .tsinfo .imptdt:contains($word) i, " +
            ".fmed b:contains($word)+span, span:contains($word)"
    }

    // The <li> template of the hidden #series-history must not be parsed as a chapter.
    override fun chapterListSelector() = "#chapterlist li"

    // The listing lives at "/?post_type=manga" and every filter is a query parameter of it.
    override fun searchMangaUrl(page: Int, query: String, filters: FilterList): HttpUrl.Builder = baseUrl.toHttpUrl().newBuilder().apply {
        addQueryParameter("post_type", "manga")
        if (query.isNotBlank()) addQueryParameter("title", query)
        addQueryParameter("page", page.toString())
        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> addQueryParameter("status", filter.selectedValue())
                is TypeFilter -> addQueryParameter("type", filter.selectedValue())
                is OrderByFilter -> addQueryParameter("order", filter.selectedValue())
                is YearFilter ->
                    filter.state.takeIf { it.isNotEmpty() }?.let { addQueryParameter("yearx", it) }

                is GenreListFilter ->
                    filter.state
                        .filter { it.state != Filter.TriState.STATE_IGNORE }
                        .forEach {
                            val value = if (it.state == Filter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                            addQueryParameter("genre[]", value)
                        }

                else -> {}
            }
        }
    }

    // The archive renders the pagination link even when the current page is the last one.
    override fun searchMangaParse(document: Document): MangasPage {
        val page = super.searchMangaParse(document)
        return MangasPage(page.mangas, page.mangas.isNotEmpty() && page.hasNextPage)
    }

    override suspend fun fetchFilterData() = parseGenres(
        client.get("$baseUrl/?post_type=manga").asJsoup(),
    ).toJsonElement()

    // The site has no author filter, asking for one always yields an empty list.
    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreData>>()?.map { Genre(it.name, it.value, it.state) }.orEmpty()
        val filters = mutableListOf<Filter<*>>(
            Filter.Separator(),
            YearFilter(intl["year_filter_title"]),
            StatusFilter(intl["status_filter_title"], statusOptions),
            TypeFilter(intl["type_filter_title"], typeFilterOptions),
            OrderByFilter(intl["order_by_filter_title"], orderByFilterOptions),
        )
        if (genres.isNotEmpty()) {
            filters += listOf(
                Filter.Header(intl["genre_exclusion_warning"]),
                GenreListFilter(intl["genre_filter_title"], genres),
            )
        }
        return FilterList(filters)
    }

    // Listing entries link to the broken permalinks, so they are replaced by the working query form.
    override fun searchMangaFromElement(element: Element): SManga = super.searchMangaFromElement(element).apply {
        element.selectFirst("a[href]")?.attr("abs:href")?.toHttpUrlOrNull()?.seriesSlug()?.let { url = seriesUrl(it) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.seriesSlug() ?: return null
        return getMangaDetails(
            SManga.create().apply { this.url = seriesUrl(slug) },
        ).takeIf { it.title.isNotEmpty() }
    }

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document).apply {
        document.location().toHttpUrlOrNull()?.seriesSlug()?.let { url = seriesUrl(it) }
    }
}
