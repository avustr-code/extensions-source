package eu.kanade.tachiyomi.extension.en.comick

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComickBrowseItem(
    val id: Int? = null,
    val hid: String,
    val slug: String,
    val title: String,
    val desc: String? = null,
    val status: Int? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val demographic: Int? = null,
    val genres: List<Int>? = null,
    @SerialName("md_covers") val mdCovers: List<ComickCover>? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("user_follow_count") val userFollowCount: Int? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
    @SerialName("last_chapter") val lastChapter: Float? = null,
    @SerialName("md_titles") val mdTitles: List<ComicTitle> = emptyList(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        val enTitle = mdTitles.firstOrNull { it.lang == "en" && it.isDefault == true }?.title
            ?: mdTitles.firstOrNull { it.lang == "en" }?.title
            ?: this@ComickBrowseItem.title
        url = "$slug|$hid"
        title = enTitle
        thumbnail_url = coverUrl ?: mdCovers?.firstOrNull()?.let { "https://meo.comick.pictures/${it.b2key}" }
        description = desc
    }
}

@Serializable
data class ComickCover(
    val w: Int? = null,
    val h: Int? = null,
    val b2key: String,
)

@Serializable
data class ComicDetailRoot(
    val comic: ComicDetail,
    val authors: List<ComicPerson> = emptyList(),
    val artists: List<ComicPerson> = emptyList(),
)

@Serializable
data class ComicDetail(
    val hid: String,
    val title: String,
    val slug: String,
    val desc: String? = null,
    val parsed: String? = null,
    val status: Int? = null,
    @SerialName("translation_completed") val translationCompleted: Boolean? = null,
    val country: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("md_covers") val mdCovers: List<ComickCover>? = null,
    @SerialName("md_titles") val mdTitles: List<ComicTitle> = emptyList(),
    @SerialName("md_comic_md_genres") val mdGenres: List<ComicGenreWrap> = emptyList(),
    @SerialName("mu_comics") val muComics: MuComics? = null,
    val year: Int? = null,
    @SerialName("bayesian_rating") val bayesianRating: String? = null,
    val rating: String? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    @SerialName("user_follow_count") val userFollowCount: Int? = null,
)

@Serializable
data class ComicPerson(val name: String, val slug: String? = null)

@Serializable
data class ComicTitle(val title: String, val lang: String? = null, @SerialName("is_default") val isDefault: Boolean? = null)

@Serializable
data class ComicGenreWrap(@SerialName("md_genres") val mdGenres: GenreName? = null)

@Serializable
data class GenreName(val name: String, val slug: String? = null)

@Serializable
data class MuComics(
    @SerialName("mu_comic_categories") val muComicCategories: List<MuCategoryWrap> = emptyList(),
)

@Serializable
data class MuCategoryWrap(
    @SerialName("mu_categories") val muCategories: MuCategory? = null,
    @SerialName("positive_vote") val positiveVote: Int = 0,
    @SerialName("negative_vote") val negativeVote: Int = 0,
)

@Serializable
data class MuCategory(val title: String, val slug: String? = null)

@Serializable
data class ChapterListResponse(
    val chapters: List<ComickChapter> = emptyList(),
    val total: Int? = null,
)

@Serializable
data class ComickChapter(
    val hid: String,
    val chap: String? = null,
    val vol: String? = null,
    val title: String? = null,
    val lang: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("publish_at") val publishAt: String? = null,
    @SerialName("group_name") val groupName: List<String> = emptyList(),
)

@Serializable
data class ChapterDetailRoot(
    val chapter: ChapterDetail,
)

@Serializable
data class ChapterDetail(
    val hid: String,
    @SerialName("md_images") val mdImages: List<ChapterImage> = emptyList(),
    @SerialName("md_comics") val mdComics: ComicDetail? = null,
)

@Serializable
data class ChapterImage(
    val b2key: String,
    val w: Int? = null,
    val h: Int? = null,
    val name: String? = null,
)

@Serializable
data class HtmlComicData(
    val title: String,
    val slug: String,
    @SerialName("default_thumbnail") val thumbnail: String? = null,
    val status: Int? = null,
    @SerialName("translation_completed") val translationCompleted: Boolean = false,
    val artists: List<NameOnly> = emptyList(),
    val authors: List<NameOnly> = emptyList(),
    val desc: String? = null,
    val parsed: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val country: String? = null,
    @SerialName("md_comic_md_genres") val genres: List<ComicGenreWrap> = emptyList(),
    @SerialName("md_titles") val titles: List<ComicTitle> = emptyList(),
    val year: Int? = null,
    @SerialName("bayesian_rating") val bayesianRating: String? = null,
    val rating: String? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
)

@Serializable
data class NameOnly(val name: String)

@Serializable
data class HtmlPageData(
    val chapter: HtmlChapterImages,
) {
    @Serializable
    data class HtmlChapterImages(val images: List<HtmlImage>)

    @Serializable
    data class HtmlImage(val url: String)
}
