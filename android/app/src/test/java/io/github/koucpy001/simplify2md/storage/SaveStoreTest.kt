package io.github.koucpy001.simplify2md.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save state machine (plan todo 11 acceptance (1), (5), (6) and the
 * read-back/length failure semantics).
 */
class SaveStoreTest {

    private val uri = "content://d/doc.md"
    private val original = "old content".toByteArray(Charsets.UTF_8)
    private val encoded = "new content 世界".toByteArray(Charsets.UTF_8)

    private val backupName = BackupPaths.backupName(uri)
    private val journalName = BackupPaths.journalName(uri)

    private fun store(
        files: FakeBackupFileSystem,
        reader: FakeDocumentReader,
        writer: FakeDocumentWriter,
    ) = SaveStore(files, reader, writer)

    @Test
    fun writeFailureIsRolledBackFromTheBackupAndKeepsTheJournal() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter().apply { writesToFail = 1 }
        writer.onWrite = { reader.current = it.copyOf() }

        var thrown: SaveException? = null
        try {
            store(files, reader, writer).save(uri, encoded)
        } catch (e: SaveException) {
            thrown = e
        }

        assertEquals(SaveFailure.WRITE_FAILED, thrown?.kind)
        assertEquals("rollback must write the saved backup back", listOf(original.toList()), writer.writes.map { it.toList() })
        assertArrayEquals(
            "the backup still holds the pre-save bytes",
            original,
            files.blobs.getValue(backupName),
        )
        val journal = SaveJournalCodec.decode(String(files.blobs.getValue(journalName), Charsets.UTF_8))
        assertEquals(JournalState.WRITING, journal?.state)
    }

    @Test
    fun backupWriteFailureAbortsBeforeTouchingTheTarget() {
        val files = FakeBackupFileSystem().apply { failWriteOn = backupName }
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter()

        var thrown: SaveException? = null
        try {
            store(files, reader, writer).save(uri, encoded)
        } catch (e: SaveException) {
            thrown = e
        }

        assertEquals(SaveFailure.BACKUP_FAILED, thrown?.kind)
        assertTrue("the target must never be opened without a backup", writer.writes.isEmpty())
        assertFalse(files.exists(journalName))
    }

    @Test
    fun journalWriteFailureAbortsAndNeverTouchesTheTarget() {
        val files = FakeBackupFileSystem().apply { failWriteOn = journalName }
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter()

        var thrown: SaveException? = null
        try {
            store(files, reader, writer).save(uri, encoded)
        } catch (e: SaveException) {
            thrown = e
        }

        assertEquals(SaveFailure.JOURNAL_FAILED, thrown?.kind)
        assertTrue("the target must never be written after a journal failure", writer.writes.isEmpty())
        assertFalse("the just-created backup is dropped to avoid a spurious orphan", files.exists(backupName))
    }

    @Test
    fun readBackFailureIsATreatedAsWriteFailureAndRestoresFromTheBackup() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original).apply { failReadAfter = 2 }
        val writer = FakeDocumentWriter()
        writer.onWrite = { reader.current = it.copyOf() }

        var thrown: SaveException? = null
        try {
            store(files, reader, writer).save(uri, encoded)
        } catch (e: SaveException) {
            thrown = e
        }

        assertEquals(SaveFailure.READBACK_FAILED, thrown?.kind)
        assertEquals(
            "the encoded write is followed by the rollback write",
            listOf(encoded.toList(), original.toList()),
            writer.writes.map { it.toList() },
        )
        assertTrue("the journal is kept for the next reconciliation", files.exists(journalName))
        assertTrue(files.exists(backupName))
    }

    @Test
    fun lengthAnomalyRollsBackFromTheBackup() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter()
        // No onWrite: the read-back still returns the old bytes, so the hash and
        // length disagree with the encoded result.

        var thrown: SaveException? = null
        try {
            store(files, reader, writer).save(uri, encoded)
        } catch (e: SaveException) {
            thrown = e
        }

        assertEquals(SaveFailure.LENGTH_ANOMALY, thrown?.kind)
        assertEquals(
            listOf(encoded.toList(), original.toList()),
            writer.writes.map { it.toList() },
        )
    }

    @Test
    fun legacyWriteModeTruncatesToTheEncodedLength() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter(WriteMode.LEGACY_W)
        writer.onWrite = { reader.current = it.copyOf() }

        store(files, reader, writer).save(uri, encoded)

        assertEquals(
            "the 'w' fallback does not truncate, so the code must truncate to the encoded size",
            listOf(encoded.size.toLong()),
            writer.truncations,
        )
    }

    @Test
    fun truncatingWtModeDoesNotNeedAnExplicitTruncate() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter(WriteMode.TRUNCATE_WT)
        writer.onWrite = { reader.current = it.copyOf() }

        store(files, reader, writer).save(uri, encoded)

        assertTrue("'wt' truncates at open", writer.truncations.isEmpty())
    }

    @Test
    fun successfulWriteMarksWrittenThenDeletesBackupAndJournal() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original)
        val writer = FakeDocumentWriter().apply { onWrite = { reader.current = it.copyOf() } }

        val receipt = store(files, reader, writer).save(uri, encoded)

        assertEquals(encoded.size.toLong(), receipt.len)
        assertEquals(Sha256.hex(encoded), receipt.sha256)
        assertTrue("cleanup removes the backup and the journal", files.blobs.isEmpty())

        val journalWrites = files.writesLog.filter { it.first == journalName }
        val lastState = SaveJournalCodec.decode(String(journalWrites.last().second, Charsets.UTF_8))
        assertEquals(JournalState.WRITTEN, lastState?.state)
        assertEquals(encoded.size.toLong(), lastState?.observedLen)
        assertEquals(Sha256.hex(encoded), lastState?.observedSha256)
    }

    @Test
    fun targetReadFailureAbortsBeforeWritingAnything() {
        val files = FakeBackupFileSystem()
        val reader = FakeDocumentReader(original).apply { failRead = true }
        val writer = FakeDocumentWriter()

        var thrown: SaveException? = null
        try {
            store(files, reader, writer).save(uri, encoded)
        } catch (e: SaveException) {
            thrown = e
        }

        assertEquals(SaveFailure.TARGET_UNREADABLE, thrown?.kind)
        assertTrue(writer.writes.isEmpty())
        assertTrue(files.blobs.isEmpty())
    }
}
