package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class BridgeTransportTest {

    private class RecordingEffects : BridgeTransport.Effects {
        val resolves = mutableListOf<Pair<String, String>>()
        val rejects = mutableListOf<Pair<String, String>>()
        val emitted = mutableListOf<Pair<String, String>>()

        override fun resolve(requestId: String, json: String) {
            resolves.add(requestId to json)
        }

        override fun reject(requestId: String, message: String) {
            rejects.add(requestId to message)
        }

        override fun emit(name: String, payloadJson: String) {
            emitted.add(name to payloadJson)
        }
    }

    private fun rejectMessage(decision: BridgeTransport.CallDecision): String {
        assertTrue(decision is BridgeTransport.CallDecision.Reject)
        return (decision as BridgeTransport.CallDecision.Reject).message
    }

    @Test
    fun unknownMethodIsRejectedWithoutDispatching() {
        val effects = RecordingEffects()
        val invoked = AtomicBoolean(false)
        val transport = BridgeTransport(effects)
        transport.registerHandler(BridgeMethods.OPEN_FILE) {
            invoked.set(true)
            null
        }

        val message = rejectMessage(transport.decide("1", "DefinitelyNotAMethod", "[]"))
        assertEquals("unknown bridge method: DefinitelyNotAMethod", message)
        assertFalse(invoked.get())
        assertEquals(0, transport.pendingCount())
    }

    @Test
    fun malformedArgumentsAreRejected() {
        val transport = BridgeTransport(RecordingEffects())
        transport.registerHandler(BridgeMethods.OPEN_FILE) { null }
        assertTrue(rejectMessage(transport.decide("1", BridgeMethods.OPEN_FILE, "[bad")).contains("invalid"))
    }

    @Test
    fun blankRequestIdIsRejected() {
        val transport = BridgeTransport(RecordingEffects())
        assertEquals("invalid requestId", rejectMessage(transport.decide("", BridgeMethods.OPEN_FILE, "[]")))
    }

    @Test
    fun whitelistedMethodWithoutHandlerIsRejectedAsNotImplemented() {
        val transport = BridgeTransport(RecordingEffects())
        val message = rejectMessage(transport.decide("1", BridgeMethods.OPEN_FILE, "[]"))
        assertTrue(message.contains("not implemented"))
    }

    @Test
    fun registeringAnUnknownMethodIsRejected() {
        val transport = BridgeTransport(RecordingEffects())
        var threw = false
        try {
            transport.registerHandler("NotAMethod") { null }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun dispatchTracksTheRequestAndResolvesTheEncodedResult() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.registerHandler(BridgeMethods.GET_STARTUP_FILE) { "dir/a.md" }

        assertTrue(transport.decide("1", BridgeMethods.GET_STARTUP_FILE, "[]") is BridgeTransport.CallDecision.Dispatch)
        assertEquals(1, transport.pendingCount())
        assertTrue(transport.complete("1", Result.success("dir/a.md")))
        assertEquals(listOf("1" to "\"dir/a.md\""), effects.resolves)
        assertEquals(0, transport.pendingCount())
    }

    @Test
    fun cancelledFailureRejectsWithCancelledSemantics() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.registerHandler(BridgeMethods.OPEN_FILE) { throw BridgeCancelledException() }

        transport.decide("1", BridgeMethods.OPEN_FILE, "[]")
        assertTrue(transport.complete("1", Result.failure(BridgeCancelledException())))
        assertEquals(1, effects.rejects.size)
        assertTrue(Regex("cancelled", RegexOption.IGNORE_CASE).containsMatchIn(effects.rejects[0].second))
    }

    @Test
    fun duplicateRequestIdIsRejectedAndTheOriginalStaysTracked() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.registerHandler(BridgeMethods.SET_DIRTY) { null }

        assertTrue(transport.decide("1", BridgeMethods.SET_DIRTY, "[true]") is BridgeTransport.CallDecision.Dispatch)
        assertTrue(rejectMessage(transport.decide("1", BridgeMethods.SET_DIRTY, "[false]")).contains("duplicate"))
        assertEquals(1, transport.pendingCount())
    }

    @Test
    fun completionAfterResetIsDroppedSilently() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        transport.registerHandler(BridgeMethods.SET_DIRTY) { null }

        transport.decide("1", BridgeMethods.SET_DIRTY, "[true]")
        transport.reset()
        assertFalse(transport.complete("1", Result.success(null)))
        assertTrue(effects.resolves.isEmpty())
        assertTrue(effects.rejects.isEmpty())
    }

    @Test
    fun eventsQueueUntilReadyThenReplayOnce() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)

        transport.emitEvent("mdview:open-path", "\"a.md\"")
        assertTrue(effects.emitted.isEmpty())

        transport.markReady()
        assertEquals(listOf("mdview:open-path" to "\"a.md\""), effects.emitted)

        transport.markReady()
        assertEquals(1, effects.emitted.size)
    }

    @Test
    fun eventsEmitImmediatelyWhenAlreadyReady() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)

        transport.markReady()
        transport.emitEvent("mdview:file-changed")
        assertEquals(listOf("mdview:file-changed" to "null"), effects.emitted)
    }

    @Test
    fun resetKeepsUndispatchedEventsForTheNextReady() {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)

        transport.emitEvent("mdview:open-text", "\"hi\"")
        transport.reset()
        assertFalse(transport.isReady())

        transport.markReady()
        assertEquals(listOf("mdview:open-text" to "\"hi\""), effects.emitted)
    }

    @Test
    fun rejectNowNeedsNoRegistryEntry() {
        val effects = RecordingEffects()
        BridgeTransport(effects).rejectNow("x", "picker-busy")
        assertEquals(listOf("x" to "picker-busy"), effects.rejects)
    }

    @Test
    fun errorMessagePreservesTokens() {
        assertEquals("cancelled", BridgeTransport.errorMessage(BridgeCancelledException()))
        assertEquals("encoding-unmappable", BridgeTransport.errorMessage(BridgeException("encoding-unmappable")))
        assertEquals("plain", BridgeTransport.errorMessage(RuntimeException("plain")))
        assertEquals("IllegalStateException", BridgeTransport.errorMessage(IllegalStateException()))
    }
}
