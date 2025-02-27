package eu.kanade.tachiyomi.extension.vi.tusachxinhxinh

import kotlinx.serialization.Serializable

@Serializable
data class SearchResponseDto(
    val success: Boolean,
    val data: List<SearchMangaDto>,
)

@Serializable
data class SearchMangaDto(
    val title: String,
    val link: String,
    val img: String,
    val star: String,
    val vote: String,
    val cstatus: String,
    val isocm: Int,
)

@Serializable
data class CipherDto(
    val ciphertext: String,
    val iv: String,
    val salt: String,
)
