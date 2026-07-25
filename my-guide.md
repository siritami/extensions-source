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

## Source Code Organization

For source files with several responsibilities, group each override with its
related parsers and helpers under consistent section markers. Keep sections in
the normal source flow: Auth (when needed), Popular, Latest, Search, Details,
Pages, Filters, then Related (when supported).

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
```

Place narrowly scoped helpers in the section that uses them. Keep shared
configuration near the top of the class and constants near the bottom. Do not
add empty optional sections, and do not reorder code when doing so would change
initialization or runtime behavior.

Place a helper next to the selector or request function that owns and calls it
when it has fewer than three call sites. Keep it in a shared Utilities section
when it is called three or more times, especially when those callers use
different selectors or parsing contexts.

## Derived Request Headers

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

Only perform the network request for a requested field. When details and chapters use independent requests, start both with `async` inside `coroutineScope` so they run in parallel, then preserve the existing value for any field that was not requested:

If the details document is always fetched and already contains the manga details, parse and return those details unconditionally instead of checking `fetchDetails`.

```kotlin
override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate = coroutineScope {
    val mangaDeferred = if (fetchDetails) {
        async {
            val document = client.get("$baseUrl${manga.url}").asJsoup()
            parseDetails(document, manga)
        }
    } else {
        null
    }
    val chaptersDeferred = if (fetchChapters) {
        async { fetchChapters(manga) }
    } else {
        null
    }

    SMangaUpdate(
        manga = mangaDeferred?.await() ?: manga,
        chapters = chaptersDeferred?.await() ?: chapters,
    )
}
```

**Why:**
- Ensures consistency in the data returned
- Avoids partial updates that may cause issues
- Simplifies the logic by removing conditional checks
- Follows the pattern used in most successful extensions

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
import keiyoushi.utils.array   // val JsonElement.array: JsonArray
import keiyoushi.utils.int     // val JsonElement.int: Int
import keiyoushi.utils.long    // val JsonElement.long: Long
import keiyoushi.utils.string  // val JsonElement.string: String
```

## Usage

```kotlin
// Before (verbose)
val genres = data?.jsonObject?.get("data") as? JsonArray

// After (shorthand)
val genres = data["data"]?.array
```

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
