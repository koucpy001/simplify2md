package io.github.koucpy001.simplify2md.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMethodsTest {

    @Test
    fun whitelistIsExactlyTheDispatchableNames() {
        val expected = setOf(
            "OpenFile",
            "SaveFile",
            "PickSavePath",
            "LoadImageForSrc",
            "ReadFileAt",
            "GetRecents",
            "GetStartupFile",
            "RemoveRecent",
            "SetDirty",
            "SetTitle",
            "ConfirmExit",
            "CheckForUpdate",
            "SaveDraft",
            "LoadDraft",
            "ListDrafts",
            "ClearDraft",
            "ClearRecents",
            "BrowserOpenURL",
        )
        assertEquals(expected, BridgeMethods.WHITELIST)
        assertEquals(18, BridgeMethods.WHITELIST.size)
        assertEquals(17, BridgeMethods.APP_FUNCTION_COUNT)
    }

    @Test
    fun unusedGoExportIsNotDispatchable() {
        assertFalse(BridgeMethods.isKnown("SetStartupArgs"))
    }

    @Test
    fun isKnownIsCaseSensitiveAndRejectsUnknownNames() {
        assertTrue(BridgeMethods.isKnown("OpenFile"))
        assertFalse(BridgeMethods.isKnown("openfile"))
        assertFalse(BridgeMethods.isKnown(""))
        assertFalse(BridgeMethods.isKnown("__bridgeCall"))
        assertFalse(BridgeMethods.isKnown("Class.forName"))
    }
}
