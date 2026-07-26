package eu.kanade.tachiyomi.extension.vi.truyenmm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class TruyenMMGetTopicResponse(
    val topic: TruyenMMTopic? = null,
)

@Serializable
class TruyenMMTopic(
    val chapters: List<TruyenMMChapter>? = null,
)

@Serializable
class TruyenMMChapter(
    val name: String,
    val id: String,
    @SerialName("update_time") val updateTime: Long? = null,
)

@Serializable
class GenreOption(
    val name: String,
    val slug: String,
)
