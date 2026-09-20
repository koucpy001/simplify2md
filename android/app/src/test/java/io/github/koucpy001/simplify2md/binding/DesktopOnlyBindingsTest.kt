package io.github.koucpy001.simplify2md.binding

import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DesktopOnlyBindingsTest {

    private val uri = "content://com.example.provider/document/notes.md"

    private fun bindings(
        gate: StartupFileGate = StartupFileGate(),
        onExit: () -> Unit = {},
    ): DesktopOnlyBindings = DesktopOnlyBindings(gate, DirtyFlag(), onExit)

    @Test
    fun setTitleIsANoOp() {
        val bindings = bindings()
        assertNull(bindings.setTitle())
        assertFalse(bindings.dirty.isDirty)
    }

    @Test
    fun setDirtyOnlyRecordsTheFlag() {
        val bindings = bindings()
        bindings.setDirty(true)
        assertTrue(bindings.dirty.isDirty)
        bindings.setDirty(false)
        assertFalse(bindings.dirty.isDirty)
    }

    @Test
    fun getStartupFileReturnsAPlainStringOnceThenEmpty() {
        val gate = StartupFileGate()
        gate.record(uri)
        val bindings = bindings(gate = gate)

        val first: Any = bindings.getStartupFile()
        assertTrue("GetStartupFile must stay a plain string, never {type,value}", first is String)
        assertEquals(uri, first)
        assertEquals("", bindings.getStartupFile())
        assertEquals("", bindings.getStartupFile())
    }

    @Test
    fun confirmExitInvokesTheActivityGlueExactlyOnce() {
        val exits = AtomicInteger(0)
        bindings(onExit = { exits.incrementAndGet() }).confirmExit()
        assertEquals(1, exits.get())
    }

    @Test
    fun allFiveContractBindingsAreWhitelistedAndSetStartupArgsIsNot() {
        assertTrue(BridgeMethods.isKnown(BridgeMethods.SET_TITLE))
        assertTrue(BridgeMethods.isKnown(BridgeMethods.SET_DIRTY))
        assertTrue(BridgeMethods.isKnown(BridgeMethods.GET_STARTUP_FILE))
        assertTrue(BridgeMethods.isKnown(BridgeMethods.CONFIRM_EXIT))
        assertFalse(BridgeMethods.isKnown("SetStartupArgs"))
    }
}
