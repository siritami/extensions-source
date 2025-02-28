package eu.kanade.tachiyomi.extension.vi.tusachxinhxinh

import android.content.SharedPreferences
import android.util.Base64
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.a3manga.A3Manga
import eu.kanade.tachiyomi.multisrc.a3manga.CipherDto
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferences
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

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
