package io.github.koucpy001.simplify2md.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draft-key identity rule (plan todo 14): `sha1(uri)` in lowercase hex, or the
 * literal `untitled`. The vectors match the frontend `sha1Hex` in
 * `App.vue:455-461`, which hashes the UTF-8 bytes with `crypto.subtle`.
 */
class DraftKeyTest {

    @Test
    fun sha1MatchesTheKnownVector() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", DraftKey.of("abc"))
        assertEquals("untitled", DraftKey.of(""))
    }

    @Test
    fun sameUriIsStableAcrossCalls() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ANotes%2Fa.md"

        assertEquals(DraftKey.of(uri), DraftKey.of(uri))
        assertEquals(40, DraftKey.of(uri).length)
        assertTrue("key must validate against the store gate", DraftStore.isValidKey(DraftKey.of(uri)))
    }

    @Test
    fun differentUrisProduceDifferentKeys() {
        val a = DraftKey.of("content://d/a.md")
        val b = DraftKey.of("content://d/b.md")

        assertNotEquals(a, b)
        assertTrue("both keys must be lowercase hex", a.matches(Regex("^[0-9a-f]{40}$")))
        assertTrue("both keys must be lowercase hex", b.matches(Regex("^[0-9a-f]{40}$")))
    }

    @Test
    fun emptyUriMapsToUntitled() {
        assertEquals("untitled", DraftKey.of(""))
        assertTrue(DraftStore.isValidKey(DraftKey.of("")))
    }

    @Test
    fun derivedKeyNeverContainsUserTextOrASeparator() {
        val uri = "content://d/../../etc/passwd"

        val key = DraftKey.of(uri)

        assertTrue(key.matches(Regex("^[0-9a-f]{40}$")))
        assertTrue("no separator may survive the digest", !key.contains("/") && !key.contains("\\"))
    }
}
