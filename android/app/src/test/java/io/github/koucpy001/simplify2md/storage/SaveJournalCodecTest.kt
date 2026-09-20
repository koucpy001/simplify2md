package io.github.koucpy001.simplify2md.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveJournalCodecTest {

    private val uri = "content://d/doc.md"

    @Test
    fun roundTripsAWritingJournal() {
        val journal = SaveJournal(uri, JournalState.WRITING, 42L, "abc123")

        val decoded = SaveJournalCodec.decode(SaveJournalCodec.encode(journal))

        assertEquals(journal, decoded)
    }

    @Test
    fun roundTripsAWrittenJournalWithObservedValues() {
        val journal = SaveJournal(uri, JournalState.WRITTEN, 42L, "abc123", 42L, "def456")

        val decoded = SaveJournalCodec.decode(SaveJournalCodec.encode(journal))

        assertEquals(journal, decoded)
        assertEquals(JournalState.WRITTEN, decoded?.state)
    }

    @Test
    fun usesTheExactWireStateStrings() {
        assertEquals("writing", JournalState.WRITING.wire)
        assertEquals("written", JournalState.WRITTEN.wire)
        assertEquals(JournalState.WRITING, JournalState.fromWire("writing"))
        assertEquals(JournalState.WRITTEN, JournalState.fromWire("written"))
    }

    @Test
    fun returnsNullForUnparseableJson() {
        assertNull(SaveJournalCodec.decode("{ this is not json"))
    }

    @Test
    fun returnsNullForAnUnknownState() {
        assertNull(SaveJournalCodec.decode("""{"uri":"$uri","state":"halfway","expectedLen":1,"expectedSha256":"aa"}"""))
    }

    @Test
    fun returnsNullForABlankUriOrMissingHash() {
        assertNull(SaveJournalCodec.decode("""{"uri":"","state":"writing","expectedLen":1,"expectedSha256":"aa"}"""))
        assertNull(SaveJournalCodec.decode("""{"uri":"$uri","state":"writing","expectedLen":1,"expectedSha256":""}"""))
    }

    @Test
    fun backupNamesAreKeyedByTheUriSha256() {
        assertEquals(Sha256.hex(uri.toByteArray(Charsets.UTF_8)) + ".bak", BackupPaths.backupName(uri))
        assertEquals(Sha256.hex(uri.toByteArray(Charsets.UTF_8)) + ".json", BackupPaths.journalName(uri))
    }

    @Test
    fun hashNamesAreStableAndContentSensitive() {
        assertEquals(Sha256.hex("abc".toByteArray()), Sha256.hex("abc".toByteArray()))
        assertNotEquals(Sha256.hex("abc".toByteArray()), Sha256.hex("abd".toByteArray()))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray()),
        )
    }
}
