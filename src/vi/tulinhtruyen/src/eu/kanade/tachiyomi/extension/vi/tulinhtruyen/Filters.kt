package eu.kanade.tachiyomi.extension.vi.tulinhtruyen

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement

fun getFilters(data: JsonElement?): FilterList {
    val filters = mutableListOf<Filter<*>>()

    val scrapedTags = runCatching {
        data?.parseAs<MangaListResponseDto>()?.data
            ?.flatMap { it.attributes.tags }
            ?.distinctBy { it.id }
            ?.sortedBy { it.name ?: it.id }
            ?.map { TagOption(it.name ?: it.id, it.id) }
    }.getOrNull().orEmpty()

    val tags = scrapedTags.ifEmpty { defaultTags }
    if (tags.isNotEmpty()) {
        filters.add(TagFilter(tags))
    }

    filters.add(StatusFilter())
    filters.add(DemographicFilter())
    filters.add(ContentRatingFilter())

    return FilterList(filters)
}

class TagOption(val name: String, val id: String)

class TagCheckBox(name: String, val tagId: String) : Filter.CheckBox(name)

class TagFilter(tags: List<TagOption>) :
    Filter.Group<TagCheckBox>(
        "Thể loại",
        tags.map { TagCheckBox(it.name, it.id) },
    ) {
    fun selectedTagIds(): List<String> = state.filter { it.state }.map { it.tagId }
}

class StatusFilter : Filter.Select<String>(
    "Trạng thái",
    STATUS_OPTIONS.map { it.first }.toTypedArray(),
) {
    fun selectedValue(): String = STATUS_OPTIONS[state].second

    companion object {
        private val STATUS_OPTIONS = arrayOf(
            "Tất cả" to "",
            "Đang tiến hành" to "ongoing",
            "Hoàn thành" to "completed",
            "Tạm ngưng" to "hiatus",
            "Đã huỷ" to "cancelled",
        )
    }
}

class DemographicFilter : Filter.Select<String>(
    "Đối tượng độc giả",
    DEMOGRAPHIC_OPTIONS.map { it.first }.toTypedArray(),
) {
    fun selectedValue(): String = DEMOGRAPHIC_OPTIONS[state].second

    companion object {
        private val DEMOGRAPHIC_OPTIONS = arrayOf(
            "Tất cả" to "",
            "Shounen" to "shounen",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Josei" to "josei",
        )
    }
}

class ContentRatingFilter : Filter.Select<String>(
    "Phân loại độ tuổi",
    CONTENT_RATING_OPTIONS.map { it.first }.toTypedArray(),
) {
    fun selectedValue(): String = CONTENT_RATING_OPTIONS[state].second

    companion object {
        private val CONTENT_RATING_OPTIONS = arrayOf(
            "Tất cả" to "",
            "An toàn (Safe)" to "safe",
            "Gợi cảm (Suggestive)" to "suggestive",
            "Nhạy cảm (Erotica)" to "erotica",
            "18+ (Pornographic)" to "pornographic",
        )
    }
}

private val defaultTags = listOf(
    TagOption("Adventure", "adventure"),
    TagOption("Award Winning", "award winning"),
    TagOption("Comedy", "comedy"),
    TagOption("Demons", "demons"),
    TagOption("Drama", "drama"),
    TagOption("Fantasy", "fantasy"),
    TagOption("Gender Bender", "gender bender"),
    TagOption("Gore", "gore"),
    TagOption("Magic", "magic"),
    TagOption("Monsters", "monsters"),
    TagOption("Mystery", "mystery"),
    TagOption("Ninja", "ninja"),
    TagOption("Office Workers", "office workers"),
    TagOption("Romance", "romance"),
    TagOption("Samurai", "samurai"),
    TagOption("School Life", "school life"),
    TagOption("Slice of Life", "slice of life"),
    TagOption("Tragedy", "tragedy"),
)
