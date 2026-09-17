package eu.kanade.tachiyomi.extension.ko.newxtoon

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("uuuu.MM.dd", Locale.KOREAN)

@Serializable
class ChapterDto(
    private val id: Int,
    private val title: String,
    private val date: String = "",
) {
    fun toSChapter(mangaId: Int): SChapter = SChapter.create().apply {
        name = title
        url = "/comics/$mangaId/chapters/$id"
        date_upload = dateFormat.tryParseDate(date, ZoneId.of("Asia/Seoul"))
    }
}

@Serializable
class ChaptersResponse(
    private val chapters: List<ChapterDto> = emptyList(),
    private val has_more: Boolean = false,
    private val next_page: Int? = null,
) {
    val hasMore: Boolean get() = has_more
    val nextPage: Int? get() = next_page

    fun toSChapters(mangaId: Int): List<SChapter> = chapters.map { it.toSChapter(mangaId) }
}
