package eu.kanade.tachiyomi.extension.vi.tusachxinhxinh

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.a3manga.A3Manga
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferences
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TuSachXinhXinh :
    A3Manga(
        "Tủ Sách Xinh Xinh",
        "https://tusachxinhxinh6.info",
        "vi",
    ),
    ConfigurableSource {

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select(".comic-title-link a").attr("href"))
        title = element.select(".comic-title").text().trim()
        thumbnail_url = element.select(".img-thumbnail").attr("data-lazy-src")
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select(".info-title").text()
        author = document.select(".comic-info strong:contains(Tác giả) + span").text().trim()
        description = document.select(".intro-container p").text().substringBefore("— Xem Thêm —")
        genre = document.select(".comic-info .tags a").joinToString { tag ->
            tag.text().split(' ').joinToString(separator = " ") { word ->
                word.replaceFirstChar { it.titlecase() }
            }
        }
        thumbnail_url = document.select(".img-thumbnail").attr("data-lazy-src")

        val statusString = document.select(".comic-info strong:contains(Tình trạng) + span").text()
        status = when (statusString) {
            "Đang tiến hành" -> SManga.ONGOING
            "Trọn bộ " -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    protected fun decodeImgList(document: Document): String? {
        val htmlContentScript = document.selectFirst("script:containsData(htmlContent)")?.html()
            ?.substringAfter("var htmlContent=\"")
            ?.substringBefore("\";")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\/", "/")

        if (htmlContentScript.isNullOrEmpty()) return null

        val htmlContent = json.decodeFromString<CipherDto>(htmlContentScript)
        val ciphertext = Base64.decode(htmlContent.ciphertext, Base64.DEFAULT)
        val iv = htmlContent.iv.decodeHex()
        val salt = htmlContent.salt.decodeHex()

        val passwordScript = document.selectFirst("script:containsData(chapterHTML)")?.html()
            ?: throw Exception("Couldn't find password script.")
        val passphrase = passwordScript.substringAfter("var chapterHTML=CryptoJSAesDecrypt('")
            .substringBefore("',htmlContent")
            .replace("'+'", "")

        val keyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
        val key = SecretKeySpec(keyFactory.generateSecret(spec).encoded, "AES")

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
	
    override fun pageListParse(document: Document): List<Page> {
        // Try decoding encrypted image list first
        val imgListHtml = runCatching { decodeImgList(document) }.getOrNull()
        if (imgListHtml != null) {
            return Jsoup.parseBodyFragment(imgListHtml).select("img").mapIndexed { idx, element ->
                val encryptedUrl = element.attributes().find { it.key.startsWith("data") }?.value
                val effectiveUrl = encryptedUrl?.decodeUrl() ?: element.attr("abs:src")
                Page(idx, imageUrl = effectiveUrl)
            }
        }

        // Fallback to direct images in #view-chapter
        val images = document.select("#view-chapter img")
        if (images.isNotEmpty()) {
            return images.mapIndexed { idx, element ->
                val src = element.attr("abs:src")
                Page(idx, imageUrl = src)
            }
        }

        throw Exception("No images found in chapter page.")
    }

    private val preferences: SharedPreferences = getPreferences()

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != super.baseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, super.baseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, super.baseUrl)
                    .apply()
            }
        }
    }

    override val baseUrl by lazy { getPrefBaseUrl() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(super.baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: ${super.baseUrl}"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP = "Khởi chạy lại ứng dụng để áp dụng thay đổi."
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
    }
}
