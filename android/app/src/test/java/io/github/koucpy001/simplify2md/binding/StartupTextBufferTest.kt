package io.github.koucpy001.simplify2md.binding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTextBufferTest {

    @Test
    fun coldSharedTextIsBufferedAndConsumedExactlyOnce() {
        val buffer = StartupTextBuffer()
        assertTrue(buffer.record("shared text"))
        assertTrue(buffer.hasPending)
        assertEquals("shared text", buffer.consume())
        assertFalse(buffer.hasPending)
        assertNull(buffer.consume())
    }

    @Test
    fun emptyTextIsNeverBuffered() {
        val buffer = StartupTextBuffer()
        assertFalse(buffer.record(""))
        assertFalse(buffer.record(null))
        assertFalse(buffer.hasPending)
    }

    @Test
    fun firstTextWinsWhilePending() {
        val buffer = StartupTextBuffer()
        buffer.record("first")
        assertFalse(buffer.record("second"))
        assertEquals("first", buffer.consume())
    }

    @Test
    fun bufferIsReusableAfterConsumption() {
        val buffer = StartupTextBuffer()
        buffer.record("first")
        buffer.consume()
        assertTrue(buffer.record("second"))
        assertEquals("second", buffer.consume())
    }
}
