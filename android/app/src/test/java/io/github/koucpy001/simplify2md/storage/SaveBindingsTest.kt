package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.BridgeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveBindingsTest {

    private val uri = "content://d/doc.md"

    private class Fixture {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader("old".toByteArray())
        val writer = FakeDocumentWriter().apply { onWrite = { reader.current = it.copyOf() } }
        val bindings = SaveBindings(SaveStore(files, reader, writer))
    }

    @Test
    fun encodesAndAppliesNewlineInMemoryBeforeWriting() {
        val fixture = Fixture()

        runBlocking { fixture.bindings.saveFile(uri, "line1\nline2", "utf-8", "crlf") }

        assertEquals(
            "the encoded CRLF bytes must be what reaches the document",
            "line1\r\nline2".toByteArray(Charsets.UTF_8).toList(),
            fixture.writer.writes.single().toList(),
        )
    }

    @Test
    fun rejectsUnmappableContentWithTheStableToken() {
        val fixture = Fixture()

        var thrown: BridgeException? = null
        try {
            runBlocking { fixture.bindings.saveFile(uri, "中", "iso-8859-1", "lf") }
        } catch (e: BridgeException) {
            thrown = e
        }

        assertEquals("encoding-unmappable", thrown?.token)
        assertEquals("nothing may be written on an unrepresentable save", emptyList<ByteArray>(), fixture.writer.writes)
    }

    @Test
    fun rejectsABlankPath() {
        val fixture = Fixture()

        var thrown: BridgeException? = null
        try {
            runBlocking { fixture.bindings.saveFile("  ", "x", "utf-8", "lf") }
        } catch (e: BridgeException) {
            thrown = e
        }

        assertEquals("empty path", thrown?.token)
    }
}
