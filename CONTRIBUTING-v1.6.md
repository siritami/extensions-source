# Contributing — Extension Library v1.6

> **This guide supersedes the lib 1.4 sections of `CONTRIBUTING.md` for extensions targeting
> `libVersion = "1.6"`.** If you are maintaining an existing lib-1.4 extension, read the
> [Migration Guide](#migrating-from-lib-14-to-lib-16) at the bottom.

---

## Table of Contents

- [What changed in lib 1.6](#what-changed-in-lib-16)
  - [KeiSource replaces HttpSource as the base class](#keisource-replaces-httpsource-as-the-base-class)
  - [Automatic OkHttpClient setup](#automatic-okhttpclient-setup)
  - [Built-in CacheControlInterceptor](#built-in-cachecontrolinterceptor)
  - [Built-in RateLimitInterceptor](#built-in-ratelimitinterceptor)
  - [Suspend-first API (no more RxJava)](#suspend-first-api-no-more-rxjava)
  - [New mandatory methods](#new-mandatory-methods)
  - [Deprecated / removed methods](#deprecated--removed-methods)
  - [Network helper functions (`keiyoushi.network.get` / `POST`)](#network-helper-functions)
- [How to write an extension with lib 1.6](#how-to-write-an-extension-with-lib-16)
  - [build.gradle.kts](#buildgradleks)
  - [Source class skeleton](#source-class-skeleton)
  - [Popular Manga](#popular-manga)
  - [Latest Manga](#latest-manga)
  - [Search](#search)
  - [Manga Details](#manga-details)
  - [Chapters](#chapters)
  - [Page List](#page-list)
  - [Filters with network fetching](#filters-with-network-fetching)
  - [Related Manga](#related-manga)
  - [Rate limiting](#rate-limiting)
  - [Client customisation](#client-customisation)
  - [Header customisation](#header-customisation)
  - [Deeplinks](#deeplinks)
  - [Multi-source themes](#multi-source-themes)
- [Migration Guide: lib 1.4 → lib 1.6](#migrating-from-lib-14-to-lib-16)
  - [Step-by-step checklist](#step-by-step-checklist)
  - [Before / After code examples](#before--after-code-examples)

---

## What changed in lib 1.6

### KeiSource replaces HttpSource as the base class

In lib 1.4, every extension directly extended `HttpSource` (from
`eu.kanade.tachiyomi.source.online.HttpSource`). In lib 1.6, you extend **`KeiSource`**
(from `keiyoushi.source.KeiSource`) instead.

`KeiSource` **extends `HttpSource`** internally, so all `HttpSource` infrastructure is still
available, but `KeiSource` adds opinionated defaults and forces a cleaner API surface.

Key characteristics of `KeiSource`:

| Aspect | lib 1.4 (`HttpSource`) | lib 1.6 (`KeiSource`) |
|---|---|---|
| Base class | `HttpSource` | `KeiSource` (wraps `HttpSource`) |
| `client` property | Manual override (`override val client`) | **Final** — built automatically by `OkHttpClient.Builder.configureClient()` |
| `headers` property | Lazy (evaluated once) | Eager (rebuilt per call) |
| `headersBuilder()` | Optional override | Override via `configureHeaders()` callback |
| `popularMangaRequest` / `popularMangaParse` | Deprecated, but still overridable | **Sealed** — throws `UnsupportedOperationException` |
| `getPopularManga(page)` | Deprecated; still RxJava-based internally | **`suspend fun`** — coroutine-first |
| `getLatestUpdates(page)` | Deprecated; still RxJava-based internally | **`suspend fun`** — coroutine-first |
| `getSearchManga` | Deprecated; still RxJava-based internally | **`suspend fun`** — coroutine-first |
| `fetchMangaDetails` / `fetchChapterList` | Separate methods | Merged into single **`suspend fun fetchMangaUpdate`** |
| `fetchPageList` | Deprecated RxJava | **`suspend fun getPageList`** |
| `fetchImageUrl` | Deprecated RxJava | Sealed — throw `UnsupportedOperationException` |
| Related manga | Not supported | Optional **`suspend fun fetchRelatedMangaList`** hook (Komikku) |
| Filter fetching | Manual caching | **Built-in** with `fetchFilterData()` + `getFilterList(data)` |
| URL search (deeplink in search bar) | Manual implementation | **Automatic dispatch** via `getMangasByUrl(url, page)` |

### Automatic OkHttpClient setup

In lib 1.6, you **never** declare `override val client`. Instead, you override a
protected callback:

```kotlin
protected open fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
```

`KeiSource` constructs the `client` lazily from the app's base `network.client`, applying:

1. **Safety checks** — ensures `UncaughtExceptionInterceptor`, `UserAgentInterceptor`, and
   `CloudflareInterceptor` are present; ensures `IgnoreGzipInterceptor` and `BrotliInterceptor`
   are absent.
2. **Your `configureClient()` call** — your custom interceptors / timeouts go here.
3. **CloudflareInterceptor reordering** — moved to the end of application interceptors so it
   runs after source-specific interceptors.
4. **CompressionInterceptor** — adds Brotli, Gzip, and Zstd decompression.
5. **CacheControlInterceptor** — network interceptor that widens cache headers (see below).
6. **RateLimitInterceptor reordering** — moved to the last network interceptor position.

You can chain `.rateLimit(...)` calls inside `configureClient()` — see [Rate limiting](#rate-limiting).

### Built-in CacheControlInterceptor

`CacheControlInterceptor` is a **network interceptor** automatically added by `KeiSource`.
It can widen an existing permissive response `Cache-Control` header when the request asks
for a positive cache lifetime but the response does not provide one.

**How it works:**

1. It only considers successful responses whose request has a positive
    `cacheControl.maxAgeSeconds`.
2. The response must already contain a `Cache-Control` header. A missing header is left
    unchanged.
3. Responses containing `no-store`, `no-cache`, `private`, or an existing `max-age` are
    left unchanged.
4. Otherwise, it replaces the response header with
    `Cache-Control: public, max-age=<requestMaxAge>`.

The default request `max-age` is **10 minutes** when using the `keiyoushi.network.get()`
helper. When the response meets the conditions above, subsequent requests to the same URL
within 10 minutes can be served from the local cache without hitting the network.

> [!NOTE]
> You do **not** need to add `CacheControlInterceptor` yourself — `KeiSource` handles it.

### Built-in RateLimitInterceptor

`RateLimitInterceptor` provides sliding-window rate limiting with optional burst smoothing.
It is applied as the **last network interceptor**, so matching network requests are limited
immediately before dispatch. Requests are tagged so retries or interceptor re-entry do not
consume another rate-limit slot.

Configure it via the `rateLimit()` extension on `OkHttpClient.Builder` inside
`configureClient()`:

```kotlin
override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
    // Allow 10 requests per second, minimum 100ms between requests
    rateLimit(permits = 10, period = 1.seconds, interval = 100.milliseconds)
}
```

Multiple rules can be chained. The first rule whose `shouldLimit` predicate matches the
request URL is applied:

```kotlin
override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
    rateLimit(5) { it.host == "api.manga.example" }
    rateLimit(20) { it.host == "img.manga.example" }
}
```

> [!IMPORTANT]
> **Never** use `Thread.sleep()` for rate limiting. Always use `rateLimit()`.

### Suspend-first API (no more RxJava)

All primary operations in `KeiSource` use **`suspend fun`**. The old RxJava `Observable`-based
methods (`fetchPopularManga`, `fetchSearchManga`, `fetchChapterList`, `fetchPageList`, etc.)
are sealed as `final override` with `@Deprecated("Hidden")`. The `fetch*` Observable methods
delegate to `super`, which internally calls the `*Request`/`*Parse` methods that throw
`UnsupportedOperationException` — so they fail at runtime if invoked.

You **must** override these operations:

| Method | Signature |
|---|---|
| Popular manga | `abstract suspend fun getPopularManga(page: Int): MangasPage` |
| Latest updates | `abstract suspend fun getLatestUpdates(page: Int): MangasPage` |
| Search | `abstract suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage` |
| Manga details + chapters | `abstract suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate` |
| Page list | `abstract suspend fun getPageList(chapter: SChapter): List<Page>` |

These hooks have defaults and are optional:

| Method | Default behavior |
|---|---|
| `protected open suspend fun getMangaByUrl(url: HttpUrl): SManga?` | Throws a not-implemented exception. Override for a single manga URL. |
| `protected open suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage` | Calls `getMangaByUrl` and wraps its result in a non-paginated `MangasPage`. Override when one URL can produce multiple or paginated manga. |
| `override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga>` | Returns `emptyList()`. |
| `override fun getMangaUrl(manga: SManga): String` | Returns `baseUrl + manga.url`. |
| `override fun getChapterUrl(chapter: SChapter): String` | Returns `baseUrl + chapter.url`. |

### New mandatory methods

#### `getSearchMangaList(page, query, filters): MangasPage`

Replaces the old `searchMangaRequest` + `searchMangaParse` pair. Implement the entire
search in one suspend function. The base class `getSearchManga` handles URL-based search
automatically. If the `query` can be parsed as an `HttpUrl` by `toHttpUrlOrNull()`, it
delegates to `getMangasByUrl(url, page)` before reaching your plain-text search method.

#### Optional URL search hooks

Override `getMangaByUrl(url)` when a recognized URL maps to one manga. Return that manga,
or `null` if the URL is not recognized. The default `getMangasByUrl` implementation wraps
that result in `MangasPage(listOfNotNull(manga), false)`.

Override `getMangasByUrl(url, page)` instead when a URL represents a listing, creator page,
tag, or another paginated result. Its `page` parameter follows the same pagination rules as
the other listing methods.

#### `fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters): SMangaUpdate`

Replaces the old pair of `fetchMangaDetails` + `fetchChapterList`. The app tells you
*what* to fetch via the boolean flags. Return an `SMangaUpdate` with only the requested
fields filled.

> [!NOTE]
> The wrapper `getMangaUpdate` (called by the app) automatically sets
> `manga.initialized = true` on the result and enforces no concurrent calls for the same
> manga URL. You do not need to set `initialized` yourself in `fetchMangaUpdate`.

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
        manga = if (fetchDetails) parseDetails(document, manga) else manga,
        chapters = if (fetchChapters) parseChapters(document) else chapters,
    )
}
```

> [!TIP]
> You can use `fetchMangaUpdate` directly in `getMangaByUrl` to resolve manga URLs —
> call `fetchMangaUpdate(manga, emptyList(), true, false)`.

### Deprecated / removed methods

The following old `HttpSource` methods are **sealed** in `KeiSource` and will throw
`UnsupportedOperationException`:

- `popularMangaRequest(page)` / `popularMangaParse(response)`
- `latestUpdatesRequest(page)` / `latestUpdatesParse(response)`
- `searchMangaRequest(page, query, filters)` / `searchMangaParse(response)`
- `mangaDetailsRequest(manga)` / `mangaDetailsParse(response)`
- `chapterListRequest(manga)` / `chapterListParse(response)`
- `pageListRequest(chapter)` / `pageListParse(response)`
- `imageUrlRequest(page)` / `imageUrlParse(response)`
- `relatedMangaListRequest(manga)` / `relatedMangaListParse(response)`

Additionally, the `Observable`-based wrappers (`fetchPopularManga`, `fetchLatestUpdates`,
`fetchSearchManga`, `fetchMangaDetails`, `fetchChapterList`, `fetchPageList`,
`fetchImageUrl`, `prepareNewChapter`) are sealed as `final override` and will throw at
runtime when their internal `*Request`/`*Parse` delegates are reached.

Do **not** override any of these methods.

### Network helper functions

`keiyoushi.network` provides suspend-capable `GET` and `POST` helpers that work with
`OkHttpClient` directly:

```kotlin
import keiyoushi.network.get

// GET with HttpUrl
val response = client.get(url, headers)

// GET with string URL
val response = client.get("$baseUrl/manga", headers)

// GET with default headers from the current source (context receiver)
context(source: HttpSource)
val response = client.get(url)
```

These helpers set a default `Cache-Control: max-age=600` (10 minutes) and optionally call
`awaitSuccess()` to throw on non-2xx responses.

---

## How to write an extension with lib 1.6

### build.gradle.kts

```kotlin
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "My Source"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"  // <-- lib 1.6

    source {
        lang = "en"
        baseUrl = "https://example.com"
    }
}
```

### Source class skeleton

```kotlin
package eu.kanade.tachiyomi.extension.en.mysource

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MySource : KeiSource() {

    // ---- Client configuration ----

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(10, 1.seconds)
    }

    // ---- Popular ----

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/popular?page=$page")
        val document = response.asJsoup()
        val mangaList = document.select(".manga-item").map { el ->
            SManga.create().apply {
                title = el.selectFirst(".title")!!.text()
                setUrlWithoutDomain(el.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = el.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(mangaList, document.selectFirst(".next-page") != null)
    }

    // ---- Latest ----

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$baseUrl/latest?page=$page")
        val document = response.asJsoup()
        val mangaList = document.select(".manga-item").map { el ->
            SManga.create().apply {
                title = el.selectFirst(".title")!!.text()
                setUrlWithoutDomain(el.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = el.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(mangaList, document.selectFirst(".next-page") != null)
    }

    // ---- Search ----

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val response = client.get("$baseUrl/search?q=$query&page=$page")
        val document = response.asJsoup()
        val mangaList = document.select(".manga-item").map { el ->
            SManga.create().apply {
                title = el.selectFirst(".title")!!.text()
                setUrlWithoutDomain(el.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = el.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(mangaList, document.selectFirst(".next-page") != null)
    }

    // ---- Optional URL search (deeplink / paste URL) ----

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url)
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()
            setUrlWithoutDomain(url.encodedPath)
            thumbnail_url = document.selectFirst("meta[property=og:image]")
                ?.attr("content")
            initialized = true
        }
    }

    // ---- Details + Chapters (unified) ----

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl${manga.url}")
        val document = response.asJsoup()

        return SMangaUpdate(
            manga = if (fetchDetails) {
                SManga.create().apply {
                    title = document.selectFirst("h1")!!.text()
                    description = document.selectFirst(".description")?.text()
                    genre = document.select(".genre a").joinToString { it.text() }
                    thumbnail_url = document.selectFirst("meta[property=og:image]")
                        ?.attr("content")
                    status = document.selectFirst(".status")?.text().toStatus()
                    setUrlWithoutDomain(manga.url)
                    initialized = true
                }
            } else manga,
            chapters = if (fetchChapters) {
                document.select(".chapter-item").map { el ->
                    SChapter.create().apply {
                        name = el.selectFirst(".chapter-title")!!.text()
                        date_upload = el.selectFirst(".date")?.text().let { parseDate(it) }
                        setUrlWithoutDomain(el.selectFirst("a")!!.absUrl("href"))
                    }
                }
            } else chapters,
        )
    }

    // ---- Pages ----

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl${chapter.url}")
        return response.asJsoup()
            .select(".page-image img")
            .mapIndexed { i, el ->
                Page(i, imageUrl = el.absUrl("data-src").ifEmpty { el.absUrl("src") })
            }
    }

    // ---- Helpers ----

    private fun String?.toStatus(): Int = when {
        this == null -> SManga.UNKNOWN
        contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
}
```

### Popular Manga

Override `suspend fun getPopularManga(page: Int): MangasPage`. Return a `MangasPage` with
the manga list and whether there is a next page. Use `client.get(url)` to fetch and
`response.asJsoup()` to parse HTML.

### Latest Manga

Override `suspend fun getLatestUpdates(page: Int): MangasPage`. Same pattern as popular
manga. Set `override val supportsLatest get() = true` (the default) if the source has a
latest listing.

### Search

Override `suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage`.
Implement the full search logic here. The base class automatically handles URL-based search
(deeplink / paste-URL) before calling your method.

### Manga Details

Implement `suspend fun fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters): SMangaUpdate`.
The app tells you what to fetch via the boolean flags. Only fill the requested fields:

- If `fetchDetails` is `true`, populate and return the updated `SManga`.
- If `fetchChapters` is `true`, parse and return the chapter list.
- For the other flag, pass through the existing value unchanged.

### Chapters

Chapters are returned as part of `fetchMangaUpdate` (see above). Each chapter should set
`name`, `date_upload` (milliseconds), and `url` (via `setUrlWithoutDomain`).

### Page List

Override `suspend fun getPageList(chapter: SChapter): List<Page>`. Return pages in the
correct order. Use `Page(index, imageUrl = url)` for direct image URLs, or
`Page(index, url = pageUrl)` if the image URL needs a separate request.

### Filters with network fetching

`KeiSource` includes a built-in filter caching system for sources whose filter options
change frequently (e.g., genre lists fetched from an API).

1. Set `override val supportsFilterFetching get() = true`
2. Implement `suspend fun fetchFilterData(): JsonElement` — fetch the data from the API.
   Throwing counts as a failed attempt; up to 3 retries are made.
3. Implement `fun getFilterList(data: JsonElement?): FilterList` — build filters from cached
   data. This is called **synchronously** on the UI thread, so it must be fast and must NOT
   perform I/O or network requests.

The base class handles caching (zstd-compressed JSON file stored in
`<cacheDir>/source_<id>/filters.json.zst`, 3-day TTL), retry logic
(max 3 attempts), and background fetching automatically.

```kotlin
override val supportsFilterFetching get() = true

override suspend fun fetchFilterData(): JsonElement {
    val response = client.get("$baseUrl/api/genres")
    return response.parseAs<JsonElement>()
}

override fun getFilterList(data: JsonElement?): FilterList {
    if (data == null) return FilterList()
    val genres = data.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    return FilterList(GenreFilter(genres.toTypedArray()))
}
```

### Related manga

Only works on Komikku. `KeiSource` defaults `supportsRelatedMangas` to `false`, and
`fetchRelatedMangaList(manga)` returns `emptyList()`.

For a source that supports related manga, set `supportsRelatedMangas = true` and override
`fetchRelatedMangaList(manga)` to return the related entries. No override is needed when the
feature is unsupported.

Set `supportRelatedMangasBySearch = true` to fall back to title-based search if the
source doesn't provide a related manga endpoint.

#### Caching data between `fetchMangaUpdate` and `fetchRelatedMangaList`

When `fetchMangaUpdate` already fetches detail data (e.g., story info) that
`fetchRelatedMangaList` also needs, avoid duplicate API calls by caching the data
in a `mutableMapOf`:

```kotlin
private val storyCache = mutableMapOf<String, StoryDetail>()

override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate {
    // ...
    val story = client.get("$apiUrl/stories/$slug")
        .parseAs<StoryDetailResponse>().data
    storyCache[slug] = story  // cache for fetchRelatedMangaList
    // ...
}

override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
    val slug = manga.url.substringAfterLast("/")
    val story = storyCache[slug]  // read from cache first
        ?: client.get("$apiUrl/stories/$slug")
            .parseAs<StoryDetailResponse>().data.also { storyCache[slug] = it }
    // ... use story to build related manga list
}
```

This avoids making the same API call twice when the app calls `fetchMangaUpdate`
with `fetchDetails = true` followed by `fetchRelatedMangaList`.

### Rate limiting

```kotlin
import keiyoushi.network.rateLimit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
    // 5 requests/second, minimum 200ms between consecutive dispatches
    rateLimit(permits = 5, period = 1.seconds, interval = 200.milliseconds)
}
```

### Client customisation

Always use `configureClient()` — never override `val client` directly:

```kotlin
override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
    // Add custom interceptors
    addInterceptor(MyCustomInterceptor())

    // Configure timeouts
    connectTimeout(30, TimeUnit.SECONDS)
    readTimeout(30, TimeUnit.SECONDS)

    // Add rate limiting
    rateLimit(10, 1.seconds)
}
```

### Header customisation

Override `configureHeaders()` to add source-specific headers. `Referer` and `Origin` are
already set automatically by `KeiSource`:

```kotlin
override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
    set("X-Custom-Header", "value")
}
```

### Deeplinks

Declare deeplinks in `build.gradle.kts`:

```kotlin
keiyoushi {
    // ...
    deeplink {
        path("/series/..*")
    }
}
```

For manga deeplinks, override `getMangaByUrl(url: HttpUrl)`. For deeplinks that can return
multiple or paginated manga, override `getMangasByUrl(url: HttpUrl, page: Int)` instead.

### Multi-source themes

Themes using lib 1.6 should extend `KeiSource` instead of `HttpSource`:

```kotlin
// lib-multisrc/mytheme/src/.../MyTheme.kt
abstract class MyTheme : KeiSource() {
    // Theme defaults...
}
```

In `lib-multisrc/mytheme/build.gradle.kts`:

```kotlin
keiyoushi {
    baseVersionCode = 1
    libVersion = "1.6"
}
```

---

## Migrating from lib 1.4 to lib 1.6

### Step-by-step checklist

1. **Update `build.gradle.kts`**: Change `libVersion = "1.4"` to `libVersion = "1.6"`.

2. **Change the base class**: Replace `HttpSource()` with `KeiSource()`.

3. **Add the `@Source` annotation** (if not already present).

4. **Remove `override val client`**: Replace with `override fun OkHttpClient.Builder.configureClient()`.

5. **Remove `popularMangaRequest` / `popularMangaParse`**: Implement `getPopularManga(page)` directly.

6. **Remove `latestUpdatesRequest` / `latestUpdatesParse`**: Implement `getLatestUpdates(page)` directly.

7. **Remove `searchMangaRequest` / `searchMangaParse`**: Implement `getSearchMangaList(page, query, filters)` directly.

8. **Remove `mangaDetailsRequest` / `mangaDetailsParse` + `chapterListRequest` / `chapterListParse`**: Implement `fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters)` directly.

9. **Remove `pageListRequest` / `pageListParse`**: Implement `getPageList(chapter)` directly.

10. **Remove `imageUrlRequest` / `imageUrlParse`**: These are sealed. If you had image URL resolution logic, set `Page.imageUrl` directly in `getPageList`.

11. **Optionally add URL handling**: Override `getMangaByUrl(url)` for one manga, or
    `getMangasByUrl(url, page)` for list/paginated URLs. Plain-text search works without either hook.

12. **Review `getMangaUrl(manga)` and `getChapterUrl(chapter)`**: The defaults return
    `baseUrl + url`; override only when that is not the correct website URL.

13. **Optionally add related manga**: It defaults to unsupported with an empty list. Set
    `supportsRelatedMangas = true` and override `fetchRelatedMangaList` only when implemented.

14. **Move rate limiting**: Replace `Thread.sleep()` or custom interceptor logic with `rateLimit()` inside `configureClient()`.

15. **Remove manual `CacheControlInterceptor`** if you had one — it's built in.

16. **Use `client.get(url)`** from `keiyoushi.network` instead of manually building `Request` objects.

### Before / After code examples

#### build.gradle.kts

**Before (lib 1.4):**
```kotlin
keiyoushi {
    name = "My Source"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "en"
        baseUrl = "https://example.com"
    }
}
```

**After (lib 1.6):**
```kotlin
keiyoushi {
    name = "My Source"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://example.com"
    }
}
```

#### Source class

**Before (lib 1.4):**
```kotlin
package eu.kanade.tachiyomi.extension.en.mysource

import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import okhttp3.OkHttpClient

@Source
abstract class MySource : HttpSource() {

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(SomeInterceptor())
        .build()

    // popularMangaRequest + popularMangaParse (deprecated but used)
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/popular?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select(".item").map { ... }
        return MangasPage(mangaList, hasNextPage)
    }

    // Separate mangaDetailsRequest + chapterListRequest
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply { ... }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".chapter").map { ... }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select(".page img").mapIndexed { i, el ->
            Page(i, imageUrl = el.absUrl("src"))
        }
    }
}
```

**After (lib 1.6):**
```kotlin
package eu.kanade.tachiyomi.extension.en.mysource

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.OkHttpClient

@Source
abstract class MySource : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(SomeInterceptor())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/popular?page=$page")
        val document = response.asJsoup()
        val mangaList = document.select(".item").map { ... }
        return MangasPage(mangaList, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = TODO()

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = TODO()

    // Optional: override getMangaByUrl or getMangasByUrl for URL searches.

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl${manga.url}")
        val document = response.asJsoup()

        return SMangaUpdate(
            manga = if (fetchDetails) {
                SManga.create().apply { /* parse details */ }
            } else manga,
            chapters = if (fetchChapters) {
                document.select(".chapter").map { ... }
            } else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl${chapter.url}")
        val document = response.asJsoup()
        return document.select(".page img").mapIndexed { i, el ->
            Page(i, imageUrl = el.absUrl("src"))
        }
    }
}
```

#### Rate limiting

**Before (lib 1.4):**
```kotlin
override fun popularMangaRequest(page: Int): Request {
    Thread.sleep(1000) // Bad! Do not use Thread.sleep
    return GET("$baseUrl/popular?page=$page", headers)
}
```

**After (lib 1.6):**
```kotlin
override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
    rateLimit(permits = 10, period = 1.seconds)
}
```

#### Manga details + chapters (unified)

**Before (lib 1.4):**
```kotlin
// Two separate methods
override fun mangaDetailsParse(response: Response): SManga {
    val doc = response.asJsoup()
    return SManga.create().apply { title = doc.selectFirst("h1")!!.text() }
}

override fun chapterListParse(response: Response): List<SChapter> {
    val doc = response.asJsoup()
    return doc.select(".chapter").map { SChapter.create().apply { name = it.text() } }
}
```

**After (lib 1.6):**
```kotlin
// One unified method
override suspend fun fetchMangaUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
): SMangaUpdate {
    val response = client.get("$baseUrl${manga.url}")
    val doc = response.asJsoup()

    return SMangaUpdate(
        manga = if (fetchDetails) {
            SManga.create().apply { title = doc.selectFirst("h1")!!.text() }
        } else manga,
        chapters = if (fetchChapters) {
            doc.select(".chapter").map { SChapter.create().apply { name = it.text() } }
        } else chapters,
    )
}
```

#### Search URL handling (deeplinks)

**Before (lib 1.4):** Manual URL detection in `fetchSearchManga` / `searchMangaParse`.

**After (lib 1.6):** Automatic dispatch. The base class checks if the query is a valid URL
and delegates to `getMangasByUrl(url, page)`. For a single manga URL, override the simpler
`getMangaByUrl` hook used by the default implementation:

```kotlin
override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
    val response = client.get(url)
    val doc = response.asJsoup()
    return SManga.create().apply {
        title = doc.selectFirst("h1")!!.text()
        setUrlWithoutDomain(url.encodedPath)
        initialized = true
    }
}
```

---

## Common Pitfalls & Compiler Errors

These are real issues encountered during a lib 1.4 → 1.6 migration. If you see similar
errors, use the fix below.

### `configureHeaders` overrides nothing / Unresolved reference `Headers`

**Error:**
```
'configureHeaders' overrides nothing. Potential signatures for overriding:
  fun Headers.Builder.configureHeaders(): Headers.Builder
Unresolved reference 'Headers'
```

**Cause:** Missing `import okhttp3.Headers`.

**Fix:** Add the import:
```kotlin
import okhttp3.Headers
```

### `SManga.url` / `SChapter.url` — val cannot be reassigned

**Error:**
```
'val' cannot be reassigned.
Assignment type mismatch: actual type is 'String', but 'HttpUrl' was expected.
```

**Cause:** In lib 1.6 the `url` property on `SManga` and `SChapter` is effectively
read-only in the `apply` scope. Direct assignment (`url = "..."`) no longer compiles.

**Fix:** Use `setUrlWithoutDomain(path)` instead:
```kotlin
// ✗ Wrong
SManga.create().apply {
    url = "/manga/my-title"
}

// ✓ Correct
SManga.create().apply {
    setUrlWithoutDomain("/manga/my-title")
}
```

### `getFilterList` in KeiSource is final and cannot be overridden

**Error:**
```
'getFilterList' in 'KeiSource' is final and cannot be overridden.
```

**Cause:** `KeiSource` declares `final override fun getFilterList(): FilterList` (the
no-arg version). The open method you should override is the overload that accepts a
`JsonElement?` parameter — called internally by the base class when `supportsFilterFetching`
is `false`.

**Fix:** Override the parameterised version:
```kotlin
import kotlinx.serialization.json.JsonElement

// ✗ Wrong — won't compile
override fun getFilterList(): FilterList = getFilters()

// ✓ Correct
override fun getFilterList(data: JsonElement?): FilterList = getFilters()
```

### `setupPreferenceScreen` overrides nothing

**Error:**
```
'setupPreferenceScreen' overrides nothing.
```

**Cause:** `setupPreferenceScreen` belongs to the `ConfigurableSource` interface. If
your source class does not declare `ConfigurableSource` in its supertype list, the
method does not exist to override — even when the build system auto-generates a
`ConfigurableSource` implementation for `mirrors(...)` / `custom(...)` baseUrl modes.

**Fix:** Add `ConfigurableSource` to the class declaration:
```kotlin
// ✗ Wrong — no ConfigurableSource
abstract class MySource : KeiSource() { ... }

// ✓ Correct
abstract class MySource : KeiSource(), ConfigurableSource { ... }
```

> [!NOTE]
> This is safe even with `custom(...)` or `mirrors(...)` in `build.gradle.kts`. The
> generated subclass calls `super.setupPreferenceScreen(screen)` first, so your custom
> preferences are preserved.

### `SManga.url` assignment inside a method that also has a `url` parameter

**Error:**
```
Assignment type mismatch: actual type is 'String', but 'HttpUrl' was expected.
```

**Cause:** If your method signature is `fun ...(url: HttpUrl)`, the parameter `url`
shadows the `SManga.url` property inside `SManga.create().apply { ... }`. The compiler
tries to assign a `String` to the `HttpUrl` parameter.

**Fix:** Use `setUrlWithoutDomain()` instead of `this.url = ...`, which avoids the
name collision entirely:
```kotlin
override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
    return SManga.create().apply {
        // ✗ Wrong — "url" refers to the HttpUrl parameter
        this.url = "/manga/title"

        // ✓ Correct — no ambiguity
        setUrlWithoutDomain("/manga/title")
    }
}
```

### `supportsLatest` and `configureHeaders` are already defaults — no need to override

In lib 1.6, `KeiSource` already provides default implementations for:
- `supportsLatest = true`
- `Referer` and `Origin` headers — set automatically by `headersBuilder()`

**Do not override these** unless you need different values. The overrides are redundant
and add unnecessary noise. However, if you need additional headers like
`Accept: application/json`, you **must** still override `configureHeaders()`:

```kotlin
// ✗ Wrong — supportsLatest is already true, Referer is already set
override val supportsLatest = true
override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
    set("Referer", "$baseUrl/")
}

// ✓ Correct — only override for extra headers
override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
    set("Accept", "application/json")
}
```

### `runCatching` is not needed for API calls

`runCatching { }.getOrNull()` is **not needed** when calling your own API. API errors
(404, 422, 500, etc.) should propagate naturally so the user sees a meaningful error
message. Wrapping API calls in `runCatching` silently swallows the error and returns
`null`, hiding the real problem.

The **only** place `runCatching` is appropriate is around `getLocalStorage` in
`loadAuthToken()`, because WebView operations can throw on timeout or render process
crash — failures that are outside your control and safe to ignore.

### Import sorting (ktlint / CI)

**Error:**
```
import·keiyoushi.utils.getLocalStorage
  +import·keiyoushi.utils.getLocalStorage
  -import·keiyoushi.utils.getLocalStorage
```

**Cause:** ktlint enforces alphabetical ordering of imports within the same package prefix.
`getLocalStorage` (`l`) must come before `getPreferences` (`p`).

**Fix:** Sort imports alphabetically:
```kotlin
// ✗ Wrong
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getLocalStorage

// ✓ Correct
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.getPreferences
```

---

> [!NOTE]
> For all other topics not covered here (tools, cloning, debugging, building, submitting PRs),
> refer to the original [CONTRIBUTING.md](./CONTRIBUTING.md).
