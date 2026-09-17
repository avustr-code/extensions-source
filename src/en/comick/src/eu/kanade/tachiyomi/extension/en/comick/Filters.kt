package eu.kanade.tachiyomi.extension.en.comick

import eu.kanade.tachiyomi.source.model.Filter

abstract class ComickSelectFilter(
    name: String,
    private val entries: List<Pair<String, String>>,
) : Filter.Select<String>(name, entries.map { it.first }.toTypedArray()) {
    val selectedValue: String? get() = entries[state].second.takeIf { it.isNotEmpty() }
}

class ComickCheckBox(name: String, val value: String) : Filter.CheckBox(name)

abstract class ComickCheckGroup(
    name: String,
    entries: List<Pair<String, String>>,
) : Filter.Group<ComickCheckBox>(name, entries.map { ComickCheckBox(it.first, it.second) }) {
    val checkedValues: List<String> get() = state.filter { it.state }.map { it.value }
}

class ComickTriState(name: String, val slug: String) : Filter.TriState(name)

abstract class ComickTriGroup(
    name: String,
    options: List<Pair<String, String>>,
) : Filter.Group<ComickTriState>(name, options.map { ComickTriState(it.first, it.second) }) {
    val includedSlugs: List<String> get() = state.filter { it.isIncluded() }.map { it.slug }
    val excludedSlugs: List<String> get() = state.filter { it.isExcluded() }.map { it.slug }
}

private val sortEntries = listOf(
    "Latest" to "created_at",
    "Popular" to "user_follow_count",
    "Rating" to "rating",
    "Last Updated" to "uploaded",
)

class SortFilter :
    Filter.Sort(
        "Sort",
        sortEntries.map { it.first }.toTypedArray(),
        Filter.Sort.Selection(0, false),
    ) {
    val apiValue: String? get() = state?.let { sortEntries[it.index].second }
}

class DemographicFilter :
    ComickCheckGroup(
        "Demographic",
        listOf(
            "Shounen" to "1",
            "Josei" to "2",
            "Seinen" to "3",
            "Shoujo" to "4",
            "None" to "0",
        ),
    )

class ContentRatingFilter :
    ComickSelectFilter(
        "Content Rating",
        listOf(
            "All" to "",
            "Safe" to "safe",
            "Suggestive" to "suggestive",
            "Erotica" to "erotica",
            "Pornographic" to "pornographic",
        ),
    )

class StatusFilter :
    ComickSelectFilter(
        "Status",
        listOf(
            "All" to "",
            "Ongoing" to "1",
            "Completed" to "2",
            "Cancelled" to "3",
            "Hiatus" to "4",
        ),
    )

class TypeFilter :
    ComickCheckGroup(
        "Type",
        listOf(
            "Manga (JP)" to "jp",
            "Manhwa (KR)" to "kr",
            "Manhua (CN)" to "cn",
            "Others" to "others",
        ),
    )

class GenreTextFilter : Filter.Text("Genres (comma, -exclude)")

class TagTextFilter : Filter.Text("Tags (comma, -exclude)")

class YearFromFilter :
    ComickSelectFilter(
        "Year From",
        buildList {
            add("Any" to "")
            val cur = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            for (y in cur downTo 1990) add(y.toString() to y.toString())
            add("Before 1990" to "0")
        },
    )

class YearToFilter :
    ComickSelectFilter(
        "Year To",
        buildList {
            add("Any" to "")
            val cur = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            for (y in cur downTo 1990) add(y.toString() to y.toString())
            add("Before 1990" to "0")
        },
    )
