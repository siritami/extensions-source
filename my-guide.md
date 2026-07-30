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
    cachedAuthToken = runCatching {
        getLocalStorage(baseUrl, "token")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
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

## Respect `fetchChapters` in `fetchMangaUpdate`

When overriding `fetchMangaUpdate`, check the `fetchChapters` parameter before
calling `fetchChapterList`. Omitting this check causes unnecessary network calls
when the client only requests manga details. When `fetchChapters` is `false`,
return the `chapters` argument so the existing chapter list is preserved.

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
        chapters = if (fetchChapters) fetchChapterList(document) else chapters,
    )
}
```

## Store Thumbnail Fallback URLs in Fragments

When each thumbnail has its own fallback URL, store the fallback in the primary
thumbnail URL's fragment and read it from an application interceptor. URL
fragments remain available through `request.url.fragment` but are not sent to
the image server.

```kotlin
private val thumbnailFallbackInterceptor = Interceptor { chain ->
    val request = chain.request()
    val response = chain.proceed(request)
    val fallbackUrl = request.url.fragment
        ?.takeIf { it.startsWith(thumbnailFallbackFragmentPrefix) }
        ?.removePrefix(thumbnailFallbackFragmentPrefix)
        ?: return@Interceptor response

    if (response.code != 401 && response.code != 404) {
        return@Interceptor response
    }

    response.close()
    chain.proceed(GET(fallbackUrl, request.headers))
}

private fun withThumbnailFallback(primaryUrl: String, fallbackUrl: String): String =
    primaryUrl.toHttpUrl().newBuilder()
        .fragment("$thumbnailFallbackFragmentPrefix$fallbackUrl")
        .build()
        .toString()

private val thumbnailFallbackFragmentPrefix = "fallback-url:"
```

Prefer this over storing primary-to-fallback pairs in a mutable map. The
fragment keeps the fallback scoped to its image request, works for repeated and
concurrent requests, and does not leave stale entries when an image is never
loaded. Use a distinct prefix so the interceptor ignores unrelated fragments,
and close the failed response before executing the fallback request.

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
- The user can resolve the condition through a concrete action.
- The message explains that action instead of reporting a generic parsing error.
- Returning `emptyList()` would hide the required action from the user.

Continue returning `emptyList()` when content is merely missing, empty, or
unsupported and there is no useful action for the user to take. Do not replace
an existing actionable custom message with `emptyList()` solely to follow the
generic empty-list recommendation.

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
parse and return both unconditionally. Do not check `fetchDetails` or
`fetchChapters` after paying for the shared request, because doing so discards
data that is already available.

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

Only preserve an unrequested existing value when obtaining that field requires
an independent request or other meaningful extra work.

---

# ISO Date Parsing with `kotlin.time.Instant`

## Why

# ISO Date Parsing with `kotlin.time.Instant`

## Why

`SimpleDateFormat` requires manually specifying the format string and time zone. `kotlin.time.Instant.parseOrNull()` automatically parses ISO 8601 date strings including the embedded time zone offset.

## Code

```kotlin
import kotlin.time.Instant

// Parse ISO date string → epoch millis (Long)
val epochMillis = Instant.parseOrNull("2025-01-15T10:30:00.000Z")?.toEpochMilliseconds() ?: 0L
```

## Notes

- `Instant.parseOrNull()` returns `null` on invalid input instead of throwing
- `toEpochMilliseconds()` returns epoch millis in UTC — suitable for `SChapter.date_upload`
- Works with all ISO 8601 offsets (`Z`, `+07:00`, etc.)
- Replaces `SimpleDateFormat` + manual `TimeZone` setup

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

## Site-local timestamps without an offset

For a fixed numeric format such as `yyyy-MM-dd HH:mm:ss`, use the thread-safe
`java.time` API. Parse it as `LocalDateTime`, apply the site's known zone, and
then convert it to epoch milliseconds:

```kotlin
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
private val dateZone = ZoneId.of("Asia/Ho_Chi_Minh")

private fun parseDate(date: String?): Long {
    if (date == null) return 0L
    return runCatching {
        LocalDateTime.parse(date, dateFormat)
            .atZone(dateZone)
            .toInstant()
            .toEpochMilli()
    }.getOrDefault(0L)
}
```

- Keep `DateTimeFormatter` and `ZoneId` at class or file level.
- `DateTimeFormatter` is immutable and thread-safe, unlike `SimpleDateFormat`.
- Use `LocalDateTime` only when the input has no offset or zone information.
- Apply the source site's zone explicitly; never rely on the device default zone.

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
