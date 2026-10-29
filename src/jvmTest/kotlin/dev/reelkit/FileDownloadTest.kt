package dev.reelkit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileDownloadTest {
    private val video = ByteArray(200_000) { (it % 251).toByte() }
    private val resolver = ReelResolver(
        IgConfig(baseUrl = "https://ig.test"),
        HttpClient(MockEngine { req ->
            if (req.url.encodedPath == "/video.mp4") respond(video) else respond("", HttpStatusCode.NotFound)
        }),
    )

    @Test
    fun file_download_is_atomic_and_leaves_no_part_file() = runBlocking<Unit> {
        val dir = createTempDirectory().toFile()
        val dest = File(dir, "sub/reel.mp4")
        resolver.download("https://ig.test/video.mp4", Path(dest.path))
        assertEquals(video.toList(), dest.readBytes().toList())
        assertFalse(File(dest.path + ".part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun failed_file_download_leaves_nothing_behind() = runBlocking<Unit> {
        val dir = createTempDirectory().toFile()
        val dest = File(dir, "reel.mp4")
        assertFailsWith<ReelException.HttpError> { resolver.download("https://ig.test/missing", Path(dest.path)) }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
        dir.deleteRecursively()
    }
}
