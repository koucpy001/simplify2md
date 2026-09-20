package io.github.koucpy001.simplify2md.storage

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draft CRUD, key validation and modTime ordering (plan todo 14 acceptance),
 * aligned with `mdview/app_draft_test.go`.
 */
class DraftStoreTest {

    private val k1 = "a".repeat(40)
    private val k2 = "b".repeat(40)
    private val k3 = "c".repeat(40)

    private fun newStore(files: FakeDraftFileSystem = FakeDraftFileSystem()) = DraftStore(files)

    @Test
    fun roundTripThroughSaveListLoadClear() {
        val files = FakeDraftFileSystem()
        val store = newStore(files)
        val content = "# draft\n\nhello world\n"

        store.save(k1, content)

        assertEquals(listOf(k1), store.list().map { it.key })
        assertEquals(content, store.load(k1))

        store.clear(k1)

        assertEquals(emptyList<String>(), store.list().map { it.key })
        assertThrowsDraft("load after clear must fail") { store.load(k1) }
    }

    @Test
    fun untitledAnd40HexKeysAreAccepted() {
        val files = FakeDraftFileSystem()
        val store = newStore(files)

        store.save("untitled", "x")
        store.save(k1, "y")

        assertEquals(setOf("untitled.md", "$k1.md"), files.names())
    }

    @Test
    fun rejectedKeysIncludeTraversalSeparatorsAndMalformedHex() {
        val files = FakeDraftFileSystem()
        val store = newStore(files)
        val bad = listOf(
            "../../../etc/passwd",
            "..%2F",
            "..\\..\\win.ini",
            "/etc/passwd",
            "a/b",
            "a\\b",
            "short",
            "f".repeat(39),
            "f".repeat(41),
            "ABCDEF0123456789ABCDEF0123456789ABCDEF0Z",
            "A".repeat(40),
            "untitled ",
            "untitled\n",
            "",
        )

        for (key in bad) {
            assertThrowsDraft("save must reject ${quote(key)}") { store.save(key, "evil") }
            assertThrowsDraft("load must reject ${quote(key)}") { store.load(key) }
            assertThrowsDraft("clear must reject ${quote(key)}") { store.clear(key) }
        }

        assertTrue("no rejected key may reach the filesystem", files.writes.isEmpty())
        assertTrue("no rejected key may create a file", files.names().isEmpty())
        for (name in files.names()) {
            assertFalse("escape write detected: $name", name.contains(".."))
        }
    }

    @Test
    fun listOfAnEmptyStoreIsEmpty() {
        assertEquals(emptyList<DraftInfo>(), newStore().list())
    }

    @Test
    fun listIsSortedByModTimeNewestFirst() {
        val files = FakeDraftFileSystem()
        val base = 1_700_000_000_000L
        files.put("$k1.md", "1", base - 3 * 3_600_000L)
        files.put("$k2.md", "2", base - 1 * 3_600_000L)
        files.put("$k3.md", "3", base)

        assertEquals(listOf(k3, k2, k1), newStore(files).list().map { it.key })
    }

    @Test
    fun listSkipsDirectoriesNonMdFilesAndInvalidKeys() {
        val files = FakeDraftFileSystem()
        files.put("$k1.md", "kept", modTimeMs = 10L)
        files.put("notes.txt", "not a draft", modTimeMs = 20L)
        files.put("${"A".repeat(40)}.md", "uppercase", modTimeMs = 30L)
        files.put("${"f".repeat(39)}.md", "partial", modTimeMs = 40L)
        files.put("$k2.md", "directory", modTimeMs = 50L, isDirectory = true)
        files.put("$k2.md.tmp", "leftover temp", modTimeMs = 60L)

        assertEquals(listOf(k1), newStore(files).list().map { it.key })
    }

    @Test
    fun clearOfAnUnknownKeyIsAnIdempotentNoOp() {
        val files = FakeDraftFileSystem()
        val store = newStore(files)

        store.clear(k1)
        store.clear(k1)

        assertTrue(files.names().isEmpty())
    }

    @Test
    fun abandonedDraftIsNotLoadableAndNotRelisted() {
        val files = FakeDraftFileSystem()
        val store = newStore(files)
        store.save(k1, "content the user chose to abandon")
        store.save(k2, "another draft")

        store.clear(k1)

        assertFalse("cleared key must stay gone", store.list().map { it.key }.contains(k1))
        assertThrowsDraft("abandoned draft must not revive") { store.load(k1) }
        assertEquals("the other draft is untouched", "another draft", store.load(k2))
    }

    @Test
    fun equalModTimesFallBackToKeyOrderDeterministically() {
        val files = FakeDraftFileSystem()
        files.put("$k2.md", "b", modTimeMs = 1_000L)
        files.put("$k1.md", "a", modTimeMs = 1_000L)

        val store = newStore(files)

        assertEquals(listOf(k1, k2), store.list().map { it.key })
        assertEquals(listOf(k1, k2), store.list().map { it.key })
    }

    @Test
    fun contentWithNulBytesRoundTripsWithoutTruncation() {
        val files = FakeDraftFileSystem()
        val store = newStore(files)
        val content = "before\u0000after\u0000"

        store.save(k1, content)

        assertEquals(content, store.load(k1))
    }

    @Test
    fun writeFailurePropagatesSoThePromiseRejects() {
        val files = FakeDraftFileSystem().apply { failWrite = true }

        var thrown: IOException? = null
        try {
            newStore(files).save(k1, "x")
        } catch (e: IOException) {
            thrown = e
        }

        assertTrue("a disk failure must not be silently swallowed", thrown != null)
    }

    private fun assertThrowsDraft(message: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError(message)
        } catch (_: DraftException) {
            // expected
        }
    }

    private fun quote(value: String): String = "\"$value\""
}
