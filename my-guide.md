# Auth Token via WebView

## How it works

1. User logs in through the in-app WebView → token is saved to `localStorage` on the source's origin
2. Extension reads the token from `localStorage` via `getLocalStorage(baseUrl, "token")`
3. Token is cached and sent as `Authorization: Bearer <token>` on API requests

## Code

```kotlin
import okhttp3.Interceptor

// 1. Cache the token in a suspend method
private var cachedAuthToken: String? = null

private suspend fun loadAuthToken() {
    if (cachedAuthToken != null) return
    cachedAuthToken = getLocalStorage(baseUrl, "token")
        ?.takeIf { it.isNotBlank() }
}

// 2. Call it from the first suspend method (e.g. getPopularManga)
override suspend fun getPopularManga(page: Int): MangasPage {
    loadAuthToken()
    // ... API call
}

// 3. Interceptor reads the cached token — no WebView, no runBlocking
private fun authInterceptor() = Interceptor { chain ->
    val original = chain.request()
    val request = original.newBuilder().apply {
        cachedAuthToken?.let { header("Authorization", "Bearer $it") }
    }.build()
    chain.proceed(request)
}
```

- Import `okhttp3.Interceptor` and use `Interceptor` in declarations. Avoid fully qualified type names such as `okhttp3.Interceptor` in implementation code.

---

## Loading JS Assets Directly (Avoid `by lazy` for Large Scripts)

When an extension injects large JavaScript via `evaluateJs` (e.g. anti-bot hooks), avoid caching the script string in a `by lazy` property. The `lazy` delegate keeps the string in memory for the lifetime of the extension class, which is wasteful since the WebView is only opened occasionally.

### Instead — load inside the suspend function

```kotlin
override suspend fun getPageList(chapter: SChapter): List<Page> {
    val hookScript = javaClass.getResource("/assets/hook.js")?.readText()
        ?: throw IllegalStateException("hook.js not found")
    val pollScript = javaClass.getResource("/assets/poll.js")?.readText()
        ?: throw IllegalStateException("poll.js not found")

    runWebView<...>(timeout = 60.seconds) {
        onPageStarted { evaluateJs(hookScript) }
        poll(1.seconds) { evaluateJs(pollScript) { ... resolve(...) } }
        loadUrl(url)
    }
}
```

This way the strings are created on demand and garbage-collected after `getPageList` returns. The I/O cost (`getResource` + `readText`) is negligible compared to the WebView startup time.

## Requirements

- `getLocalStorage` creates a WebView at `baseUrl` origin and reads `localStorage`
- This only works if the user logged in through the **in-app WebView**, not the system browser
- The in-app WebView and `getLocalStorage`'s WebView share the same DOM storage (same app, same origin)

---

# General Extension Development Notes

## Pass the Document Directly

When a helper function needs to extract data from HTML, pass the parsed `Document` directly instead of passing `html` string and re-parsing with `Jsoup.parse`. Re-parsing is wasteful since `asJsoup()` already parsed the response.

`Response.asJsoup()` parses the response body from its request URL and closes the response automatically. Use `asJsoup(Parser.xmlParser)` only when the response requires XML parsing; both overloads return a `Document`. For HTML strings, use `String.asJsoup(baseUrl)` when you need relative links resolved against an explicit base URL.

```kotlin
// ❌ Don't — re-parses the HTML
override suspend fun getPageList(chapter: SChapter): List<Page> {
    val document = client.get(getChapterUrl(chapter)).asJsoup()
    val imageUrls = extractImageUrls(document.html(), baseUrl)
    return imageUrls.mapIndexed { idx, url -> Page(idx, imageUrl = url) }
}

private fun extractImageUrls(html: String, baseUrl: String): List<String> {
    val doc = org.jsoup.Jsoup.parse(html, baseUrl)
    // ...
}

// ✅ Do — reuse the already-parsed Document
override suspend fun getPageList(chapter: SChapter): List<Page> {
    val document = client.get(getChapterUrl(chapter)).asJsoup()
    val imageUrls = extractImageUrls(document)
    return imageUrls.mapIndexed { idx, url -> Page(idx, imageUrl = url) }
}

private fun extractImageUrls(document: Document): List<String> {
    val viewChapter = document.selectFirst("#view-chapter") ?: document
    // ...
}
```

## Throw `HttpException` for HTTP Errors

When handling non-successful HTTP responses, throw `HttpException` instead of a generic `Exception`. It provides a standardized error message and carries the status code.

```kotlin
import eu.kanade.tachiyomi.network.HttpException

// ❌ Don't
if (!response.isSuccessful) {
    throw Exception("HTTP error ${response.code}")
}

// ✅ Do
if (!response.isSuccessful) {
    val code = response.code
    response.close()
    throw HttpException(code)
}
```

`HttpException` is defined in `eu.kanade.tachiyomi.network` and extends `IllegalStateException` with a `code` property. Use it when you need to inspect the status code (e.g. to show a custom message for 403) before throwing.

## Close Unused Responses Directly

When a request is made only to verify that an endpoint succeeds and its response
body is intentionally unused, close the response directly:

```kotlin
client.get(url).close()
```

## Check Content Type Before Copying Response Bodies

When an interceptor only needs to inspect HTML, check the response content type
before calling `peekBody`, `string`, or another operation that copies or consumes
the body. This avoids buffering images and other potentially large binary responses.

```kotlin
val contentType = response.body.contentType()
val isHtml = contentType?.let {
    (it.type == "text" && it.subtype == "html") || it.subtype == "xhtml+xml"
} == true
if (!isHtml) return response

val html = response.peekBody(Long.MAX_VALUE).string()
```

Accept both `text/html` and XHTML when the inspected page may use either format.

## Read Complete Response Buffers

When code must pass an entire response body as a `ByteArray`, fully buffer the
source before reading `source.buffer`:

```kotlin
response.body.use {
    val source = it.source()
    source.request(Long.MAX_VALUE)
    decode(source.buffer.readByteArray())
}
```

Do not read `source.buffer` after requesting only a small signature prefix. The
buffer may contain only that prefix and produce truncated data. Keep the read
inside `ResponseBody.use` so the original response body is closed.

## `fetchMangaUpdate` Flags — Do Not Gate Same-Page Parsing

CONTRIBUTING is explicit about `fetchDetails` / `fetchChapters`:

> If manga details and the chapter list come from the **same page or the same API
> response**, fetch and parse it once and return both the updated `SManga` and the
> full chapter list, **regardless of the `fetchDetails`/`fetchChapters` flags** —
> there's no separate request to skip, so honoring the flags would just mean
> discarding data you already parsed.

Do **not** write patterns like:

```kotlin
// ❌ Don't — same document already contains chapters
chapters = if (fetchChapters) parseChapterList(document) else chapters,
```

Once you have paid for `client.get(...).asJsoup()` (or one API call that includes
both fields), parse and return **both**. Throwing away the chapter list when
`fetchChapters == false` leaves manga state stale and contradicts
[Fetch Manga Update — Always Return Both](#fetch-manga-update---always-return-both).

Only honor the flags when details and chapters live behind **separate** requests.
Then skip the call the flag does not ask for (and if both flags are true, fire the
two requests concurrently — see
[Fetch Details and Chapters Concurrently](#fetch-details-and-chapters-concurrently)).

## URL Fragments vs `memo`

CONTRIBUTING limits URL fragments and prefers `memo`:

- **URL fragments** (`#...`) are only for **`Page.url`**, to pass transient data to
  OkHttp image interceptors. The fragment is not sent to the server.
- **Do not** put manga- or chapter-level state (IDs, slugs, fallback URLs, auth
  bits) into `SManga.url` / `SChapter.url` fragments. That corrupts URLs and breaks
  when URLs are stripped or re-parsed.
- Use **`SManga.memo` / `SChapter.memo`** (`JsonObject`) for identifiers and
  metadata that must survive search → details → chapters.

```kotlin
// ❌ Don't — fragment on the manga URL
setUrlWithoutDomain("/manga/$slug#postId=$id")

// ✅ Do — structured memo
SManga.create().apply {
    setUrlWithoutDomain("/manga/$slug")
    memo = buildJsonObject { put("postId", id) }
}
```

If a thumbnail needs a secondary URL for a failed primary fetch, do not hide that
pair in a URL fragment. Prefer resolving a single good URL when scraping, or keep
extra image-request data only on `Page.url` fragments (the one case CONTRIBUTING
allows). For anything the library must remember, use `memo`.

## Preserve Actionable Custom Messages

The general recommendation to return `emptyList()` for locked or empty content
must not be applied mechanically when an extension already throws a purposeful
custom message that tells the user how to resolve the problem.

For example, keep an existing exception that instructs the user to open the
chapter in WebView and enter its password:

```kotlin
if (document.selectFirst("form.post-password-form") != null) {
    throw Exception(passwordWebViewMessage)
}
```

Preserve this behavior when all of the following are true:

- The condition is detected explicitly, such as a password, login, or access form.

## Use the New Date Helpers

When parsing date strings from a source, prefer the helpers in `keiyoushi.utils.Date.kt` over manual `LocalDate.parse` + `atStartOfDay` chains. They handle null inputs, parse errors, and zone resolution consistently.

```kotlin
import keiyoushi.utils.tryParseDate
import keiyoushi.utils.tryParseDateTime
import keiyoushi.utils.tryParseZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

// Date only (e.g. "23/08/26" → start of day in zone)
private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ROOT)
private fun parseChapterDate(dateStr: String): Long = dateFormat.tryParseDate(dateStr, ZoneId.of("Asia/Ho_Chi_Minh"))

// Date + time, no offset (e.g. "2026-08-11 14:30:00")
private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun parseUploadTime(dateStr: String): Long = dateTimeFormat.tryParseDateTime(dateStr, ZoneId.of("Asia/Ho_Chi_Minh"))

// Date + time with offset/zone (e.g. ISO 8601 "2026-08-11T14:30:00+07:00")
private val zonedDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
private fun parseTimestamp(dateStr: String): Long = zonedDateTimeFormat.tryParseZonedDateTime(dateStr)
```

- `tryParseDate` — use when the source sends a date only (no time). Resolves to start of day in the given zone.
- `tryParseDateTime` — use when the source sends a local date + time with no offset. The zone decides the instant.
- `tryParseZonedDateTime` — use when the source sends a date + time with its own offset/zone id. The offset in the string decides the instant.

All three helpers accept a `zone` parameter (defaults to `ZoneId.systemDefault()`). When the source operates in a specific timezone, pass it explicitly:

```kotlin
private val dateZone = ZoneId.of("Asia/Ho_Chi_Minh")

private fun parseChapterDate(dateStr: String): Long = dateFormat.tryParseDate(dateStr, dateZone)
```

This ensures dates are resolved to the correct start-of-day instant regardless of the user's device timezone.

All three return `0L` if the input is null or cannot be parsed.

---

# Cloudflare Turnstile Resolution (`TurnstileHelper`)

Cloudflare Turnstile challenges can be solved directly within extensions using `keiyoushi.utils.getTurnstileToken`.

## Architecture & How It Works

Turnstile resolution in `:core` (`keiyoushi.utils.TurnstileHelper.kt`) uses a dual headless / interactive strategy:

1. **Headless Execution First**:
   - Creates a WebView using `runWebView(activity, timeout)`.
   - Configures the WebView with actual device display metrics (`setupWebView`) so Cloudflare receives realistic viewport metrics.
   - Detects system UI dark/light mode via `Configuration.UI_MODE_NIGHT_MASK` and passes `theme: 'dark'` or `'light'` to Turnstile.
   - Renders Turnstile explicitly with `appearance: 'interaction-only'`. If Cloudflare evaluates the request and verifies the client without requiring human interaction, the token is obtained silently without displaying any UI.

2. **Seamless In-App Interactive Overlay (`CaptchaOverlayDialog`)**:
   - If Cloudflare requires user interaction (e.g., clicking a checkbox or solving a puzzle), Turnstile's `before-interactive-callback` fires.
   - A JavaScript bridge (`window.turnstileShow.post('show')`) instructs Kotlin to display `CaptchaOverlayDialog`.
   - `CaptchaOverlayDialog` attaches a translucent, full-screen dialog (`android.R.style.Theme_Translucent_NoTitleBar`) with a dimmed background (`dimAmount = 0.6f`) to the foreground `Activity` obtained from `topActivity()`.
   - The WebView hosting the challenge is displayed directly inside this dialog at native device dimensions.
   - If user dismisses the dialog or taps outside, the challenge is cancelled and throws an exception.
   - If the hosting Activity is destroyed, lifecycle hooks unhook and dismiss the dialog cleanly without leaking memory.

3. **Bridge Callbacks & Error Handling**:
   - `turnstileToken`: On challenge success, posts the token to Kotlin, dismisses the overlay dialog, and completes `runWebView` with the token string.
   - `turnstileError`: Maps internal error codes to clear human-readable messages (e.g. expired, timeout, script failure, sitekey rejected `110100`/`110110`/`400020`, unauthorized domain `110200`, incorrect device clock `200100`).
   - `turnstileCancel`: Rejects the coroutine with `"Captcha cancelled"`.

## API Signatures

`TurnstileHelper` provides two suspend functions:

```kotlin
package keiyoushi.utils

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// 1. Direct function
suspend fun getTurnstileToken(
    url: String,
    siteKey: String,
    userAgent: String,
    action: String? = null,
    cData: String? = null,
    timeout: Duration = 2.minutes,
): String

// 2. Context receiver overload (inside HttpSource / KeiSource)
context(source: HttpSource)
suspend fun getTurnstileToken(
    url: String,
    siteKey: String,
    action: String? = null,
    cData: String? = null,
    timeout: Duration = 2.minutes,
): String
```

- `url`: Target page URL (used as the origin base URL).
- `siteKey`: The Cloudflare Turnstile sitekey extracted from the target website.
- `userAgent`: Client user-agent string. In the `context(source: HttpSource)` overload, it is automatically extracted from `source.headers["User-Agent"]!!`.
- `action`: Optional Turnstile action parameter.
- `cData`: Optional custom data parameter passed to Turnstile.
- `timeout`: Maximum wait time before throwing `WebViewTimeoutException` (defaults to `2.minutes`).

## Practical Usage Examples

### 1. Extracting Sitekey and Solving Turnstile in `KeiSource`

Inside any `KeiSource` suspend method (e.g. `getPageList` or `getSearchMangaList`):

```kotlin
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getTurnstileToken
import okhttp3.FormBody
import org.jsoup.nodes.Document

override suspend fun getPageList(chapter: SChapter): List<Page> {
    val chapterUrl = getChapterUrl(chapter)
    val document = client.get(chapterUrl).asJsoup()

    // 1. Check if page contains Turnstile challenge
    val challengeElement = document.selectFirst(".cf-turnstile, [data-sitekey]")
    if (challengeElement != null) {
        val siteKey = challengeElement.attr("data-sitekey")

        // 2. Solve challenge using the context receiver overload
        val turnstileToken = getTurnstileToken(
            url = chapterUrl,
            siteKey = siteKey,
        )

        // 3. Submit token in POST body or Header as expected by the site
        val formBody = FormBody.Builder()
            .add("cf-turnstile-response", turnstileToken)
            .build()

        val unlockedDoc = client.post(chapterUrl, formBody).asJsoup()
        return parsePages(unlockedDoc)
    }

    return parsePages(document)
}
```

### 2. Turnstile via Header / JSON API

When an API endpoint requires the Turnstile token in headers:

```kotlin
suspend fun fetchProtectedApi(endpoint: String, siteKey: String): ApiResponse {
    val token = getTurnstileToken(url = baseUrl, siteKey = siteKey)

    val customHeaders = headers.newBuilder()
        .set("CF-Turnstile-Response", token)
        .build()

    return client.get(endpoint, customHeaders).parseAs<ApiResponse>()
}
```

---

# In-App UI Dialogs & View Rendering (`ActivityTracker` & `DialogHelper`)

Instead of writing manual `ActivityLifecycleCallbacks` and managing `WeakReference<Activity>` boilerplate inside extensions, use the centralized UI helpers in `keiyoushi.utils.ui.*`.

## Activity Tracking (`ActivityTracker.kt`)

The foreground `Activity` is tracked globally by `ActivityTracker`:
- Registers `Application.ActivityLifecycleCallbacks` once and tracks resumed activities.
- Includes fallback reflection on `ActivityThread.mActivities` if needed.
- `topActivity()`: Returns the foreground `Activity` ready to host dialogs. Throws `IllegalStateException` if no usable Activity is available.
- `Activity.usable()`: Checks `!isFinishing && !isDestroyed`.
- `Activity.onDestroyed(block)`: Registers a one-time destruction hook that returns an unregister function.

## Standard In-App Dialogs (`DialogHelper.kt`)

All dialog functions in `DialogHelper` run on `Dispatchers.Main`, suspend cleanly, handle screen dismissal, and unhook listeners when the hosting Activity is destroyed.

### 1. Information Dialog: `showInfo`
Shows an informational dialog with a single button and suspends until dismissed.

```kotlin
import keiyoushi.utils.ui.showInfo

suspend fun notifyMaintenance() {
    showInfo(
        title = "Notice",
        message = "Source is currently under maintenance. Please try again later.",
        buttonText = "OK",
    )
}
```

### 2. Confirmation Dialog: `askConfirm`
Shows a Yes/No question dialog. Returns `true` for positive, `false` for negative or dismiss.

```kotlin
import keiyoushi.utils.ui.askConfirm

suspend fun promptAdultConfirmation(): Boolean {
    return askConfirm(
        title = "Age Verification",
        message = "This series contains mature content. Do you want to continue?",
        yesText = "Confirm",
        noText = "Cancel",
    )
}
```

### 3. Text Input Dialog: `askInput`
Shows a dialog with a single `EditText` input. When `required = true`, the positive button is automatically disabled until input is entered.

```kotlin
import keiyoushi.utils.ui.askInput

suspend fun requestSecurityPin(): String? {
    return askInput(
        title = "Security Pin",
        message = "Enter your 6-digit access code:",
        hint = "123456",
        required = true,
    )
}
```

### 4. Password Input Dialog: `askPassword`
Shorthand for `askInput` with masked input and a built-in eye icon toggle (`EyeDrawable`) allowing users to show or hide their password.

```kotlin
import keiyoushi.utils.ui.askPassword

suspend fun requestChapterPassword(chapterName: String): String? {
    return askPassword(
        title = chapterName,
        message = "This chapter is locked with a password.",
        hint = "Password",
        required = true,
    )
}
```

### 5. Selection Dialog: `askSelect` / `askSelectOption`
Shows a single-choice list of options with radio buttons.

```kotlin
import keiyoushi.utils.ui.askSelect
import keiyoushi.utils.ui.askSelectOption

suspend fun chooseServer(servers: List<String>): String? {
    // Returns selected String directly, or null if canceled
    return askSelectOption(
        title = "Select Image Server",
        options = servers,
        selectedIndex = 0,
    )
}
```

## Refactored WordPress Password Protection Example

Using `askPassword`, the previous 80-line manual dialog implementation reduces to:

```kotlin
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.ui.askPassword
import okhttp3.FormBody

override suspend fun getPageList(chapter: SChapter): List<Page> {
    val chapterUrl = getChapterUrl(chapter)
    var document = client.get(chapterUrl).asJsoup()

    val lockForm = document.selectFirst("form.post-password-form")
    if (lockForm != null) {
        val password = askPassword(
            title = chapter.name,
            message = "This chapter requires a password",
        ) ?: throw Exception("Chapter password entry was cancelled")

        val postAction = lockForm.absUrl("action").ifEmpty {
            "$baseUrl/wp-login.php?action=postpass"
        }
        val formBody = FormBody.Builder()
            .add("post_password", password)
            .add("redirect_to", chapterUrl)
            .build()

        val postResponse = client.post(postAction, formBody, ensureSuccess = false)
        document = if (postResponse.isSuccessful && !postResponse.request.url.toString().contains("wp-login.php")) {
            postResponse.asJsoup()
        } else {
            client.get(chapterUrl).asJsoup()
        }

        if (document.selectFirst("form.post-password-form") != null) {
            throw Exception("Incorrect password")
        }
    }

    return parsePages(document)
}
```

## Custom UI View Rendering on Top Activity

When extensions need to render custom views (such as custom layout containers, image previews, or interactive components), obtain the foreground `Activity` with `topActivity()` and render with Android View APIs inside a coroutine:

```kotlin
import android.app.AlertDialog
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import keiyoushi.utils.ui.onDestroyed
import keiyoushi.utils.ui.topActivity
import keiyoushi.utils.ui.usable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> showCustomDialog(
    configure: AlertDialog.Builder.(resolve: (T) -> Unit) -> Unit,
): T = withContext(Dispatchers.Main) {
    val activity = topActivity()
    if (!activity.usable()) throw IllegalStateException("Activity unavailable for dialog")

    suspendCancellableCoroutine { cont ->
        try {
            var resolved = false
            var unhook: (() -> Unit)? = null
            val builder = AlertDialog.Builder(activity)

            builder.configure { value ->
                resolved = true
                if (cont.isActive) cont.resume(value)
            }

            builder.setOnDismissListener {
                unhook?.invoke()
                unhook = null
                if (cont.isActive && !resolved) {
                    cont.resumeWithException(Exception("Dialog dismissed"))
                }
            }

            val dialog = builder.show()
            unhook = activity.onDestroyed {
                runCatching { if (dialog.isShowing) dialog.dismiss() }
            }
            cont.invokeOnCancellation {
                activity.runOnUiThread {
                    runCatching { if (dialog.isShowing) dialog.dismiss() }
                }
            }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
    }
}
```

### Key Rules for Custom UI Rendering:
- **Always run on `Dispatchers.Main`**: Android UI widgets and `AlertDialog` can only be manipulated on the main looper thread.
- **Check `usable()`**: Verify `!isFinishing && !isDestroyed` before building or displaying dialogs.
- **Hook `onDestroyed`**: Always unregister and dismiss dialogs when the hosting `Activity` is destroyed to avoid window leaks.
- **Support Coroutine Cancellation**: Use `cont.invokeOnCancellation` so cancelling the parent job (e.g. user navigating back in Mihon) dismisses the dialog immediately.

---

# Advanced WebView Execution (`runWebView`)

When a source requires executing client-side scripts, decoding JavaScript obfuscation, solving custom challenges, or reading authentication state, use `keiyoushi.utils.runWebView`.

## Execution Mechanics & Defaults

- `runWebView` runs on `Dispatchers.Main` internally.
- Sets up viewport matching device metrics (`widthPixels`, `heightPixels`).
- Automatically enables:
  - `javaScriptEnabled = true`
  - `domStorageEnabled = true`
  - `blockNetworkImage = false`
- Automatically synchronizes `Sec-CH-UA` client hints when setting `userAgent`.
- Cleans up WebView on completion, rejection, or timeout: cancels loading, removes from parent hierarchy, and calls `webView.destroy()`.

## DSL Capabilities (`WebViewScope<T>`)

1. **JavaScript Bridge (`jsBridge`)**: Exposes two-way communication from page script to Kotlin coroutine:
   ```kotlin
   jsBridge("myBridge") { message ->
       resolve(message)
   }
   ```
   Inside page JS: `window.myBridge.post("result data")`.

2. **Network Interceptor (`interceptRequest`)**:
   Inspect, block, or mock network requests made by the WebView:
   ```kotlin
   interceptRequest { request ->
       if (request.url.path?.endsWith(".jpg") == true) {
           // Block images to save bandwidth
           WebResourceResponse("text/plain", "UTF-8", null)
       } else {
           null // Let request proceed normally
       }
   }
   ```

3. **Lifecycle Hooks**:
   - `onPageStarted { url -> ... }`
   - `onPageFinished { url -> ... }`
   - `onReceivedError { request, error -> ... }`

4. **Polling & Script Evaluation**:
   - `evaluateJs(script) { result -> ... }`: Runs script on page and receives result.
   - `poll(interval = 500.milliseconds) { ... }`: Periodically checks conditions until `resolve` or `reject` is invoked.

5. **Synchronous Execution for Interceptors (`runWebViewBlocking`)**:
   When inside an OkHttp `Interceptor` (non-suspend context), use `runWebViewBlocking(call = chain.call())`. It watches `call.isCanceled()` and cancels the WebView coroutine cleanly if the network call is aborted.

---

## Pass POST Bodies Positionally

Pass the request body as the second positional argument to `client.post`:

```kotlin
client.post(url, formBody)
```

Do not use the redundant named form `client.post(url, body = formBody)`.

## Extract Typed Next.js Data Directly

When the target is a serializable object with distinctive required fields, use
the predicate-free `extractNextJs<T>()` overload. It infers the match from the
DTO descriptor and returns the deserialized object directly. Do not traverse as
`JsonElement`, mutate external collections inside the predicate, and always
return `false` merely to collect data.

```kotlin
val chapter = document.extractNextJs<ReaderChapter>() ?: return emptyList()
val pages = chapter.images.sortedBy { it.order }
```

Use an explicit predicate only when the inferred required fields are not unique
enough to identify the intended object.

### Join Split Next.js Flight Records Locally

Use the normal `extractNextJs<T>()` flow first. Some App Router sites split one
RSC ByteText chunk across multiple ordered `self.__next_f.push` scripts. For
example, one script may end with the ByteText header `21:Tc4e,`, while the next
script starts with the referenced HTML. Parsing each script separately leaves
the model field unresolved as `"$21"` or resolves the chunk as an empty string.

When this behavior is confirmed for a source, decode the second item from each
push array, join those strings in document order, and pass the continuous RSC
stream to `extractNextJsRsc<T>()`:

```kotlin
private fun Document.extractReaderChapter(chapterSlug: String): ReaderChapter? {
    val rscBody = select("script:not([src])")
        .mapNotNull { script ->
            val data = script.data()
            if (!data.startsWith(nextFlightPrefix)) return@mapNotNull null

            runCatching {
                data.substring(nextFlightPrefix.length, data.lastIndexOf(')'))
                    .parseAs<JsonArray>()
                    .getOrNull(1)
                    ?.stringOrNull
            }.getOrNull()
        }
        .joinToString("")

    return rscBody.extractNextJsRsc<ReaderChapter> { element ->
        element is JsonObject &&
            element.getStringOrNull("slug") == chapterSlug &&
            !element.getStringOrNull("content").isNullOrBlank()
    }
}
```

Keep this workaround local to the affected source unless multiple sources prove
that the shared parser needs the same behavior. Preserve script order, decode
the push arrays with JSON utilities instead of manually unescaping JavaScript,
and continue using an explicit predicate only when the target object is not
uniquely identifiable from its required fields.

## Source Code Organization

For source files with several responsibilities, group each override with its
related parsers and helpers under consistent section markers. Keep sections in
the normal source flow: Auth (when needed), Popular, Latest, Search, Details,
Pages, Filters, Related (when supported), then Utilities (when needed).

```kotlin
// ================================ Auth =================================
// Optional: include only when the website uses login or authentication.

// ============================== Popular ===============================

// ============================== Latest ===============================

// ============================== Search ===============================

// ============================== Details ===============================

// ============================== Pages ===============================

// ============================== Filters ===============================

// =============================== Related ==============================
// Optional: include only when the source implements related manga support.

// ============================= Utilities =============================
// Optional: include only when the source has shared helpers or constants.
```

Place narrowly scoped helpers in the section that uses them. Keep shared
configuration near the top of the class and constants near the bottom. Do not
add empty optional sections, and do not reorder code when doing so would change
initialization or runtime behavior.

Place a helper next to the selector or request function that owns and calls it
when it has fewer than three call sites. Keep it in a shared Utilities section
when it is called three or more times, especially when those callers use
different selectors or parsing contexts. Place the Utilities section after all
feature sections so shared helpers and constants do not interrupt the normal
source flow. Do not use it as a catch-all for helpers owned by one feature.

Do not use callable references such as `Element::helper` for member extension
functions declared inside a source class; Kotlin prohibits references to
elements that are members and extensions at the same time. Call the extension
through a lambda or use a regular member helper that accepts the receiver as a
parameter.

## Derived Request Headers

`KeiSource` already adds the default User-Agent, root `Referer` (`$baseUrl/`),
and `Origin` headers. Do not override `configureHeaders()` only to set the same
root `Referer`. Override it only when the source requires a different or
additional global header.

Declare custom request headers with an explicit `Headers` getter when they should be built from the source's current `headersBuilder()` on each access:

```kotlin
private val xhrHeaders: Headers
    get() = headersBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .build()
```

    ## Derived URLs

    When a URL is derived from a configurable `baseUrl`, declare it with a getter so changes to the custom URL are reflected on every access:

    ```kotlin
    private val apiUrl get() = "https://api.${baseUrl.toHttpUrl().host}"
    ```

## Fetch Independent Data in Parallel

When a suspend method needs multiple independent network responses, fetch them concurrently with structured concurrency instead of waiting for each request sequentially. This is especially useful in `fetchFilterData()` when filter groups come from separate endpoints.

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

override suspend fun fetchFilterData(): JsonElement = coroutineScope {
    val genres = async { client.get("$baseUrl/api/genres").parseAs<GenreResponse>() }
    val teams = async { client.get("$baseUrl/api/teams").parseAs<TeamResponse>() }

    FilterData(
        genres = genres.await(),
        teams = teams.await(),
    ).toJsonElement()
}
```

- Use `coroutineScope` so failures cancel sibling requests and propagate normally.
- Start all independent `async` operations before calling `await()`.
- Do not parallelize requests when one depends on the result of another.

When dynamically fetched filter options are null or empty, omit that filter
group from `FilterList` instead of adding an empty selector or fallback option.
Do not pass `genres.orEmpty()` to `GenreFilter`, because that still displays an
empty genre selector. Add static filters normally:

```kotlin
fun getFilters(genres: List<Genre>, teams: List<Team>): FilterList {
    val filters = mutableListOf<Filter<*>>()
    if (genres.isNotEmpty()) filters += GenreFilter(genres)
    if (teams.isNotEmpty()) filters += TeamFilter(teams)
    filters += SortFilter()
    filters += StatusFilter()
    return FilterList(filters)
}
```

### Paginated APIs Without a Total Count

If the API does not return a total page count, do not launch an arbitrary number of page requests. Fetch the first page, then request a small bounded batch of consecutive pages concurrently. Process responses in page order and stop at the first empty or short page.

```kotlin
var nextPage = 2
var hasMorePages = firstPage.size >= pageSize

while (hasMorePages) {
    val pages = (nextPage until nextPage + batchSize)
        .map { page -> async { fetchPage(page) } }
        .awaitAll()

    for (items in pages) {
        results += items
        if (items.size < pageSize) {
            hasMorePages = false
            break
        }
    }

    nextPage += batchSize
}
```

- Keep the batch small to limit speculative requests beyond the final page.
- `awaitAll()` returns results in the same order as the deferred list, preserving pagination order.
- If the API provides a reliable total count, calculate the exact page range and fetch those pages concurrently instead.

### Avoid Fixed Page Sizes for Source Pagination

For popular, latest, text search, and filtered search, avoid determining
`MangasPage.hasNextPage` from a hardcoded result count such as
`mangas.size >= 24` whenever possible. A site may change its page size, return
fewer entries because of removed content, or use a different size for filters.

Prefer a reliable server-provided signal, such as a next-page link, cursor,
total page count, or explicit `hasNextPage` field. If the site provides no
reliable signal, continue while the current response contains results:

```kotlin
return MangasPage(mangas, mangas.isNotEmpty())
```

This fallback may request one empty page before stopping. Use it only when the
server returns an empty result beyond the final page; do not use it when the
server repeats the last page for out-of-range requests.

## Deeplink Configuration

Configure deeplinks in `build.gradle.kts` to match only the site's routes that can resolve to manga entries. Prefer the narrowest pattern that covers both manga details and chapter URLs:

```kotlin
deeplink {
    path("/manga/.*")
}
```

For example, this pattern covers `/manga/<slug>` and `/manga/<slug>/chapters/<chapter>` without sending unrelated site URLs to the extension.

**Best Practices:**
- Avoid `path("/.*")` when the supported routes have a stable prefix
- Use the narrowest pattern that includes every URL handled by `getMangaByUrl()`
- Use `path("/.*")` only when valid manga URLs have no reliable shared route pattern
- Avoid using `host()` as it is not necessary when using `baseUrl`
- Verify that detail and chapter URLs are both covered

## Search Functionality

When implementing search in extensions, consider these approaches:

### 1. Query-Based Search (Recommended)

```kotlin
override suspend fun getSearchMangaList(
    page: Int,
    query: String,
    filters: FilterList,
): MangasPage {
    val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
        if (query.isNotEmpty()) addQueryParameter("q", query)
    }.build()

    return parseMangaList(client.get(url))
}
```

**Benefits:**
- Simpler implementation
- Works across different sites

### URL Search with KeiSource

`KeiSource` detects full URL queries before `getSearchMangaList` is called and
routes them to `getMangaByUrl(HttpUrl)` automatically. Keep
`getSearchMangaList` focused on normal text and filter searches, and implement
detail or chapter URL resolution in `getMangaByUrl`.

Do not add custom pseudo-query prefixes such as `id:` merely to turn a slug or
ID into a URL. They are undocumented user-facing syntax and duplicate URL
routing already provided by `KeiSource`. Keep one only when the source has a
real, pre-existing ID-search requirement that cannot be represented by a
normal site URL.

```kotlin
override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
    if (url.host != baseUrl.toHttpUrl().host) return null
    // Resolve recognized detail or chapter paths.
}
```

### 2. Filter-Based Search

When extending `Filter.Select`, do not declare a property named `values` in
the subclass. `Filter.Select` already has a member with that name, so the new
property hides the inherited member and causes a compilation error. Use a
specific name such as `slugs`, `ids`, or `paths` instead:

```kotlin
class GenreFilter(genres: List<GenreOption>) :
    Filter.Select<String>("Genre", genres.map { it.name }.toTypedArray()) {
    private val slugs = genres.map { it.slug }

    fun toUriPart(): String = slugs[state]
}
```

```kotlin
override suspend fun getSearchMangaList(
    page: Int,
    query: String,
    filters: FilterList,
): MangasPage {
    val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
    val categoryPath = categoryFilter?.getCategoryPath()

    if (categoryPath != null) {
        return parseMangaList(client.get("$baseUrl/$categoryPath?page=$page"))
    }

    // Fallback to query search
    val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
        if (query.isNotEmpty()) addQueryParameter("q", query)
    }.build()

    return parseMangaList(client.get(url))
}
```

**Considerations:**
- Filters may not work consistently across all sites
- Category paths can change over time
- Query-based search is often more reliable

**Recommendation:**
Prefer query-based search as the primary method. If a query is provided, always perform a search. If the query is empty and a category filter is available, use that category. Otherwise, fall back to a general search without a query.

```kotlin
override suspend fun getSearchMangaList(
    page: Int,
    query: String,
    filters: FilterList,
): MangasPage {
    val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
    val categoryPath = categoryFilter?.getCategoryPath()

    if (query.isNotEmpty()) {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("q", query)
        }.build()
        return parseMangaList(client.get(url))
    }

    if (categoryPath != null) {
        return parseMangaList(client.get("$baseUrl/$categoryPath?page=$page"))
    }

    val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
    }.build()

    return parseMangaList(client.get(url))
}
```

---

## Fetch Manga Update - Always Return Both

`KeiSource` guarantees that `fetchDetails` and `fetchChapters` are not both `false`. Do not add an early return for that impossible state.

When one fetched document already contains both manga details and chapters,
parse and return both unconditionally, even when one fetch flag is false. Do not
check the flags after paying for the shared request, because returning the
incoming cached value discards data that is already available and can leave the
manga state stale.

```kotlin
override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate {
    val document = client.get(getMangaUrl(manga)).asJsoup()
    return SMangaUpdate(
        manga = parseMangaDetails(document, manga),
        chapters = parseChapterList(document),
    )
}
```

For API-based sources where a single endpoint returns both details and chapters:

```kotlin
override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate = coroutineScope {
    loadAuthToken()
    val detailResponse = client.get("$baseUrl/api/comics/${manga.url}").parseAs<ComicDetailResponse>()

    SMangaUpdate(
        manga = detailResponse.data.toSManga(),
        chapters = detailResponse.data.chapters.map { it.toSChapter(manga.url) },
    )
}
```

Since `KeiSource` guarantees that `fetchDetails` and `fetchChapters` are never both `false`, the `if (fetchDetails || fetchChapters)` check is unnecessary. Always fetch and return both fields unconditionally.

Only preserve an unrequested existing value when obtaining that field requires
an independent request or other meaningful extra work. In that case, guard the
extra request with the corresponding flag and return the existing value when it
was not requested.

---

# Shared Date Parsing Utilities

The helpers in `core/src/main/kotlin/keiyoushi/utils/Date.kt` convert nullable date
strings directly to epoch milliseconds for `SChapter.date_upload`. They return `0L`
when the input is null or cannot be parsed. Prefer them over source-local
`runCatching` wrappers and the deprecated `SimpleDateFormat.tryParse` helper.

Choose the helper based on what information the source provides:

- `Instant.tryParse(date)` parses a complete ISO 8601 instant whose `Z` or offset
    determines the result, such as `2025-01-15T10:30:00Z` or
    `2025-01-15T10:30:00+07:00`.
- `DateTimeFormatter.tryParseDate(date, zone)` parses a date without a time, such as
    `2025-01-15` or `Aug 06, 2026`, and resolves it to the start of that day in `zone`.
- `DateTimeFormatter.tryParseDateTime(date, zone)` parses a local date and time whose
    result must use the supplied site zone.
- `DateTimeFormatter.tryParseZonedDateTime(date)` parses a value whose formatter
    includes a trustworthy offset or zone. Do not pass a separate zone for this case.

The `tryParse` helpers accept nullable input and return `0L` when the value is
null or invalid. Pass an optional timestamp directly instead of wrapping it in
`?.let { ... } ?: 0L`:

```kotlin
val dateTime: String? = element.selectFirst("time")?.attr("datetime")
dateUpload = Instant.tryParse(dateTime)
```

Avoid the redundant form:

```kotlin
dateUpload = dateTime?.let { Instant.tryParse(it) } ?: 0L
```

```kotlin
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseDate
import keiyoushi.utils.tryParseDateTime
import keiyoushi.utils.tryParseZonedDateTime
import kotlin.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val siteZone = ZoneId.of("Asia/Ho_Chi_Minh")
private val chapterDateFormat = DateTimeFormatter.ofPattern("MMM dd, uuuu", Locale.ENGLISH)
private val localDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
private val zonedDateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

val isoDate = Instant.tryParse("2025-01-15T10:30:00Z")
val chapterDate = chapterDateFormat.tryParseDate("Aug 06, 2026", siteZone)
val localDateTime = localDateTimeFormat.tryParseDateTime("2025-01-15 10:30:00", siteZone)
val zonedDateTime = zonedDateTimeFormat.tryParseZonedDateTime("2025-01-15T10:30:00+07:00")
```

- Always pass the source site's known `ZoneId` to `tryParseDate` and
    `tryParseDateTime`; their default is the device timezone and should only be used when
    that behavior is intentional.
- Use `Locale.ENGLISH` when the pattern parses English textual fields such as `MMM`,
    `MMMM`, `EEE`, or `EEEE`. Use the site's matching locale for text in another
    language. Use `Locale.ROOT` for numeric-only patterns.
- `tryParseDate` and `tryParseDateTime` deliberately use the supplied zone even if the
    input contains offset or zone fields. Use `tryParseZonedDateTime` when the input's own
    offset or zone must decide the instant.
- Keep reusable `DateTimeFormatter` and `ZoneId` values at class or file level.

## Relative timestamps with `Clock.System.now()`

For relative chapter dates such as `5 phút trước`, `2 giờ trước`, or
`3 ngày trước`, subtract a `kotlin.time.Duration` from
`Clock.System.now()` and convert the resulting instant to epoch milliseconds.
Avoid `Calendar` for this duration-based arithmetic.

```kotlin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private fun parseRelativeDate(value: String): Long {
    val amount = relativeDateRegex.find(value)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: return 0L

    val duration = when {
        "phút" in value -> amount.minutes
        "giờ" in value -> amount.hours
        "ngày" in value -> amount.days
        else -> return 0L
    }

    return (Clock.System.now() - duration).toEpochMilliseconds()
}

private val relativeDateRegex = Regex("""(\d+)""")
```

- Return `0L` when the value is missing or unsupported.
- Cache reusable `Regex` instances at class or file level.
- Weeks can be represented as `amount * 7` days.
- `Duration` has no calendar month or year unit. If the site only provides
  approximate relative labels, document and use a consistent approximation
  such as 30 days per month and 365 days per year. Use calendar-aware date
  APIs instead when exact month or year boundaries matter.

---

# Import Linting

## Rules

1. **Unused imports must be removed.** ktlint will fail on unused imports. If you remove code that used a symbol, remove its import too.
2. **Sort order** (Android/ktlint default):
   - `android.*`
   - `androidx.*`
   - `eu.kanade.*`
   - `keiyoushi.*`
   - `kotlinx.*`
   - `kotlin.*`
   - `okhttp3.*`
   - `org.*`
   - `java.*`
3. **No blank lines between imports.** A single blank line separates the import block from the class declaration.

---

# Reusable Constants and Regexes

Keep private reusable values inside the source class when they are only used by that source. Do not add a `companion object` solely to hold them.

```kotlin
@Source
class Example : KeiSource() {
    // Source implementation

    private val pageNumberRegex = Regex("""/page/(\d+)/""")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
```

- Use `private val` for objects such as `Regex`, `DateTimeFormatter`, and `ZoneId`; they cannot be declared with `const val`.
- Name class properties in lower camel case, as required by ktlint's `property-naming` rule.
- Place source-specific reusable values at the bottom of the source class, after its methods.
- Use file-level declarations only when a value is shared by multiple classes or top-level functions in the same file.
- Retain a `companion object` only when its members require class-scoped access or Java-style static interoperability.

---

# WebView Defaults

`runWebView` already enables these WebView settings by default, so do not repeat them in your `runWebView` block:

- `javaScriptEnabled = true`
- `domStorageEnabled = true`
- `blockNetworkImage = false` (`blockImages = false` in the DSL)

Only set settings that differ from the defaults:

```kotlin
runWebView(timeout = 45.seconds) {
    loadWithOverviewMode = true
    useWideViewPort = true
    userAgent = userAgent.replace(Regex(""";\s*wv\)"""), ")")
    // ...
}
```

Check the current defaults in `extensions-source/core/src/main/kotlin/keiyoushi/utils/WebView.kt` (`setupWebView` function) before adding settings.

## Resolve Directly in Callbacks

When extracting data from `evaluateJs` callbacks, call `resolve(Unit)` directly instead of setting a flag and checking it later:

```kotlin
runWebView(timeout = 45.seconds) {
    poll(1.seconds) {
        evaluateJs(CHECK_AND_DECODE_SCRIPT) { value ->
            val parsed = parseResult(value) ?: return@evaluateJs
            token = parsed.first
            urls = parsed.second
            resolve(Unit)  // complete immediately
        }
    }

    loadUrl(chapterUrl)
}
```

Avoid the pattern of setting `resolved = true` in a callback, guarding `return@poll`, and checking the flag after each `evaluateJs` call. Calling `resolve` directly is simpler and avoids unnecessary state management.

---

# JsonElement Utilities

`keiyoushi.utils` provides shorthand extensions for `kotlinx.serialization.json.JsonElement`.
Import them to avoid verbose `jsonObject`/`jsonArray`/`jsonPrimitive` chains.

## Key imports

```kotlin
import keiyoushi.utils.get     // operator fun JsonElement?.get(key: String): JsonElement?
import keiyoushi.utils.obj     // val JsonElement.obj: JsonObject
import keiyoushi.utils.array   // val JsonElement.array: JsonArray
import keiyoushi.utils.int     // val JsonElement.int: Int
import keiyoushi.utils.long    // val JsonElement.long: Long
import keiyoushi.utils.string  // val JsonElement.string: String
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.getStringOrNull
```

## Usage

```kotlin
// Before (verbose)
val genres = data?.jsonObject?.get("data") as? JsonArray

// After (shorthand)
val genres = data["data"]?.array
```

For optional string fields, prefer the shared nullable helpers over direct
`jsonPrimitive.contentOrNull` chains:

```kotlin
// JsonObject
val slug = element.getStringOrNull("slug")

// JsonElement?
val slug = data["slug"]?.stringOrNull
```

For whole-object checks and optional primitive values, use the shared accessors
instead of importing `jsonObject`, `jsonPrimitive`, and `contentOrNull`:

```kotlin
if ("encrypted" in body.obj) {
    // Parse the encrypted response.
}

val value = node.s?.stringOrNull
```

Keep the `kotlinx.serialization.json.JsonObject` import when it is required for
a predicate type check, but use the `keiyoushi.utils` accessors for its values.

The `get` operator on `JsonElement?` internally calls `this?.jsonObject?.get(key)`,
and `array` wraps `this.jsonArray`. Both throw on type mismatch — use `?.` to get
`null` on missing keys instead.

The terminal accessors (`string`, `int`, `long`, and `boolean`) also throw for a
null element or incompatible value. For optional or inconsistent API fields,
preserve fallback behavior with a nullable receiver and `runCatching`:

```kotlin
val chapterId = data["id"]?.let { runCatching { it.string }.getOrNull() }
val level = data["level"]?.let { runCatching { it.int }.getOrNull() } ?: 0
val expiresAt = data["expires_at"]?.let { runCatching { it.long }.getOrNull() } ?: 0L
```

Prefer these shared helpers over source-local `JsonElement` conversion extensions.

## Store Request Identifiers in Memo

When a listing response provides an identifier needed by later requests, store it
in `SManga.memo` and reuse it instead of fetching the details page only to recover
the same identifier. Prefer the shared JSON helpers from `keiyoushi.utils` for
reading and encoding memo values:

Store the canonical manga ID in `SManga.memo` even when new entries also include
that ID in `SManga.url`; memo provides a stable lookup for old, slug-based, or
deeplink-derived URLs and avoids reparsing or refetching the page during updates.

```kotlin
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonObject

val postId = manga.memo["postId"]?.stringOrNull ?: fetchPostId(manga.url)

private fun JsonObject.withPostId(postId: String): JsonObject =
    JsonObject(this + ("postId" to postId.toJsonElement()))
```

Use `JsonObject` directly when an updated memo must preserve existing entries.
The shared utilities provide typed accessors and serialization, but do not
provide an immutable object-merge builder.

Preserve the memo value on the manga returned from `fetchMangaUpdate`. Keep a
network fallback for old library entries created before the memo was added, so
they fetch the identifier once and retain it for subsequent updates.

Do not fetch the details document unconditionally in `fetchMangaUpdate`. When
only chapters are requested and the identifier already exists in `SManga.memo`,
use it directly and skip the details request. Fetch the document only when
details are requested or when a requested operation still needs a missing
identifier.

When parsing filter data produced by the extension's own `fetchFilterData()`, do
not wrap `parseAs` in `runCatching`. Handle only the nullable input explicitly and
let malformed non-null filter data propagate:

```kotlin
override fun getFilterList(data: JsonElement?): FilterList {
    val filterData = data?.parseAs<FilterData>()
    return getFilters(filterData)
}
```

In lib 1.6, use the `filters` argument passed to `getSearchMangaList` directly.
Do not replace an empty list with `getFilters()`: the application obtains the
source's filter list through `getFilterList`, including dynamically fetched
filter data, and passes the current filter state to the search method.

## Prefer `parseAs` / `jsonInstance` Over a Local `Json`

Do not create `private val json = Json { ... }` in a source object when
`keiyoushi.utils.parseAs` (shared `jsonInstance`) is enough.

```kotlin
// ❌ Don't
object PagesCrypto {
    private val json = Json { ignoreUnknownKeys = true }
    fun decrypt(...): PagesPayload =
        json.decodeFromString(PagesPayload.serializer(), plaintext)
}

// ✅ Do
object PagesCrypto {
    fun decrypt(...): PagesPayload = plaintext.parseAs()
}
```

Use `String.parseAs<T>()`, `Response.parseAs<T>()`, or `JsonElement.parseAs<T>()`.
Set `ignoreUnknownKeys` only if the shared instance is insufficient and a custom
serializer is truly required.

## Meaningful DTO Property Names with `@SerialName`

When the API uses short or cryptic JSON keys, name Kotlin properties for readers
and map the wire keys with `@SerialName`. Do not copy the API's one-letter names
into the source when a clear name is available.

```kotlin
// ❌ Don't
@Serializable
class PagesResponse(val e: String)
@Serializable
class PagesPayload(val p: List<String> = emptyList(), val s: List<String?>? = null)

// ✅ Do
@Serializable
class PagesResponse(
    @SerialName("e") val encrypted: String,
)

@Serializable
class PagesPayload(
    @SerialName("p") val pages: List<String> = emptyList(),
    @SerialName("s") val scrambleKeys: List<String?>? = null,
)
```

Apply the same idea to API fields such as `cover_full_url` → `coverFullUrl` with
`@SerialName("cover_full_url")`.

## HTML to Plain Text with Jsoup

Strip tags and unescape entities with Jsoup instead of hand-rolled regex +
entity maps.

```kotlin
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

// ❌ Don't — regex strip + manual &amp; / &lt; replacements
private fun htmlToText(html: String): String = html
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&")
    // ...

// ✅ Do
private fun htmlToText(html: String): String {
    val text = Jsoup.parse(html).wholeOwnText()
    return Parser.unescapeEntities(text, false).trim()
}
```

Use `wholeOwnText()` when you want the element's own text without merging block
boundaries the way `wholeText()` can. Always run `Parser.unescapeEntities` so
character references become real characters.

## No Unnecessary `runCatching` Around Parsing

`parseAs` / `getLocalStorage` already return null or throw on bad data. Do not
wrap them in `runCatching { ... }.getOrNull()` unless a specific failure must be
ignored. Prefer an explicit `?.` for nullable helpers and let parse errors surface.

```kotlin
// ❌ Don't
val token = runCatching {
    raw.parseAs<AuthStorage>().state?.token
}.getOrNull()

// ✅ Do
val token = raw.parseAs<AuthStorage>().state?.token?.takeIf { it.isNotBlank() }
```

## Fetch Details and Chapters Concurrently

When `fetchMangaUpdate` hits independent details and chapter endpoints, run both
in `coroutineScope` + `async` (see also *Fetch Independent Data in Parallel*).

```kotlin
override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate = coroutineScope {
    val detailsDeferred = async {
        if (!fetchDetails) return@async manga
        fetchDetails(manga)
    }
    val chaptersDeferred = async {
        if (fetchChapters) fetchChapterList(manga) else chapters
    }
    SMangaUpdate(manga = detailsDeferred.await(), chapters = chaptersDeferred.await())
}
```

## Return Filter Payloads as the Raw List

When `fetchFilterData()` only collects a list of options, return that list as a
`JsonElement` (`genres.toJsonElement()`). Avoid wrapping it in
`buildJsonObject { put("genres", ...) }` unless extra sibling keys are required.

```kotlin
// ✅ Do
override suspend fun fetchFilterData(): JsonElement = loadGenres().toJsonElement()

override fun getFilterList(data: JsonElement?): FilterList =
    getFilters(data?.parseAs<List<GenreOption>>())
```

Match `getFilterList` to the same shape. If the stored payload is a bare array,
parse `List<T>`, not an object wrapper. Do not wrap `parseAs` in `runCatching`.

## Store Related-Taxonomy Slugs in Memo

If related manga are resolved from group/author/artist/genre slugs, write those
slugs into `SManga.memo` during `fetchMangaUpdate` so `fetchRelatedMangaList` can
reuse them without another details request (see *Store Request Identifiers in
Memo*).

```kotlin
memo = buildJsonObject {
    dto.group?.slug?.let { put("group_slug", it) }
    dto.author?.slug?.let { put("author_slug", it) }
    dto.artist?.slug?.let { put("artist_slug", it) }
    dto.genres.firstOrNull()?.slug?.let { put("genre_slug", it) }
}

// later
val sources = listOfNotNull(
    manga.memo["group_slug"]?.stringOrNull?.let { "groups" to it },
    // ...
)
```

Keep a details-request fallback for library entries created before the memo keys
existed.
