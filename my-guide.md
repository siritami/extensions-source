# General Extension Development Notes

## Pass the Document Directly

When a helper function needs to extract data from HTML, pass the parsed `Document` directly instead of passing `html` string and re-parsing with `Jsoup.parse`. Re-parsing is wasteful since `asJsoup()` already parsed the response.

`Response.asJsoup()` parses the response body from its request URL and closes the response automatically. Use `asJsoup(Parser.xmlParser)` only when the response requires XML parsing; both overloads return a `Document`. For HTML strings, use `String.asJsoup(baseUrl)` when you need relative links resolved against an explicit base URL.

```kotlin
// ❌ Don't — re-parses the HTML
override suspend fun getPageList(chapter: SChapter): List<Page> {
    val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
    val imageUrls = extractImageUrls(document.html(), baseUrl)
    return imageUrls.mapIndexed { idx, url -> Page(idx, imageUrl = url) }
}

private fun extractImageUrls(html: String, baseUrl: String): List<String> {
    val doc = org.jsoup.Jsoup.parse(html, baseUrl)
    // ...
}

// ✅ Do — reuse the already-parsed Document
override suspend fun getPageList(chapter: SChapter): List<Page> {
    val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
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
client.get(url, Fetch.DOCUMENT).close()
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


---

# Cloudflare Turnstile Resolution (`TurnstileHelper`)

Cloudflare Turnstile challenges can be solved directly within extensions using `keiyoushi.utils.getTurnstileToken`.

## Architecture & How It Works

Turnstile resolution in `:core` (`keiyoushi.utils.TurnstileHelper.kt`) uses a dual headless / interactive strategy:

1. **Headless Execution First**:
   - Creates a WebView using `runWebView(timeout)`.
   - Configures the WebView with actual device display metrics (`setupWebView`) so Cloudflare receives realistic viewport metrics.
   - Detects system UI dark/light mode via `Configuration.UI_MODE_NIGHT_MASK` and passes `theme: 'dark'` or `'light'` to Turnstile.
   - Renders Turnstile explicitly with `appearance: 'interaction-only'`. If Cloudflare evaluates the request and verifies the client without requiring human interaction, the token is obtained silently without displaying any UI.

2. **Seamless In-App Interactive Overlay (`CaptchaOverlayDialog`)**:
   - If Cloudflare requires user interaction (e.g., clicking a checkbox or solving a puzzle), Turnstile's `before-interactive-callback` fires.
   - A single `webMessageBridge` named `turnstileBridge` (scoped to the page origin) posts a `"show"` message to Kotlin, which displays `CaptchaOverlayDialog`.
   - `CaptchaOverlayDialog` attaches a translucent, full-screen dialog (`android.R.style.Theme_Translucent_NoTitleBar`) with a dimmed background (`dimAmount = 0.6f`) to the foreground `Activity` obtained from `topActivity()`.
   - The WebView hosting the challenge is displayed directly inside this dialog at native device dimensions.
   - If user dismisses the dialog or taps outside (the page body forwards a `cancel` message), the challenge is cancelled and throws an exception.
   - If the hosting Activity is destroyed, lifecycle hooks unhook and dismiss the dialog cleanly without leaking memory.
   - If no foreground Activity exists when interaction is needed, the run fails with `IllegalStateException` instead of hanging.
   - The dialog is also dismissed in a `finally` block when `runWebView` returns, throws, or times out.

3. **Bridge Messages & Error Handling**:
   The page communicates with Kotlin through `window.turnstileBridge.postMessage(...)` (a `webMessageBridge`, not `jsBridge`). Allowed origins are restricted to the target page origin so Cloudflare's iframes cannot post.
   - `"token:<value>"`: on challenge success, dismisses the overlay and completes `runWebView` with the token string.
   - `"error:<code>"`: maps internal error codes to clear messages (e.g. expired, timeout, `script_load_failed`, sitekey rejected `110100`/`110110`/`400020`, unauthorized domain `110200`, wrong device clock `200100`).
   - `"cancel"`: rejects with `"Captcha cancelled"`.
   - `"show"` / `"hide"`: shows/dismisses the interactive overlay dialog.

## API Signature

`TurnstileHelper` exposes a single suspend function:

```kotlin
package keiyoushi.utils

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

suspend fun getTurnstileToken(
    url: String,
    siteKey: String,
    userAgent: String? = null,
    action: String? = null,
    cData: String? = null,
    timeout: Duration = 2.minutes,
): String
```

- `url`: Target page URL (used as the WebView page origin via `loadData`, and for the `webMessageBridge` allowed-origin rule).
- `siteKey`: The Cloudflare Turnstile sitekey extracted from the target website.
- `userAgent`: Optional client user-agent. When `null` (default), the helper derives a reduced-Chrome UA from the WebView's own Chrome major version (`Mozilla/5.0 (Linux; Android 10; K) ... Chrome/<major>.0.0.0 Mobile Safari/537.36`) so the claimed browser matches the engine. Turnstile tokens are not tied to a UA, so the token can still be sent on requests that use the source's own `User-Agent` header. Only pass `userAgent` when the site requires a specific one.
- `action`: Optional Turnstile `action` render option (e.g. `"msr_read"`).
- `cData`: Optional Turnstile `cData` render option.
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
    val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()

    // 1. Check if page contains Turnstile challenge
    val challengeElement = document.selectFirst(".cf-turnstile, [data-sitekey]")
    if (challengeElement != null) {
        val siteKey = challengeElement.attr("data-sitekey")

        // 2. Solve challenge headlessly (interactive overlay appears only if needed)
        val turnstileToken = getTurnstileToken(
            url = chapterUrl,
            siteKey = siteKey,
        )

        // 3. Submit token in POST body or Header as expected by the site
        val formBody = FormBody.Builder()
            .add("cf-turnstile-response", turnstileToken)
            .build()

        val unlockedDoc = client.post(url = chapterUrl, fetch = Fetch.DOCUMENT, body = formBody).asJsoup()
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

    return client.get(endpoint, customHeaders, Fetch.XHR).parseAs<ApiResponse>()
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
    var document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()

    val lockForm = document.selectFirst("form.post-password-form")
    if (lockForm != null) {
        val password = askPassword(
            title = chapter.name,
            message = "This chapter requires a password",
        ) ?: return emptyList()

        val postAction = lockForm.absUrl("action").ifEmpty {
            "$baseUrl/wp-login.php?action=postpass"
        }
        val formBody = FormBody.Builder()
            .add("post_password", password)
            .add("redirect_to", chapterUrl)
            .build()

        val postResponse = client.post(url = postAction, fetch = Fetch.DOCUMENT, body = formBody, ensureSuccess = false)
        document = if (postResponse.isSuccessful && !postResponse.request.url.toString().contains("wp-login.php")) {
            postResponse.asJsoup()
        } else {
            client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
        }

        if (document.selectFirst("form.post-password-form") != null) {
            return emptyList()
        }
    }

    return parsePages(document)
}
```

### Cache Trap: Password-Locked Pages

Password-protected WordPress pages answer the *locked* HTML with a cacheable response — e.g.
`Cache-Control: max-age=3600, public` and **no `Vary: Cookie`**. The OkHttp disk cache stores the
locked page keyed on URL alone, so after `wp-login.php?action=postpass` sets the `wp-postpass`
cookie, a normal `client.get(chapterUrl)` (default `Cache-Control: max-age=600`) keeps serving the
stale locked page — the password prompt appears to fail even though the cookie is correct, and the
chapter list keeps its lock icons.

This hits three places at once:

- the **initial** chapter GET (serves a previously cached lock page — the user is asked again),
- the **redirected GET inside** `client.post(...)` (the 302-follow GET inherits the request's
  cache-control and can hit cache — `postResponse.body` is then the old locked page),
- any **explicit refetch** (e.g. the chapter-list refresh that should drop the lock icons).

Fix: use `CacheControl.FORCE_NETWORK` on every GET that can see post-login/post-password state.

```kotlin
import okhttp3.CacheControl

override suspend fun getPageList(chapter: SChapter): List<Page> {
    val chapterUrl = getChapterUrl(chapter)
    var html = client.get(chapterUrl, Fetch.DOCUMENT, CacheControl.FORCE_NETWORK).body.string()
    var document = Jsoup.parse(html, chapterUrl)

    val lockForm = document.selectFirst("form.post-password-form")
    if (lockForm != null) {
        val password = askPassword(
            title = chapter.name,
            message = "This chapter requires a password",
        ) ?: throw Exception("Password input cancelled")

        val postAction = lockForm.absUrl("action").ifEmpty {
            "$baseUrl/wp-login.php?action=postpass"
        }
        val formBody = FormBody.Builder()
            .add("post_password", password)
            .add("redirect_to", chapterUrl)
            .add("Submit", "Nhập")
            .build()
        val postHeaders = headers.newBuilder().set("Referer", chapterUrl).build()

        // Discard the followed redirect (it can be a cached locked page); refetch from network.
        client.post(postAction, postHeaders, Fetch.DOCUMENT, formBody, ensureSuccess = false).close()
        html = client.get(chapterUrl, Fetch.DOCUMENT, CacheControl.FORCE_NETWORK).body.string()
        document = Jsoup.parse(html, chapterUrl)

        if (document.selectFirst("form.post-password-form") != null) {
            throw Exception("Incorrect password")
        }
    }
    // ...
}
```

Notes:

- `client.get`/`post` accept a `cacheControl` parameter; `CacheControl.FORCE_NETWORK` skips disk
  cache reads entirely.
- Symptom fingerprint: the refetch returns a 200 in single-digit milliseconds — a real network
  round-trip can't be that fast.
- WordPress sets a `wp-postpass_*` cookie even for a wrong password — the cookie's presence is not
  proof of success; check for the password form in the final HTML instead.

### Cancellation & Exception Handling in Dialogs

- **No `try`/`catch` or `CancellationException` import needed:** When a user dismisses the dialog (tapping outside, pressing Back, or clicking Cancel), dialog helpers return `null` (or `false` for `askConfirm`). Handle cancellation with `?: return emptyList()` (or another non-throwing fallback); CONTRIBUTING prefers `emptyList()` over a hardcoded exception for locked content so the app can show a localized message.
- Coroutine cancellation propagates automatically through `suspendCancellableCoroutine`, dismissing the dialog and unhooking listeners cleanly without manual handling.
- Do **not** wrap dialog calls in `try { ... } catch (e: Exception)` or `runCatching` solely to handle cancel/dismiss, as doing so swallows coroutine cancellation or catches unrelated errors.

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

## Pass POST Bodies with Named Parameters

CONTRIBUTING's `post` signature is `post(url, headers, fetch, body)`. Prefer named arguments so the `Fetch` kind is explicit and the body does not land in the `headers` slot by position:

```kotlin
client.post(url = url, fetch = Fetch.DOCUMENT, body = formBody)
```

Do not use the positional shorthand `client.post(url, formBody)` — it silently places `formBody` in the `headers` parameter slot and drops browser headers.

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
    val genres = async { client.get("$baseUrl/api/genres", Fetch.XHR).parseAs<GenreResponse>() }
    val teams = async { client.get("$baseUrl/api/teams", Fetch.XHR).parseAs<TeamResponse>() }

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

    return parseMangaList(client.get(url, Fetch.DOCUMENT))
}
```

**Benefits:**
- Simpler implementation
- Works across different sites

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
        return parseMangaList(client.get("$baseUrl/$categoryPath?page=$page", Fetch.DOCUMENT))
    }

    // Fallback to query search
    val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
        if (query.isNotEmpty()) addQueryParameter("q", query)
    }.build()

    return parseMangaList(client.get(url, Fetch.DOCUMENT))
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
        return parseMangaList(client.get(url, Fetch.DOCUMENT))
    }

    if (categoryPath != null) {
        return parseMangaList(client.get("$baseUrl/$categoryPath?page=$page", Fetch.DOCUMENT))
    }

    val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
        addQueryParameter("page", page.toString())
    }.build()

    return parseMangaList(client.get(url, Fetch.DOCUMENT))
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
    val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
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
    val detailResponse = client.get("$baseUrl/api/comics/${manga.url}", Fetch.XHR).parseAs<ComicDetailResponse>()

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
2. **Sort order:** alphabetical ASCII order — the same ordering IntelliJ IDEA and ktlint's default `import-ordering` rule produce. In practice that puts `android.*`/`androidx.*` first, then `eu.kanade.*`, `java.*`, `javax.*`, `keiyoushi.*`, `kotlinx.*`, `kotlin.*`, `okhttp3.*`, `okio.*`, `org.*`, with no blank lines between them.
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

## Chapter Numbers — Pass Through, Never Parse

Do not extract chapter numbers with regexes in the extension. The app's
`ChapterRecognition` already parses them from the chapter name when
`chapter_number` is unset — it recognizes "Chap 4: ...", "Chapter 20 - ...",
"Chương N", plain leading numbers, volumes, and parts, and even numbers unnamed
chapters from their neighbours. The extension only needs two things:

1. **Set `chapter_number` only when the API provides a numeric value.** Leave it
   unset (default `-1f`) otherwise — the app treats `-1` as unknown and falls
   back to name parsing. Never invent a number by scanning the title or the
   chapterNumber string with a regex.
2. **Sort chapters by an explicit API ordering field** (`order`, index, or the
   payload's own order), not by parsing numbers. Fall back to a plain
   `toDoubleOrNull()` on the API's number field, nothing fancier.

```kotlin
// ❌ Don't — parses numbers the app already knows how to parse
private val chapterNumberRegex = Regex("""\d+(?:\.\d+)?""")

chapter_number = chapterNumberRegex.find(chapter.title)?.value?.toFloatOrNull() ?: -1f

// ✅ Do — pass through the API value when present, else let the app parse the name
chapter.chapterNumber?.toDoubleOrNull()?.let { chapter_number = it.toFloat() }
```

```kotlin
// ❌ Don't — sort by extracted numbers
chapters.sortedByDescending {
    chapterNumberRegex.find(it.chapterNumber)?.value?.toDoubleOrNull()
}

// ✅ Do — sort by the API's explicit order, plain parse as fallback
chapter.order ?: chapter.chapterNumber?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
```

This also makes nullable API fields trivial to handle: when a source starts
returning `chapterNumber: null` for some entries, the DTO becomes
`String? = null` and nothing else changes — those chapters simply fall back to
app-side name recognition.
