package eu.kanade.tachiyomi.extension.vi.springblossoms

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class MangaDto(
    private val title: String,
    @SerialName("serial_id") val serialId: Int,
    private val author: String? = null,
    private val status: String? = null,
    @SerialName("cover_image_path") private val coverImagePath: String? = null,
    private val genres: List<String> = emptyList(),
    private val synopsis: String? = null,
    private val description: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/manga/$serialId"
        title = this@MangaDto.title
        author = this@MangaDto.author
        genre = this@MangaDto.genres.joinToString()
        status = parseStatus(this@MangaDto.status)
        thumbnail_url = this@MangaDto.coverImagePath
        description = this@MangaDto.synopsis ?: this@MangaDto.description
    }
}

@Serializable
class MangaIdDto(val id: String)

@Serializable
class ChapterDto(
    private val id: String,
    @SerialName("chapter_number") private val chapterNumber: Float,
    private val title: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    @SerialName("publish_date") private val publishDate: String? = null,
) {
    fun toSChapter(serialId: String) = SChapter.create().apply {
        url = "/manga/$serialId/read/$id"
        name = buildString {
            append("Chapter ${chapterNumber.toString().removeSuffix(".0")}")
            if (!this@ChapterDto.title.isNullOrBlank()) append(" - ${this@ChapterDto.title}")
        }
        chapter_number = chapterNumber
        date_upload = Instant.parseOrNull(publishDate ?: createdAt.orEmpty())
            ?.toEpochMilliseconds()
            ?: 0L
    }
}

@Serializable
class PagesDto(val pages: List<String> = emptyList())

private fun parseStatus(status: String?) = when (status) {
    "Ongoing" -> SManga.ONGOING
    "Completed" -> SManga.COMPLETED
    "Hiatus" -> SManga.ON_HIATUS
    else -> SManga.UNKNOWN
}
