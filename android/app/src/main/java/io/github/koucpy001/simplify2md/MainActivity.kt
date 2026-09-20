package io.github.koucpy001.simplify2md

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewAssetLoader
import io.github.koucpy001.simplify2md.binding.BackKeyGuard
import io.github.koucpy001.simplify2md.binding.DesktopOnlyBindings
import io.github.koucpy001.simplify2md.binding.DirtyFlag
import io.github.koucpy001.simplify2md.binding.ExternalLinks
import io.github.koucpy001.simplify2md.binding.ExternalLinksBindings
import io.github.koucpy001.simplify2md.binding.ExternalUrlLauncher
import io.github.koucpy001.simplify2md.binding.IntentPayload
import io.github.koucpy001.simplify2md.binding.IntentRoute
import io.github.koucpy001.simplify2md.binding.IntentRouter
import io.github.koucpy001.simplify2md.binding.StartupFileGate
import io.github.koucpy001.simplify2md.binding.StartupTextBuffer
import io.github.koucpy001.simplify2md.bridge.AppEvents
import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.ime.WebViewMinVersion
import io.github.koucpy001.simplify2md.image.AndroidChildDocumentUriBuilder
import io.github.koucpy001.simplify2md.image.AndroidImageDocumentReader
import io.github.koucpy001.simplify2md.image.AndroidTreeGrantPersistence
import io.github.koucpy001.simplify2md.image.AndroidTreeLauncher
import io.github.koucpy001.simplify2md.image.ImageBindings
import io.github.koucpy001.simplify2md.image.ImageTreeStore
import io.github.koucpy001.simplify2md.image.MediaTokenStore
import io.github.koucpy001.simplify2md.image.RelativeImageResolver
import io.github.koucpy001.simplify2md.image.RelativeImageTreeCoordinator
import io.github.koucpy001.simplify2md.image.authorityOf
import io.github.koucpy001.simplify2md.image.documentIdOf
import io.github.koucpy001.simplify2md.image.treeDocumentIdOf
import io.github.koucpy001.simplify2md.storage.AndroidBackupFileSystem
import io.github.koucpy001.simplify2md.storage.AndroidConfigFileSystem
import io.github.koucpy001.simplify2md.storage.AndroidDocumentContentReader
import io.github.koucpy001.simplify2md.storage.AndroidDocumentMetadataReader
import io.github.koucpy001.simplify2md.storage.AndroidDraftFileSystem
import io.github.koucpy001.simplify2md.storage.AndroidRecoveryPrompt
import io.github.koucpy001.simplify2md.storage.AndroidSafLauncher
import io.github.koucpy001.simplify2md.storage.AndroidSaveDocumentIo
import io.github.koucpy001.simplify2md.storage.AndroidUriGrantReleaser
import io.github.koucpy001.simplify2md.storage.AndroidUriPermissionStore
import io.github.koucpy001.simplify2md.storage.CurrentDocument
import io.github.koucpy001.simplify2md.storage.DraftBindings
import io.github.koucpy001.simplify2md.storage.DraftStore
import io.github.koucpy001.simplify2md.storage.ForegroundRefreshIo
import io.github.koucpy001.simplify2md.storage.ForegroundRefreshPolicy
import io.github.koucpy001.simplify2md.storage.ReconcileCoordinator
import io.github.koucpy001.simplify2md.storage.ReconcileEngine
import io.github.koucpy001.simplify2md.storage.ReconcileNotice
import io.github.koucpy001.simplify2md.storage.RecentsBindings
import io.github.koucpy001.simplify2md.storage.RecentsStore
import io.github.koucpy001.simplify2md.storage.SafBindings
import io.github.koucpy001.simplify2md.storage.SafStore
import io.github.koucpy001.simplify2md.storage.SaveBindings
import io.github.koucpy001.simplify2md.storage.SaveStore
import io.github.koucpy001.simplify2md.storage.SelfWriteWindow
import io.github.koucpy001.simplify2md.update.HttpUrlConnectionUpdateClient
import io.github.koucpy001.simplify2md.update.UpdateBindings
import io.github.koucpy001.simplify2md.update.UpdateChecker
import io.github.koucpy001.simplify2md.web.AssetPathMapper
import io.github.koucpy001.simplify2md.web.AssetWebViewPathHandler
import io.github.koucpy001.simplify2md.web.MediaWebViewPathHandler
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
     * Token table for the `/media/<token>` streaming endpoint (todo 13e).
     * Created before the asset loader so the path handler can be registered;
     * cleared on destroy so no stale document URI outlives the session.
     */
    private lateinit var mediaTokens: MediaTokenStore

    /** The tree picker for relative-image authorization (todo 13c). */
    private lateinit var treeLauncher: AndroidTreeLauncher

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
     * Cold-start SHARED PLAIN TEXT (`ACTION_SEND` + `EXTRA_TEXT`, todo 18). It
     * does not enter the event queue (single-delivery rule: the queue is for
     * warm `onNewIntent` only) and is flushed exactly once when the frontend
     * signals bridge-ready — after `GetStartupFile` was consumed, the startup
     * restore settled and the draft-recovery modal settled, so a shared text
     * can never overwrite draft recovery.
     */
    private val startupTextBuffer = StartupTextBuffer()

    /**
     * Document modified flag reported by `SetDirty`. The back-key guard reads it
     * in todo 18; this class only records it.
     */
    private val dirtyFlag = DirtyFlag()

    /**
     * Desktop watcher's self-write window (plan todo 19). The save binding marks
     * it before a write; [onResume] ignores refreshes while it is open.
     */
    private val selfWriteWindow = SelfWriteWindow()

    /** Recents + the currently open URI; the resume policy reads/updates it. */
    private lateinit var recents: RecentsStore

    /** Foreground refresh policy (plan todo 19); created with the WebView. */
    private lateinit var foregroundRefresh: ForegroundRefreshPolicy

    /**
     * API 33+ back-invoked callback (todo 18a). Registered with default
     * priority: the system does NOT auto-finish behind it, so the guard fully
     * owns the decision and predictive back cannot bypass the exit guard.
     */
    private val backInvokedCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            object : OnBackInvokedCallback {
                override fun onBackInvoked() {
                    handleBackKey()
                }
            }
        } else {
            null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge, todo 17 mechanism (a): the window does NOT fit system
        // windows; the insets are consumed manually below. This is the ONLY
        // mechanism — the manifest pins windowSoftInputMode=adjustNothing and
        // the viewport meta carries no interactive-widget, so there is no
        // viewport resize that could cancel or double-deduct these insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Cold-start intent routing (todo 18c): FILE intents (ACTION_VIEW /
        // ACTION_EDIT / ACTION_SEND + EXTRA_STREAM) travel ONLY through
        // GetStartupFile (consume-once) and never through the event queue;
        // PURE-TEXT intents (ACTION_SEND + EXTRA_TEXT) are buffered in
        // [startupTextBuffer] and dispatched as `mdview:open-text` after
        // bridge-ready. See android/README.md, "Intent 排队规则".
        routeLaunchIntent(intent, warmStart = false)

        // Predictive back (todo 18g): on API 33+ the manifest's
        // enableOnBackInvokedCallback routes the back gesture to THIS callback
        // (default priority), so the system never finishes behind our back and
        // the exit guard cannot be bypassed. Below API 33 onBackPressed runs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback,
            )
        }

        // Token table for the /media/<token> image endpoint; must exist before
        // the asset loader so the path handler can be registered.
        mediaTokens = MediaTokenStore()

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_ASSETS_DOMAIN)
            .addPathHandler("${AssetPathMapper.FRONTEND_ROUTE}/", AssetWebViewPathHandler(assets))
            .addPathHandler("${AssetPathMapper.ASSETS_ROUTE}/", AssetWebViewPathHandler(assets))
            .addPathHandler(
                "${MediaWebViewPathHandler.MEDIA_ROUTE}/",
                MediaWebViewPathHandler(mediaTokens, contentResolver),
            )
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

        // Cold-start shared text (todo 18e): flushed exactly once when the JS
        // context signals ready — never queued, never re-delivered.
        bridge.setReadyListener { flushStartupText() }

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
        recents = RecentsStore(
            files = AndroidConfigFileSystem(filesDir),
            releaser = AndroidUriGrantReleaser(contentResolver),
        )

        // Relative-image resolution (todo 13): folder-tree grant + token endpoint.
        // The tree picker is a separate launcher from the single SAF picker slot,
        // and the (c) pending state machine is pure JVM behind these seams.
        treeLauncher = AndroidTreeLauncher(this)
        val treeStore = ImageTreeStore(
            persistence = AndroidTreeGrantPersistence(contentResolver),
            treeDocIdOf = ::treeDocumentIdOf,
            authorityOf = ::authorityOf,
            onSessionGrant = {
                runOnUiThread {
                    Toast.makeText(this, R.string.image_tree_session_grant, Toast.LENGTH_LONG).show()
                }
            },
        )
        val imageCoordinator = RelativeImageTreeCoordinator(treeStore, treeLauncher)
        treeLauncher.setLateResultHandler { result -> imageCoordinator.onLauncherResult(result) }
        val imageResolver = RelativeImageResolver(
            treeStore = treeStore,
            coordinator = imageCoordinator,
            reader = AndroidImageDocumentReader(contentResolver),
            tokens = mediaTokens,
            documentIdOf = ::documentIdOf,
            authorityOf = ::authorityOf,
            buildChildUri = AndroidChildDocumentUriBuilder(),
            endpointBase = "https://$APP_ASSETS_DOMAIN${MediaWebViewPathHandler.MEDIA_ROUTE}/",
        )
        ImageBindings(imageResolver).registerOn(bridge)

        val documentReader = AndroidDocumentContentReader(contentResolver)
        SafBindings(
            safStore,
            documentReader,
            recents,
            // A successful document load bumps the image generation and rejects
            // the previous document's pending tree requests (plan todo 13, D5).
            onDocumentLoaded = { uri -> imageCoordinator.onDocumentLoaded(uri) },
        ).registerOn(bridge)
        RecentsBindings(recents).registerOn(bridge)

        // Foreground refresh (todo 19): the resumable replacement for the desktop
        // fsnotify watcher. Dirty -> only the existing file-changed prompt; clean
        // -> re-read + silent reload; unreadable -> RemoveRecent + prompt. No
        // polling and no background thread: one evaluation per onResume.
        foregroundRefresh = ForegroundRefreshPolicy(
            current = object : CurrentDocument {
                override fun uri(): String? = recents.current()
                override fun isDirty(): Boolean = dirtyFlag.isDirty
            },
            io = object : ForegroundRefreshIo {
                override fun isSelfWriteWindowActive(): Boolean = selfWriteWindow.isActive()

                override fun canRead(uri: String): Boolean = try {
                    contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }

                override fun reRead(uri: String) {
                    documentReader.read(uri)
                }

                override fun emitFileChanged() {
                    appEvents.fileChanged()
                }

                override fun removeRecent(uri: String) {
                    recents.remove(uri)
                }

                override fun notifyUnavailable(uri: String) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.foreground_document_gone,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )

        // SaveFile (todo 11): encode in memory, then rollback-on-failure + journal.
        // The self-write window is opened BEFORE the write (todo 19), mirroring
        // the Go watcher's mark at the top of SaveFile (`mdview/app.go:226`).
        SaveBindings(SaveStore(backupFs, saveIo, saveIo), onSelfWrite = selfWriteWindow::mark)
            .registerOn(bridge)

        // Autosave drafts (todo 14): app-private filesDir/autosave/, keys are
        // sha1(uri) or the literal "untitled" (DraftKey.of, matching App.vue).
        DraftBindings(DraftStore(AndroidDraftFileSystem(filesDir))).registerOn(bridge)

        // Update check (todo 15): GitHub Releases over HttpURLConnection with
        // explicit connect/read timeouts. The running version comes from
        // BuildConfig.VERSION_NAME; a "dev" build short-circuits with no call.
        UpdateBindings(
            UpdateChecker(
                http = HttpUrlConnectionUpdateClient(),
                currentVersion = { BuildConfig.VERSION_NAME },
            ),
        ).registerOn(bridge)

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        installImeInsets(root)
        checkWebViewVersion()

        webView.loadUrl(START_URL)
    }

    /**
     * Edge-to-edge inset consumption, todo 17 mechanism (b) — the ONLY inset
     * mechanism in the app (no viewport resize, no fixed bar-height constants).
     *
     * The listener consumes BOTH `systemBars()` top+bottom and `ime()` bottom:
     * the top becomes the WebView container's paddingTop (the toolbar is the
     * first page element, so without it the status bar / notch would cover the
     * toolbar in edge-to-edge), and the bottom is `max(systemBars.bottom,
     * ime.bottom)` as the container's paddingBottom. The keyboard height is
     * reported to the frontend via `mdview:ime` so the editor can keep the
     * caret line visible as a scrollIntoView fallback.
     *
     * `ime()` insets only exist on API 30+; below that the keyboard overlays
     * under `adjustNothing` and the reported height is 0 (documented limit).
     */
    private fun installImeInsets(root: FrameLayout) {
        var lastImeBottom = -1
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeBottom =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                } else {
                    0
                }
            root.setPadding(0, bars.top, 0, maxOf(bars.bottom, imeBottom))
            if (imeBottom != lastImeBottom) {
                lastImeBottom = imeBottom
                appEvents.ime(imeBottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Todo 17(f): an outdated embedded WebView cannot be relied on for the IME
     * behaviour above; surface a hint instead of failing silently. A null
     * package (some OEMs/emulators) counts as undeterminable -> hint.
     */
    private fun checkWebViewVersion() {
        val current = WebViewCompat.getCurrentWebViewPackage(this)
        if (!WebViewMinVersion.isSupported(current?.versionName)) {
            Toast.makeText(this, R.string.ime_webview_outdated, Toast.LENGTH_LONG).show()
        }
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
     * Routes the pre-registered SAF picker and tree-picker results. This
     * Activity uses no other request code, so nothing is forwarded to the
     * deprecated super implementation.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // The reconciliation gate creates the pickers only after the WebView
        // exists; a result arriving before that has no launcher to route to.
        if (::safLauncher.isInitialized && safLauncher.onActivityResult(requestCode, resultCode, data)) {
            return
        }
        if (::treeLauncher.isInitialized && treeLauncher.onActivityResult(requestCode, resultCode, data)) {
            return
        }
    }

    /**
     * Routes a launch intent through the delivery matrix (todo 18c-e). The
     * decision is pure ([IntentRouter]); this glue only extracts the payload
     * and applies the route. Warm routes require [appEvents], which exists only
     * after the WebView is set up — a warm intent arriving before that (the
     * reconciliation gate) is dropped rather than crashed on.
     */
    private fun routeLaunchIntent(intent: Intent?, warmStart: Boolean) {
        if (intent == null) return
        val payload = IntentPayload(
            action = intent.action,
            dataUri = intent.dataString,
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
            extraStreamUri = readExtraStreamUri(intent),
        )
        when (val route = IntentRouter.route(payload, warmStart)) {
            is IntentRoute.ColdFile -> startupFileGate.record(route.uri)
            is IntentRoute.ColdText -> startupTextBuffer.record(route.text)
            is IntentRoute.WarmFile -> if (::appEvents.isInitialized) appEvents.openPath(route.uri)
            is IntentRoute.WarmText -> if (::appEvents.isInitialized) appEvents.openText(route.text)
            IntentRoute.Ignore -> Unit
        }
    }

    /**
     * `EXTRA_STREAM` is a Uri (or a list of Uris); anything else — a String, a
     * foreign Parcelable, garbage — is NOT a URI and is dropped so a malformed
     * share can never leak arbitrary text into the file path.
     */
    private fun readExtraStreamUri(intent: Intent): String? {
        val raw = intent.extras?.get(Intent.EXTRA_STREAM) ?: return null
        return when (raw) {
            is Uri -> raw.toString()
            is List<*> -> raw.filterIsInstance<Uri>().firstOrNull()?.toString()
            else -> null
        }
    }

    /** Flushes the buffered cold-start shared text; consume-once, ready-gated. */
    private fun flushStartupText() {
        val text = startupTextBuffer.consume() ?: return
        if (::appEvents.isInitialized) appEvents.openText(text)
    }

    /**
     * Back key (todo 18a): dirty -> `mdview:confirm-exit` so the EXISTING
     * frontend guard runs; clean -> finish. Never exits by bypassing the guard.
     */
    private fun handleBackKey() {
        val dirty = if (::bindings.isInitialized) bindings.dirty.isDirty else false
        when (BackKeyGuard.decide(dirty)) {
            BackKeyGuard.Action.EMIT_CONFIRM_EXIT ->
                if (::appEvents.isInitialized) appEvents.confirmExit() else finish()
            BackKeyGuard.Action.FINISH -> finish()
        }
    }

    /**
     * API < 33 fallback only (lint GestureBackNavigation suppressed deliberately:
     * on 33+ the manifest's enableOnBackInvokedCallback routes the gesture to
     * [backInvokedCallback], and androidx's OnBackPressedDispatcher would need
     * ComponentActivity, which this shell deliberately does not use).
     */
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            handleBackKey()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Warm start (todo 18b): `singleTask` reuses this instance for a launch
     * from a file manager, so the new intent arrives here. `setIntent` is
     * MANDATORY — without it `getIntent` keeps returning the stale launch
     * intent and a recreated process would re-deliver the old URI.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeLaunchIntent(intent, warmStart = true)
    }

    /**
     * The entire external-change detection mechanism (plan todo 19): evaluated on
     * resume only — no polling, no timer, no background thread. Until the WebView
     * (and thus the policy) exists, a resume has nothing to refresh and is
     * skipped; the first real resume after a document was opened does the work.
     */
    override fun onResume() {
        super.onResume()
        if (::foregroundRefresh.isInitialized) {
            foregroundRefresh.onResume()
        }
    }

    override fun onDestroy() {
        // Stop the reconciliation coroutine: destroying the Activity must not
        // leave a dialog or IO continuation alive.
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backInvokedCallback)
        }
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
        // Drop every media token so no stale document URI outlives the session.
        if (::mediaTokens.isInitialized) {
            mediaTokens.clear()
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
