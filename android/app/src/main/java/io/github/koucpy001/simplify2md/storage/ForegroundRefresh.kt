package io.github.koucpy001.simplify2md.storage

/**
 * Foreground refresh policy replacing the desktop fsnotify watcher (plan todo 19).
 *
 * Android cannot watch a SAF document for external writes, and the plan
 * explicitly forbids polling and background threads. The equivalent is a policy
 * evaluated exactly once per `Activity.onResume`:
 *
 *  - [ForegroundRefreshAction.NO_DOCUMENT] nothing is open (untitled / empty);
 *  - [ForegroundRefreshAction.SELF_WRITE] our own save is still inside the
 *    desktop watcher's self-write window (`mdview/app.go:226`, 500 ms), so a
 *    resume right after saving is NOT an external change and is ignored;
 *  - [ForegroundRefreshAction.RELOADED] `dirty == false`: the document was
 *    re-read and `mdview:file-changed` was emitted, so the frontend's existing
 *    handler reloads it silently (`App.vue:1397-1406`);
 *  - [ForegroundRefreshAction.PROMPTED] `dirty == true`: NOTHING is read and
 *    `mdview:file-changed` is emitted exactly once so the EXISTING prompt dialog
 *    runs. The editor buffer is never touched — this is the data-loss guard that
 *    exists precisely so an external change can never silently overwrite edits;
 *  - [ForegroundRefreshAction.UNAVAILABLE] the document can no longer be read
 *    (deleted, or the SAF grant was revoked): the user is told and the entry is
 *    routed through `RemoveRecent`.
 *
 * Why [ForegroundRefreshAction.RELOADED] also emits the event: the only channel
 * to update the WebView buffer is the frontend itself. Kotlin re-reads to prove
 * the URI is still readable (deletion/grant revocation is only detectable by a
 * read attempt), then asks the frontend to reload through the one event name
 * that already exists. `mdview:file-changed` is frozen in `BridgeEvents`; no new
 * event and no new bridge function is introduced.
 *
 * All IO is behind [ForegroundRefreshIo], so the three branches run under plain
 * JVM JUnit and the data-loss test asserts on the recorded calls rather than on
 * a return value.
 */
enum class ForegroundRefreshAction {
    /** No open document: nothing to refresh. */
    NO_DOCUMENT,

    /** Our own save is still inside the self-write window; ignore this resume. */
    SELF_WRITE,

    /** `dirty == false`: the document was re-read and a silent reload requested. */
    RELOADED,

    /** `dirty == true`: only the file-changed event was emitted; buffer untouched. */
    PROMPTED,

    /** The document is unreadable: the entry was removed and the user was told. */
    UNAVAILABLE,
}

/** The editor document as the native side knows it. */
interface CurrentDocument {
    /** The open document URI, or null when there is none (untitled document). */
    fun uri(): String?

    /** The dirty flag last reported by `SetDirty`. */
    fun isDirty(): Boolean
}

/** Everything the resume policy needs from the platform, recorded by tests. */
interface ForegroundRefreshIo {
    /** True while the self-write window opened by our own save is still active. */
    fun isSelfWriteWindowActive(): Boolean

    /** Checks the read grant; false when it was revoked or the document is gone. */
    fun canRead(uri: String): Boolean

    /** Reads the document; throws when it was deleted / is unreadable. */
    fun reRead(uri: String)

    /** Emits `mdview:file-changed` (the only refresh channel that exists). */
    fun emitFileChanged()

    /** Routes the entry through `RemoveRecent`. */
    fun removeRecent(uri: String)

    /** Shows the user a prompt that the document can no longer be read. */
    fun notifyUnavailable(uri: String)
}

/**
 * The state machine. Pure JVM: no Android type and no clock of its own. One call
 * per resume; the class holds no mutable state, so repeated resumes are
 * independent evaluations and cannot accumulate.
 */
class ForegroundRefreshPolicy(
    private val current: CurrentDocument,
    private val io: ForegroundRefreshIo,
) {

    fun onResume(): ForegroundRefreshAction {
        val uri = current.uri()?.takeIf { it.isNotBlank() }
            ?: return ForegroundRefreshAction.NO_DOCUMENT

        // A resume immediately after our own save is not an external change
        // (desktop self-write window, `mdview/app.go:330`): skip entirely, so the
        // user never sees a "file changed" prompt for their own write.
        if (io.isSelfWriteWindowActive()) return ForegroundRefreshAction.SELF_WRITE

        if (current.isDirty()) {
            // Branch 2 (data-loss guard): never read and never touch the buffer.
            // The frontend's existing dialog decides, and it cannot reload the
            // document without the user's explicit choice.
            io.emitFileChanged()
            return ForegroundRefreshAction.PROMPTED
        }

        if (!io.canRead(uri)) return unavailable(uri)

        return try {
            io.reRead(uri)
            // dirty == false, so the frontend's existing file-changed handler
            // reloads silently (`App.vue:1399-1402`) — the visible content
            // refreshes with no prompt.
            io.emitFileChanged()
            ForegroundRefreshAction.RELOADED
        } catch (_: Exception) {
            unavailable(uri)
        }
    }

    private fun unavailable(uri: String): ForegroundRefreshAction {
        io.removeRecent(uri)
        io.notifyUnavailable(uri)
        return ForegroundRefreshAction.UNAVAILABLE
    }
}

/**
 * The desktop watcher's self-write window (`mdview/app.go:218-238`, 500 ms).
 *
 * Android has no watcher to suppress, but a resume that lands just after our own
 * save must still not be mistaken for an external change. The save path marks
 * this window before it starts writing; the resume policy ignores refreshes while
 * it is open. The clock is injectable so the 500 ms boundary is deterministic.
 */
class SelfWriteWindow(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = System::nanoTime,
) {

    @Volatile
    private var untilNs: Long = 0

    /** Opens the window; called before a save starts writing. */
    fun mark() {
        untilNs = now() + windowMs * NANOS_PER_MS
    }

    /** True while a resume must be treated as our own write, not an external one. */
    fun isActive(): Boolean = now() < untilNs

    companion object {
        /** The exact window the Go watcher uses (`500 * time.Millisecond`). */
        const val DEFAULT_WINDOW_MS = 500L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
