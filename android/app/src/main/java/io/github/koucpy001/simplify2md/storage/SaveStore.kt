package io.github.koucpy001.simplify2md.storage

/**
 * A failed save, carrying a stable machine-readable [kind] plus a technical
 * (English) message for the log/status bar.
 */
class SaveException(
    val kind: SaveFailure,
    detail: String,
    cause: Throwable? = null,
) : Exception("${kind.token}: $detail", cause)

/** Failure classes of the save state machine. Tokens are stable and asserted by tests. */
enum class SaveFailure(val token: String) {
    /** The target could not be read before writing, so no backup could be made. */
    TARGET_UNREADABLE("target-unreadable"),

    /** The shadow copy could not be written; the target was never touched. */
    BACKUP_FAILED("backup-failed"),

    /** The journal could not be written; the target was never touched. */
    JOURNAL_FAILED("journal-failed"),

    /** The target write threw; a rollback was attempted. */
    WRITE_FAILED("write-failed"),

    /** The target could not be read back; treated as a write failure (review D7). */
    READBACK_FAILED("readback-failed"),

    /** The read-back length/hash disagreed with the encoded result. */
    LENGTH_ANOMALY("length-anomaly"),

    /** The rollback itself failed; journal + backup are kept for reconciliation. */
    ROLLBACK_FAILED("rollback-failed"),
}

/** A successful save: what was written and the full hash that was verified. */
data class SaveReceipt(val uri: String, val len: Long, val sha256: String)

/**
 * The save write-failure-rollback state machine (plan todo 11a-e, g).
 *
 * Order of operations, and why each step is where it is:
 *
 *  1. read the target's current bytes — the *on-disk* content, never the
 *     editor's in-memory text — and copy them to `<hash>.bak`;
 *  2. journal `writing` with the full expected hash/length;
 *  3. write the target (`"wt"`, or `"w"` + explicit truncate);
 *  4. immediately read the target back and compare full length + hash;
 *  5. journal `written` with the observed values, then delete backup + journal.
 *
 * If the backup or the journal cannot be written the save aborts before the
 * target is opened (step 1/2 failures): writing the target without a backup in
 * place is the one thing this design must never do.
 *
 * On any write/read-back/length failure the target is restored from the backup
 * and the exception is re-thrown with the failure kind. The journal and backup
 * are deliberately **kept** in that case so the next Activity start can
 * reconcile: an in-session rollback can itself fail, and a startup check that
 * finds `writing` with a mismatched target will ask the user rather than trust
 * the rollback silently.
 *
 * All IO is behind [DocumentReader]/[DocumentWriter]/[BackupFileSystem], so the
 * whole machine is exercised by plain JUnit fakes.
 */
class SaveStore(
    private val files: BackupFileSystem,
    private val reader: DocumentReader,
    private val writer: DocumentWriter,
) {

    fun save(uri: String, encoded: ByteArray): SaveReceipt {
        val expectedLen = encoded.size.toLong()
        val expectedSha = Sha256.hex(encoded)

        // (1) Snapshot the current on-disk bytes. A read failure aborts before
        // anything is written: without this snapshot there is no rollback.
        val current = try {
            reader.read(uri)
        } catch (e: Exception) {
            throw SaveException(SaveFailure.TARGET_UNREADABLE, "cannot read target before backup", e)
        }
        val backupBytes = current ?: ByteArray(0)
        val backupName = BackupPaths.backupName(uri)
        val journalName = BackupPaths.journalName(uri)

        try {
            files.writeAtomic(backupName, backupBytes)
        } catch (e: Exception) {
            throw SaveException(SaveFailure.BACKUP_FAILED, "cannot create backup; save aborted", e)
        }

        val writing = SaveJournal(uri, JournalState.WRITING, expectedLen, expectedSha)
        try {
            files.writeAtomic(journalName, SaveJournalCodec.encode(writing).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // The target has not been touched; drop the backup we just made so
            // the next start does not report a spurious orphan backup.
            runCatching { files.delete(backupName) }
            throw SaveException(SaveFailure.JOURNAL_FAILED, "cannot write journal; save aborted", e)
        }

        // (3) Write the target.
        try {
            writeTargetBytes(writer, uri, encoded)
        } catch (e: Exception) {
            throw rollback(uri, backupBytes, SaveFailure.WRITE_FAILED, e)
        }

        // (4) Read the target back immediately. A read-back that throws is a
        // write failure, not a success (review D7): the target may be truncated.
        val observed = try {
            reader.read(uri)
        } catch (e: Exception) {
            throw rollback(uri, backupBytes, SaveFailure.READBACK_FAILED, e)
        }

        if (observed == null || observed.size.toLong() != expectedLen || Sha256.hex(observed) != expectedSha) {
            throw rollback(uri, backupBytes, SaveFailure.LENGTH_ANOMALY, null)
        }

        // (5) Mark written (atomically), then clean up. A crash between these
        // two steps leaves a `written` journal, which reconciles as complete.
        val written = writing.copy(
            state = JournalState.WRITTEN,
            observedLen = observed.size.toLong(),
            observedSha256 = Sha256.hex(observed),
        )
        runCatching {
            files.writeAtomic(journalName, SaveJournalCodec.encode(written).toByteArray(Charsets.UTF_8))
        }
        runCatching { files.delete(backupName) }
        runCatching { files.delete(journalName) }

        return SaveReceipt(uri, expectedLen, expectedSha)
    }

    /**
     * Restores [backupBytes] over the target and reports a [SaveException].
     *
     * A rollback failure returns [SaveFailure.ROLLBACK_FAILED] and leaves the
     * journal/backup in place; the next reconciliation can still recover.
     */
    private fun rollback(
        uri: String,
        backupBytes: ByteArray,
        kind: SaveFailure,
        cause: Throwable?,
    ): SaveException = try {
        writeTargetBytes(writer, uri, backupBytes)
        SaveException(kind, cause?.message ?: "target write failed; restored from backup", cause)
    } catch (e: Exception) {
        SaveException(SaveFailure.ROLLBACK_FAILED, "restore from backup failed: ${e.message}", e)
    }
}
