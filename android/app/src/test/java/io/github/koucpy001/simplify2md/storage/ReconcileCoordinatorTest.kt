package io.github.koucpy001.simplify2md.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinator flow: every mismatched `writing` journal becomes exactly one
 * dialog, and nothing is applied without the prompt returning a choice.
 */
class ReconcileCoordinatorTest {

    private class RecordingPrompt(
        var choice: RecoveryChoice = RecoveryChoice.KEEP_CURRENT,
    ) : RecoveryPrompt {
        val asked = mutableListOf<RecoveryQuestion>()

        override suspend fun ask(question: RecoveryQuestion): RecoveryChoice {
            asked.add(question)
            return choice
        }
    }

    private val uri = "content://d/doc.md"
    private val original = "old content".toByteArray(Charsets.UTF_8)
    private val encoded = "new content 世界".toByteArray(Charsets.UTF_8)

    private fun differentBytesSameLength(): ByteArray {
        val copy = encoded.copyOf()
        copy[0] = (copy[0].toInt() xor 0x01).toByte()
        return copy
    }

    @Test
    fun asksOncePerMismatchAndAppliesTheChoice() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(BackupPaths.backupName(uri), original)
        files.writeAtomic(
            BackupPaths.journalName(uri),
            journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)),
        )
        val reader = FakeDocumentReader(differentBytesSameLength())
        val writer = FakeDocumentWriter().apply { onWrite = { reader.current = it.copyOf() } }
        val prompt = RecordingPrompt(RecoveryChoice.RESTORE_BACKUP)

        val report = runBlocking {
            ReconcileCoordinator(ReconcileEngine(files, reader, writer), prompt).run()
        }

        assertEquals(1, prompt.asked.size)
        assertEquals(RecoveryChoice.RESTORE_BACKUP, report.applied.single().choice)
        assertEquals(ReconcileApplyResult.Done, report.applied.single().result)
        assertTrue(files.blobs.isEmpty())
    }

    @Test
    fun doesNotAskWhenEverythingVerifies() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(BackupPaths.backupName(uri), original)
        files.writeAtomic(
            BackupPaths.journalName(uri),
            journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)),
        )
        val prompt = RecordingPrompt()

        val report = runBlocking {
            ReconcileCoordinator(ReconcileEngine(files, FakeDocumentReader(encoded), FakeDocumentWriter()), prompt).run()
        }

        assertTrue(prompt.asked.isEmpty())
        assertEquals(listOf(uri), report.cleaned)
        assertTrue(report.applied.isEmpty())
    }

    @Test
    fun handsTheLengthBasedDefaultChoiceToThePrompt() {
        val files = FakeBackupFileSystem()
        files.writeAtomic(BackupPaths.backupName(uri), original)
        files.writeAtomic(
            BackupPaths.journalName(uri),
            journalBytes(uri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)),
        )
        val prompt = RecordingPrompt(RecoveryChoice.KEEP_CURRENT)

        runBlocking {
            ReconcileCoordinator(
                ReconcileEngine(files, FakeDocumentReader(differentBytesSameLength()), FakeDocumentWriter()),
                prompt,
            ).run()
        }

        assertEquals(RecoveryChoice.KEEP_CURRENT, prompt.asked.single().defaultChoice)
    }

    @Test
    fun surfacesNoticesForCorruptOrphanAndUnavailableFingerprint() {
        val corruptUri = "content://d/corrupt.md"
        val unavailableUri = "content://d/unavailable.md"
        val orphanUri = "content://d/orphan.md"

        val files = FakeBackupFileSystem()
        files.writeAtomic(BackupPaths.backupName(corruptUri), original)
        files.writeAtomic(BackupPaths.journalName(corruptUri), "{bad".toByteArray(Charsets.UTF_8))
        files.writeAtomic(BackupPaths.backupName(unavailableUri), original)
        files.writeAtomic(
            BackupPaths.journalName(unavailableUri),
            journalBytes(unavailableUri, JournalState.WRITING, encoded.size.toLong(), Sha256.hex(encoded)),
        )
        files.writeAtomic(BackupPaths.backupName(orphanUri), original)

        val reader = FakeDocumentReader(encoded).apply { failFingerprint = true }
        val notices = mutableListOf<ReconcileNotice>()

        runBlocking {
            ReconcileCoordinator(
                ReconcileEngine(files, reader, FakeDocumentWriter()),
                RecordingPrompt(),
                onNotice = { notices.add(it) },
            ).run()
        }

        assertTrue(notices.contains(ReconcileNotice.CORRUPT_JOURNAL))
        assertTrue(notices.contains(ReconcileNotice.ORPHAN_BACKUP))
        assertTrue(notices.contains(ReconcileNotice.FINGERPRINT_UNAVAILABLE))
    }
}
