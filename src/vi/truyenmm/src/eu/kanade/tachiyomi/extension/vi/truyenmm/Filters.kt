package eu.kanade.tachiyomi.extension.vi.truyenmm

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class GenreFilter(genres: List<GenreOption>) :
    Filter.Select<String>(
        "Thể loại",
        genres.map { it.name }.toTypedArray(),
    ) {
    private val slugs = genres.map { it.slug }

    fun toUriPart(): String = slugs[state]
}

fun getFilters(genres: List<GenreOption>?): FilterList =
    genres?.takeIf { it.isNotEmpty() }
        ?.let { FilterList(GenreFilter(it)) }
        ?: FilterList()
