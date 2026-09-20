package io.github.koucpy001.simplify2md.bridge

/** A Kotlin-to-JS event: [name] is the frontend event name, [payloadJson] its JSON payload. */
data class BridgeEvent(val name: String, val payloadJson: String)

/**
 * Ready gate for outbound events.
 *
 * The frontend registers its `EventsOn` handlers during mount and only then
 * signals readiness. An event produced before that point would be lost, so it is
 * queued here and drained when the ready signal arrives. Once ready, events are
 * delivered immediately.
 *
 * [reset] clears the ready flag but keeps undispatched events: after a page
 * reload the JS context is new, so a fresh ready signal must replay anything
 * that was never delivered. Events already dispatched are gone and are never
 * replayed.
 */
class ReadyEventQueue {

    private val lock = Any()
    private var ready = false
    private val queue = ArrayDeque<BridgeEvent>()

    /** True when the event was queued (not ready); false when the caller must dispatch now. */
    fun offer(event: BridgeEvent): Boolean {
        var queued = false
        synchronized(lock) {
            if (!ready) {
                queue.addLast(event)
                queued = true
            }
        }
        return queued
    }

    /**
     * Flips to ready and returns the queued events in arrival order. Idempotent:
     * a second call after the queue was drained returns an empty list and never
     * re-delivers what was already dispatched.
     */
    fun markReady(): List<BridgeEvent> {
        var drained: List<BridgeEvent> = emptyList()
        synchronized(lock) {
            ready = true
            if (queue.isNotEmpty()) {
                drained = ArrayList(queue)
                queue.clear()
            }
        }
        return drained
    }

    fun reset() {
        synchronized(lock) {
            ready = false
        }
    }

    val isReady: Boolean get() = synchronized(lock) { ready }

    val queuedCount: Int get() = synchronized(lock) { queue.size }
}
