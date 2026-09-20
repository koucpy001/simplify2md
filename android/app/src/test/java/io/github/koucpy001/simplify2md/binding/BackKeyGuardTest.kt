package io.github.koucpy001.simplify2md.binding

import org.junit.Assert.assertEquals
import org.junit.Test

class BackKeyGuardTest {

    @Test
    fun dirtyDocumentEmitsConfirmExitInsteadOfFinishing() {
        assertEquals(BackKeyGuard.Action.EMIT_CONFIRM_EXIT, BackKeyGuard.decide(dirty = true))
    }

    @Test
    fun cleanDocumentFinishes() {
        assertEquals(BackKeyGuard.Action.FINISH, BackKeyGuard.decide(dirty = false))
    }
}
