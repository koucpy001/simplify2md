package io.github.koucpy001.simplify2md.web

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.IOException
import java.net.URLConnection

/**
 * [WebViewAssetLoader.PathHandler] that serves the staged frontend from the APK
 * assets according to [AssetPathMapper].
 *
 * Registered once per route prefix (`/frontend/` and `/assets/`).
 *
 * **Contract (verified in androidx.webkit 1.17.0 bytecode after a real device
 * failed with ERR_NAME_NOT_RESOLVED): the loader strips the registered prefix
 * before calling [handle]** — `PathMatcher.match` does
 * `Uri.getPath().replaceFirst(prefix, "")`, so `/frontend/index.html` arrives
 * as `index.html`. [pathPrefix] re-attaches it for the full-path mapper; the
 * original prefix-less version returned null for every request, the loader
 * fell through to the network stack, and the appassets domain can never
 * resolve — a bug class only a device can surface (CI has none).
 *
 * Missing assets are translated into an explicit HTTP 404 response instead of
 * throwing: before todo 3 stages `assets/frontend/`, and for any genuinely
 * missing file, the WebView IO thread must not see an exception.
 */
class AssetWebViewPathHandler(
    private val assets: AssetManager,
    private val pathPrefix: String,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val assetPath = AssetPathMapper.toAssetPath(pathPrefix + path) ?: return null
        return try {
            val stream = assets.open(assetPath, AssetManager.ACCESS_STREAMING)
            WebResourceResponse(mimeTypeOf(assetPath), null, stream)
        } catch (e: IOException) {
            WebResourceResponse(null, null, HTTP_NOT_FOUND, "Not Found", emptyMap(), null)
        }
    }

    private fun mimeTypeOf(assetPath: String): String {
        val extension = assetPath.substringAfterLast('.', "").lowercase()
        EXPLICIT_MIME_TYPES[extension]?.let { return it }
        return URLConnection.guessContentTypeFromName(assetPath) ?: DEFAULT_MIME_TYPE
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val DEFAULT_MIME_TYPE = "application/octet-stream"

        val EXPLICIT_MIME_TYPES: Map<String, String> = mapOf(
            "html" to "text/html",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "map" to "application/json",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "wasm" to "application/wasm",
            "txt" to "text/plain",
        )
    }
}
