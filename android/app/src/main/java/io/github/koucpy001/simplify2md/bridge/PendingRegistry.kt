package io.github.koucpy001.simplify2md.bridge

import java.util.concurrent.ConcurrentHashMap

/** Outcome of [PendingRegistry.track]. */
enum class TrackOutcome {
    TRACKED,
    DUPLICATE,
    CAPACITY_EXCEEDED,
}

/**
 * Tracks requestIds whose JS Promise is still outstanding.
 *
 * Threaded freely from the JavaBridge thread, the IO dispatcher and the main
 * thread, so the backing store is a [ConcurrentHashMap]. Bounded by [capacity]
 * so a frontend that never settles its Promises cannot grow the registry
 * without limit. A repeated requestId is reported as a duplicate instead of
 * silently overwriting the first one's bookkeeping.
 */
class PendingRegistry(private val capacity: Int = MAX_PENDING) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val pending = ConcurrentHashMap<String, Long>()

    fun track(requestId: String): TrackOutcome {
        if (requestId.isEmpty()) return TrackOutcome.DUPLICATE
        if (pending.size >= capacity) return TrackOutcome.CAPACITY_EXCEEDED
        return if (pending.putIfAbsent(requestId, System.nanoTime()) == null) {
            TrackOutcome.TRACKED
        } else {
            TrackOutcome.DUPLICATE
        }
    }

    /** Removes the id; false means the callback is stale and must be dropped. */
    fun settle(requestId: String): Boolean = pending.remove(requestId) != null

    /** Removes every id and returns them. */
    fun clear(): List<String> {
        val ids = ArrayList(pending.keys)
        pending.clear()
        return ids
    }

    fun contains(requestId: String): Boolean = pending.containsKey(requestId)

    val size: Int get() = pending.size

    companion object {
        const val MAX_PENDING = 256
    }
}
