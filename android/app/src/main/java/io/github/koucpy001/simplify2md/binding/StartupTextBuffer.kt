package io.github.koucpy001.simplify2md.binding

import java.util.concurrent.atomic.AtomicReference

/**
 * Consume-once holder for the cold-start SHARED PLAIN TEXT (plan todo 18c/e).
 *
 * Cold-start `ACTION_SEND` + `EXTRA_TEXT` must NOT enter the event queue (the
 * single-delivery rule reserves the queue for warm `onNewIntent`), but it must
 * not be dropped either — the first "share text to app" would otherwise be
 * silently lost. It is buffered here and flushed exactly once when the frontend
 * signals bridge-ready (which only happens after `GetStartupFile` was consumed,
 * the startup restore settled and the draft-recovery modal settled), so the
 * shared text can never overwrite draft recovery.
 *
 * Pure JVM: no Android dependency, no coroutines.
 */
class StartupTextBuffer {

    private val pending = AtomicReference<String?>(null)

    /**
     * Records the shared text. The FIRST non-empty text wins; later records are
     * ignored while a value is still waiting (two rapid shares on a cold start
     * keep the first one, matching the gate's first-wins rule). Empty text is
     * ignored.
     *
     * @return true when this call stored a text.
     */
    fun record(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return pending.compareAndSet(null, text)
    }

    /** Returns the buffered text and clears it, or null when nothing was buffered. */
    fun consume(): String? = pending.getAndSet(null)

    /** True while a shared text is waiting to be flushed (test/diagnostic aid). */
    val hasPending: Boolean get() = pending.get() != null
}
