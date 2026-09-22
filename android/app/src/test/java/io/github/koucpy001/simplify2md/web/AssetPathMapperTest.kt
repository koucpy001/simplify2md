package io.github.koucpy001.simplify2md.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for the WebViewAssetLoader path mapping.
 *
 * The loader contract these tests pin (verified in androidx.webkit 1.17.0
 * bytecode, `PathMatcher.match` → `path.replaceFirst(prefix, "")`): `handle()`
 * receives the request path with the registered prefix STRIPPED, so the
 * handlers re-attach their prefix before calling [AssetPathMapper.toAssetPath].
 * On a real device the missing re-attachment made every request fall through
 * to the network (`ERR_NAME_NOT_RESOLVED` on the appassets domain) — the
 * mapping itself is pure JVM and pinned here.
 */
class AssetPathMapperTest {

    @Test
    fun entryDocumentMapsToTheStagedIndex() {
        assertEquals(
            "frontend/index.html",
            AssetPathMapper.toAssetPath(AssetPathMapper.FRONTEND_ROUTE + "/"),
        )
        assertEquals(
            "frontend/index.html",
            AssetPathMapper.toAssetPath(AssetPathMapper.FRONTEND_ROUTE + "/index.html"),
        )
    }

    @Test
    fun viteAssetsRouteMapsUnderFrontendAssets() {
        assertEquals(
            "frontend/assets/index-AbCdEf123.js",
            AssetPathMapper.toAssetPath(AssetPathMapper.ASSETS_ROUTE + "/index-AbCdEf123.js"),
        )
        assertEquals(
            "frontend/assets/katex/katex.min.css",
            AssetPathMapper.toAssetPath(AssetPathMapper.ASSETS_ROUTE + "/katex/katex.min.css"),
        )
    }

    @Test
    fun traversalSuffixesAreRejected() {
        assertNull(AssetPathMapper.toAssetPath(AssetPathMapper.ASSETS_ROUTE + "/../frontend/index.html"))
        // The loader maps from Uri.getPath(), which is already percent-DECODED,
        // so an encoded traversal arrives here in decoded form and must still
        // be rejected segment-wise.
        assertNull(AssetPathMapper.toAssetPath(AssetPathMapper.FRONTEND_ROUTE + "/../../secret"))
        assertNull(AssetPathMapper.toAssetPath(AssetPathMapper.ASSETS_ROUTE + "//double-slash.js"))
    }

    @Test
    fun unregisteredRoutesReturnNull() {
        assertNull(AssetPathMapper.toAssetPath("/media/abc123"))
        assertNull(AssetPathMapper.toAssetPath("/favicon.ico"))
    }
}
