package io.github.koucpy001.simplify2md.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android glue for the JS bridge.
 *
 * `__bridgeCall` and `__bridgeReady` run on the WebView's JavaBridge thread and
 * never touch a View: dispatch decisions are pure, method bodies execute on
 * `Dispatchers.IO`, and every `evaluateJavascript` is marshalled to the main
 * thread. The protocol surface is:
 *
 *  - Kotlin -> JS: `__bridgeResolve`, `__bridgeReject`, `__bridgeEmit`, `__bridgeReset`
 *  - JS -> Kotlin: `window.__bridge.__bridgeCall`, `window.__bridge.__bridgeReady`
 *
 * `addJavascriptInterface` reaches every frame with no origin filtering; the
 * real boundaries are the Activity's navigation whitelist, the frontend CSP
 * `frame-src 'none'`, and the disabled file/content access flags. No
 * `addWebMessageListener` is involved, and `view.url` is not treated as a frame
 * identity check.
 */
class Bridge(
    private val webView: WebView,
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val transport = BridgeTransport(object : BridgeTransport.Effects {
        override fun resolve(requestId: String, json: String) {
            evaluate("window.__bridgeResolve && window.__bridgeResolve(${BridgeCodec.quote(requestId)}, ${BridgeCodec.quote(json)})")
        }

        override fun reject(requestId: String, message: String) {
            evaluate("window.__bridgeReject && window.__bridgeReject(${BridgeCodec.quote(requestId)}, ${BridgeCodec.quote(message)})")
        }

        override fun emit(name: String, payloadJson: String) {
            evaluate("window.__bridgeEmit && window.__bridgeEmit(${BridgeCodec.quote(name)}, ${BridgeCodec.quote(payloadJson)})")
        }
    })

    /** Shared by OpenFile and PickSavePath: one outstanding SAF picker at a time. */
    val pickerSlot = PickerSlot()

    /** Separate launcher for the relative-image tree authorization. */
    val treeAuthDedup = TreeAuthDedup()

    fun registerHandler(method: String, handler: BridgeHandler) {
        transport.registerHandler(method, handler)
    }

    fun emitEvent(name: String, payloadJson: String = "null") {
        transport.emitEvent(name, payloadJson)
    }

    /**
     * Delivers a call. Returns immediately; the handler runs off this thread and
     * the result is posted back to the main thread after the registry confirms
     * the request is still live.
     */
    @JavascriptInterface
    fun __bridgeCall(requestId: String, method: String, argsJson: String) {
        when (val decision = transport.decide(requestId, method, argsJson)) {
            is BridgeTransport.CallDecision.Reject -> transport.rejectNow(requestId, decision.message)
            is BridgeTransport.CallDecision.Dispatch -> scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { decision.handler.handle(decision.args) }
                }
                withContext(Dispatchers.Main) { transport.complete(requestId, result) }
            }
        }
    }

    /** JS readiness signal. Idempotent; only replays still-undispatched events. */
    @JavascriptInterface
    fun __bridgeReady() {
        transport.markReady()
    }

    /**
     * Called from `onCreate` (including after process recreation) before any new
     * call is accepted, so a stale Promise from a previous JS context can never
     * remain pending.
     */
    fun onActivityCreated() {
        transport.reset()
        evaluate(SCRIPT_RESET)
        scheduleReadyTimeout()
    }

    /**
     * Called from `onDestroy` before the WebView is torn down. The Kotlin
     * registry alone is not enough: JS must reject its pending Promises, which
     * `__bridgeReset` does with a `bridge-reset` reason.
     */
    fun onActivityDestroying() {
        transport.reset()
        evaluate(SCRIPT_RESET)
    }

    /** Cancels the scope and drops queued callbacks after the WebView is gone. */
    fun dispose() {
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    private fun scheduleReadyTimeout() {
        mainHandler.postDelayed({
            if (!transport.isReady()) {
                Log.w(
                    TAG,
                    "bridge ready signal not received within ${readyTimeoutMs}ms; the page may need a manual refresh",
                )
            }
        }, readyTimeoutMs)
    }

    private fun evaluate(script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(script, null)
        } else {
            mainHandler.post { webView.evaluateJavascript(script, null) }
        }
    }

    companion object {
        /** Injected object name: JS reaches members as `window.__bridge.<member>`. */
        const val JAVASCRIPT_INTERFACE_NAME = "__bridge"

        const val DEFAULT_READY_TIMEOUT_MS = 10_000L

        private const val TAG = "Simplify2mdBridge"
        private const val SCRIPT_RESET = "window.__bridgeReset && window.__bridgeReset()"
    }
}
