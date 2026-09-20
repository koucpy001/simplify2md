package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEventsTest {

    @Test
    fun frozenNamesMatchTheAppVueListenersExactly() {
        assertEquals(
            setOf("mdview:confirm-exit", "mdview:open-path", "mdview:file-changed"),
            BridgeEvents.REGISTERED,
        )
    }

    @Test
    fun constantsHaveTheExpectedValues() {
        assertEquals("mdview:confirm-exit", BridgeEvents.CONFIRM_EXIT)
        assertEquals("mdview:open-path", BridgeEvents.OPEN_PATH)
        assertEquals("mdview:file-changed", BridgeEvents.FILE_CHANGED)
        assertEquals("null", BridgeEvents.NULL_PAYLOAD)
    }

    @Test
    fun onlyTheFrozenNamesAreRegistered() {
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.CONFIRM_EXIT))
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.OPEN_PATH))
        assertTrue(BridgeEvents.isRegistered(BridgeEvents.FILE_CHANGED))
        assertFalse(BridgeEvents.isRegistered("mdview:open-text"))
        assertFalse(BridgeEvents.isRegistered("not-an-event"))
    }
}
