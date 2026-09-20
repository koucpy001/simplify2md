package io.github.koucpy001.simplify2md.storage

/** The three choices offered by the startup recovery dialog (plan todo 11f3). */
enum class RecoveryChoice {
    /** `保留当前内容` — keep the target exactly as it is. */
    KEEP_CURRENT,

    /** `用备份恢复` — overwrite the target with the shadow copy. */
    RESTORE_BACKUP,

    /** `两者都保留` — keep the target and preserve the backup as a `.kept` file. */
    KEEP_BOTH,
}

/**
 * A pending recovery question for the native dialog. [defaultChoice] is selected
 * by length (plan 11f3 / review D8) and must be honoured by the presentation;
 * nothing is applied until the user confirms.
 */
data class RecoveryQuestion(
    val uri: String,
    val journalName: String,
    val backupName: String,
    /** Actual target length, or null when the target no longer exists. */
    val actualLen: Long?,
    val expectedLen: Long,
    val backupPresent: Boolean,
    val defaultChoice: RecoveryChoice,
)

/** Result of applying a user choice from the recovery dialog. */
sealed interface ReconcileApplyResult {
    data object Done : ReconcileApplyResult
    data class Failed(val token: String, val detail: String) : ReconcileApplyResult
}

/** One applied choice, recorded in the report. */
data class AppliedRecovery(
    val uri: String,
    val choice: RecoveryChoice,
    val result: ReconcileApplyResult,
)

/** Everything the startup scan observed (plan todo 11f). */
data class ReconcileReport(
    /** Journals judged complete and cleaned silently (f1/f2). */
    val cleaned: List<String> = emptyList(),
    /** `writing` journals whose target does not match; require the three-choice dialog. */
    val questions: List<RecoveryQuestion> = emptyList(),
    /** Targets whose fingerprint could not be read; backup kept (f4). */
    val fingerprintUnavailable: List<String> = emptyList(),
    /** Unparseable journals; backup kept and journal renamed `.corrupt` (f5). */
    val corrupt: List<String> = emptyList(),
    /** `.bak` files with no journal; reported, never auto-deleted (f6). */
    val orphans: List<String> = emptyList(),
    /** `.kept` files produced by a previous "keep both" decision. */
    val kept: List<String> = emptyList(),
    /** Applied recovery choices, filled in by the coordinator. */
    val applied: List<AppliedRecovery> = emptyList(),
)

/**
 * Pure decision rules for reconciliation (plan todo 11f3, review D8/L5).
 * Extracted so "which item is the default" is unit-testable without a dialog.
 */
object ReconcileDecision {

    /**
     * Default dialog item, chosen only by length:
     *  - the actual length is >= the expected length -> `保留当前内容`
     *    (the file is at least as large as the intended write, so it is not a
     *    truncated write being trusted);
     *  - the actual length is < the expected length -> `用备份恢复`
     *    (suspected truncation);
     *  - the target is missing entirely -> `用备份恢复` (there is nothing to keep).
     *
     * This never decides by content and never auto-applies anything.
     */
    fun defaultChoice(actualLen: Long?, expectedLen: Long): RecoveryChoice = when {
        actualLen == null -> RecoveryChoice.RESTORE_BACKUP
        actualLen >= expectedLen -> RecoveryChoice.KEEP_CURRENT
        else -> RecoveryChoice.RESTORE_BACKUP
    }
}

/**
 * Startup reconciliation (plan todo 11f). Scans `filesDir/backup/` and either
 * cleans verified-complete saves or surfaces a question; it never overwrites or
 * rolls back on its own.
 *
 *   - `written`              -> complete, clean silently (f1);
 *   - `writing` + full hash matches -> complete, clean silently (f2);
 *   - `writing` + mismatch    -> [RecoveryQuestion] (f3), three choices required;
 *   - fingerprint unavailable -> backup kept, reported (f4);
 *   - journal unparseable     -> backup kept, journal renamed `.corrupt` (f5);
 *   - `.bak` with no journal  -> orphan, reported, never deleted (f6).
 */
class ReconcileEngine(
    private val files: BackupFileSystem,
    private val reader: DocumentReader,
    private val writer: DocumentWriter,
) {

    fun scan(): ReconcileReport {
        val entries = files.list()
        val journalNames = entries.filter { it.endsWith(BackupPaths.JOURNAL_SUFFIX) }
        val cleaned = mutableListOf<String>()
        val questions = mutableListOf<RecoveryQuestion>()
        val unavailable = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        val corruptHashes = mutableSetOf<String>()

        for (journalName in journalNames) {
            val journal = files.read(journalName)
                ?.let { SaveJournalCodec.decode(String(it, Charsets.UTF_8)) }
            if (journal == null) {
                // Keep the bad journal (renamed) and, crucially, keep the `.bak`
                // for manual recovery (review D6).
                files.rename(journalName, BackupPaths.corruptName(journalName))
                corrupt.add(journalName)
                corruptHashes.add(BackupPaths.hashOfJournal(journalName))
                continue
            }

            val backupName = BackupPaths.backupName(journal.uri)
            when (journal.state) {
                JournalState.WRITTEN -> {
                    // The target was written and verified before the crash.
                    clean(journalName, backupName)
                    cleaned.add(journal.uri)
                }

                JournalState.WRITING -> {
                    val fingerprint = try {
                        reader.fingerprint(journal.uri)
                    } catch (_: Exception) {
                        // Cannot verify: never delete the backup (f4).
                        unavailable.add(journal.uri)
                        continue
                    }
                    if (fingerprint != null && fingerprint.sha256 == journal.expectedSha256) {
                        // The whole encoded result is present: the save completed.
                        clean(journalName, backupName)
                        cleaned.add(journal.uri)
                    } else {
                        questions.add(
                            RecoveryQuestion(
                                uri = journal.uri,
                                journalName = journalName,
                                backupName = backupName,
                                actualLen = fingerprint?.len,
                                expectedLen = journal.expectedLen,
                                backupPresent = files.exists(backupName),
                                defaultChoice = ReconcileDecision.defaultChoice(fingerprint?.len, journal.expectedLen),
                            ),
                        )
                    }
                }
            }
        }

        val knownHashes = journalNames.map { BackupPaths.hashOfJournal(it) }.toSet()
        val orphans = entries
            .filter { it.endsWith(BackupPaths.BAK_SUFFIX) }
            .filter { bak ->
                val hash = BackupPaths.hashOfBackup(bak)
                hash !in knownHashes && hash !in corruptHashes
            }
        val kept = entries.filter { it.endsWith(BackupPaths.KEPT_SUFFIX) }

        return ReconcileReport(
            cleaned = cleaned,
            questions = questions,
            fingerprintUnavailable = unavailable,
            corrupt = corrupt,
            orphans = orphans,
            kept = kept,
        )
    }

    /**
     * Applies the user's choice. Only called after the dialog returns; the
     * engine never picks a choice itself.
     */
    fun apply(question: RecoveryQuestion, choice: RecoveryChoice): ReconcileApplyResult = try {
        when (choice) {
            RecoveryChoice.KEEP_CURRENT -> {
                files.delete(question.journalName)
                if (question.backupPresent) files.delete(question.backupName)
                ReconcileApplyResult.Done
            }

            RecoveryChoice.KEEP_BOTH -> {
                if (question.backupPresent) {
                    files.rename(question.backupName, BackupPaths.keptName(question.backupName))
                }
                files.delete(question.journalName)
                ReconcileApplyResult.Done
            }

            RecoveryChoice.RESTORE_BACKUP -> restore(question)
        }
    } catch (e: Exception) {
        ReconcileApplyResult.Failed("recovery-failed", e.message ?: "recovery failed")
    }

    private fun restore(question: RecoveryQuestion): ReconcileApplyResult {
        if (!question.backupPresent) {
            return ReconcileApplyResult.Failed("backup-missing", "no backup for ${question.uri}")
        }
        val bytes = try {
            files.read(question.backupName)
        } catch (e: Exception) {
            return ReconcileApplyResult.Failed("backup-unreadable", e.message ?: "backup unreadable")
        } ?: return ReconcileApplyResult.Failed("backup-missing", "no backup for ${question.uri}")

        // A failed restore keeps journal + backup so the next start can retry.
        try {
            writeTargetBytes(writer, question.uri, bytes)
        } catch (e: Exception) {
            return ReconcileApplyResult.Failed("restore-failed", e.message ?: "restore failed")
        }

        val verified = try {
            reader.fingerprint(question.uri)
        } catch (e: Exception) {
            return ReconcileApplyResult.Failed("restore-unverified", e.message ?: "restore unverified")
        }
        if (verified == null || verified.len != bytes.size.toLong() || verified.sha256 != Sha256.hex(bytes)) {
            return ReconcileApplyResult.Failed("restore-unverified", "restored bytes do not match the backup")
        }

        files.delete(question.journalName)
        files.delete(question.backupName)
        return ReconcileApplyResult.Done
    }

    private fun clean(journalName: String, backupName: String) {
        runCatching { files.delete(backupName) }
        runCatching { files.delete(journalName) }
    }
}
