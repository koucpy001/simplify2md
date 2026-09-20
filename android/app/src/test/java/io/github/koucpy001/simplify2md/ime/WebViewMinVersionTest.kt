package io.github.koucpy001.simplify2md.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewMinVersionTest {

    @Test
    fun plausibleRealVersionsParse() {
        assertTrue(WebViewMinVersion.isSupported("130.0.6723.107"))
        assertTrue(WebViewMinVersion.isSupported("90.0.4430.210"))
        assertFalse(WebViewMinVersion.isSupported("89.0.4389.90"))
        assertFalse(WebViewMinVersion.isSupported("37.0.0.0"))
    }

    @Test
    fun malformedOrMissingVersionsAreUnsupported() {
        // Cannot determine the WebView package (some OEMs / emulators) -> hint.
        assertFalse(WebViewMinVersion.isSupported(null))
        assertFalse(WebViewMinVersion.isSupported(""))
        assertFalse(WebViewMinVersion.isSupported("not-a-version"))
        assertFalse(WebViewMinVersion.isSupported("v90"))
        assertFalse(WebViewMinVersion.isSupported(" .90.0"))
    }

    @Test
    fun leadingWhitespaceIsTolerated() {
        assertTrue(WebViewMinVersion.isSupported(" 91.0.1.0"))
    }
}
