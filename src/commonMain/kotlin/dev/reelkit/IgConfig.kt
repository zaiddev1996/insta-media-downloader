package dev.reelkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything Instagram is known to rotate or that the extraction depends on.
 *
 * Ship these through remote config (Firebase Remote Config, your own JSON endpoint, ...) so a
 * breakage can be fixed without an app release. See [fromJson] and [IgConfigProvider].
 */
public data class IgConfig(
    /** Site root. Only change for testing. */
    val baseUrl: String = "https://www.instagram.com",
    /** `X-IG-App-ID` header. */
    val appId: String = "936619743392459",
    /** `X-ASBD-ID` header. */
    val asbdId: String = "359341",
    /** GraphQL `doc_id` (fallback path only). */
    val docId: String = "27130156389949648",
    /** GraphQL friendly name (fallback path only). */
    val friendlyName: String = "PolarisLoggedOutDesktopWWWPostRootContentQuery",
    val userAgent: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    /** JSON key that wraps the media object inside the page/GraphQL payload. */
    val mediaKey: String = "xig_polaris_media",
    /** JSON key, inside [mediaKey], that holds the logged-out media object. */
    val productKey: String = "if_not_gated_logged_out",
    /** Try the GraphQL endpoint when the page route finds nothing. */
    val graphqlFallback: Boolean = true,
) {
    public companion object {
        /**
         * Builds a config from a JSON object whose keys match the property names, e.g.
         * `{"appId":"...","docId":"..."}`. Missing or blank keys keep their value from [base].
         */
        public fun fromJson(json: String, base: IgConfig = IgConfig()): IgConfig {
            val o: JsonObject = Json.parseToJsonElement(json).jsonObject
            fun str(key: String, default: String) =
                o[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: default
            return base.copy(
                baseUrl = str("baseUrl", base.baseUrl),
                appId = str("appId", base.appId),
                asbdId = str("asbdId", base.asbdId),
                docId = str("docId", base.docId),
                friendlyName = str("friendlyName", base.friendlyName),
                userAgent = str("userAgent", base.userAgent),
                mediaKey = str("mediaKey", base.mediaKey),
                productKey = str("productKey", base.productKey),
                graphqlFallback = o["graphqlFallback"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: base.graphqlFallback,
            )
        }
    }
}

/** Supplies the config on every [ReelResolver.resolve] call, so remote values can change at runtime. */
public fun interface IgConfigProvider {
    public suspend fun get(): IgConfig

    public companion object {
        public fun static(config: IgConfig = IgConfig()): IgConfigProvider = IgConfigProvider { config }
    }
}
