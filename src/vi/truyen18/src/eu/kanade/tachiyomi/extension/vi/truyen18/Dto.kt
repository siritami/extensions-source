package eu.kanade.tachiyomi.extension.vi.truyen18

import kotlinx.serialization.Serializable

@Serializable
class ChapterData(
    val chapter: ReaderChapter,
)

@Serializable
class ReaderChapter(
    val slug: String,
    val content: String,
)