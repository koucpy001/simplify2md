package io.github.koucpy001.simplify2md.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup reconciliation (plan todo 11 acceptance (2), (3), (4), (7) plus the
 * f4/f5/f6 edge states). Nothing here is ever auto-applied.
 */
class ReconcileEngineTest {

    private val uri = "content://d/doc.md"
    private val original = "old content".toByteArray(Charsets.UTF_8)
    private val encoded = "new content 世界".toByteArray(Charsets.UTF_8)

    private val backupName = BackupPaths.backupName(uri)
    private val journalName = BackupPaths.journalName(uri)

    private fun engine(
        files: FakeBackupFileSystem,
        reader: FakeDocumentReader,
        writer: FakeDocumentWriter,
    ) = ReconcileEngine(files, reader, writer)

    private fun differentBytesSameLength(): ByteArray {
        val copy = encoded.copyOf()
        copy[0] = (copy[0].toInt() xor 0x01).toByte()
        return copy
    }

    @Test
    fun writtenStateIsCleanedSilently() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(
            journalName,
            journalBytes(uri, JournalState.WRITTEN, encoded.size.toLong(), Sha256.hex(encoded), encoded.size.toLong(), Sha256.hex(encoded)),
        )
        val writer = FakeDocumentWriter()

        val report = engine(files, FakeDocumentReader(encoded), writer).scan()

        assertEquals(listOf(uri), report.cleaned)
        assertTrue("a written journal is complete; both files are cleaned", files.blobs.isEmpty())
        assertTrue(report.questions.isEmpty())
        assertTrue(writer.writes.isEmpty())
    }

    @Test
    fun writingWithConsistentFullHashIsCleaned() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val writer = FakeDocumentWriter()

        val report = engine(files, FakeDocumentReader(encoded), writer).scan()

        assertEquals(listOf(uri), report.cleaned)
        assertTrue(files.blobs.isEmpty())
        assertTrue(writer.writes.isEmpty())
    }

    @Test
    fun sameLengthDifferentContentIsIncompleteAndNeverAutoRolledBack() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val writer = FakeDocumentWriter()

        val report = engine(files, FakeDocumentReader(differentBytesSameLength()), writer).scan()

        assertEquals("a same-length mismatch must become a question", 1, report.questions.size)
        assertEquals(uri, report.questions.single().uri)
        assertTrue("the scan must not roll back on its own", writer.writes.isEmpty())
        assertTrue("the backup is kept until the user decides", files.exists(backupName))
        assertEquals(
            "equal length (not truncated) defaults to keeping the current content",
            RecoveryChoice.KEEP_CURRENT,
            report.questions.single().defaultChoice,
        )
    }

    @Test
    fun defaultChoiceIsDecidedOnlyByLength() {
        assertEquals(RecoveryChoice.KEEP_CURRENT, ReconcileDecision.defaultChoice(actualLen = 10, expectedLen = 10))
        assertEquals(RecoveryChoice.KEEP_CURRENT, ReconcileDecision.defaultChoice(actualLen = 11, expectedLen = 10))
        assertEquals(RecoveryChoice.RESTORE_BACKUP, ReconcileDecision.defaultChoice(actualLen = 9, expectedLen = 10))
        assertEquals(RecoveryChoice.RESTORE_BACKUP, ReconcileDecision.defaultChoice(actualLen = null, expectedLen = 10))
        assertEquals(RecoveryChoice.KEEP_CURRENT, ReconcileDecision.defaultChoice(actualLen = 0, expectedLen = 0))
    }

    @Test
    fun missingTargetDefaultsToRestore() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))

        val question = engine(files, FakeDocumentReader(null), FakeDocumentWriter()).scan().questions.single()

        assertEquals(RecoveryChoice.RESTORE_BACKUP, question.defaultChoice)
        assertEquals(null, question.actualLen)
    }

    @Test
    fun fingerprintUnavailableKeepsTheBackup() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val reader = FakeDocumentReader(encoded).apply { failFingerprint = true }

        val report = engine(files, reader, FakeDocumentWriter()).scan()

        assertEquals(listOf(uri), report.fingerprintUnavailable)
        assertTrue("an unverifiable save never deletes the backup", files.exists(backupName))
        assertTrue(files.exists(journalName))
    }

    @Test
    fun unparseableJournalIsRenamedCorruptAndTheBackupIsKept() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, "{not-json".toByteArray(Charsets.UTF_8))

        val report = engine(files, FakeDocumentReader(original), FakeDocumentWriter()).scan()

        assertEquals(listOf(journalName), report.corrupt)
        assertTrue("the bad journal is kept with a .corrupt suffix", files.exists(BackupPaths.corruptName(journalName)))
        assertFalse(files.exists(journalName))
        assertTrue("the backup is preserved for manual recovery", files.exists(backupName))
    }

    @Test
    fun orphanBackupIsReportedAndNotDeleted() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)

        val report = engine(files, FakeDocumentReader(original), FakeDocumentWriter()).scan()

        assertEquals(listOf(backupName), report.orphans)
        assertTrue("an orphan backup is surfaced, never silently deleted", files.exists(backupName))
    }

    @Test
    fun restoreBackupAppliesTheUserChoice() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val reader = FakeDocumentReader(differentBytesSameLength())
        val writer = FakeDocumentWriter().apply { onWrite = { reader.current = it.copyOf() } }
        val engine = engine(files, reader, writer)
        val question = engine.scan().questions.single()

        val result = engine.apply(question, RecoveryChoice.RESTORE_BACKUP)

        assertEquals(ReconcileApplyResult.Done, result)
        assertEquals(original.toList(), writer.writes.single().toList())
        assertTrue(files.blobs.isEmpty())
    }

    @Test
    fun keepCurrentDeletesJournalAndBackup() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val writer = FakeDocumentWriter()
        val engine = engine(files, FakeDocumentReader(differentBytesSameLength()), writer)
        val question = engine.scan().questions.single()

        val result = engine.apply(question, RecoveryChoice.KEEP_CURRENT)

        assertEquals(ReconcileApplyResult.Done, result)
        assertTrue(files.blobs.isEmpty())
        assertTrue(writer.writes.isEmpty())
    }

    @Test
    fun keepBothPreservesTheBackupAsKept() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(backupName, original)
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val engine = engine(files, FakeDocumentReader(differentBytesSameLength()), FakeDocumentWriter())
        val question = engine.scan().questions.single()

        val result = engine.apply(question, RecoveryChoice.KEEP_BOTH)

        assertEquals(ReconcileApplyResult.Done, result)
        assertEquals(listOf(BackupPaths.keptName(backupName)), reportKept(files))
        assertFalse(files.exists(journalName))
        assertFalse(files.exists(backupName))
    }

    @Test
    fun restoreWithoutABackupFailsAndKeepsTheJournal() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(journalName, journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)))
        val engine = engine(files, FakeDocumentReader(differentBytesSameLength()), FakeDocumentWriter())
        val question = engine.scan().questions.single()

        val result = engine.apply(question, RecoveryChoice.RESTORE_BACKUP)

        assertEquals(ReconcileApplyResult.Failed("backup-missing", "no backup for $uri"), result)
        assertTrue(files.exists(journalName))
    }

    private fun reportKept(files: FakeBackupFileSystem): List<String> =
        files.list().filter { it.endsWith(BackupPaths.KEPT_SUFFIX) }
}
