package io.github.koucpy001.simplify2md.binding

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the document's modified flag reported by the frontend through `SetDirty`.
 *
 * On desktop, `SetDirty` is consumed by the Go `beforeClose` hook to decide
 * whether closing the window must first ask the frontend to confirm. Android has
 * no window-close hook: the equivalent is the back key, whose guard is owned by
 * todo 18. This class only *records* the flag — it deliberately contains no
 * state machine, so the frontend's existing exit guard (`App.vue:1378-1380`)
 * stays the single source of truth.
 *
 * Pure JVM: no Android dependency.
 */
class DirtyFlag {

    private val dirty = AtomicBoolean(false)

    fun set(value: Boolean) {
        dirty.set(value)
    }

    val isDirty: Boolean get() = dirty.get()
}
