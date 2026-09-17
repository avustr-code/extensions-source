package eu.kanade.tachiyomi.extension.th.blackmanga

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.ParseException

@Source
abstract class BlackManga : MangaThemesia() {

    // Manga page URLs are at the root (no /manga/ directory).
    override val mangaUrlDirectory = ""

    private val thaiMonths = listOf(
        "มกราคม" to "January",
        "กุมภาพันธ์" to "February",
        "มีนาคม" to "March",
        "เมษายน" to "April",
        "พฤษภาคม" to "May",
        "มิถุนายน" to "June",
        "กรกฎาคม" to "July",
        "สิงหาคม" to "August",
        "กันยายน" to "September",
        "ตุลาคม" to "October",
        "พฤศจิกายน" to "November",
        "ธันวาคม" to "December",
    )

    // The /manga/ archive has no "dropped" status option.
    override val statusOptions = arrayOf(
        Pair(intl["status_filter_option_all"], ""),
        Pair(intl["status_filter_option_ongoing"], "ongoing"),
        Pair(intl["status_filter_option_completed"], "completed"),
        Pair(intl["status_filter_option_hiatus"], "hiatus"),
    )

    // The archive uses lowercase type values.
    override val typeFilterOptions = arrayOf(
        Pair(intl["type_filter_option_all"], ""),
        Pair(intl["type_filter_option_manga"], "manga"),
        Pair(intl["type_filter_option_manhwa"], "manhwa"),
        Pair(intl["type_filter_option_manhua"], "manhua"),
    )

    override val seriesArtistSelector = infotableSelector(
        listOf(
            "artist",
            "Artiste",
            "Artista",
            "الرسام",
            "الناشر",
            "İllüstratör",
            "Çizer",
            "Sanatçı",
            "นักเขียน",
        ),
    )

    override val seriesAuthorSelector = infotableSelector(
        listOf(
            "Author",
            "Auteur",
            "autor",
            "المؤلف",
            "Mangaka",
            "seniman",
            "Pengarang",
            "Yazar",
            "ผู้แต่ง",
        ),
    )

    private companion object {
        fun infotableSelector(contains: List<String>): String = contains.joinToString(", ") { word ->
            ".infotable tr:contains($word) td:last-child, .tsinfo .imptdt:contains($word) i, " +
                ".fmed b:contains($word)+span, span:contains($word)"
        }
    }

    // The template inside the hidden #series-history (also matched by ".bxcl li" and
    // "ul li:has(div.chbox):has(div.eph-num)") must not be parsed as a chapter.
    override fun chapterListSelector() = "#chapterlist li"

    override fun String?.parseChapterDate(): Long {
        val dateText = this ?: return 0L
        var date = dateText
        for ((thai, english) in thaiMonths) {
            if (date.contains(thai)) {
                date = date.replace(thai, english)
                break
            }
        }
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("manga")
                .addQueryParameter("page", page.toString())
                .apply {
                    filters.forEach { filter ->
                        when (filter) {
                            is StatusFilter -> addQueryParameter("status", filter.selectedValue())
                            is TypeFilter -> addQueryParameter("type", filter.selectedValue())
                            is OrderByFilter -> addQueryParameter("order", filter.selectedValue())
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
                .build()
        } else {
            baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("page", page.toString())
                .build()
        }

        return GET(url, headers)
    }

    // Manga pages are at the root and chapter slugs are the manga slug plus a "ตอนที่" suffix,
    // so a single non-empty path segment is enough to resolve a manga page.
    override fun mangaPathFromUrl(urlString: String): String? {
        val url = urlString.toHttpUrlOrNull() ?: return null
        if (url.host != baseUrl.toHttpUrl().host) return null
        return url.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }?.substringBefore("-ตอนที่")
    }

    // The site stores the release year in the "กำหนดปล่อย" infotable row and alternative names as
    // one comma-joined blob, so the year is shown on top and the blob is left as-is.
    override fun mangaDetailsParse(document: Document): SManga {
        val smanga = super.mangaDetailsParse(document)
        val year = document.selectFirst(
            ".infotable tr:contains(กำหนดปล่อย) td:last-child, .infotable tr:contains(Released) td:last-child",
        )?.text()?.trim()
        if (!year.isNullOrBlank()) {
            smanga.description = "Released: $year\n\n${smanga.description.orEmpty().trim()}"
        }
        return smanga
    }
}
