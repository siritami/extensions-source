package eu.kanade.tachiyomi.extension.vi.daomeoden

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchResponse(
    val data: List<BookDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

@Serializable
class PaginationDto(
    val page: Int = 1,
    val limit: Int = 0,
    val total: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("has_next") val hasNext: Boolean = false,
)

@Serializable
class ImagesDto(
    val cover: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
)

@Serializable
class BookDto(
    val id: Int = 0,
    val name: String = "",
    val title: String = "",
    val slug: String = "",
    val url: String = "",
    val status: String? = null,
    @SerialName("is_adult") val isAdult: Boolean = false,
    val images: ImagesDto = ImagesDto(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = name.ifEmpty { this@BookDto.title }
        url = this@BookDto.url
        thumbnail_url = images.coverUrl
        status = parseStatus(this@BookDto.status)
    }
}

@Serializable
class FiltersResponse(
    val data: FilterDataDto = FilterDataDto(),
)

@Serializable
class FilterDataDto(
    val categories: List<FilterOptionDto> = emptyList(),
    val tags: List<FilterOptionDto> = emptyList(),
)

@Serializable
class FilterOptionDto(
    val id: Int = 0,
    val name: String,
    val slug: String,
)

internal fun parseStatus(statusText: String?): Int = when (statusText?.trim()?.lowercase()) {
    "ongoing", "đang ra" -> SManga.ONGOING
    "completed", "hoàn thành", "full" -> SManga.COMPLETED
    "hiatus", "tạm ngưng" -> SManga.ON_HIATUS
    "cancelled", "dropped", "drop" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
