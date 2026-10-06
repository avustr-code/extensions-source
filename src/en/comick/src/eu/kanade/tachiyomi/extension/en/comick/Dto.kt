package eu.kanade.tachiyomi.extension.en.comick

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.time.Instant

internal const val API_URL = "https://api.comick.dev"
internal const val IMAGE_HOST = "https://meo.comick.pictures"

/**
 * Covers are stored under a single b2key and the requested size is a suffix on the stem
 * (`abc.jpg`, `abc-m.jpg`, `abc-s.jpg`). Thumbnails are only ever generated as JPEG, even when
 * the full size cover is a PNG, so the extension is hardcoded for the small variant.
 */
internal fun coverImageUrl(b2key: String, small: Boolean = false): String {
    val stem = b2key.substringBeforeLast('.')
    return if (small) "$IMAGE_HOST/$stem-s.jpg" else "$IMAGE_HOST/$b2key"
}

@Serializable
class AltTitle(
    val title: String,
    val lang: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
class Cover(
    val b2key: String,
)

@Serializable
class Person(
    val name: String,
)

/** Serves both the `/genre/` metadata and the `md_genres` entries embedded in comic payloads. */
@Serializable
class Genre(
    val id: Int? = null,
    val name: String,
    val slug: String,
    val group: String = "",
)

@Serializable
class GenreRef(
    @SerialName("md_genres") val genre: Genre,
)

/** Community tags, a separate list from the editorial genres. */
@Serializable
class CategoryRef(
    @SerialName("mu_categories") val category: Tag,
)

/** Categories are named `title` rather than `name`. */
@Serializable
class Tag(
    @SerialName("title") val name: String,
    val slug: String,
    @SerialName("comic_count") val comicCount: Int? = null,
)

/**
 * The stored title is usually what the site itself shows, but on some series it is the romanized or
 * original name and the English one only exists in `md_titles`. The order of `md_titles` differs
 * between the search and detail endpoints, so its position is never used as a signal: the stored
 * title wins whenever it is one of the English entries, otherwise `is_default` picks, otherwise the
 * first English entry. The detail endpoint can be asked for the title the site renders with
 * `title_locale`, which is why this guess is only the fallback there.
 */
internal fun englishTitle(fallback: String, altTitles: List<AltTitle>?): String {
    val english = altTitles.orEmpty().filter { it.lang == "en" || it.lang == null }
    if (english.any { it.title == fallback }) return fallback
    return altTitles.orEmpty().firstOrNull { it.lang == "en" && it.isDefault }?.title
        ?: english.firstOrNull()?.title
        ?: fallback
}

/** Listing entry shared by the search, trending and latest-chapter endpoints. */
@Serializable
class MangaEntry(
    private val hid: String,
    private val title: String,
    @SerialName("cover_url") private val coverUrl: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val genres: List<Int>? = null,
    @SerialName("md_covers") private val mdCovers: List<Cover>? = null,
    @SerialName("md_titles") private val mdTitles: List<AltTitle>? = null,
) {
    fun englishTitle() = englishTitle(title, mdTitles)

    internal val id: String get() = hid

    fun toSManga() = SManga.create().apply {
        url = hid
        this.title = englishTitle()
        thumbnail_url = coverUrl ?: mdCovers?.firstOrNull()?.let { coverImageUrl(it.b2key, small = true) }
    }
}

@Serializable
class ComicDetail(
    val comic: ComicDto,
    val authors: List<Person> = emptyList(),
    val artists: List<Person> = emptyList(),
    // Only present when the request asked for it with title_locale; this is the exact heading the
    // site renders, so it beats guessing from md_titles. The JSON key is snake_case.
    @SerialName("display_titles") val displayTitles: DisplayTitles? = null,
)

@Serializable
class DisplayTitles(
    val main: String? = null,
)

@Serializable
class ComicDto(
    private val hid: String,
    private val title: String,
    private val desc: String? = null,
    private val year: Int? = null,
    private val status: Int? = null,
    @SerialName("bayesian_rating") private val bayesianRating: String? = null,
    @SerialName("md_titles") private val mdTitles: List<AltTitle>? = null,
    @SerialName("md_covers") private val mdCovers: List<Cover>? = null,
    @SerialName("md_comic_md_genres") private val mdGenres: List<GenreRef>? = null,
    @SerialName("mu_comic_categories") private val categories: List<CategoryRef>? = null,
) {
    internal fun toSManga(detail: ComicDetail, tagDisplay: TagDisplay) = SManga.create().apply {
        url = hid
        this.title = detail.displayTitles?.main ?: englishTitle(this@ComicDto.title, mdTitles)
        author = detail.authors.joinToString { it.name }
        artist = detail.artists.joinToString { it.name }
        description = buildDescription(this.title)
        genre = genreString(tagDisplay)
        status = toSMangaStatus()
        thumbnail_url = mdCovers?.firstOrNull()?.let { coverImageUrl(it.b2key) }
    }

    internal fun genreString(tagDisplay: TagDisplay): String {
        val genres = mdGenres.orEmpty().map { it.genre }
        val names = when (tagDisplay) {
            TagDisplay.SHORT -> genres.filter { it.group != "Content" }.map { it.name }
            TagDisplay.FULL -> genres.map { it.name } + categories.orEmpty().map { it.category.name }
        }
        return names.joinToString()
    }

    private fun toSMangaStatus() = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        3 -> SManga.CANCELLED
        4 -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun buildDescription(resolvedTitle: String) = buildString {
        append("Released: ${year ?: "?"} · Rating: ${ratingStars()}\n\n")
        append(desc.orEmpty())
        // The heading already shows the resolved title, so its copies in md_titles are dropped.
        val alternatives = mdTitles.orEmpty()
            .filter { it.title != resolvedTitle }
            .distinctBy { it.title }
            .sortedBy { it.lang }
        if (alternatives.isNotEmpty()) {
            append("\n\nAlternative Titles:")
            alternatives.forEach { append("\n● ", it.title) }
        }
    }

    /** Ratings are on a ten point scale; the site shows them as five stars next to the number. */
    private fun ratingStars(): String {
        val score = bayesianRating?.toDoubleOrNull() ?: return "N/A"
        val full = (score / 2.0).coerceIn(0.0, 5.0).toInt()
        val stars = "★".repeat(full) + "☆".repeat(5 - full)
        return "$stars (${String.format(Locale.ROOT, "%.1f", score)})"
    }
}

@Serializable
class OverviewResponse(
    val results: List<OverviewEntry> = emptyList(),
)

@Serializable
class OverviewEntry(
    private val hid: String? = null,
    private val title: String,
    private val matchedTitle: String? = null,
    private val type: String,
    @SerialName("media_type") private val mediaType: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val genres: List<Int>? = null,
    @SerialName("md_covers") private val mdCovers: List<Cover>? = null,
) {
    /**
     * The results array also holds authors, groups and users. Anime entries arrive as `type: "comic"`
     * too and are only told apart by `media_type`; their /comic/ page is a 404, so both kinds are
     * dropped here. The API ignores `media_type` as a request parameter, so it has to be read off
     * the payload.
     */
    fun toSMangaOrNull(): SManga? {
        if (type != "comic" || hid == null || mediaType == "anime") return null
        return SManga.create().apply {
            url = hid
            this.title = matchedTitle ?: this@OverviewEntry.title
            thumbnail_url = mdCovers?.firstOrNull()?.let { coverImageUrl(it.b2key, small = true) }
        }
    }
}

@Serializable
class ChapterList(
    val chapters: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    private val hid: String,
    private val chap: String,
    private val lang: String,
    private val vol: String? = null,
    private val title: String? = null,
    @SerialName("group_name") private val groupName: List<String>? = null,
    @SerialName("publish_at") private val publishAt: String? = null,
    @SerialName("created_at") private val createdAt: String,
) {
    fun toSChapter(mangaId: String) = SChapter.create().apply {
        // Path form of the site's chapter address, so the default getChapterUrl() resolves.
        url = "/comic/$mangaId/$hid-chapter-$chap-$lang"
        name = buildString {
            vol?.let { append("Vol. ", it, " ") }
            append("Chapter ", chap)
            this@ChapterDto.title?.let { append(": ", it) }
        }
        date_upload = Instant.tryParse(publishAt ?: createdAt)
        scanlator = groupName?.takeIf { it.isNotEmpty() }?.joinToString()
        chap.toFloatOrNull()?.let { chapter_number = it }
    }
}

@Serializable
class ImageDto(
    private val b2key: String,
) {
    fun toPage(index: Int) = Page(index, imageUrl = "$IMAGE_HOST/$b2key")
}

@Serializable
class FilterData(
    val genres: List<Genre> = emptyList(),
    val tags: List<Tag> = emptyList(),
)
