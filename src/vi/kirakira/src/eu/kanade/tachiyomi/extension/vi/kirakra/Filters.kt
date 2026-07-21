package eu.kanade.tachiyomi.extension.vi.kirakira

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class GenreFilter(private val genres: List<GenreOption>) :
    Filter.Select<String>(
        "Thể loại",
        genres.map { it.name }.toTypedArray(),
    ) {
    val selected: GenreOption
        get() = genres[state]
}

fun getFilters(genres: List<GenreOption>): FilterList {
    if (genres.isEmpty()) return FilterList()

    return FilterList(
        Filter.Header("Lọc theo thể loại"),
        GenreFilter(listOf(GenreOption("Tất cả")) + genres),
    )
}

class GenreOption(
    val name: String,
    val id: String? = null,
)
