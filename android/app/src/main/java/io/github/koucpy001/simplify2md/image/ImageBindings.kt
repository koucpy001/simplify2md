package io.github.koucpy001.simplify2md.image

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONArray
import org.json.JSONObject

/**
 * `LoadImageForSrc` binding (plan todo 13). The method name is already a member
 * of the frozen [BridgeMethods.WHITELIST], so registering it cannot widen the
 * dispatch surface and no new bridge function is introduced.
 *
 * The result is `{url, mime}` — a token endpoint the WebView streams — NOT
 * `{b64, mime}`. Desktop keeps its base64 shape; the frontend branches on
 * whichever field is present.
 */
class ImageBindings(private val resolver: RelativeImageResolver) {

    suspend fun loadImageForSrc(src: String, mdUri: String, imageRoot: String): JSONObject {
        val resolved = resolver.resolve(src, mdUri, imageRoot)
        return JSONObject()
            .put(KEY_URL, resolved.url)
            .put(KEY_MIME, resolved.mime)
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.LOAD_IMAGE_FOR_SRC) { args: JSONArray ->
            loadImageForSrc(
                src = args.optString(0, ""),
                mdUri = args.optString(1, ""),
                imageRoot = args.optString(2, ""),
            )
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_MIME = "mime"
    }
}
