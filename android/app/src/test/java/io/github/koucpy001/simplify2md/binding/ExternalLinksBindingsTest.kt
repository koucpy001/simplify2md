package io.github.koucpy001.simplify2md.binding

import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import io.github.koucpy001.simplify2md.bridge.BridgeTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinksBindingsTest {

    private class RecordingEffects : BridgeTransport.Effects {
        val resolves = mutableListOf<Pair<String, String>>()
        val rejects = mutableListOf<Pair<String, String>>()

        override fun resolve(requestId: String, json: String) {
            resolves.add(requestId to json)
        }

        override fun reject(requestId: String, message: String) {
            rejects.add(requestId to message)
        }

        override fun emit(name: String, payloadJson: String) = Unit
    }

    private val launched = mutableListOf<String>()
    private val outcomes = mutableListOf<ExternalLinkOutcome>()

    private fun bindings(canLaunch: Boolean = true): ExternalLinksBindings =
        ExternalLinksBindings(
            ExternalLinks(
                ExternalUrlLauncher { url ->
                    launched.add(url)
                    canLaunch
                },
            ),
            onOutcome = { outcomes.add(it) },
        )

    @Test
    fun fireAndForgetCallAlwaysReturnsNullEvenWithNoHandler() {
        val bindings = bindings(canLaunch = false)
        assertNull(bindings.openFromBridge("https://example.com"))
        assertEquals(listOf<ExternalLinkOutcome>(ExternalLinkOutcome.NoHandler("https")), outcomes)
    }

    @Test
    fun blockedSchemeIsReportedAndNeverLaunched() {
        val bindings = bindings()
        assertNull(bindings.openFromBridge("foo://bar"))
        assertEquals(listOf<ExternalLinkOutcome>(ExternalLinkOutcome.Blocked("foo")), outcomes)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun browserOpenUrlIsWhitelistedAndTheDispatchedHandlerResolvesNull() = runBlocking {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        val bindings = bindings(canLaunch = false)
        transport.registerHandler(BridgeMethods.BROWSER_OPEN_URL) { args: JSONArray ->
            bindings.openFromBridge(args.optString(0, ""))
        }

        val decision = transport.decide("1", BridgeMethods.BROWSER_OPEN_URL, "[\"https://example.com/x\"]")
        assertTrue(decision is BridgeTransport.CallDecision.Dispatch)
        val dispatched = decision as BridgeTransport.CallDecision.Dispatch
        val result = dispatched.handler.handle(dispatched.args)

        assertTrue(transport.complete("1", Result.success(result)))
        assertEquals(listOf("1" to "null"), effects.resolves)
        assertTrue(effects.rejects.isEmpty())
        assertEquals(listOf("https://example.com/x"), launched)
    }

    @Test
    fun browserOpenUrlIsInTheFrozenWhitelist() {
        assertTrue(BridgeMethods.isKnown(BridgeMethods.BROWSER_OPEN_URL))
        assertTrue(BridgeMethods.BROWSER_OPEN_URL in BridgeMethods.WHITELIST)
    }
}
