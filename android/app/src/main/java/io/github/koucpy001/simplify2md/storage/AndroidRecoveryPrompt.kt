package io.github.koucpy001.simplify2md.storage

import android.app.Activity
import android.app.AlertDialog
import io.github.koucpy001.simplify2md.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Kotlin-native reconciliation dialog (plan todo 11f3, review D1).
 *
 * Reconciliation runs at Activity start, before the WebView exists and before
 * JS is ready, so the choice cannot travel through a JS event and no bridge
 * function may be added. The user decides inside this native `AlertDialog`;
 * the pure rules live in [ReconcileDecision] and are unit-tested separately.
 *
 * Exactly three choices are offered, always with [RecoveryQuestion.defaultChoice]
 * preselected (by length). The dialog is not cancelable, so there is no path
 * that applies a recovery without an explicit tap; dismissing the Activity
 * cancels the coroutine instead.
 */
class AndroidRecoveryPrompt(private val activity: Activity) : RecoveryPrompt {

    override suspend fun ask(question: RecoveryQuestion): RecoveryChoice =
        suspendCancellableCoroutine { continuation ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    // The Activity is going away; do NOT auto-apply a choice.
                    continuation.cancel()
                    return@runOnUiThread
                }

                val labels = arrayOf(
                    activity.getString(R.string.save_recovery_keep_current),
                    activity.getString(R.string.save_recovery_restore_backup),
                    activity.getString(R.string.save_recovery_keep_both),
                )
                var selected = question.defaultChoice.ordinal

                val dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.save_recovery_title)
                    .setMessage(activity.getString(R.string.save_recovery_message, question.uri))
                    .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
                    .setCancelable(false)
                    .setPositiveButton(R.string.save_recovery_confirm) { _, _ ->
                        continuation.resume(RecoveryChoice.entries[selected])
                    }
                    .create()

                continuation.invokeOnCancellation {
                    activity.runOnUiThread { dialog.dismiss() }
                }
                dialog.show()
            }
        }
}
