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
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import io.github.koucpy001.simplify2md.binding.DesktopOnlyBindings
import io.github.koucpy001.simplify2md.binding.DirtyFlag
import io.github.koucpy001.simplify2md.binding.ExternalLinks
import io.github.koucpy001.simplify2md.binding.ExternalLinksBindings
import io.github.koucpy001.simplify2md.binding.ExternalUrlLauncher
import io.github.koucpy001.simplify2md.binding.StartupFileGate
import io.github.koucpy001.simplify2md.bridge.AppEvents
import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.storage.AndroidBackupFileSystem
import io.github.koucpy001.simplify2md.storage.AndroidConfigFileSystem
import io.github.koucpy001.simplify2md.storage.AndroidDocumentContentReader
import io.github.koucpy001.simplify2md.storage.AndroidDocumentMetadataReader
import io.github.koucpy001.simplify2md.storage.AndroidRecoveryPrompt
import io.github.koucpy001.simplify2md.storage.AndroidSafLauncher
import io.github.koucpy001.simplify2md.storage.AndroidSaveDocumentIo
import io.github.koucpy001.simplify2md.storage.AndroidUriGrantReleaser
import io.github.koucpy001.simplify2md.storage.AndroidUriPermissionStore
import io.github.koucpy001.simplify2md.storage.ReconcileCoordinator
import io.github.koucpy001.simplify2md.storage.ReconcileEngine
import io.github.koucpy001.simplify2md.storage.ReconcileNotice
import io.github.koucpy001.simplify2md.storage.RecentsBindings
import io.github.koucpy001.simplify2md.storage.RecentsStore
import io.github.koucpy001.simplify2md.storage.SafBindings
import io.github.koucpy001.simplify2md.storage.SafStore
import io.github.koucpy001.simplify2md.storage.SaveBindings
import io.github.koucpy001.simplify2md.storage.SaveStore
import io.github.koucpy001.simplify2md.web.AssetPathMapper
import io.github.koucpy001.simplify2md.web.AssetWebViewPathHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
     * App-private save IO (todo 11). Created before the WebView so startup
     * reconciliation can run first; the same instances back `SaveFile`.
     */
    private lateinit var backupFs: AndroidBackupFileSystem
    private lateinit var saveIo: AndroidSaveDocumentIo

    /** Drives the pre-WebView reconciliation gate; cancelled with the Activity. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Pre-registered SAF picker (plan todo 10). Its fixed request code is routed
     * in [onActivityResult]; the single outstanding pick is gated by
     * [Bridge.pickerSlot], which is shared with `PickSavePath`.
     */
    private lateinit var safLauncher: AndroidSafLauncher

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

        // A stable root container so later todos (IME insets in todo 17) can
        // attach an OnApplyWindowInsetsListener without touching the WebView.
        val root = FrameLayout(this)
        setContentView(root)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Save IO is independent of the WebView so reconciliation can run first.
        backupFs = AndroidBackupFileSystem(filesDir)
        saveIo = AndroidSaveDocumentIo(contentResolver)

        // Reconciliation must finish BEFORE the WebView is created (plan todo 11j):
        // the user's recovery choice has to be applied before any file can load,
        // and the three-choice dialog is native Kotlin (no JS event, no bridge
        // function). This is an async gate — the WebView is created from the
        // coroutine callback, never by blocking the main thread. `ContentResolver`
        // is already available here.
        scope.launch {
            ReconcileCoordinator(
                engine = ReconcileEngine(backupFs, saveIo, saveIo),
                prompt = AndroidRecoveryPrompt(this@MainActivity),
                onNotice = { notice -> runOnUiThread { showReconcileNotice(notice) } },
            ).run()
            setupWebView(root)
        }
    }

    /**
     * Creates and wires the WebView. Called only after startup reconciliation
     * has settled, so any recovery decision is already applied.
     */
    private fun setupWebView(root: FrameLayout) {
        if (isFinishing || isDestroyed) return

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

        // SAF open / save-as (todo 10). The picker shares bridge.pickerSlot, so
        // OpenFile and PickSavePath cannot both be outstanding.
        safLauncher = AndroidSafLauncher(this)
        val safStore = SafStore(
            pickerSlot = bridge.pickerSlot,
            launcher = safLauncher,
            metadata = AndroidDocumentMetadataReader(contentResolver),
            permissions = AndroidUriPermissionStore(contentResolver),
            onSessionGrant = {
                // The grant works this session but will not survive a restart;
                // say so instead of failing later.
                runOnUiThread {
                    Toast.makeText(this, R.string.saf_session_grant, Toast.LENGTH_LONG).show()
                }
            },
        )
        // Recents (todo 12): URI + provider DISPLAY_NAME persisted to
        // filesDir/config.json; the store also owns persisted-grant release and
        // tracks the currently open document so its grant is never released.
        val recents = RecentsStore(
            files = AndroidConfigFileSystem(filesDir),
            releaser = AndroidUriGrantReleaser(contentResolver),
        )
        SafBindings(safStore, AndroidDocumentContentReader(contentResolver), recents).registerOn(bridge)
        RecentsBindings(recents).registerOn(bridge)

        // SaveFile (todo 11): encode in memory, then rollback-on-failure + journal.
        SaveBindings(SaveStore(backupFs, saveIo, saveIo)).registerOn(bridge)

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        webView.loadUrl(START_URL)
    }

    /** Surfaces a pre-WebView reconciliation notice as a native status toast. */
    private fun showReconcileNotice(notice: ReconcileNotice) {
        val messageRes = when (notice) {
            ReconcileNotice.CORRUPT_JOURNAL -> R.string.save_corrupt_journal
            ReconcileNotice.ORPHAN_BACKUP -> R.string.save_orphan_backup
            ReconcileNotice.FINGERPRINT_UNAVAILABLE -> R.string.save_fingerprint_unavailable
            ReconcileNotice.APPLY_FAILED -> R.string.save_recovery_failed
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
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

    /**
     * Routes the pre-registered SAF picker result back to [safLauncher]. This
     * Activity uses no other request code, so nothing is forwarded to the
     * deprecated super implementation.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // The reconciliation gate creates the picker only after the WebView
        // exists; a result arriving before that has no launcher to route to.
        if (::safLauncher.isInitialized) {
            safLauncher.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        // Stop the reconciliation coroutine: destroying the Activity must not
        // leave a dialog or IO continuation alive.
        scope.cancel()
        if (::bridge.isInitialized) {
            bridge.onActivityDestroying()
        }
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        if (::bridge.isInitialized) {
            bridge.dispose()
        }
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
