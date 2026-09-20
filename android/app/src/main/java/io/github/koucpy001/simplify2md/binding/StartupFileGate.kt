package io.github.koucpy001.simplify2md.binding

import java.util.concurrent.atomic.AtomicReference

/**
 * Consume-once holder for the cold-start document URI.
 *
 * The desktop `GetStartupFile()` returns the path the process was launched with
 * and keeps returning it; the Android equivalent is the launch Intent. Cold-start
 * file intents (`ACTION_VIEW` / `ACTION_EDIT`) therefore do **not** travel
 * through the event queue: they are recorded here exactly once and delivered to
 * the frontend through `GetStartupFile`, which consumes the value. A second call
 * returns `""`.
 *
 * The value stays a plain `String` (a document URI such as
 * `content://com.example.provider/...`). It must never be wrapped in a
 * `{type, value}` object: plain shared text always travels via the
 * `mdview:open-text` event, so a one-shape contract keeps both platforms
 * identical (`Promise<string>`).
 *
 * This class has no Android dependency and no coroutines, so both branches
 * (empty and present) run under plain JVM JUnit tests.
 */
class StartupFileGate {

    private val pending = AtomicReference<String?>(null)

    /**
     * Records the cold-start URI. The **first** non-blank URI wins; later records
     * are ignored while a value is still waiting to be consumed, so a process
     * recreated from a stray intent can never replace the user-visible startup
     * file.
     *
     * Blank, empty and null URIs are ignored: a launch without a file (or with a
     * malformed intent) must still answer `""`, never an empty-path open request.
     *
     * @return true when this call stored a URI.
     */
    fun record(uri: String?): Boolean {
        val normalized = uri?.trim()
        if (normalized.isNullOrEmpty()) return false
        return pending.compareAndSet(null, normalized)
    }

    /**
     * Returns the recorded URI and clears it, or `""` when none was recorded.
     * Atomic: concurrent callers cannot both observe the same URI.
     */
    fun consume(): String = pending.getAndSet(null) ?: ""

    /** True while a cold-start URI is waiting to be consumed (test/diagnostic aid). */
    val hasPending: Boolean get() = pending.get() != null
}
