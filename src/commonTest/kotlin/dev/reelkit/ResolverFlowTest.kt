package dev.reelkit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResolverFlowTest {
    private val base = "https://ig.test"
    private val config = IgConfig(baseUrl = base)
    private val video = ByteArray(200_000) { (it % 251).toByte() }
    private val requests = mutableListOf<String>()

    private val goodPage =
        """<script data-sjs>{"x":{"xig_polaris_media":{"if_not_gated_logged_out":{"video_versions":[{"url":"$base/video.mp4","width":1,"height":2}]}}}}</script>"""

    private fun client(
        page: MockRequestHandleScope.() -> HttpResponseData = { respond(goodPage) },
        ruling: String = """{"status":"ok"}""",
    ) = HttpClient(MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        when {
            path == "/" -> respond(
                """...["LSD",[],{"token":"LSDTOK"}]...""",
                headers = headersOf("Set-Cookie", "csrftoken=abc; Path=/"),
            )
            path.startsWith("/api/v1/web/get_ruling_for_content") -> respond(ruling)
            path.startsWith("/p/") -> page()
            path == "/accounts/login/" -> respond("<html>login</html>")
            path == "/api/graphql" -> respond("<!DOCTYPE html><html></html>")
            path == "/video.mp4" -> respond(video, headers = headersOf(HttpHeaders.ContentLength, video.size.toString()))
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    private fun resolver(http: HttpClient, cfg: IgConfig = config) = ReelResolver(cfg, http)
    private val reel = "https://www.instagram.com/reel/De2lOHNxuiW/"

    @Test
    fun resolves_and_downloads_end_to_end() = runTest {
        val r = resolver(client())
        val info = r.resolve("$reel?x=1")
        assertEquals("4014559815388293270", info.mediaId)
        assertEquals("$base/video.mp4", info.videoUrl)
        assertEquals(1, info.items.single().width)

        val sink = Buffer()
        var last = 0L
        var total = 0L
        val n = r.download(info.videoUrl!!, sink) { done, t -> last = done; total = t }
        assertEquals(video.size.toLong(), n)
        assertEquals(n, last)
        assertEquals(n, total)
        assertContentEquals(video, sink.readByteArray())
        assertTrue(requests.none { it.startsWith("POST") }, "page route should not need graphql")
    }

    @Test
    fun falls_back_to_graphql_then_reports_parse_failure() = runTest {
        val e = assertFailsWith<ReelException.ParseFailure> {
            resolver(client(page = { respond("<html>nothing</html>") })).resolve(reel)
        }
        assertTrue(e.message!!.contains("remote config"))
        assertTrue(requests.contains("POST /api/graphql"))
    }

    @Test
    fun graphql_fallback_can_be_disabled() = runTest {
        assertFailsWith<ReelException.ParseFailure> {
            resolver(client(page = { respond("<html>nothing</html>") }), config.copy(graphqlFallback = false)).resolve(reel)
        }
        assertTrue(requests.none { it.startsWith("POST") })
    }

    @Test
    fun login_redirect_maps_to_LoginRequired() = runTest {
        val http = client(page = { respond("", HttpStatusCode.Found, headersOf("Location", "/accounts/login/")) })
        assertFailsWith<ReelException.LoginRequired> { resolver(http).resolve(reel) }
    }

    @Test
    fun ruling_message_surfaces_as_MediaNotFound() = runTest {
        val http = client(page = { respond("<html></html>") }, ruling = """{"status":"fail","title":"Gone","description":"Deleted"}""")
        val e = assertFailsWith<ReelException.MediaNotFound> { resolver(http).resolve(reel) }
        assertEquals("Gone: Deleted", e.message)
    }

    @Test
    fun download_http_error_is_typed() = runTest {
        val http = client()
        val e = assertFailsWith<ReelException.HttpError> { resolver(http).download("$base/missing", Buffer()) }
        assertEquals(404, e.code)
    }

    @Test
    fun invalid_url_fails_fast_without_network() = runTest {
        assertFailsWith<ReelException.InvalidUrl> { resolver(client()).resolve("https://example.com/nope") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun provider_is_consulted_on_every_call_so_remote_config_updates_apply() = runTest {
        var calls = 0
        val r = ReelResolver(IgConfigProvider { calls++; config }, client())
        r.resolve(reel)
        r.resolve(reel)
        assertEquals(2, calls)
    }
}
