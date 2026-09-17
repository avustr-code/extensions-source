package eu.kanade.tachiyomi.extension.en.novatoon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class SortFilter :
    Filter.Select<String>(
        "Order By",
        arrayOf("Default", "Title A-Z", "Title Z-A", "Update", "Added", "Popular"),
        0,
    ) {
    val sort: String
        get() = when (state) {
            1 -> "title"
            2 -> "titlereverse"
            3 -> "update"
            4 -> "latest"
            5 -> "popular"
            else -> ""
        }
}

class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed", "Hiatus"), 0) {
    val value: String
        get() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            else -> ""
        }
}

class TypeFilter : Filter.Select<String>("Type", arrayOf("All", "Manga", "Manhwa", "Manhua", "Comic", "Novel"), 0) {
    val value: String
        get() = when (state) {
            1 -> "manga"
            2 -> "manhwa"
            3 -> "manhua"
            4 -> "comic"
            5 -> "novel"
            else -> ""
        }
}

class Genre(name: String, val id: String) : Filter.TriState(name)

class GenreFilter :
    Filter.Group<Genre>(
        "Genres",
        listOf(
            Genre("Action", "action"),
            Genre("Adventure", "adventure"),
            Genre("Comedy", "comedy"),
            Genre("Drama", "drama"),
            Genre("Fantasy", "fantasy"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Josei", "josei"),
            Genre("Martial Arts", "martial-arts"),
            Genre("Mecha", "mecha"),
            Genre("Psychological", "psychological"),
            Genre("Romance", "romance"),
            Genre("School Life", "school-life"),
            Genre("Seinen", "seinen"),
            Genre("Shounen", "shounen"),
            Genre("Slice of Life", "slice-of-life"),
            Genre("Supernatural", "supernatural"),
            Genre("Tragedy", "tragedy"),
        ),
    )

fun novatoonFilters() = FilterList(
    SortFilter(),
    StatusFilter(),
    TypeFilter(),
    GenreFilter(),
)
