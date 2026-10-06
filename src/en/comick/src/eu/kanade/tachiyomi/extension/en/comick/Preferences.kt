package eu.kanade.tachiyomi.extension.en.comick

import android.content.SharedPreferences

internal const val KEY_TAG_DISPLAY = "tag_display"
internal const val KEY_CONTENT_RATING = "content_rating"
internal const val KEY_GENRE_BLOCKLIST = "genre_blocklist"
internal const val KEY_GENRE_LABELS = "genre_labels"

/** Whether genre lists include the API's `Content` group, which flags explicit material. */
internal enum class TagDisplay(val prefValue: Int) {
    SHORT(0),
    FULL(1),
    ;

    companion object {
        fun from(value: Int) = entries.firstOrNull { it.prefValue == value } ?: SHORT
    }
}

/** Ordered from least to most explicit; the empty value keeps everything. */
internal val CONTENT_RATINGS = listOf(
    "Any" to "",
    "Safe" to "safe",
    "Suggestive" to "suggestive",
    "Erotic" to "erotica",
    "Pornographic" to "pornographic",
)

internal fun SharedPreferences.maxContentRating(): String = getString(KEY_CONTENT_RATING, "").orEmpty()

/** Genre ids the user hid from the browse lists. Ids are stored so renamed genres keep matching. */
internal fun SharedPreferences.genreBlocklist(): Set<Int> = getStringSet(KEY_GENRE_BLOCKLIST, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

internal fun isContentAllowed(rating: String?, max: String): Boolean {
    if (max.isEmpty()) return true
    val order = CONTENT_RATINGS.map { it.second }
    // Unrated entries are kept so an unknown value never silently hides a title.
    val level = order.indexOf(rating)
    if (level < 0) return true
    return level <= order.indexOf(max)
}
