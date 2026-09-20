package io.github.koucpy001.simplify2md.binding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two branches the plan makes mandatory: absent -> "", present -> the URI
 * exactly once and "" on the second call.
 */
class StartupFileGateTest {

    private val uri = "content://com.example.provider/document/notes.md"

    @Test
    fun absentBranchAnswersEmptyString() {
        assertEquals("", StartupFileGate().consume())
    }

    @Test
    fun presentBranchAnswersTheUriOnceThenEmpty() {
        val gate = StartupFileGate()
        assertTrue(gate.record(uri))

        assertEquals(uri, gate.consume())
        assertEquals("", gate.consume())
    }

    @Test
    fun nullBlankAndWhitespaceUrisAreIgnored() {
        val gate = StartupFileGate()
        assertFalse(gate.record(null))
        assertFalse(gate.record(""))
        assertFalse(gate.record("   "))
        assertEquals("", gate.consume())
    }

    @Test
    fun theFirstUriWins() {
        val gate = StartupFileGate()
        gate.record(uri)
        gate.record("content://com.example.provider/document/other.md")
        assertEquals(uri, gate.consume())
    }

    @Test
    fun recordingAgainAfterConsumeWorks() {
        val gate = StartupFileGate()
        gate.record(uri)
        assertEquals(uri, gate.consume())

        assertTrue(gate.record("content://com.example.provider/document/second.md"))
        assertEquals("content://com.example.provider/document/second.md", gate.consume())
    }

    @Test
    fun hasPendingTracksTheConsumeOnceWindow() {
        val gate = StartupFileGate()
        assertFalse(gate.hasPending)
        gate.record(uri)
        assertTrue(gate.hasPending)
        gate.consume()
        assertFalse(gate.hasPending)
    }
}
