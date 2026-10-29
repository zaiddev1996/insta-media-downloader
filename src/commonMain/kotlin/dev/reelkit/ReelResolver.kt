package dev.reelkit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves public Instagram posts/reels to direct media URLs and downloads them.
 *
 * ```
 * val resolver = ReelResolver(IgConfigProvider { IgConfig.fromJson(remoteConfig.getString("reelkit_config")) })
 * val info = resolver.resolve("https://www.instagram.com/reel/XXXXXXXXXXX/")
 * resolver.download(info.videoUrl!!, Path(cacheDir, "reel.mp4")) { done, total -> ... }
 * ```
 *
 * All functions are main-safe suspend functions and cancel their network calls with the coroutine.
 *
 * @param httpClient your own Ktor client (shared engine, proxy, ...). When omitted, a client with the
 *   platform engine (OkHttp on Android/JVM, Darwin on iOS) is created; release it with [close].
 */
public class ReelResolver(
    private val configProvider: IgConfigProvider = IgConfigProvider.static(),
    httpClient: HttpClient? = null,
    private val logger: (String) -> Unit = {},
) {
    private val ownsClient = httpClient == null
    private val baseClient: HttpClient = httpClient ?: HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Convenience constructor with a fixed config. */
    public constructor(config: IgConfig, httpClient: HttpClient? = null) :
        this(IgConfigProvider.static(config), httpClient)

    /** Releases the internally created HTTP client. A client you passed in is left open. */
    public fun close() {
        if (ownsClient) baseClient.close()
    }

    /** @throws ReelException on any failure. */
    @Throws(ReelException::class, CancellationException::class)
    public suspend fun resolve(reelUrl: String): ReelInfo = withContext(Dispatchers.Default) {
        val shortcode = ShortcodeCodec.shortcodeFromUrl(reelUrl) ?: throw ReelException.InvalidUrl(reelUrl)
        val mediaId = try {
            ShortcodeCodec.toMediaId(shortcode)
        } catch (e: IllegalArgumentException) {
            throw ReelException.InvalidUrl(reelUrl)
        }
        val config = configProvider.get()
        val session = Session(config, baseClient)
        logger("shortcode=$shortcode mediaId=$mediaId")

        try {
            // 1. Session setup: cookies + LSD token.
            val home = session.getText("${config.baseUrl}/")
            val lsd = PageParser.extractLsd(home.body)
            logger("home: HTTP ${home.code}, lsd=${if (lsd != null) "found" else "missing"}")

            // 2. Accessibility check. Failures here are non-fatal; the message is only used for diagnostics.
            val rulingMessage = try {
                val r = session.getText(
                    "${config.baseUrl}/api/v1/web/get_ruling_for_content/?content_type=MEDIA&target_id=$mediaId",
                    api = true,
                )
                val o = Json.parseToJsonElement(r.body).jsonObject
                if (o["status"]?.jsonPrimitive?.contentOrNull == "ok") null
                else listOfNotNull(o["title"], o["description"]).mapNotNull { it.jsonPrimitive.contentOrNull }
                    .filter { it.isNotBlank() }.joinToString(": ").ifBlank { null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            logger("ruling: ${rulingMessage ?: "ok"}")

            // 3. Primary route: the public post page embeds the media JSON.
            val fromPage = try {
                productFromPage(session, shortcode)
            } catch (e: ReelException.LoginRequired) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger("page route failed: ${e.message}")
                null
            }
            if (fromPage != null) return@withContext PageParser.toReelInfo(fromPage, shortcode, mediaId)

            // 4. Fallback: GraphQL (may be rejected for non-browser TLS fingerprints).
            if (config.graphqlFallback && lsd != null) {
                val fromGraphql = try {
                    productFromGraphql(session, reelUrl, mediaId, lsd)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger("graphql route failed: ${e.message}")
                    null
                }
                if (fromGraphql != null) return@withContext PageParser.toReelInfo(fromGraphql, shortcode, mediaId)
            }

            when {
                rulingMessage?.contains("Restricted", ignoreCase = true) == true ->
                    throw ReelException.LoginRequired(rulingMessage)
                rulingMessage != null -> throw ReelException.MediaNotFound(rulingMessage)
                shortcode.length > 28 -> throw ReelException.LoginRequired(
                    "Private post: only available to registered users who follow the account"
                )
                else -> throw ReelException.ParseFailure(
                    "Instagram returned no media for $shortcode. If the post is public, the page format " +
                        "may have changed: update mediaKey/productKey/appId in your remote config."
                )
            }
        } catch (e: ReelException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ReelException.Network(e)
        } finally {
            session.close()
        }
    }

    /**
     * Streams [url] into [sink] (you close it). [onProgress] gets (bytesRead, totalBytes); total is -1 when unknown.
     * @return bytes written
     */
    @Throws(ReelException::class, CancellationException::class)
    public suspend fun download(
        url: String,
        sink: Sink,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(IoDispatcher) {
        val config = configProvider.get()
        val session = Session(config, baseClient)
        try {
            session.client.prepareGet(url) { session.applyBase(this) }.execute { res ->
                if (!res.status.isSuccess()) throw ReelException.HttpError(res.status.value, url)
                val total = res.contentLength() ?: -1L
                val channel = res.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    written += n
                    onProgress(written, total)
                }
                sink.flush()
                written
            }
        } catch (e: ReelException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ReelException.Network(e)
        } finally {
            session.close()
        }
    }

    /** Downloads to [dest] via a temporary `.part` file, so a cancelled/failed download never leaves a partial [dest]. */
    @Throws(ReelException::class, CancellationException::class)
    public suspend fun download(
        url: String,
        dest: Path,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Path = withContext(IoDispatcher) {
        val fs = SystemFileSystem
        dest.parent?.let { fs.createDirectories(it) }
        val part = Path("$dest.part")
        try {
            fs.sink(part).buffered().use { download(url, it, onProgress) }
            fs.delete(dest, mustExist = false)
            fs.atomicMove(part, dest)
        } finally {
            fs.delete(part, mustExist = false)
        }
        dest
    }

    /** Same as [download] with a plain path string, which is easier to call from Swift. Returns [filePath]. */
    @Throws(ReelException::class, CancellationException::class)
    public suspend fun downloadToFile(
        url: String,
        filePath: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String = download(url, Path(filePath), onProgress).toString()

    private suspend fun productFromPage(session: Session, shortcode: String): JsonObject? {
        val config = session.config
        val res = session.getText("${config.baseUrl}/p/$shortcode", html = true)
        logger("page: HTTP ${res.code}, final=${res.finalPath}, ${res.body.length} chars")
        if (res.finalPath.startsWith("/accounts/login") || res.finalPath == "/") {
            throw ReelException.LoginRequired("Redirected to login: anonymous access is rate-limited")
        }
        return PageParser.productFromPage(res.body, config)
    }

    private suspend fun productFromGraphql(session: Session, referer: String, mediaId: String, lsd: String): JsonObject? {
        val config = session.config
        val csrf = session.cookie("csrftoken")
        val res = session.client.submitForm(
            url = "${config.baseUrl}/api/graphql",
            formParameters = parameters {
                append("lsd", lsd)
                append("fb_api_caller_class", "RelayModern")
                append("fb_api_req_friendly_name", config.friendlyName)
                append("server_timestamps", "true")
                append("variables", """{"media_id":"$mediaId"}""")
                append("doc_id", config.docId)
            },
        ) {
            session.applyApi(this)
            header("X-FB-Friendly-Name", config.friendlyName)
            header("X-FB-LSD", lsd)
            header("X-Requested-With", "XMLHttpRequest")
            header("Referer", referer)
            if (csrf != null) header("X-CSRFToken", csrf)
        }
        val body = res.bodyAsText()
        logger("graphql: HTTP ${res.status.value}, ${body.length} chars")
        return PageParser.productFromGraphql(body, config)
    }

    private class Text(val code: Int, val finalPath: String, val body: String)

    /** One resolve()/download() call: its own cookie storage, sharing the caller's engine and connection pool. */
    private class Session(val config: IgConfig, base: HttpClient) {
        val client: HttpClient = base.config { install(HttpCookies) }

        /** Closes only this derived client; the shared engine stays open. */
        fun close() = client.close()

        suspend fun cookie(name: String): String? =
            client.cookies(config.baseUrl).firstOrNull { it.name == name }?.value

        fun applyBase(b: HttpRequestBuilder) {
            b.header("User-Agent", config.userAgent)
            b.header("Accept-Language", "en-US,en;q=0.9")
        }

        fun applyApi(b: HttpRequestBuilder) {
            applyBase(b)
            b.header("X-IG-App-ID", config.appId)
            b.header("X-ASBD-ID", config.asbdId)
            b.header("X-IG-WWW-Claim", "0")
            b.header("Origin", config.baseUrl)
            b.header("Accept", "*/*")
        }

        suspend fun getText(url: String, api: Boolean = false, html: Boolean = false): Text {
            val res: HttpResponse = client.get(url) {
                if (api) applyApi(this) else applyBase(this)
                if (html) {
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header("Sec-Fetch-Mode", "navigate")
                }
            }
            return Text(res.status.value, res.request.url.encodedPath, res.bodyAsText())
        }
    }
}
