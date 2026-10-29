package dev.reelkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure functions that pull data out of Instagram HTML/JSON. No I/O, so they are easy to unit test. */
internal object PageParser {
    private val SJS_REGEX = Regex("""<script\b[^>]+\bdata-sjs>(\{.+?\})</script>""")
    private val LSD_REGEXES = listOf(
        Regex("""\["LSD",\[\],\{"token":"([^"]+)""""),
        Regex("""id="__eqmc"[^>]*>\{[^}]*"l":"([^"]+)""""),
    )

    fun extractLsd(html: String): String? =
        LSD_REGEXES.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }

    /** Finds the logged-out media object in the `<script data-sjs>` blobs of a post page. */
    fun productFromPage(html: String, config: IgConfig): JsonObject? {
        for (m in SJS_REGEX.findAll(html)) {
            val root = runCatching { Json.parseToJsonElement(m.groupValues[1]) }.getOrNull() ?: continue
            findKey(root, config.mediaKey)?.get(config.productKey)?.asObjectOrNull()?.let { return it }
        }
        return null
    }

    /** Extracts the media object from a GraphQL response body. */
    fun productFromGraphql(body: String, config: IgConfig): JsonObject? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        return findKey(root, config.mediaKey)?.get(config.productKey)?.asObjectOrNull()
    }

    fun toReelInfo(product: JsonObject, shortcode: String, mediaId: String): ReelInfo {
        val carousel = product["carousel_media"]?.let { runCatching { it.jsonArray }.getOrNull() }
        val items = if (carousel != null && carousel.isNotEmpty()) {
            carousel.mapNotNull { it.asObjectOrNull()?.let(::toItem) }
        } else {
            listOf(toItem(product))
        }
        return ReelInfo(
            shortcode = shortcode,
            mediaId = mediaId,
            items = items.filter { it.videoUrl != null || it.imageUrl != null },
            caption = product["caption"]?.asObjectOrNull()?.get("text")?.str(),
            username = product["user"]?.asObjectOrNull()?.get("username")?.str(),
        )
    }

    private fun toItem(o: JsonObject): MediaItem {
        val video = o["video_versions"]?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
        val image = o["image_versions2"]?.asObjectOrNull()?.get("candidates")
            ?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
        return MediaItem(
            videoUrl = video?.get("url")?.str(),
            imageUrl = image?.get("url")?.str(),
            width = video?.get("width")?.int() ?: o["original_width"]?.int(),
            height = video?.get("height")?.int() ?: o["original_height"]?.int(),
        )
    }

    private fun findKey(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> node[key]?.asObjectOrNull() ?: node.values.firstNotNullOfOrNull { findKey(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { findKey(it, key) }
        else -> null
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
}
