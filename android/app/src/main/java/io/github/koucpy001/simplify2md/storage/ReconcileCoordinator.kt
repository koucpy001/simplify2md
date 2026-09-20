package io.github.koucpy001.simplify2md.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A non-fatal condition the startup scan wants to surface as a status hint. */
enum class ReconcileNotice {
    /** A journal could not be parsed; the backup was kept (f5). */
    CORRUPT_JOURNAL,

    /** A `.bak` has no journal; it was kept and reported (f6). */
    ORPHAN_BACKUP,

    /** The target fingerprint could not be read; the backup was kept (f4). */
    FINGERPRINT_UNAVAILABLE,

    /** The user's chosen recovery action could not be completed. */
    APPLY_FAILED,
}

/**
 * Presents one recovery question and suspends until the user picks a choice.
 * The Android implementation is a native `AlertDialog` (see
 * `AndroidRecoveryPrompt.kt`); tests provide a recording fake. This indirection
 * is what keeps the reconciliation flow free of Android types.
 */
fun interface RecoveryPrompt {
    suspend fun ask(question: RecoveryQuestion): RecoveryChoice
}

/**
 * Drives the startup reconciliation (plan todo 11f, 11j).
 *
 * It scans (IO), asks the user for every mismatched `writing` journal through
 * [prompt], and applies the picks. It never auto-applies a choice: only a
 * [RecoveryQuestion] produced by the scan triggers a dialog, and the engine
 * applies nothing until [RecoveryPrompt.ask] returns.
 *
 * Threading: the scan/apply IO runs on [Dispatchers.IO]; the prompt is
 * responsible for marshalling its own presentation (the Android dialog posts to
 * the main thread internally). [run] is therefore safe to call from the main
 * coroutine without blocking the UI thread.
 */
class ReconcileCoordinator(
    private val engine: ReconcileEngine,
    private val prompt: RecoveryPrompt,
    private val onNotice: (ReconcileNotice) -> Unit = {},
) {

    suspend fun run(): ReconcileReport {
        val report = withContext(Dispatchers.IO) { engine.scan() }
        val applied = mutableListOf<AppliedRecovery>()

        for (question in report.questions) {
            val choice = prompt.ask(question)
            val result = withContext(Dispatchers.IO) { engine.apply(question, choice) }
            applied.add(AppliedRecovery(question.uri, choice, result))
            if (result is ReconcileApplyResult.Failed) onNotice(ReconcileNotice.APPLY_FAILED)
        }

        if (report.corrupt.isNotEmpty()) onNotice(ReconcileNotice.CORRUPT_JOURNAL)
        if (report.orphans.isNotEmpty()) onNotice(ReconcileNotice.ORPHAN_BACKUP)
        if (report.fingerprintUnavailable.isNotEmpty()) onNotice(ReconcileNotice.FINGERPRINT_UNAVAILABLE)

        return report.copy(applied = applied)
    }
}
