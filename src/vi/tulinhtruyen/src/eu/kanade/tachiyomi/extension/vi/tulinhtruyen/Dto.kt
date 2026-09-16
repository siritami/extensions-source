package eu.kanade.tachiyomi.extension.vi.tulinhtruyen

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

@Serializable
class MangaListResponseDto(
    val result: String? = null,
    val response: String? = null,
    val data: List<MangaDataDto> = emptyList(),
    val limit: Int = 20,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
class MangaDetailsResponseDto(
    val result: String? = null,
    val data: MangaDataDto,
)

@Serializable
class MangaDataDto(
    val id: String,
    val type: String? = null,
    val attributes: MangaAttributesDto,
    val relationships: List<RelationshipDto> = emptyList(),
) {
    fun getTitle(): String = attributes.title["vi"]
        ?: attributes.title["en"]
        ?: attributes.title.values.firstOrNull()
        ?: "Tu Linh Manga"

    fun getCoverUrl(baseUrl: String): String? {
        val coverRel = relationships.firstOrNull { it.type == "cover_art" }
        val fileName = coverRel?.attributes?.fileName ?: return null
        return if (fileName.startsWith("http")) {
            fileName
        } else {
            "$baseUrl/covers/$id/$fileName"
        }
    }

    fun getAuthor(): String? = relationships.firstOrNull { it.type == "author" }?.attributes?.name

    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "/manga/$id"
        title = getTitle()
        thumbnail_url = getCoverUrl(baseUrl)
    }

    fun toDetailedSManga(baseUrl: String): SManga = SManga.create().apply {
        title = getTitle()
        thumbnail_url = getCoverUrl(baseUrl)
        val authorName = getAuthor()
        author = authorName
        artist = authorName

        val desc = attributes.description["vi"]
            ?: attributes.description["en"]
            ?: attributes.description.values.firstOrNull()

        val viewsStr = attributes.views?.content ?: "0"
        val fullDesc = buildString {
            append("👁 Lượt xem: ").append(viewsStr).append(" lượt đọc\n────────────────\n")
            if (!desc.isNullOrEmpty()) {
                append(desc)
            }
        }
        description = fullDesc

        status = when (attributes.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        genre = attributes.tags.mapNotNull { it.name }.joinToString()
    }
}

@Serializable
class MangaAttributesDto(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val status: String? = null,
    val publicationDemographic: String? = null,
    val contentRating: String? = null,
    val tags: List<TagDto> = emptyList(),
    val views: JsonPrimitive? = null,
)

@Serializable
class TagDto(
    val id: String,
    val name: String? = null,
)

@Serializable
class RelationshipDto(
    val id: String? = null,
    val type: String,
    val attributes: RelationshipAttributesDto? = null,
)

@Serializable
class RelationshipAttributesDto(
    val fileName: String? = null,
    val name: String? = null,
)

@Serializable
class ChapterListResponseDto(
    val result: String? = null,
    val data: List<ChapterDataDto> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
class ChapterDataDto(
    val id: String,
    val attributes: ChapterAttributesDto,
    val relationships: List<RelationshipDto> = emptyList(),
) {
    fun toSChapter(): SChapter {
        val chapterNum = attributes.chapter.orEmpty()
        val chapterTitle = attributes.title

        val chapterName = when {
            chapterNum.isEmpty() -> chapterTitle?.takeIf { it.isNotEmpty() } ?: "Chương"
            chapterTitle.isNullOrEmpty() -> "Chương $chapterNum"
            else -> "Chương $chapterNum: $chapterTitle"
        }

        val uploadDate = Instant.tryParse(attributes.publishAt)

        val groupName = relationships.firstOrNull { it.type == "scanlation_group" }
            ?.attributes?.name ?: "TuLinh Team"
        val views = attributes.views?.content ?: "0"
        val scanlatorInfo = "$groupName • 👁 $views"

        return SChapter.create().apply {
            url = "/at-home/server/$id"
            name = chapterName
            date_upload = uploadDate
            scanlator = scanlatorInfo
        }
    }
}

@Serializable
class ChapterAttributesDto(
    val chapter: String? = null,
    val title: String? = null,
    val publishAt: String? = null,
    val views: JsonPrimitive? = null,
)

@Serializable
class ChapterPagesResponseDto(
    val result: String? = null,
    val baseUrl: String? = null,
    val chapter: ChapterPayloadDto? = null,
)

@Serializable
class ChapterPayloadDto(
    val hash: String,
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)

@Serializable
class PairingRequestDto(
    val pairingCode: String,
)

@Serializable
class PairingResponseDto(
    val result: String? = null,
    val token: String? = null,
    val message: String? = null,
)
