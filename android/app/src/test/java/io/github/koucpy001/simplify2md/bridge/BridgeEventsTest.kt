package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEventsTest {

    @Test
    fun frozenNamesMatchTheAppVueListenersExactly() {
        assertEquals(
            setOf(
                "mdview:confirm-exit",
                "mdview:open-path",
                "mdview:file-changed",
                "mdview:ime",
            ),
            BridgeEvents.REGISTERED,
        )
    }

    @Test
    fun constantsHaveTheExpectedValues() {
        assertEquals("mdview:confirm-exit", BridgeEvents.CONFIRM_EXIT)
        assertEquals("mdview:open-path", BridgeEvents.OPEN_PATH)
        assertEquals("mdview:file-changed", BridgeEvents.FILE_CHANGED)
        assertEquals("mdview:ime", BridgeEvents.IME)
        assertEquals("null", BridgeEvents.NULL_PAYLOAD)
    }

    @Test
    fun onlyTheFrozenNamesAreRegistered() {
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.CONFIRM_EXIT))
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.OPEN_PATH))
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.FILE_CHANGED))
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.IME))
        assertFalse(BridgeEvents.isRegistered("mdview:open-text"))
        assertFalse(BridgeEvents.isRegistered("not-an-event"))
    }

    @Test
    fun imeEmitterBuildsAJsonObjectWithTheHeight() {
        val sent = mutableListOf<Pair<String, String>>()
        val events = AppEvents { name, payloadJson -> sent.add(name to payloadJson) }
        events.ime(312)
        assertEquals("mdview:ime" to "{\"height\":312}", sent.single())
        // Zero (keyboard closed) and a large value stay well-formed JSON ints.
        events.ime(0)
        assertEquals("{\"height\":0}", sent.last().second)
        events.ime(9999)
        assertEquals("{\"height\":9999}", sent.last().second)
    }
}
