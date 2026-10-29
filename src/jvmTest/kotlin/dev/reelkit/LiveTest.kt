package dev.reelkit

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Hits real Instagram. Run with: REEL_URL="https://www.instagram.com/reel/XXXX/" ./gradlew jvmTest --tests '*LiveTest*' */
class LiveTest {
    @Test
    fun downloads_a_real_reel() = runBlocking {
        val url = System.getenv("REEL_URL")
        assumeTrue(url != null, "set REEL_URL to run the live test")
        val resolver = ReelResolver(logger = { println("  [reelkit] $it") })
        try {
            val info = resolver.resolve(url!!)
            val video = info.videoUrl ?: error("no video in $info")
            val dest = Path("build/live/${info.shortcode}.mp4")
            resolver.download(video, dest) { d, t -> if (d == t) println("  downloaded $d bytes") }
            val file = File(dest.toString())
            println("  saved ${file.absolutePath} (${file.length()} bytes), user=${info.username}")
            assertTrue(file.length() > 10_000)
        } finally {
            resolver.close()
        }
    }
}
