package eu.kanade.tachiyomi.extension.vi.kamicomic

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable

fun getFilters(genres: List<GenreOption>?): FilterList = FilterList(
    buildList {
        genres?.let { add(GenreFilter(it.map { genre -> Genre(genre.name, genre.slug) })) }
    },
)

@Serializable
class GenreOption(
    val name: String,
    val slug: String,
)

class Genre(name: String, val slug: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Thể loại", genres)
