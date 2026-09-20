package io.github.koucpy001.simplify2md.ime

/**
 * Minimum WebView version gate (plan todo 17f). Below the threshold the IME /
 * inset behaviour of the embedded engine is not guaranteed, so the host shows a
 * hint about possible input problems instead of failing silently.
 *
 * Pure JVM so the threshold logic is unit-testable without a device.
 */
object WebViewMinVersion {

    /**
     * First Chromium major with the modern inset/IME behaviour this port relies
     * on (edge-to-edge hosts, `visual-viewport` correctness). 90 (2021) is the
     * pragmatic floor; older engines are far outside any supported WebView.
     */
    const val MIN_MAJOR = 90

    /** Leading digits of a WebView version string ("130.0.6723.107" -> 130). */
    fun majorVersionOf(version: String?): Int? =
        version?.trimStart()?.let { Regex("^\\d+").find(it)?.value?.toIntOrNull() }

    /** True only when a real major version >= [MIN_MAJOR] could be parsed. */
    fun isSupported(version: String?): Boolean {
        val major = majorVersionOf(version) ?: return false
        return major >= MIN_MAJOR
    }
}
