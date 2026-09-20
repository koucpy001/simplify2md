package io.github.koucpy001.simplify2md.storage

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SaveDraft` / `LoadDraft` / `ListDrafts` / `ClearDraft` reply shapes (plan
 * todo 14). Registration itself is constrained by `BridgeMethods.WHITELIST`
 * (asserted in `BridgeMethodsTest`), so these tests exercise the four methods
 * the handlers delegate to.
 */
class DraftBindingsTest {

    private val k1 = "a".repeat(40)
    private val k2 = "b".repeat(40)

    private fun fixture(): DraftBindings = DraftBindings(DraftStore(FakeDraftFileSystem()))

    @Test
    fun saveThenLoadRoundTripsThroughTheBindings() {
        val files = FakeDraftFileSystem()
        val store = DraftStore(files)
        val bindings = DraftBindings(store)

        bindings.saveDraft(k1, "draft body")

        assertEquals("draft body", bindings.loadDraft(k1))
    }

    @Test
    fun listDraftsReturnsKeyAndModTimeNewestFirst() {
        val files = FakeDraftFileSystem()
        files.put("$k1.md", "old", modTimeMs = 5_000L)
        files.put("$k2.md", "new", modTimeMs = 9_000L)
        val bindings = DraftBindings(DraftStore(files))

        val array: JSONArray = bindings.listDrafts()

        assertEquals(2, array.length())
        assertEquals(k2, array.getJSONObject(0).getString(DraftBindings.KEY_KEY))
        assertEquals(9L, array.getJSONObject(0).getLong(DraftBindings.KEY_MOD_TIME))
        assertEquals(k1, array.getJSONObject(1).getString(DraftBindings.KEY_KEY))
        assertEquals(5L, array.getJSONObject(1).getLong(DraftBindings.KEY_MOD_TIME))
    }

    @Test
    fun emptyListEncodesAsAnEmptyArray() {
        assertEquals("[]", fixture().listDrafts().toString())
    }

    @Test
    fun clearDraftRemovesTheDraft() {
        val files = FakeDraftFileSystem()
        val bindings = DraftBindings(DraftStore(files))
        bindings.saveDraft(k1, "x")

        bindings.clearDraft(k1)

        assertEquals("[]", bindings.listDrafts().toString())
    }

    @Test
    fun traversalKeyIsRejectedAndWritesNothing() {
        val files = FakeDraftFileSystem()
        val bindings = DraftBindings(DraftStore(files))

        var rejected = false
        try {
            bindings.saveDraft("../../../etc/passwd", "evil")
        } catch (_: DraftException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(emptySet<String>(), files.names())
    }
}
