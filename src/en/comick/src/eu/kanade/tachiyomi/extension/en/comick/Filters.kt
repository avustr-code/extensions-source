package eu.kanade.tachiyomi.extension.en.comick

import eu.kanade.tachiyomi.source.model.Filter

/** Single choice filter; an empty value means the parameter is left out of the request. */
abstract class OptionFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String?
        get() = options.getOrNull(state)?.second?.takeIf { it.isNotEmpty() }
}

/** Multi choice filter for parameters the API only accepts as inclusions. */
abstract class CheckGroupFilter(
    name: String,
    options: List<Pair<String, String>>,
) : Filter.Group<ValueCheckBox>(name, options.map { ValueCheckBox(it.first, it.second) }) {
    val selected: List<String> get() = state.filter { it.state }.map { it.value }
}

/**
 * Multi choice filter for parameters the API accepts as inclusions and exclusions
 * (`genres`/`excludes`), so a single list covers both directions.
 */
abstract class SlugGroupFilter(
    name: String,
    options: List<Pair<String, String>>,
) : Filter.Group<ValueTriState>(name, options.map { ValueTriState(it.first, it.second) }) {
    val included: List<String> get() = state.filter { it.isIncluded() }.map { it.slug }
    val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.slug }
}

class ValueCheckBox(
    name: String,
    val value: String,
) : Filter.CheckBox(name)

class ValueTriState(
    name: String,
    val slug: String,
) : Filter.TriState(name)

class GenreFilter(genres: List<Genre>) :
    SlugGroupFilter(
        "Genres",
        genres.sortedBy { it.name }.map { it.name to it.slug },
    )

class TagFilter(tags: List<Tag>) :
    SlugGroupFilter(
        "Tags",
        tags.sortedBy { it.name }.map { it.name to it.slug },
    )

class OriginFilter :
    CheckGroupFilter(
        "Origin",
        listOf(
            "Japan" to "jp",
            "South Korea" to "kr",
            "China" to "cn",
            "Hong Kong" to "hk",
            "United Kingdom" to "gb",
        ),
    )

class DemographicFilter :
    OptionFilter(
        "Demographic",
        listOf(
            "Any" to "",
            "Shounen" to "1",
            "Shoujo" to "2",
            "Seinen" to "3",
            "Josei" to "4",
            "None" to "0",
        ),
    )

class StatusFilter :
    OptionFilter(
        "Status",
        listOf(
            "Any" to "",
            "Ongoing" to "1",
            "Completed" to "2",
            "Cancelled" to "3",
            "Hiatus" to "4",
        ),
    )

class ContentRatingFilter : OptionFilter("Content Rating", CONTENT_RATINGS)

class ExcludedContentRatingFilter : CheckGroupFilter("Exclude Content Rating", CONTENT_RATINGS.filterNot { it.second.isEmpty() })

class ViolenceRatingFilter :
    OptionFilter(
        "Violence Rating",
        listOf(
            "Any" to "",
            "None" to "none",
            "Graphic" to "graphic",
        ),
    )

class ExcludedViolenceRatingFilter :
    CheckGroupFilter(
        "Exclude Violence Rating",
        listOf(
            "None" to "none",
            "Graphic" to "graphic",
        ),
    )

class MinimumChaptersFilter : Filter.Text("Minimum chapters") {
    val value: String? get() = state.trim().toIntOrNull()?.toString()
}

class MaximumChaptersFilter : Filter.Text("Maximum chapters") {
    val value: String? get() = state.trim().toIntOrNull()?.toString()
}

class MinimumRatingFilter :
    OptionFilter(
        "Minimum Rating",
        listOf(
            "Any" to "",
            "5.0+" to "5",
            "6.0+" to "6",
            "7.0+" to "7",
            "8.0+" to "8",
            "9.0+" to "9",
        ),
    )

class CompletedFilter : Filter.CheckBox("Translation Completed")

class SortFilter :
    OptionFilter(
        "Sort",
        listOf(
            // The first entry is the default, matching what the popular list shows.
            "Most Followed" to "user_follow_count",
            "Default" to "",
            "Most Viewed" to "view",
            "Bayesian Rating" to "rating",
            "Average Rating" to "average_rating",
            "Latest" to "created_at",
            "Last Uploaded" to "uploaded",
            "Most Recent" to "follow",
        ),
    )

/** Single text field holding a range, e.g. `2010-2020`; either side may be left out. */
class YearRangeFilter : Filter.Text("Year Range (2010-2020)") {
    private fun bound(index: Int): String? = PATTERN.matchEntire(state)?.groupValues?.get(index)?.takeIf { it.isNotEmpty() }

    val from: String? get() = bound(1)
    val to: String? get() = bound(2)

    private companion object {
        val PATTERN = Regex("^\\s*(\\d{4})?\\s*-\\s*(\\d{4})?\\s*$")
    }
}
