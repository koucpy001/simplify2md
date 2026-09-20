package io.github.koucpy001.simplify2md.binding

/**
 * Back-key decision (plan todo 18a).
 *
 * The back key must NEVER exit by bypassing the frontend's existing exit guard
 * (`App.vue:1378-1380`): when the document has unsaved changes the guard emits
 * `mdview:confirm-exit` so the EXISTING frontend dialog runs; only a clean
 * document finishes the Activity. The decision is pure so it runs under plain
 * JVM JUnit; [MainActivity] only adapts it to the back-invoked callbacks.
 */
object BackKeyGuard {

    enum class Action {
        /** Document is dirty: emit `mdview:confirm-exit`, the frontend guard decides. */
        EMIT_CONFIRM_EXIT,

        /** Document is clean: finish the Activity. */
        FINISH,
    }

    fun decide(dirty: Boolean): Action =
        if (dirty) Action.EMIT_CONFIRM_EXIT else Action.FINISH
}
