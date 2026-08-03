package eu.kanade.tachiyomi.extension.vi.cuutruyen

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable

@Serializable
class TagOption(val name: String, val id: Int)

class TagFilter(tags: List<TagOption>) :
    Filter.Group<Filter.CheckBox>(
        "Thẻ phổ biến",
        tags.map { TagCheckBox(it.name, it.id) },
    ) {
    fun selectedIds(): List<Int> = state.filter { it.state }.map { it.id }
}

private class TagCheckBox(name: String, val id: Int) : Filter.CheckBox(name)

fun getFilters(tags: List<TagOption>?): FilterList = if (tags.isNullOrEmpty()) {
    FilterList()
} else {
    FilterList(TagFilter(tags))
}
