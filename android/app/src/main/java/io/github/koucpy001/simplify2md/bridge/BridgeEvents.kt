package io.github.koucpy001.simplify2md.bridge

/**
 * Kotlin-to-JS event names.
 *
 * These strings are the contract with the frontend: `App.vue` registers a
 * listener for each one with `EventsOn` (confirm-exit, open-path, file-changed,
 * ime — registration lives in `mdview/frontend/src/lib/bridge-events.ts`), and
 * the Android shim `window.__bridgeEmit(name, payloadJson)` dispatches to
 * those listeners. They are therefore frozen here as constants rather than
 * spelled inline, and [REGISTERED] lists exactly the names the Android port may
 * emit. `mdview:open-text` (shared plain text) is owned by todo 18 and is
 * deliberately not part of this task's surface.
 *
 * `mdview:ime` (todo 17) reports the keyboard height in physical pixels as
 * `{"height":<int>}`; the frontend writes it into the `--mdview-ime-height`
 * CSS variable as the editor's scrollIntoView fallback.
 *
 * `App.vue` listener lines: `mdview:confirm-exit` -> 1242, `mdview:open-path`
 * -> 1245, `mdview:file-changed` -> 1246. `BridgeEventsTest` pins the values so
 * a rename on either side fails a JVM test.
 */
object BridgeEvents {

    const val CONFIRM_EXIT = "mdview:confirm-exit"
    const val OPEN_PATH = "mdview:open-path"
    const val FILE_CHANGED = "mdview:file-changed"
    const val IME = "mdview:ime"

    /**
     * Payload for listener-only events: the shim parses it to `undefined`, and
     * `App.vue`'s handlers take no argument.
     */
    const val NULL_PAYLOAD = "null"

    /** Exactly the names this port emits today. */
    val REGISTERED: Set<String> = setOf(CONFIRM_EXIT, OPEN_PATH, FILE_CHANGED, IME)

    /** True only for the frozen names; guards [AppEvents] against typos. */
    fun isRegistered(name: String): Boolean = REGISTERED.contains(name)
}
