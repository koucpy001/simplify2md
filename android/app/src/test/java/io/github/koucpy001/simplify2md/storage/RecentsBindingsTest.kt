package io.github.koucpy001.simplify2md.storage

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `GetRecents` / `RemoveRecent` / `ClearRecents` shape and release delegation
 * (plan todo 12).
 */
class RecentsBindingsTest {

    private fun fixture(
        files: FakeConfigFiles = FakeConfigFiles(),
        releaser: RecordingGrantReleaser = RecordingGrantReleaser(),
    ): Pair<RecentsStore, RecentsBindings> {
        val store = RecentsStore(files, releaser)
        return store to RecentsBindings(store)
    }

    @Test
    fun getRecentsUsesTheProviderDisplayNameForAnOpaqueUri() {
        val (store, bindings) = fixture()
        val uri = "content://com.android.providers.downloads.documents/document/msf%3A1000000123"
        store.record(safeDocument(uri, "年度报告.md"))

        val array: JSONArray = bindings.getRecents()

        assertEquals(1, array.length())
        assertEquals(uri, array.getJSONObject(0).getString(RecentsBindings.KEY_ID))
        assertEquals("年度报告.md", array.getJSONObject(0).getString(RecentsBindings.KEY_NAME))
    }

    @Test
    fun getRecentsIsMostRecentFirst() {
        val (store, bindings) = fixture()
        store.record(safeDocument("content://d/a", "a.md"))
        store.record(safeDocument("content://d/b", "b.md"))

        val array = bindings.getRecents()

        assertEquals("content://d/b", array.getJSONObject(0).getString(RecentsBindings.KEY_ID))
        assertEquals("content://d/a", array.getJSONObject(1).getString(RecentsBindings.KEY_ID))
    }

    @Test
    fun emptyListEncodesAsAnEmptyArray() {
        val (_, bindings) = fixture()
        assertEquals("[]", bindings.getRecents().toString())
    }

    @Test
    fun removeRecentDropsTheEntryAndReleasesItsGrant() {
        val releaser = RecordingGrantReleaser()
        val (store, bindings) = fixture(releaser = releaser)
        store.record(safeDocument("content://d/a", "a.md"))

        bindings.removeRecent("content://d/a")

        assertEquals(0, bindings.getRecents().length())
        assertEquals(listOf("content://d/a"), releaser.released)
    }

    @Test
    fun removeRecentOfABlankIdIsANoOp() {
        val releaser = RecordingGrantReleaser()
        val (store, bindings) = fixture(releaser = releaser)
        store.record(safeDocument("content://d/a", "a.md"))

        bindings.removeRecent("")

        assertEquals(1, bindings.getRecents().length())
        assertEquals(emptyList<String>(), releaser.released)
    }

    @Test
    fun clearRecentsReleasesEverythingButTheOpenDocument() {
        val releaser = RecordingGrantReleaser()
        val (store, bindings) = fixture(releaser = releaser)
        store.record(safeDocument("content://d/a", "a.md"))
        store.record(safeDocument("content://d/b", "b.md"))
        store.markCurrent("content://d/b")

        bindings.clearRecents()

        assertEquals(0, bindings.getRecents().length())
        assertEquals(listOf("content://d/a"), releaser.released)
    }
}
