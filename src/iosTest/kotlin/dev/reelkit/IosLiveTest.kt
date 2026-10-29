package dev.reelkit

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits real Instagram through the Darwin engine. Skipped unless REEL_URL is set. Run with:
 * SIMCTL_CHILD_REEL_URL="https://www.instagram.com/reel/XXXX/" ./gradlew iosSimulatorArm64Test --tests '*IosLiveTest*'
 */
@OptIn(ExperimentalForeignApi::class)
class IosLiveTest {
    @Test
    fun downloads_a_real_reel() = runBlocking {
        val url = getenv("REEL_URL")?.toKString()
        if (url == null) {
            println("IosLiveTest skipped: REEL_URL not set")
            return@runBlocking
        }
        val resolver = ReelResolver(logger = { println("  [reelkit] $it") })
        try {
            val info = resolver.resolve(url)
            val video = info.videoUrl ?: error("no video in $info")
            val dest = Path(NSTemporaryDirectory() + "reelkit-live/${info.shortcode}.mp4")
            resolver.download(video, dest)
            val size = SystemFileSystem.metadataOrNull(dest)?.size ?: 0L
            println("  saved $dest ($size bytes), user=${info.username}")
            assertTrue(size > 10_000, "downloaded file too small: $size")
        } finally {
            resolver.close()
        }
    }
}
