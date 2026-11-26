package eu.kanade.tachiyomi.extension.vi.panomic

import eu.kanade.tachiyomi.multisrc.koi.Koi
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.addCookie
import keiyoushi.network.post
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.ui.askPassword
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

@Source
abstract class Panomic : Koi() {

    override val mangaPath = "truyen"

    override fun isExcludedMangaUrl(url: String): Boolean = "/$mangaPath/novel-" in url

    // ========================= Passcode token =============================

    private val preferences by getPreferencesLazy()

    private var currentPasscodeToken: String
        get() = preferences.getString("pref_passcode_token", DEFAULT_PASSCODE_TOKEN)
            ?: DEFAULT_PASSCODE_TOKEN
        set(value) = preferences.edit().putString("pref_passcode_token", value).apply()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = super.configureClient()
        .addCookie {
            listOf("a3_site_passcode_token" to currentPasscodeToken)
        }

    // =============================== Pages ================================

    override suspend fun unlockChapterHtml(html: String, chapterUrl: String, chapter: SChapter): String {
        val loginButton = Jsoup.parse(html).selectFirst(
            "button.v-btn.v-big-btn[data-toggle=modal][data-target='#info-modal']",
        )
        if (loginButton?.text() == "Đăng nhập") {
            throw Exception("Đăng nhập webview bằng tài khoản phù hợp để xem chương này")
        }

        var pageHtml = super.unlockChapterHtml(html, chapterUrl, chapter)
        if ("entered_secret_code" !in pageHtml) return pageHtml

        val document = Jsoup.parse(pageHtml, chapterUrl)
        val hint = document.selectFirst(".gate-box p")?.text()
        val message = if (!hint.isNullOrBlank()) {
            "Chương này yêu cầu mã bí mật\n\n$hint"
        } else {
            "Chương này yêu cầu mã bí mật"
        }
        val password = askPassword(
            title = chapter.name,
            message = message,
            hint = "Mã bí mật",
        ) ?: throw Exception("Đã hủy nhập mã bí mật")

        val lockForm = document.selectFirst("form:has(input[name=entered_secret_code])")
        val postAction = lockForm?.absUrl("action")?.ifEmpty { chapterUrl } ?: chapterUrl

        val formBody = FormBody.Builder()
            .add("entered_secret_code", password)
            .add("submit_secret_code", "")
            .build()

        val postHeaders = headers.newBuilder()
            .set("Referer", chapterUrl)
            .build()

        val postResponse = client.post(postAction, postHeaders, Fetch.DOCUMENT, formBody, ensureSuccess = false)
        val postHtml = postResponse.body.string()

        val setCookieHeaders = postResponse.headers("Set-Cookie") +
            (postResponse.priorResponse?.headers("Set-Cookie") ?: emptyList())
        setCookieHeaders.firstNotNullOfOrNull { header ->
            passcodeCookieRegex.find(header)?.groupValues?.get(1)
        }?.let { newToken ->
            currentPasscodeToken = newToken
        }

        pageHtml = if (postResponse.isSuccessful && !postHtml.contains("entered_secret_code")) {
            postHtml
        } else {
            fetchChapterHtml(chapterUrl)
        }

        if ("entered_secret_code" in pageHtml) {
            throw Exception("Mật khẩu không chính xác")
        }

        return pageHtml
    }

    private val passcodeCookieRegex = Regex("""a3_site_passcode_token=([a-fA-F0-9]+)""")

    companion object {
        private const val DEFAULT_PASSCODE_TOKEN = "0d9678e234bc5f4234834a559f1c0157b3d933222d4b147ae2d6e1ac58a1dae0"
    }
}
