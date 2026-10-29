package dev.reelkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    @Test
    fun `shortcode decodes to the media id yt-dlp computes`() {
        assertEquals("4014559815388293270", ShortcodeCodec.toMediaId("De2lOHNxuiW"))
        assertEquals("4014515767420229441", ShortcodeCodec.toMediaId("De2bNIWSZdB"))
    }

    @Test
    fun `long private shortcodes drop the 28 char suffix`() {
        assertEquals(ShortcodeCodec.toMediaId("De2lOHNxuiW"), ShortcodeCodec.toMediaId("De2lOHNxuiW" + "A".repeat(28)))
    }

    @Test
    fun `url forms are recognised`() {
        for (u in listOf(
            "https://www.instagram.com/reel/De2lOHNxuiW/?dlrf=abc",
            "https://instagram.com/reels/De2lOHNxuiW",
            "https://www.instagram.com/p/De2lOHNxuiW/",
            "https://www.instagram.com/someuser/reel/De2lOHNxuiW/",
        )) assertEquals("De2lOHNxuiW", ShortcodeCodec.shortcodeFromUrl(u), u)
        assertNull(ShortcodeCodec.shortcodeFromUrl("https://www.instagram.com/share/reel/BAbc/"))
        assertNull(ShortcodeCodec.shortcodeFromUrl("https://example.com/reel/abc"))
    }

    @Test
    fun `invalid shortcode character is rejected`() {
        assertFailsWith<IllegalArgumentException> { ShortcodeCodec.toMediaId("bad!code") }
    }

    @Test
    fun `lsd token is found in both known layouts`() {
        assertEquals("tok1", PageParser.extractLsd("""x["LSD",[],{"token":"tok1"},323]y"""))
        assertEquals("tok2", PageParser.extractLsd("""<script type="application/json" id="__eqmc">{"u":"/x","l":"tok2"}</script>"""))
        assertNull(PageParser.extractLsd("<html></html>"))
    }

    private fun page(json: String) = """<html><script type="application/json" data-sjs>$json</script></html>"""

    @Test
    fun `single reel is parsed from a nested data-sjs blob`() {
        val html = page(
            """{"require":[["x",[{"__bbox":{"result":{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{
            "video_versions":[{"url":"https://cdn/v.mp4","width":720,"height":1280}],
            "image_versions2":{"candidates":[{"url":"https://cdn/t.jpg"}]},
            "caption":{"text":"hello"},"user":{"username":"bob"}}}}}}}]]]}""".replace("\n", "")
        )
        val product = assertNotNull(PageParser.productFromPage(html, IgConfig()))
        val info = PageParser.toReelInfo(product, "SC", "1")
        assertEquals("https://cdn/v.mp4", info.videoUrl)
        assertEquals("https://cdn/t.jpg", info.thumbnailUrl)
        assertEquals(720, info.items.single().width)
        assertEquals("hello", info.caption)
        assertEquals("bob", info.username)
    }

    @Test
    fun `carousel yields one item per entry`() {
        val html = page(
            """{"a":{"xig_polaris_media":{"if_not_gated_logged_out":{"carousel_media":[
            {"image_versions2":{"candidates":[{"url":"https://cdn/1.jpg"}]},"original_width":10,"original_height":20},
            {"video_versions":[{"url":"https://cdn/2.mp4"}],"image_versions2":{"candidates":[{"url":"https://cdn/2.jpg"}]}}
            ]}}}}""".replace("\n", "")
        )
        val info = PageParser.toReelInfo(assertNotNull(PageParser.productFromPage(html, IgConfig())), "SC", "1")
        assertEquals(2, info.items.size)
        assertTrue(!info.items[0].isVideo)
        assertEquals(10, info.items[0].width)
        assertEquals("https://cdn/2.mp4", info.videoUrl)
    }

    @Test
    fun `renamed keys are honoured via config`() {
        val html = page("""{"w":{"new_media":{"inner":{"video_versions":[{"url":"https://cdn/v.mp4"}]}}}}""")
        assertNull(PageParser.productFromPage(html, IgConfig()))
        assertNotNull(PageParser.productFromPage(html, IgConfig(mediaKey = "new_media", productKey = "inner")))
    }

    @Test
    fun `config fromJson overrides only present keys`() {
        val c = IgConfig.fromJson("""{"docId":"999","appId":" ","graphqlFallback":false}""")
        assertEquals("999", c.docId)
        assertEquals(IgConfig().appId, c.appId)
        assertEquals(false, c.graphqlFallback)
    }
}
