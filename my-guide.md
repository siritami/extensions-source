# Auth Token via WebView

## How it works

1. User logs in through the in-app WebView → token is saved to `localStorage` on the source's origin
2. Extension reads the token from `localStorage` via `getLocalStorage(baseUrl, "token")`
3. Token is cached and sent as `Authorization: Bearer <token>` on API requests

## Code

```kotlin
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
private fun authInterceptor() = okhttp3.Interceptor { chain ->
    val original = chain.request()
    val request = original.newBuilder().apply {
        cachedAuthToken?.let { header("Authorization", "Bearer $it") }
    }.build()
    chain.proceed(request)
}
```

## Requirements

- `getLocalStorage` creates a WebView at `baseUrl` origin and reads `localStorage`
- This only works if the user logged in through the **in-app WebView**, not the system browser
- The in-app WebView and `getLocalStorage`'s WebView share the same DOM storage (same app, same origin)

---

# General Extension Development Notes

## Deeplink Configuration

For any extension, ensure deeplink configuration in `build.gradle.kts` properly matches the site's URL structure:

```kotlin
deelink {
    path("/.*")
}
```

This ensures deeplinks like `https://<source-domain>.com/any-path` work correctly.

**Best Practices:**
- Use `path("/.*")` to match any path pattern
- Avoid using `host()` as it is not necessary when using `baseUrl`
- Avoid overly restrictive patterns that may miss valid URLs

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
- More reliable for direct URLs
- Better compatibility with deeplinks
- Simpler implementation
- Works across different sites

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

When implementing `fetchMangaUpdate`, always return both manga details and chapters unconditionally:

```kotlin
override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate {
    val response = client.get("$baseUrl${manga.url}")
    val document = response.asJsoup()

    return SMangaUpdate(
        manga = parseDetails(document, manga),
        chapters = parseChapters(document),
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

# JsonElement Utilities

`keiyoushi.utils` provides shorthand extensions for `kotlinx.serialization.json.JsonElement`.
Import them to avoid verbose `jsonObject`/`jsonArray`/`jsonPrimitive` chains.

## Key imports

```kotlin
import keiyoushi.utils.get     // operator fun JsonElement?.get(key: String): JsonElement?
import keiyoushi.utils.array   // val JsonElement.array: JsonArray
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
