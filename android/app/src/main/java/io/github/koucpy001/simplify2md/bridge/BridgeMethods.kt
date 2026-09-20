package io.github.koucpy001.simplify2md.bridge

/**
 * Explicit, immutable dispatch whitelist for [BridgeTransport].
 *
 * A call is dispatched only when its method name is a member of [WHITELIST];
 * every other name is rejected before any lookup happens. The set is written
 * out literally so no reflective or classpath-driven dispatch can widen it.
 *
 * The frozen cross-platform `@bridge` export set is 20 symbols: these 17 App
 * functions plus `BrowserOpenURL` (also a `__bridgeCall` method), and two that
 * do not travel through `__bridgeCall` — `EventsOn` (JS-local event registry)
 * and `notifyBridgeReady` (separate `__bridgeReady` interface method).
 * `SetStartupArgs` is exported by the Go binding but never called from the
 * frontend and is intentionally absent.
 */
object BridgeMethods {

    const val OPEN_FILE = "OpenFile"
    const val SAVE_FILE = "SaveFile"
    const val PICK_SAVE_PATH = "PickSavePath"
    const val LOAD_IMAGE_FOR_SRC = "LoadImageForSrc"
    const val READ_FILE_AT = "ReadFileAt"
    const val GET_RECENTS = "GetRecents"
    const val GET_STARTUP_FILE = "GetStartupFile"
    const val REMOVE_RECENT = "RemoveRecent"
    const val SET_DIRTY = "SetDirty"
    const val SET_TITLE = "SetTitle"
    const val CONFIRM_EXIT = "ConfirmExit"
    const val CHECK_FOR_UPDATE = "CheckForUpdate"
    const val SAVE_DRAFT = "SaveDraft"
    const val LOAD_DRAFT = "LoadDraft"
    const val LIST_DRAFTS = "ListDrafts"
    const val CLEAR_DRAFT = "ClearDraft"
    const val CLEAR_RECENTS = "ClearRecents"
    const val BROWSER_OPEN_URL = "BrowserOpenURL"

    val WHITELIST: Set<String> = setOf(
        OPEN_FILE,
        SAVE_FILE,
        PICK_SAVE_PATH,
        LOAD_IMAGE_FOR_SRC,
        READ_FILE_AT,
        GET_RECENTS,
        GET_STARTUP_FILE,
        REMOVE_RECENT,
        SET_DIRTY,
        SET_TITLE,
        CONFIRM_EXIT,
        CHECK_FOR_UPDATE,
        SAVE_DRAFT,
        LOAD_DRAFT,
        LIST_DRAFTS,
        CLEAR_DRAFT,
        CLEAR_RECENTS,
        BROWSER_OPEN_URL,
    )

    /** The 17 App functions of the frozen export set; `BrowserOpenURL` is not counted. */
    const val APP_FUNCTION_COUNT = 17

    fun isKnown(method: String): Boolean = WHITELIST.contains(method)
}
