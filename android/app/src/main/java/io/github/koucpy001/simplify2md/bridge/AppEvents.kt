package io.github.koucpy001.simplify2md.bridge

/**
 * Kotlin-to-JS event emitters for the names [BridgeEvents] freezes.
 *
 * The emitters take a plain `(name, payloadJson) -> Unit` so they carry no
 * WebView dependency and run under plain JVM JUnit; the host wires that lambda
 * to [Bridge.emitEvent], which is ready-gated and never widens the dispatch
 * surface. Event *triggers* (back key, `onNewIntent`, `ACTION_SEND`, foreground
 * refresh) are owned by todos 18/19 — this class is the seam they call into.
 *
 * The payload is always JSON-encoded, never string-concatenated, so a URI that
 * contains quotes, newlines or U+2028/U+2029 cannot break out of the
 * `evaluateJavascript` call or be read as anything but a JSON string.
 */
class AppEvents(private val emitter: (name: String, payloadJson: String) -> Unit) {

    /**
     * `App.vue:1242` opens the exit-confirm dialog. Triggered by the back key in
     * todo 18.
     */
    fun confirmExit() {
        emit(BridgeEvents.CONFIRM_EXIT, BridgeEvents.NULL_PAYLOAD)
    }

    /**
     * `App.vue:1245` loads [path] (a document URI on Android). Triggered by a
     * second launch / `ACTION_SEND` stream in todo 18.
     */
    fun openPath(path: String) {
        emit(BridgeEvents.OPEN_PATH, BridgeCodec.quote(path))
    }

    /**
     * `App.vue:1246` reloads the current file or asks the user. Triggered by the
     * foreground refresh in todo 19.
     */
    fun fileChanged() {
        emit(BridgeEvents.FILE_CHANGED, BridgeEvents.NULL_PAYLOAD)
    }

    /**
     * `mdview:ime` (todo 17): the keyboard height in physical pixels, from the
     * root layout's `OnApplyWindowInsetsListener` (`ime()` bottom). The payload
     * is a hand-built `{"height":<int>}` object — an int needs no JSON library,
     * and the frontend's `imeInsetPx` sanitizes every shape anyway.
     */
    fun ime(heightPx: Int) {
        emit(BridgeEvents.IME, "{\"height\":$heightPx}")
    }

    private fun emit(name: String, payloadJson: String) {
        require(BridgeEvents.isRegistered(name)) { "not a registered event name: $name" }
        emitter(name, payloadJson)
    }
}
