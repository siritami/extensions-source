package eu.kanade.tachiyomi.extension.vi.leesincomic

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable

@Serializable
class FilterOption(val name: String, val path: String)

@Serializable
class FilterData(
    val types: List<FilterOption> = emptyList(),
    val genres: List<FilterOption> = emptyList(),
    val groups: List<FilterOption> = emptyList(),
)

fun getFilters(data: FilterData?): FilterList = FilterList(
    Filter.Header("Tìm kiếm không kết hợp với bộ lọc"),
    TypeFilter(data?.types.orEmpty()),
    GenreFilter(data?.genres.orEmpty()),
    GroupFilter(data?.groups.orEmpty()),
)

class TypeFilter(options: List<FilterOption>) : UriPartFilter("Danh sách", options)

class GenreFilter(options: List<FilterOption>) : UriPartFilter("Thể loại", options)

class GroupFilter(options: List<FilterOption>) : UriPartFilter("Nhóm dịch", options)

open class UriPartFilter(
    name: String,
    options: List<FilterOption>,
) : Filter.Select<String>(
    name,
    (listOf(FilterOption("Tất cả", "")) + options).map { it.name }.toTypedArray(),
) {
    private val paths = (listOf(FilterOption("Tất cả", "")) + options).map { it.path }

    fun toUriPart(): String = paths.getOrNull(state).orEmpty()
}
