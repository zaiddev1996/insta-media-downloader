# Using insta-media-downloader in an app

insta-media-downloader resolves a public Instagram reel URL to a direct video URL and downloads it. It is a Kotlin
Multiplatform library (Android, iOS, JVM). This guide covers adding it to an app and calling it from
Android, iOS and shared code.

> Unofficial: it uses Instagram's undocumented web endpoints, can break at any time, and may violate
> Instagram's Terms of Use. Only download content you have the right to.

## 1. Add the dependency

The coordinates are:

```
com.github.zaiddev1996:insta-media-downloader:0.2.0
```

For Android and JVM, add `maven("https://jitpack.io")` to your repositories and the dependency resolves from
JitPack. JitPack cannot build the iOS targets, so for iOS (or to work offline) publish it from a Mac to your local
Maven repository (`~/.m2`) with `./gradlew publishToMavenLocal`, and add `mavenLocal()` as shown below. If you
fork the library, change `group` in `build.gradle.kts` (JitPack requires `com.github.<username>`).

To republish after changing the library: `cd insta-media-downloader && ./gradlew publishToMavenLocal`.

### Kotlin Multiplatform app (shared module)

```kotlin
// settings.gradle.kts of the app
dependencyResolutionManagement {
    repositories {
        mavenLocal()      // <- where insta-media-downloader was published
        google()
        mavenCentral()
    }
}

// shared/build.gradle.kts
kotlin.sourceSets.commonMain.dependencies {
    implementation("com.github.zaiddev1996:insta-media-downloader:0.2.0")
}
```

Gradle picks the right artifact per target (`insta-media-downloader-android`, `insta-media-downloader-iosarm64`,
`insta-media-downloader-iossimulatorarm64`, `insta-media-downloader-iosx64`, `insta-media-downloader-jvm`). iOS artifacts only exist because it was
published from a Mac.

### Android-only app

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }

// app/build.gradle.kts
dependencies { implementation("com.github.zaiddev1996:insta-media-downloader:0.2.0") }
```

Add `<uses-permission android:name="android.permission.INTERNET" />` to the manifest. minSdk is 21.

### Without a registry (alternative)

Include the repo as a composite build: `includeBuild("../insta-media-downloader")` in the app's `settings.gradle.kts`.

## 2. The API in 30 seconds

```kotlin
val resolver = ReelResolver()                       // creates its own HTTP client; call close() when done

val info: ReelInfo = resolver.resolve("https://www.instagram.com/reel/De2lOHNxuiW/")
info.videoUrl      // direct mp4 URL (valid for a limited time: download right away)
info.thumbnailUrl
info.username
info.caption
info.items         // carousels: one MediaItem per video/image (videoUrl / imageUrl / width / height)

resolver.download(info.videoUrl!!, Path(dir, "reel.mp4")) { done, total -> }   // to a file
resolver.download(info.videoUrl!!, sink)                                         // to any kotlinx.io Sink
resolver.downloadToFile(info.videoUrl!!, "/path/reel.mp4")                       // String path (handy from Swift)
```

- `resolve` and `download` are `suspend` functions and safe to call from the main thread; cancelling the
  coroutine cancels the request.
- `Path` is `kotlinx.io.files.Path`.
- File downloads write to `reel.mp4.part` and rename on success, so a failed or cancelled download never
  leaves a half-written file.
- `total` in the progress callback is `-1` when the server doesn't send a length.

### Errors

Everything throws a subclass of `ReelException`:

| Exception | Meaning | What to do |
|---|---|---|
| `InvalidUrl` | Not an Instagram post/reel URL (`/share/...` links aren't supported) | Tell the user |
| `LoginRequired` | Private post, or Instagram is rate-limiting anonymous access | Retry later / tell the user |
| `MediaNotFound` | Deleted or restricted | Tell the user |
| `ParseFailure` | Instagram changed its response format | Update the remote config (section 5) or the library |
| `HttpError(code)` | Download URL returned a non-2xx status | Re-resolve and retry |
| `Network` | Connectivity or other engine failure | Retry |

## 3. Android example

ViewModel that resolves a reel and saves it to the user's gallery (`Movies/`) with `MediaStore`
(Android 10+; for older versions write to a file instead). Using the shared `kotlinx.io` sink API:

```kotlin
class ReelViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = ReelResolver()
    val state = MutableStateFlow<String>("idle")

    fun save(url: String) = viewModelScope.launch {
        state.value = "resolving…"
        try {
            val info = resolver.resolve(url)
            val cr = getApplication<Application>().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${info.shortcode}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Reels")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            cr.openOutputStream(uri)!!.asSink().buffered().use { sink ->
                resolver.download(info.videoUrl!!, sink) { done, total ->
                    state.value = if (total > 0) "downloading ${done * 100 / total}%" else "downloading…"
                }
            }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); cr.update(uri, values, null, null)
            state.value = "saved"
        } catch (e: ReelException.LoginRequired) { state.value = "Instagram asked for a login. Try again later." }
          catch (e: ReelException) { state.value = "Failed: ${e.message}" }
    }

    override fun onCleared() = resolver.close()
}
```

`asSink()` is `kotlinx.io.asSink` for `OutputStream`, and `buffered()` is `kotlinx.io.buffered`.
To save to app storage instead: `resolver.download(url, Path(context.cacheDir.path, "x.mp4"))`.

## 4. iOS example

Kotlin suspend functions reach Swift as `async` functions. Two rules:

1. insta-media-downloader's functions are annotated `@Throws`, so a `ReelException` arrives in Swift as an `NSError`
   you can catch. (Without that annotation a Kotlin exception would crash the app.)
2. Kotlin default arguments don't exist in Swift. Wrap insta-media-downloader in a small class in your **shared module**
   so Swift sees a simple API:

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
// Swift (framework name is whatever your shared module exports, e.g. Shared)
import Shared

let downloader = ReelDownloader()

func save(_ url: String) async {
    let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
    do {
        let path = try await downloader.download(url: url, directory: dir)
        print("saved to \(path)")        // play with AVPlayer(url: URL(fileURLWithPath: path))
    } catch {
        print("failed: \(error.localizedDescription)")   // ReelException message
    }
}
```

To put it in the photo library, pass the file to `PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL:) }`
(needs the `NSPhotoLibraryAddUsageDescription` key in Info.plist).

No App Transport Security changes are needed (all requests are HTTPS).

## 5. Remote config (recommended)

When Instagram changes something, you usually only need to change a value or a JSON key name, not code.
Serve this JSON from Firebase Remote Config or your own endpoint (any key you omit keeps its default):

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

Hand it to the resolver through a provider. The provider runs on **every** `resolve`/`download`, so a
freshly fetched value applies immediately:

```kotlin
// commonMain: your app supplies the fetch (Firebase on Android/iOS, Ktor call, etc.)
class RemoteReelConfig(private val fetchJson: suspend () -> String?) : IgConfigProvider {
    private var cached: IgConfig = IgConfig()
    override suspend fun get(): IgConfig {
        runCatching { fetchJson() }.getOrNull()?.let { json ->
            runCatching { IgConfig.fromJson(json) }.onSuccess { cached = it }
        }
        return cached   // falls back to the last good (or default) config
    }
}

val resolver = ReelResolver(RemoteReelConfig { remoteConfig.getString("reelkit_config") })
```

Which value fixes what:

| Symptom | Likely fix |
|---|---|
| `ParseFailure` for public reels | Update `mediaKey` / `productKey` (the JSON key names in Instagram's page) |
| HTTP errors on the API check | Update `appId` / `asbdId` / `userAgent` |
| Only the GraphQL fallback broke | Update `docId` / `friendlyName`, or set `graphqlFallback` to `false` |

[yt-dlp's Instagram extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/instagram.py)
is the best place to see the current values when something breaks.

## 6. Good practice

- Create one `ReelResolver` and reuse it (it shares one connection pool); call `close()` when your app
  component is destroyed. If you pass your own Ktor `HttpClient`, insta-media-downloader leaves it open.
- Download straight after resolving: the direct video URL expires.
- Expect `LoginRequired` under heavy use: Instagram rate-limits anonymous requests per IP. Back off and retry.
- Show the creator's `username` and respect their copyright.

## 7. Troubleshooting

| Problem | Cause / fix |
|---|---|
| `Could not resolve com.github.zaiddev1996:insta-media-downloader` | Add `mavenLocal()` to the app's repositories, and make sure you ran `publishToMavenLocal` |
| iOS target can't find the artifact | It was published on Linux/CI. Publish from a Mac so the `insta-media-downloader-ios*` artifacts exist |
| Swift app crashes with an uncaught Kotlin exception | The call isn't annotated `@Throws` (your own wrapper functions need it too) |
| iOS simulator *tests* fail with TLS errors | Standalone simulator mode has no trust daemon: boot a simulator and pass `-PiosBootedSimulator=true` (see README) |
| `ParseFailure` suddenly | Instagram changed its format: update the remote config (section 5) or update insta-media-downloader |
