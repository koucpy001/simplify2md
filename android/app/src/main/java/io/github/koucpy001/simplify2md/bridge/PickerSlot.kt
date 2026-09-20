package io.github.koucpy001.simplify2md.bridge

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-slot gate for the SAF picker round-trip.
 *
 * A process/Activity can be killed while a system picker is open, so pickers
 * must be pre-registered and only one may be outstanding: [OpenFile] and
 * [PickSavePath] share this slot and a concurrent request is rejected at once.
 * The slot is released when the launcher result is delivered.
 *
 * The relative-image tree authorization deliberately uses a separate launcher
 * and is not gated here — see [TreeAuthDedup].
 */
class PickerSlot {

    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }

    val isPending: Boolean get() = inFlight.get()
}

/**
 * Per-document dedup for the separate tree-authorization launcher.
 *
 * Independent of [PickerSlot]: a pending tree prompt neither blocks nor is
 * blocked by an OpenFile/PickSavePath picker. A document is prompted at most
 * once; [forget] allows a retry after the user revokes or the grant is lost.
 * The launcher itself is wired by the image-resolution work.
 */
class TreeAuthDedup {

    private val prompted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True the first time a document is seen, false afterwards. */
    fun markIfFirst(documentUri: String): Boolean = prompted.add(documentUri)

    fun forget(documentUri: String) {
        prompted.remove(documentUri)
    }

    fun isPrompted(documentUri: String): Boolean = prompted.contains(documentUri)

    fun clear() {
        prompted.clear()
    }
}
