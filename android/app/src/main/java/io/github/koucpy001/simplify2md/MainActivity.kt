package io.github.koucpy001.simplify2md

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import io.github.koucpy001.simplify2md.binding.DesktopOnlyBindings
import io.github.koucpy001.simplify2md.binding.DirtyFlag
import io.github.koucpy001.simplify2md.binding.ExternalLinks
import io.github.koucpy001.simplify2md.binding.ExternalLinksBindings
import io.github.koucpy001.simplify2md.binding.ExternalUrlLauncher
import io.github.koucpy001.simplify2md.binding.StartupFileGate
import io.github.koucpy001.simplify2md.bridge.AppEvents
import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.web.AssetPathMapper
import io.github.koucpy001.simplify2md.web.AssetWebViewPathHandler

/**
 * Must stay https: `crypto.subtle` is only exposed in a secure context, and the
 * draft key derivation relies on it (App.vue:421-423).
 */
private const val APP_ASSETS_DOMAIN = "appassets.androidplatform.net"

private val START_URL = "https://$APP_ASSETS_DOMAIN${AssetPathMapper.FRONTEND_ROUTE}/index.html"

/**
 * Thin WebView host for the simplify2md Android port.
 *
 * This class is deliberately a *shell*: it configures the WebView, serves the
 * staged frontend over [WebViewAssetLoader], applies a navigation whitelist and
 * installs the [Bridge] transport. It contains no product logic; SAF, recents
 * and drafts are owned by todos 9-15.
 *
 * Security model (see plan todo 2(f)): `addJavascriptInterface` applies to every
 * frame with no origin filtering and is not claimed to be restrictable. The
 * real controls are (1) this client's navigation whitelist, which only lets
 * the `https://appassets.androidplatform.net` origin load inside the WebView, (2)
 * the existing frontend CSP `frame-src 'none'`, and (3) the file/content access
 * flags disabled below. `addWebMessageListener` is intentionally not used.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var bridge: Bridge
    private lateinit var bindings: DesktopOnlyBindings

    /**
     * `BrowserOpenURL` policy plus the Android launch glue. The glue catches
     * `ActivityNotFoundException`: a URL whose scheme has no installed handler
     * (or an allowed scheme with no browser at all) must leave the app usable,
     * per the plan's todo 8 failure scenario. The policy itself is pure JVM code
     * (`binding/ExternalLinks.kt`).
     */
    private val externalLinks = ExternalLinks(
        ExternalUrlLauncher { url ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        },
    )

    /**
     * Kotlin-to-JS event emitters (names frozen in `bridge/BridgeEvents.kt`).
     * The triggers live in todo 18/19; this is the seam they call into, and it
     * is what makes the three names `App.vue` listens for reachable from Kotlin.
     */
    lateinit var appEvents: AppEvents
        private set

    /**
     * Cold-start file URI, delivered to the frontend through `GetStartupFile` and
     * consumed exactly once. Kept as a field so process recreation (`onCreate`
     * again) starts from a clean gate.
     */
    private val startupFileGate = StartupFileGate()

    /**
     * Document modified flag reported by `SetDirty`. The back-key guard reads it
     * in todo 18; this class only records it.
     */
    private val dirtyFlag = DirtyFlag()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cold-start file intents (ACTION_VIEW / ACTION_EDIT) travel ONLY through
        // GetStartupFile (consume-once) and never through the event queue — see
        // android/README.md, "Intent 排队规则". `dataString` is null for
        // ACTION_SEND (its payload lives in EXTRA_TEXT / EXTRA_STREAM) and for
        // fileless launches, in which case the gate answers "". ACTION_SEND and
        // onNewIntent dispatch are owned by todo 18.
        startupFileGate.record(intent?.dataString)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_ASSETS_DOMAIN)
            .addPathHandler("${AssetPathMapper.FRONTEND_ROUTE}/", AssetWebViewPathHandler(assets))
            .addPathHandler("${AssetPathMapper.ASSETS_ROUTE}/", AssetWebViewPathHandler(assets))
            .build()

        webView = createWebView()

        bridge = Bridge(webView)
        webView.addJavascriptInterface(bridge, Bridge.JAVASCRIPT_INTERFACE_NAME)
        // Reject stale JS Promises before accepting new calls (relevant after
        // process recreation, when the Activity is rebuilt from scratch).
        bridge.onActivityCreated()

        // Android semantics for the desktop-only bindings (plan todo 7). The
        // Activity-dependent part is the exit glue only; `finish()` must run on
        // the UI thread because bridge handlers execute on Dispatchers.IO.
        bindings = DesktopOnlyBindings(
            startupFileGate = startupFileGate,
            dirtyFlag = dirtyFlag,
            requestExit = { runOnUiThread { finish() } },
        )
        bindings.registerOn(bridge)
        ExternalLinksBindings(externalLinks).registerOn(bridge)
        appEvents = AppEvents { name, payloadJson -> bridge.emitEvent(name, payloadJson) }

        // A stable root container so later todos (IME insets in todo 17) can
        // attach an OnApplyWindowInsetsListener without touching the WebView.
        val root = FrameLayout(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.loadUrl(START_URL)
    }

    private fun createWebView(): WebView = WebView(this).apply {
        val settings = settings
        settings.javaScriptEnabled = true
        // Required: the frontend reads localStorage during setup (App.vue:196-198);
        // without it the mount throws and the app shows a blank page.
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

        webViewClient = AssetServingWebViewClient(assetLoader, externalLinks)
    }

    override fun onDestroy() {
        bridge.onActivityDestroying()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        bridge.dispose()
        super.onDestroy()
    }

    /**
     * Serves appassets requests from the APK and restricts in-WebView navigation
     * to the asset domain. Everything else is handed to the system browser when a
     * handler exists, and non-http(s) schemes are denied.
     */
    private class AssetServingWebViewClient(
        private val assetLoader: WebViewAssetLoader,
        private val externalLinks: ExternalLinks,
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            if (isAppAssetUrl(url)) {
                // Whitelisted: let the WebView load it; the asset loader above
                // supplies the bytes.
                return false
            }
            // Navigation off the asset origin is never loaded in the WebView.
            // The same policy as BrowserOpenURL decides whether the system gets
            // it; unknown / dangerous schemes are denied instead of forwarded.
            externalLinks.open(url.toString())
            return true
        }

        private fun isAppAssetUrl(url: Uri): Boolean =
            url.scheme == "https" && url.host == APP_ASSETS_DOMAIN
    }
}
