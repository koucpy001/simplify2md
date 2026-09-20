package io.github.koucpy001.simplify2md.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEventsTest {

    private class RecordingEffects : BridgeTransport.Effects {
        val emitted = mutableListOf<Pair<String, String>>()

        override fun resolve(requestId: String, json: String) = Unit

        override fun reject(requestId: String, message: String) = Unit

        override fun emit(name: String, payloadJson: String) {
            emitted.add(name to payloadJson)
        }
    }

    private val emitted = mutableListOf<Pair<String, String>>()

    private fun events(): AppEvents = AppEvents { name, payload -> emitted.add(name to payload) }

    @Test
    fun confirmExitUsesTheExactListenerNameAndANullPayload() {
        events().confirmExit()
        assertEquals(listOf(BridgeEvents.CONFIRM_EXIT to "null"), emitted)
    }

    @Test
    fun fileChangedUsesTheExactListenerNameAndANullPayload() {
        events().fileChanged()
        assertEquals(listOf(BridgeEvents.FILE_CHANGED to "null"), emitted)
    }

    @Test
    fun openPathEmitsAJsonQuotedUri() {
        events().openPath("content://com.example.provider/notes.md")
        assertEquals(
            listOf(BridgeEvents.OPEN_PATH to "\"content://com.example.provider/notes.md\""),
            emitted,
        )
    }

    @Test
    fun openPathEscapesHostileCharacters() {
        events().openPath("a\"b\nc\u2028d")
        val payload = emitted.single().second
        assertEquals("\"a\\\"b\\nc\\u2028d\"", payload)
        assertEquals("a\"b\nc\u2028d", JSONArray("[$payload]").getString(0))
    }

    @Test
    fun eventsAreReadyGatedAndArriveWithTheExactNames() = runBlocking {
        val effects = RecordingEffects()
        val transport = BridgeTransport(effects)
        val events = AppEvents { name, payload -> transport.emitEvent(name, payload) }

        events.openPath("content://x")
        events.confirmExit()
        assertTrue(effects.emitted.isEmpty())

        transport.markReady()
        assertEquals(
            listOf(
                BridgeEvents.OPEN_PATH to "\"content://x\"",
                BridgeEvents.CONFIRM_EXIT to "null",
            ),
            effects.emitted,
        )

        events.fileChanged()
        assertEquals(BridgeEvents.FILE_CHANGED to "null", effects.emitted.last())
    }
}
