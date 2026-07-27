package eu.kanade.tachiyomi.extension.vi.zettruyen

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable

fun getFilters(genres: List<GenreOption>?) = FilterList(
    buildList {
        add(SortFilter())
        add(StatusFilter())
        add(TypeFilter())
        add(ChapterFilter())
        genres?.takeIf { it.isNotEmpty() }?.let {
            add(GenreFilter(it.map { genre -> Genre(genre.name, genre.id) }))
        }
    },
)

@Serializable
class GenreOption(
    val name: String,
    val id: String,
)

class SortFilter :
    Filter.Select<String>(
        "Sắp xếp",
        arrayOf("Mới cập nhật", "Xếp hạng", "Số lượng bookmark", "Tên A-Z", "Tên Z-A"),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "rating"
        2 -> "bookmark"
        3 -> "name_asc"
        4 -> "name_desc"
        else -> "latest"
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Trạng thái",
        arrayOf("Tất cả", "Đang tiến hành", "Hoàn thành"),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "Đang"
        2 -> "Hoàn thành"
        else -> "all"
    }
}

class TypeFilter :
    Filter.Select<String>(
        "Loại truyện",
        arrayOf("Tất cả", "Manga", "Manhua", "Manhwa"),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "manga"
        2 -> "manhua"
        3 -> "manhwa"
        else -> "all"
    }
}

class ChapterFilter :
    Filter.Select<String>(
        "Số chương",
        arrayOf(
            "Tất cả",
            "1 - 100 chương",
            "101 - 500 chương",
            "501 - 1000 chương",
            "Trên 1000 chương",
        ),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "1-100"
        2 -> "101-500"
        3 -> "501-1000"
        4 -> "1001-plus"
        else -> "all"
    }
}

class Genre(name: String, val id: String) : Filter.CheckBox(name, false)

class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Thể loại", genres)
