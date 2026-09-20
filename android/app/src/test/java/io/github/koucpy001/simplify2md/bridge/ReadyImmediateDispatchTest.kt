package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the "stall in queue" defect is absent (plan todo 18g): an event
 * enqueued AFTER the ready signal must be dispatched IMMEDIATELY, not left in
 * the queue waiting for a ready that already happened.
 */
class ReadyImmediateDispatchTest {

    private class RecordingEffects : BridgeTransport.Effects {
        val emitted = mutableListOf<Pair<String, String>>()
        override fun resolve(requestId: String, json: String) = Unit
        override fun reject(requestId: String, message: String) = Unit
        override fun emit(name: String, payloadJson: String) {
            emitted += name to payloadJson
        }
    }

    @Test
    fun warmIntentEnqueuedAfterReadyIsDispatchedImmediately() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.markReady()
        transport.emitEvent(BridgeEvents.OPEN_PATH, BridgeCodec.quote("content://a/2.md"))
        assertEquals(
            listOf(BridgeEvents.OPEN_PATH to BridgeCodec.quote("content://a/2.md")),
            effects.emitted,
        )
        assertEquals(0, transport.pendingCount())
    }

    @Test
    fun warmIntentsEnqueuedAfterReadyKeepArrivalOrder() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.markReady()
        transport.emitEvent(BridgeEvents.OPEN_PATH, BridgeCodec.quote("content://a/1.md"))
        transport.emitEvent("mdview:open-text", BridgeCodec.quote("text"))
        assertEquals(
            listOf(BridgeEvents.OPEN_PATH, "mdview:open-text"),
            effects.emitted.map { it.first },
        )
    }

    @Test
    fun readyListenerFiresExactlyOncePerJsContext() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        var readyCalls = 0
        transport.readyListener = { readyCalls += 1 }
        transport.markReady()
        transport.markReady()
        assertEquals(1, readyCalls)
        // A page reload resets the flag: the new JS context notifies again.
        transport.reset()
        transport.markReady()
        assertEquals(2, readyCalls)
    }

    @Test
    fun queuedEventsBeforeReadyAreStillDrainedInOrder() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.emitEvent(BridgeEvents.OPEN_PATH, BridgeCodec.quote("content://a/1.md"))
        transport.markReady()
        assertTrue(effects.emitted.map { it.first } == listOf(BridgeEvents.OPEN_PATH))
    }
}
