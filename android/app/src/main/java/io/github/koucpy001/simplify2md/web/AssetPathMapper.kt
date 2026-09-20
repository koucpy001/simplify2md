package io.github.koucpy001.simplify2md.web

/**
 * Pure mapping from a [androidx.webkit.WebViewAssetLoader] URL path to the asset
 * path inside the APK.
 *
 * Why this exists: the Vite build keeps its default `base: '/'`, so the built
 * `dist/index.html` references root-absolute URLs (`/assets/index-*.js`,
 * `/assets/index-*.css`). The frontend is staged under `assets/frontend/` in
 * the APK (todo 3), so two routes must be translated:
 *
 *   /frontend/<p>  ->  frontend/<p>
 *   /assets/<p>    ->  frontend/assets/<p>
 *
 * Omitting the `/assets/` rule yields a white screen because every script and
 * stylesheet 404s. This class deliberately has no Android dependency so it can
 * be unit-tested on the JVM and reused by later todos.
 */
object AssetPathMapper {

    /** Route served by the WebView entry document. */
    const val FRONTEND_ROUTE: String = "/frontend"

    /** Vite's default root-absolute asset prefix. */
    const val ASSETS_ROUTE: String = "/assets"

    /** Entry document inside the APK assets. */
    const val FRONTEND_ENTRY: String = "frontend/index.html"

    /**
     * Returns the APK asset path for [urlPath], or null when the path is not one
     * of the two supported routes (or is a traversal attempt). The caller is
     * expected to translate null into a 404 / pass-through, never a crash.
     */
    fun toAssetPath(urlPath: String): String? {
        val path = urlPath.substringBefore('?').substringBefore('#')
        if (path == FRONTEND_ROUTE || path == "$FRONTEND_ROUTE/") {
            return FRONTEND_ENTRY
        }
        if (path.startsWith("$FRONTEND_ROUTE/")) {
            val rest = safeRelative(path.removePrefix("$FRONTEND_ROUTE/")) ?: return null
            return "frontend/$rest"
        }
        if (path.startsWith("$ASSETS_ROUTE/")) {
            val rest = safeRelative(path.removePrefix("$ASSETS_ROUTE/")) ?: return null
            return "frontend/assets/$rest"
        }
        return null
    }

    /**
     * Rejects anything that could escape the staged asset root: empty paths,
     * absolute paths, NUL bytes and `.` / `..` segments.
     */
    private fun safeRelative(relative: String): String? {
        if (relative.isEmpty()) return null
        if (relative.startsWith("/")) return null
        if (relative.contains('\u0000')) return null
        for (segment in relative.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") return null
        }
        return relative
    }
}
