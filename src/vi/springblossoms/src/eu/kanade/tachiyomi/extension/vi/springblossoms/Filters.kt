package eu.kanade.tachiyomi.extension.vi.springblossoms

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GenreFilter(genres: List<Pair<String, String>>) : Filter.Select<String>("Thể loại", genres.map { it.first }.toTypedArray()) {
    private val genreValues = genres.map { it.second }

    val selected get() = genreValues[state].takeIf(String::isNotEmpty)
}

class StatusFilter :
    Filter.Select<String>(
        "Trạng thái",
        arrayOf("Tất cả", "Đang thực hiện", "Đã hoàn thành", "Tạm ngưng", "Sắp ra mắt"),
    ) {
    val selected get() = when (state) {
        1 -> "Ongoing"
        2 -> "Completed"
        3 -> "Hiatus"
        4 -> "Upcoming"
        else -> null
    }
}

class SortFilter :
    Filter.Select<String>(
        "Sắp xếp",
        arrayOf("Cập nhật mới", "Phổ biến nhất", "Đánh giá cao", "A-Z"),
    ) {
    val selected get() = when (state) {
        0 -> "updated_at.desc"
        1 -> "view_count.desc"
        2 -> "rating.desc"
        3 -> "title.asc"
        else -> "updated_at.desc"
    }
}

class AdultFilter :
    Filter.Select<String>(
        "Nội dung 18+",
        arrayOf("Tất cả", "Chỉ 18+", "Không 18+"),
    ) {
    val selected get() = when (state) {
        1 -> true
        2 -> false
        else -> null
    }
}

private fun getGenreList(data: JsonElement?) = buildList {
    add("Tất cả" to "")

    data?.jsonArray
        .orEmpty()
        .flatMap { item ->
            item.jsonObject["genres"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { genre -> genre.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        }
        .distinct()
        .sorted()
        .forEach { genre -> add(genre to genre) }
}

fun getFilters(data: JsonElement?) = FilterList(
    GenreFilter(getGenreList(data)),
    StatusFilter(),
    SortFilter(),
    AdultFilter(),
)
