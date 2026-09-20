package io.github.koucpy001.simplify2md.binding

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONArray

/**
 * Android semantics for the bindings that have no 1:1 desktop counterpart.
 *
 * These five names exist in the desktop Go backend but mean something different
 * in a single-Activity WebView app. The policy is frozen here and documented in
 * `android/README.md`; the plan (todo 7) is the authority.
 *
 * | binding         | Android semantics                                             |
 * |-----------------|---------------------------------------------------------------|
 * | SetTitle(t)     | no-op — there is no window title; the toolbar shows name+dirty |
 * | SetStartupArgs  | not in the JS bridge — the launch Intent is the equivalent     |
 * | GetStartupFile  | consume-once URI string, `""` when absent (never an object)    |
 * | SetDirty(b)     | record the flag for the back-key guard (wired by todo 18)       |
 * | ConfirmExit()   | finish() the Activity — the thin glue lives in MainActivity     |
 *
 * The behaviour is pure/testable: [setTitle], [setDirty], [getStartupFile] and
 * [confirmExit] carry all decisions; [registerOn] only adapts them to the
 * [Bridge] transport, which requires the method to be in
 * [BridgeMethods.WHITELIST].
 *
 * Deliberately NOT here (other todos own them): the back-key guard itself
 * (todo 18), intent parsing and `mdview:open-text` / `mdview:open-path` event
 * dispatch for `ACTION_SEND` (todo 18), SAF (todo 10), and the save/reconcile
 * state machine (todo 11).
 */
class DesktopOnlyBindings(
    private val startupFileGate: StartupFileGate,
    private val dirtyFlag: DirtyFlag,
    private val requestExit: () -> Unit,
) {

    /** The dirty flag the back-key guard reads (todo 18). */
    val dirty: DirtyFlag get() = dirtyFlag

    /**
     * `SetTitle` is a no-op: Android has no native window title, and the WebView
     * toolbar already renders the filename plus the `●` dirty marker
     * (`App.vue:1321`). Inventing a title bar is explicitly out of scope.
     */
    fun setTitle(): Any? = null

    /** `SetDirty` only records the flag; the frontend keeps owning the guard UI. */
    fun setDirty(dirty: Boolean) {
        dirtyFlag.set(dirty)
    }

    /**
     * `GetStartupFile` returns the first cold-start document URI and consumes it.
     * Returns `""` when the launch carried no file. The return type is a plain
     * `String` — the `@bridge` contract on both platforms is `Promise<string>`,
     * and any `{type, value}` wrapping would be a type divergence.
     */
    fun getStartupFile(): String = startupFileGate.consume()

    /** `ConfirmExit` finishes the Activity; the glue is supplied by the host. */
    fun confirmExit() {
        requestExit()
    }

    /**
     * Registers the four handlers on [Bridge]. [Bridge.registerHandler] rejects
     * any method outside [BridgeMethods.WHITELIST], so this cannot widen the
     * dispatch surface.
     */
    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.SET_TITLE) { setTitle() }
        bridge.registerHandler(BridgeMethods.SET_DIRTY) { args: JSONArray ->
            setDirty(args.optBoolean(0, false))
            null
        }
        bridge.registerHandler(BridgeMethods.GET_STARTUP_FILE) { getStartupFile() }
        bridge.registerHandler(BridgeMethods.CONFIRM_EXIT) {
            confirmExit()
            null
        }
    }
}
