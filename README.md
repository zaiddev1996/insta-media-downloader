# insta-media-downloader

[![CI](https://github.com/zaiddev1996/insta-media-downloader/actions/workflows/ci.yml/badge.svg)](https://github.com/zaiddev1996/insta-media-downloader/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/zaiddev1996/insta-media-downloader.svg)](https://jitpack.io/#zaiddev1996/insta-media-downloader)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)

**Resolve and download public Instagram reels and posts from a Kotlin Multiplatform app.**
Android, iOS and plain JVM. Built on Ktor and coroutines. No API key, no login, no browser, no WebView.

```kotlin
val resolver = ReelResolver()
val info = resolver.resolve("https://www.instagram.com/reel/De2lOHNxuiW/")
resolver.download(info.videoUrl!!, Path(cacheDir, "${info.shortcode}.mp4"))
```

Everything Instagram is likely to rotate (header ids, a GraphQL id, JSON key names, the user agent) is a value you
can change at runtime through **remote config**, so a breakage usually doesn't need an app release.

> ## Read this first: disclaimer
>
> insta-media-downloader uses Instagram's **undocumented web endpoints**. It is **unofficial**, is not affiliated with or endorsed
> by Instagram or Meta, **can stop working at any time**, and **may violate Instagram's Terms of Use**.
>
> Reels and posts are copyrighted by their creators. Only download content you have the right to, show the creator's
> `username`, and respect their rights. You are solely responsible for how you use this library, including
> compliance with Instagram's terms, copyright law, and the rules of any app store you ship to.

---

## Contents

- [Features](#features)
- [Supported platforms](#supported-platforms)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Usage on Android](#usage-on-android)
- [Usage on iOS (Swift)](#usage-on-ios-swift)
- [API reference](#api-reference)
- [Error handling](#error-handling)
- [Remote config](#remote-config)
- [How it works](#how-it-works)
- [Supported links](#supported-links)
- [Limits](#limits)
- [Best practices](#best-practices)
- [Troubleshooting](#troubleshooting)
- [Building and testing](#building-and-testing)
- [Project layout](#project-layout)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [License](#license)

---

## Features

- **One API for Android, iOS and JVM** (Kotlin Multiplatform). Suspend functions that are safe to call from the main thread.
- **Reels, posts and carousels.** Each result is a list of items, and each item is a video or an image.
- **Streaming downloads** to a file or to any `kotlinx.io` `Sink`, with progress. File downloads go to a `.part` file and
  are renamed on success, so a failed or cancelled download never leaves a half-written file.
- **Cancellation aware.** Cancelling the coroutine cancels the HTTP call.
- **Remote-configurable.** Header ids, the GraphQL id, JSON key names, the user agent and the fallback switch can all be
  supplied at runtime.
- **Typed errors.** Every failure is a subclass of `ReelException`, and the Swift side gets them as `NSError`s.
- **Bring your own HTTP client** (proxy, logging, shared engine), or let it create one with the platform engine.
- **Small.** About 500 lines of library code and no reflection.

## Supported platforms

| Target | HTTP engine | Notes |
|---|---|---|
| Android (minSdk 21) | Ktor OkHttp | Needs the `INTERNET` permission. |
| iOS (arm64, x64, simulator arm64) | Ktor Darwin | No App Transport Security changes: every request is HTTPS. |
| JVM (11+) | Ktor OkHttp | Handy for scripts, servers and tests. |

## Installation

Coordinates: `com.github.zaiddev1996:insta-media-downloader:0.2.0`

### Android and JVM: through JitPack

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// shared/build.gradle.kts (Kotlin Multiplatform) ...
kotlin.sourceSets.commonMain.dependencies {
    implementation("com.github.zaiddev1996:insta-media-downloader:0.2.0")
}

// ... or app/build.gradle.kts (Android only)
dependencies {
    implementation("com.github.zaiddev1996:insta-media-downloader:0.2.0")
}
```

Add `<uses-permission android:name="android.permission.INTERNET" />` to your manifest.

### iOS: build it on a Mac

JitPack builds on Linux, and Linux cannot compile Kotlin/Native iOS targets, so **JitPack serves only the Android and JVM
artifacts**. To use the iOS targets, pick one:

**A. Publish to your local Maven repository (simplest)**

```bash
git clone https://github.com/zaiddev1996/insta-media-downloader.git
cd insta-media-downloader
./gradlew publishToMavenLocal       # on a Mac: publishes android, jvm and all three iOS targets
```

```kotlin
// your settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }
```

Then use the same dependency line as above. Gradle picks `insta-media-downloader-iosarm64`, `insta-media-downloader-iossimulatorarm64` or
`insta-media-downloader-iosx64` for each target. Re-run `publishToMavenLocal` after you change the library.

**B. Composite build (no publishing step)**

```kotlin
// your settings.gradle.kts
includeBuild("../insta-media-downloader")
```

**C. Git submodule + composite build**, if you want the source pinned inside your repo.

> For a team, publish the iOS artifacts to a private Maven repository (or Maven Central) from a Mac and depend on that.
> A dependency that only exists in one developer's `~/.m2` will not resolve on CI or on a teammate's machine.

## Quick start

```kotlin
import dev.reelkit.*
import kotlinx.io.files.Path

suspend fun saveReel(url: String, directory: String): String? {
    val resolver = ReelResolver()
    try {
        val info = resolver.resolve(url)
        val videoUrl = info.videoUrl ?: return null           // a photo-only post has no video
        val file = Path(directory, "${info.shortcode}.mp4")
        resolver.download(videoUrl, file) { done, total ->
            // total is -1 when the server doesn't send a length
        }
        println("Saved ${info.username}'s reel to $file")
        return file.toString()
    } catch (e: ReelException.LoginRequired) {
        println("Private post, or Instagram is rate-limiting anonymous access")
    } catch (e: ReelException) {
        println("Failed: ${e.message}")
    } finally {
        resolver.close()
    }
    return null
}
```

Create **one** `ReelResolver` and reuse it (it shares one connection pool). Call `close()` when the owning component is
destroyed. If you pass your own Ktor `HttpClient`, insta-media-downloader leaves it open.

## Usage on Android

Save a reel to the gallery (`Movies/Reels`) with `MediaStore` (Android 10+). This uses the `Sink` overload so nothing
is written to a temp file first:

```kotlin
class ReelViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = ReelResolver()
    val state = MutableStateFlow("idle")

    fun save(url: String) = viewModelScope.launch {
        state.value = "resolving…"
        try {
            val info = resolver.resolve(url)
            val videoUrl = info.videoUrl ?: error("No video in this post")

            val cr = getApplication<Application>().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${info.shortcode}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Reels")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!

            cr.openOutputStream(uri)!!.asSink().buffered().use { sink ->
                resolver.download(videoUrl, sink) { done, total ->
                    state.value = if (total > 0) "downloading ${done * 100 / total}%" else "downloading…"
                }
            }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); cr.update(uri, values, null, null)
            state.value = "saved"
        } catch (e: ReelException.LoginRequired) {
            state.value = "Instagram asked for a login. Try again later."
        } catch (e: ReelException) {
            state.value = "Failed: ${e.message}"
        }
    }

    override fun onCleared() = resolver.close()
}
```

`asSink()` and `buffered()` come from `kotlinx.io` (`kotlinx.io.asSink`, `kotlinx.io.buffered`). To keep the file in app
storage instead: `resolver.download(videoUrl, Path(context.cacheDir.path, "x.mp4"))`.

### Reading a download into memory

```kotlin
val buffer = Buffer()                                   // kotlinx.io.Buffer is a Sink
resolver.download(videoUrl, buffer)
val bytes: ByteArray = buffer.readByteArray()
```

Stop early if a file is too big by throwing from the progress callback (it comes back as `ReelException.Network`
with your exception as the cause):

```kotlin
resolver.download(videoUrl, buffer) { done, total -> if (total > MAX || done > MAX) throw TooLarge() }
```

## Usage on iOS (Swift)

Kotlin suspend functions reach Swift as `async` functions, and insta-media-downloader marks them `@Throws`, so a `ReelException` arrives as
an `NSError` you can catch. Kotlin default arguments don't exist in Swift, so wrap insta-media-downloader in a small class in **your
shared module**:

```kotlin
// shared/src/commonMain/kotlin/ReelDownloader.kt
import dev.reelkit.*
import kotlin.coroutines.cancellation.CancellationException

class ReelDownloader {
    private val resolver = ReelResolver()

    /** Returns the saved file path. */
    @Throws(ReelException::class, CancellationException::class)
    suspend fun download(url: String, directory: String): String {
        val info = resolver.resolve(url)
        val video = info.videoUrl ?: throw ReelException.MediaNotFound("No video in this post")
        return resolver.downloadToFile(video, "$directory/${info.shortcode}.mp4")
    }

    fun close() = resolver.close()
}
```

```swift
import Shared   // the framework name your shared module exports

let downloader = ReelDownloader()

func save(_ url: String) async {
    let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
    do {
        let path = try await downloader.download(url: url, directory: dir)
        print("saved to \(path)")                 // play it with AVPlayer(url: URL(fileURLWithPath: path))
    } catch {
        print("failed: \(error.localizedDescription)")
    }
}
```

To put the file in the photo library, use `PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL:) }`
(add `NSPhotoLibraryAddUsageDescription` to your `Info.plist`).

## API reference

### `ReelResolver`

```kotlin
public class ReelResolver(
    configProvider: IgConfigProvider = IgConfigProvider.static(),
    httpClient: HttpClient? = null,
    logger: (String) -> Unit = {},
)
public constructor(config: IgConfig, httpClient: HttpClient? = null)
```

| Member | Description |
|---|---|
| `suspend fun resolve(reelUrl: String): ReelInfo` | Turns a post or reel URL into its media. Throws `ReelException`. |
| `suspend fun download(url: String, sink: Sink, onProgress: (Long, Long) -> Unit = {_, _ -> }): Long` | Streams `url` into `sink` (you close it). Returns the bytes written. `onProgress(bytesRead, totalBytes)`; the total is `-1` when unknown. |
| `suspend fun download(url: String, dest: Path, onProgress: ...): Path` | Downloads to a `.part` file and renames it to `dest` on success. Creates missing parent directories. |
| `suspend fun downloadToFile(url: String, filePath: String, onProgress: ...): String` | Same, with a plain path string (easiest from Swift). Returns `filePath`. |
| `fun close()` | Releases the HTTP client **if insta-media-downloader created it**. A client you passed in stays open. |

`logger` receives short diagnostic lines: the shortcode and media id, HTTP status codes, response sizes, and which route
(page or GraphQL) failed and why. It does not log cookies or tokens. Handy while debugging a breakage.

### `ReelInfo` and `MediaItem`

```kotlin
public data class ReelInfo(
    val shortcode: String,
    val mediaId: String,
    val items: List<MediaItem>,
    val caption: String? = null,
    val username: String? = null,
) {
    val videoUrl: String?       // the first video's direct URL (for a reel, the reel itself)
    val thumbnailUrl: String?   // the first item's image
}

public data class MediaItem(
    val videoUrl: String?,
    val imageUrl: String?,
    val width: Int?,
    val height: Int?,
) { val isVideo: Boolean }
```

- A **reel** is one item with a `videoUrl`.
- A **photo post** is one item with an `imageUrl` and no `videoUrl`.
- A **carousel** has one item per slide, each a video or an image.
- Items with neither a `videoUrl` nor an `imageUrl` are dropped.
- Direct media URLs **expire**. Download straight after resolving.

```kotlin
info.items.forEachIndexed { i, item ->
    val url = item.videoUrl ?: item.imageUrl ?: return@forEachIndexed
    val ext = if (item.isVideo) "mp4" else "jpg"
    resolver.download(url, Path(dir, "${info.shortcode}-$i.$ext"))
}
```

### `IgConfig` and `IgConfigProvider`

| Property | Default | What it is |
|---|---|---|
| `baseUrl` | `https://www.instagram.com` | Site root. Only change it for testing. |
| `appId` | `936619743392459` | The `X-IG-App-ID` header. |
| `asbdId` | `359341` | The `X-ASBD-ID` header. |
| `docId` | `27130156389949648` | The GraphQL `doc_id` (fallback path only). |
| `friendlyName` | `PolarisLoggedOutDesktopWWWPostRootContentQuery` | The GraphQL query name (fallback path only). |
| `userAgent` | a desktop Chrome UA | The `User-Agent` sent. |
| `mediaKey` | `xig_polaris_media` | JSON key that wraps the media object. |
| `productKey` | `if_not_gated_logged_out` | JSON key, inside `mediaKey`, that holds the logged-out media. |
| `graphqlFallback` | `true` | Try GraphQL when the page route finds nothing. |

`IgConfig.fromJson(json, base = IgConfig())` builds a config from a JSON object whose keys match these names. Missing or blank
keys keep the value from `base`. `IgConfigProvider` is a `fun interface` whose `get()` is called on **every**
`resolve` and `download`.

## Error handling

Everything throws a subclass of `ReelException`:

| Exception | Meaning | What to do |
|---|---|---|
| `InvalidUrl` | Not an Instagram post/reel URL (`/share/…` links aren't supported). | Tell the user. |
| `LoginRequired` | A private post, a restricted one, or Instagram is rate-limiting anonymous access. | Back off and retry later, or tell the user. Don't hammer it. |
| `MediaNotFound` | Deleted or unavailable. | Tell the user. |
| `ParseFailure` | Instagram changed its response format. | Update the [remote config](#remote-config), or the library. |
| `HttpError(code)` | The media URL returned a non-2xx status (often an expired URL). | Re-resolve, then retry once. |
| `Network` | Connectivity or any other engine failure (the cause is attached). | Retry. |

Cancellation is **not** wrapped: a cancelled coroutine throws `CancellationException` as usual.

```kotlin
try { ... }
catch (e: ReelException.LoginRequired) { showMessage("Instagram is limiting access. Try again later.") }
catch (e: ReelException.MediaNotFound) { showMessage("That post isn't available.") }
catch (e: ReelException) { log(e) }
```

## Remote config

When Instagram changes something, you usually only need to change a value or a JSON key name, not code. Serve this JSON from
Firebase Remote Config or your own endpoint (any key you omit keeps its default):

```json
{
  "appId": "936619743392459",
  "asbdId": "359341",
  "docId": "27130156389949648",
  "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
  "mediaKey": "xig_polaris_media",
  "productKey": "if_not_gated_logged_out",
  "graphqlFallback": true
}
```

Hand it to the resolver through a provider. It runs on **every** call, so a freshly fetched value applies immediately.
Cache the last good config and fall back to it (or the defaults) if a fetch fails:

```kotlin
class RemoteReelConfig(private val fetchJson: suspend () -> String?) : IgConfigProvider {
    private var cached = IgConfig()
    override suspend fun get(): IgConfig {
        runCatching { fetchJson() }.getOrNull()?.let { json ->
            runCatching { IgConfig.fromJson(json) }.onSuccess { cached = it }
        }
        return cached
    }
}

val resolver = ReelResolver(RemoteReelConfig { remoteConfig.getString("reelkit_config") })
```

| Symptom | Likely fix |
|---|---|
| `ParseFailure` for public reels | Update `mediaKey` and `productKey` (the JSON key names in Instagram's page). |
| HTTP errors on the accessibility check | Update `appId`, `asbdId` and `userAgent`. |
| Only the GraphQL fallback broke | Update `docId` and `friendlyName`, or set `graphqlFallback` to `false`. |

[yt-dlp's Instagram extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/instagram.py) is the best place
to see the current values when something breaks.

## How it works

For one `resolve(url)`:

1. **Parse the URL** to a shortcode and convert it to the numeric media id (a base-64 to decimal conversion that doesn't
   overflow a `Long`).
2. `GET /` to collect session cookies and the `LSD` token.
3. `GET /api/v1/web/get_ruling_for_content/` (an accessibility check). It is non-fatal; its message explains deleted or
   restricted posts and is used to pick a precise error.
4. `GET /p/<shortcode>` and read the media JSON embedded in the page's `<script data-sjs>` tags. This is the primary route.
5. **Fallback:** a GraphQL POST using `docId` (best effort; Instagram often rejects it from non-browser TLS stacks).
6. Map the media object to `ReelInfo` (video versions, image candidates, caption, username).

`download` then streams the CDN URL in 64 KB chunks, checking for cancellation between chunks.

Nothing is stored between calls: each call builds a fresh session, and the HTTP client is the only long-lived object.

## Supported links

| Link | Supported |
|---|---|
| `https://www.instagram.com/reel/<code>/` and `/reels/<code>/` | Yes |
| `https://www.instagram.com/p/<code>/` (photo, video or carousel) | Yes |
| `https://www.instagram.com/tv/<code>/` | Yes |
| `https://www.instagram.com/<user>/reel/<code>/` | Yes |
| Links with tracking parameters (`?igsh=…`) | Yes (ignored) |
| `https://www.instagram.com/share/…` | **No.** Open it once to get the redirect target, then pass that. |
| Stories, profiles, highlights | No |

## Limits

- **Public posts only.** There is no login support, so private accounts and stories are out of scope.
- **Anonymous access is rate-limited, typically per IP.** Expect `LoginRequired` under heavy use. Back off.
- **It can break at any time.** Instagram changes things without notice. Use [remote config](#remote-config) and keep the
  library up to date.
- **Direct URLs expire.** Resolve and download back to back.
- `/share/…` links aren't supported.
- Unexpected failures from the HTTP engine are wrapped in `ReelException.Network`.

## Best practices

- Create one `ReelResolver` per app component and reuse it.
- Download immediately after resolving.
- Back off after `LoginRequired`. Don't retry in a tight loop.
- Cap what you accept (file size, item count) if you run this on user-supplied links, and validate that a link is an
  Instagram URL before you call `resolve`.
- Consider running it on a **backend you control**. A server that fetches reels lets you fix breakages without shipping
  an app, cache results, and rate-limit your users. The resolver is plain Kotlin and works on the JVM.
- Show the creator's `username` and respect their copyright.

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| `Could not resolve com.github.zaiddev1996:insta-media-downloader` | Add `maven("https://jitpack.io")` to your repositories (Android/JVM), or `mavenLocal()` after `publishToMavenLocal`. |
| iOS target can't find the artifact | JitPack serves no iOS artifacts. Publish from a Mac (`./gradlew publishToMavenLocal`) or use `includeBuild`. |
| Swift crashes with an uncaught Kotlin exception | The function isn't `@Throws`. Your own wrapper functions need the annotation too. |
| `ParseFailure` suddenly | Instagram changed its format: update [remote config](#remote-config) or the library. |
| `LoginRequired` for a public reel | Rate limiting on your IP. Wait, or try another network. |
| `HttpError` on download | The media URL probably expired. Call `resolve` again, then download. |
| iOS simulator tests fail with TLS errors | The standalone simulator has no trust daemon. Boot a simulator and pass `-PiosBootedSimulator=true` (see below). |
| Works on a laptop but fails from a server | Datacenter IPs tend to be rate-limited harder than home or mobile ones. |

Pass a `logger` to see each step:

```kotlin
val resolver = ReelResolver(logger = { println("[insta-media-downloader] $it") })
```

## Building and testing

Requires JDK 17 and, for the iOS targets, a Mac with Xcode.

```bash
./gradlew jvmTest testDebugUnitTest iosSimulatorArm64Test   # offline tests (mock HTTP engine)
./gradlew publishToMavenLocal                              # build and publish every target
```

Live tests hit real Instagram and are skipped unless you give them a URL:

```bash
# JVM
REEL_URL="https://www.instagram.com/reel/XXXXXXXXXXX/" ./gradlew jvmTest --tests '*LiveTest*'

# iOS simulator. A *booted* simulator is required: the default standalone mode has no trustd,
# so every HTTPS request fails with a TLS error.
xcrun simctl boot "iPhone 17 Pro"
SIMCTL_CHILD_REEL_URL="https://www.instagram.com/reel/XXXXXXXXXXX/" \
  ./gradlew iosSimulatorArm64Test --tests '*IosLiveTest*' -PiosBootedSimulator=true -PiosSimulatorDevice="iPhone 17 Pro"
```

The offline tests cover URL and shortcode parsing, finding the LSD token, parsing a single reel and a carousel, renamed
JSON keys through the config, `IgConfig.fromJson`, the resolve flow against a mock engine (the GraphQL fallback and turning
it off, a login redirect, a ruling message, a download HTTP error, an invalid URL, and the provider being consulted on every
call), and atomic file downloads.

## Project layout

```
src/commonMain/kotlin/dev/insta-media-downloader/
  ReelResolver.kt      resolve() and the download functions
  PageParser.kt        finds and maps the media JSON in a page
  ShortcodeCodec.kt    URL → shortcode → numeric media id
  IgConfig.kt          IgConfig, IgConfigProvider, remote-config JSON
  Models.kt            ReelInfo, MediaItem
  Errors.kt            ReelException
src/{android,ios,jvm}Main/   the IO dispatcher per platform
src/commonTest, jvmTest, iosTest/   offline tests and live tests
```

The code is under `explicitApi()`: anything public is deliberate.

## Contributing

Issues and pull requests are welcome. The most useful contributions are fixes for when Instagram changes something:
include the symptom, the `ReelException` type, and (if you can) which key or id changed. Please keep changes
small, add or update an offline test, and run `./gradlew jvmTest testDebugUnitTest` before you open a PR.

Please **don't** open issues that ask for support for private content, logins, or bypassing rate limits: those are out of scope.

## Acknowledgements

The extraction approach follows [yt-dlp](https://github.com/yt-dlp/yt-dlp)'s Instagram extractor. Thanks to its maintainers.

## License

[MIT](LICENSE). The software is provided "as is", without warranty of any kind. The license covers this code only; it
grants no rights to Instagram's content or trademarks.
