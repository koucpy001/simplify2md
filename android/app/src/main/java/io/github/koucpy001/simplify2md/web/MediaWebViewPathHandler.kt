package io.github.koucpy001.simplify2md.web

import android.content.ContentResolver
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import io.github.koucpy001.simplify2md.image.MediaTokenStore

/**
 * Read-only streaming endpoint for resolved relative images (plan todo 13e).
 *
 * `LoadImageForSrc` never returns base64 on Android: it registers the resolved
 * child document URI under an opaque token and hands the frontend a
 * `https://appassets.androidplatform.net/media/<token>` URL. When the WebView
 * requests that URL, this handler streams the bytes straight from the
 * `ContentResolver` — a low-end device never materializes the ~43 MB base64 JS
 * string a 32 MB image would produce over the bridge.
 *
 * Unknown / evicted tokens and unreadable URIs answer an explicit HTTP 404 so
 * the WebView shows a broken-image placeholder instead of hanging.
 */
class MediaWebViewPathHandler(
    private val tokens: MediaTokenStore,
    private val resolver: ContentResolver,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        // The loader strips the registered "/media/" prefix before calling
        // handle() (contract verified in bytecode, see AssetWebViewPathHandler):
        // what arrives here is the bare token.
        val token = path
        if (token.isEmpty() || token.contains('/')) return notFound()
        val entry = tokens.resolve(token) ?: return notFound()
        val stream = try {
            resolver.openInputStream(Uri.parse(entry.uri))
        } catch (_: Exception) {
            null
        } ?: return notFound()
        return WebResourceResponse(entry.mime, null, stream)
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(null, null, HTTP_NOT_FOUND, "Not Found", emptyMap(), null)

    companion object {
        /** Route prefix registered on the WebViewAssetLoader. */
        const val MEDIA_ROUTE: String = "/media"

        private const val HTTP_NOT_FOUND = 404
    }
}