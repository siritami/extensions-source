package eu.kanade.tachiyomi.extension.vi.cuutruyen

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.util.Locale

@Serializable
class MangaListResponse(
    val data: List<MangaListItem>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
class MangaListItem(
    private val id: Int,
    private val name: String,
    @SerialName("cover_url") private val coverUrl: String,
    @SerialName("cover_mobile_url") private val coverMobileUrl: String? = null,
) {
    fun toSManga(useMobileCover: Boolean): SManga = SManga.create().apply {
        url = id.toString()
        title = name
        thumbnail_url = if (useMobileCover) coverMobileUrl ?: coverUrl else coverUrl
    }
}

@Serializable
class MangaDetailResponse(val data: MangaDetailDto)

@Serializable
class MangaDetailDto(
    private val id: Int,
    private val name: String,
    @SerialName("cover_url") private val coverUrl: String,
    @SerialName("cover_mobile_url") private val coverMobileUrl: String? = null,
    private val author: AuthorDto? = null,
    @SerialName("full_description") private val fullDescription: String? = null,
    private val tags: List<TagDto>,
) {
    fun toSManga(useMobileCover: Boolean): SManga = SManga.create().apply {
        url = id.toString()
        title = name
        thumbnail_url = if (useMobileCover) coverMobileUrl ?: coverUrl else coverUrl
        author = this@MangaDetailDto.author?.name
        genre = tags.joinToString { it.name }
        description = fullDescription.toPlainText()
        status = tags.toStatus()
    }

    private fun List<TagDto>.toStatus(): Int {
        val statusTags = joinToString(" ") { it.name }.lowercase(Locale.ROOT)
        return when {
            "tạm ngưng" in statusTags -> SManga.ON_HIATUS
            "hoàn thành" in statusTags -> SManga.COMPLETED
            else -> SManga.ONGOING
        }
    }
}

@Serializable
class AuthorDto(val name: String)

@Serializable
class ChapterListResponse(val data: List<ChapterDto>)

@Serializable
class ChapterDto(
    private val id: Int,
    private val number: String,
    private val name: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        url = "$mangaId/$id"
        name = buildString {
            append("Chương ")
            append(number)
            this@ChapterDto.name?.takeIf { it.isNotEmpty() }?.let {
                append(' ')
                append(it)
            }
        }
        date_upload = createdAt?.let(Instant::parseOrNull)?.toEpochMilliseconds() ?: 0L
    }
}

@Serializable
class ChapterReaderResponse(val data: ChapterReaderDto)

@Serializable
class ChapterReaderDto(val pages: List<ChapterPageDto>)

@Serializable
class ChapterPageDto(
    val order: Int,
    @SerialName("image_url") private val imageUrl: String,
    @SerialName("drm_data") private val drmData: String? = null,
) {
    fun imageUrlWithDrm(): String = imageUrl.toHttpUrl().newBuilder()
        .apply {
            drmData?.takeIf(String::isNotBlank)?.let {
                fragment("drm_data=$it")
            }
        }
        .build()
        .toString()
}

@Serializable
class TagResponse(val data: TagGroupsDto)

@Serializable
class TagGroupsDto(
    @SerialName("common_tags") private val commonTags: List<TagDto>,
    @SerialName("warning_tags") private val warningTags: List<TagDto>,
    @SerialName("normal_tags") private val normalTags: List<TagDto>,
) {
    fun allTags(): List<TagOption> = (commonTags + warningTags + normalTags)
        .distinctBy { it.id }
        .map { TagOption(it.name, it.id) }
}

@Serializable
class TagDto(
    val id: Int,
    val name: String,
)

internal fun String?.toPlainText(): String? = this
    ?.let(Jsoup::parseBodyFragment)
    ?.wholeText()
    ?.takeIf { it.isNotEmpty() }