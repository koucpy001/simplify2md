package io.github.koucpy001.simplify2md.storage

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recents model + permission-release rules (plan todo 12 acceptance). The store
 * is pure JVM: config bytes and grant release are both injected fakes.
 */
class RecentsStoreTest {

    private fun store(
        files: FakeConfigFiles = FakeConfigFiles(),
        releaser: RecordingGrantReleaser = RecordingGrantReleaser(),
        maxEntries: Int = RecentsStore.MAX_ENTRIES,
        clock: () -> Long = System::currentTimeMillis,
    ) = RecentsStore(files, releaser, maxEntries, clock)

    @Test
    fun recordPrependsDeduplicatesAndPersistsTheConfig() {
        val files = FakeConfigFiles()
        val releaser = RecordingGrantReleaser()
        var tick = 0L
        val recents = store(files, releaser, clock = { ++tick })

        recents.record(safeDocument("content://d/a", "a.md"))
        recents.record(safeDocument("content://d/b", "b.md", readonly = true))
        recents.record(safeDocument("content://d/a", "a.md"))

        assertEquals(listOf("content://d/a", "content://d/b"), recents.list().map { it.uri })
        assertEquals(3L, recents.list()[0].lastOpened)
        assertTrue("nothing is evicted, so no grant is released", releaser.released.isEmpty())

        val persisted = JSONArray(files.lastWritten!!)
        assertEquals(2, persisted.length())
        assertEquals("content://d/a", persisted.getJSONObject(0).getString(AppConfigCodec.KEY_URI))
        assertEquals("a.md", persisted.getJSONObject(0).getString(AppConfigCodec.KEY_DISPLAY_NAME))
        assertTrue(persisted.getJSONObject(1).getBoolean(AppConfigCodec.KEY_READONLY))
    }

    @Test
    fun removeDropsTheEntryReleasesItsGrantOnceAndPersists() {
        val files = FakeConfigFiles()
        val releaser = RecordingGrantReleaser()
        val recents = store(files, releaser)
        recents.record(safeDocument("content://d/a", "a.md"))
        recents.record(safeDocument("content://d/b", "b.md"))

        recents.remove("content://d/a")

        assertEquals(listOf("content://d/b"), recents.list().map { it.uri })
        assertEquals(listOf("content://d/a"), releaser.released)
        assertEquals(1, JSONArray(files.lastWritten!!).length())
    }

    @Test
    fun removeOfAnUnknownUriReleasesNothing() {
        val releaser = RecordingGrantReleaser()
        val recents = store(releaser = releaser)
        recents.record(safeDocument("content://d/a", "a.md"))

        recents.remove("content://d/missing")

        assertEquals(emptyList<String>(), releaser.released)
    }

    @Test
    fun removingTheCurrentlyOpenDocumentDoesNotReleaseItsGrant() {
        val releaser = RecordingGrantReleaser()
        val recents = store(releaser = releaser)
        recents.record(safeDocument("content://d/a", "a.md"))
        recents.record(safeDocument("content://d/b", "b.md"))
        recents.markCurrent("content://d/a")

        recents.remove("content://d/a")

        assertEquals(emptyList<String>(), releaser.released)
        assertEquals(listOf("content://d/b"), recents.list().map { it.uri })
    }

    @Test
    fun clearReleasesEveryGrantExceptTheCurrentlyOpenDocument() {
        val files = FakeConfigFiles()
        val releaser = RecordingGrantReleaser()
        val recents = store(files, releaser)
        recents.record(safeDocument("content://d/a", "a.md"))
        recents.record(safeDocument("content://d/b", "b.md"))
        recents.record(safeDocument("content://d/c", "c.md"))
        recents.markCurrent("content://d/b")

        recents.clear()

        assertEquals(listOf("content://d/c", "content://d/a"), releaser.released)
        assertTrue(recents.list().isEmpty())
        assertEquals("[]", files.lastWritten)
    }

    @Test
    fun capEvictionReleasesOnlyTheEvictedGrant() {
        val releaser = RecordingGrantReleaser()
        val recents = store(releaser = releaser, maxEntries = 2)
        recents.record(safeDocument("content://d/a", "a.md"))
        recents.record(safeDocument("content://d/b", "b.md"))
        recents.record(safeDocument("content://d/c", "c.md"))

        assertEquals(listOf("content://d/c", "content://d/b"), recents.list().map { it.uri })
        assertEquals(listOf("content://d/a"), releaser.released)
    }

    @Test
    fun capEvictionSkipsTheCurrentlyOpenDocument() {
        val releaser = RecordingGrantReleaser()
        val recents = store(releaser = releaser, maxEntries = 1)
        recents.record(safeDocument("content://d/a", "a.md"))
        recents.markCurrent("content://d/a")

        recents.record(safeDocument("content://d/b", "b.md"))

        assertEquals(listOf("content://d/b"), recents.list().map { it.uri })
        assertEquals(emptyList<String>(), releaser.released)
    }

    @Test
    fun releaseFailureDoesNotCrashOrBlockTheMutation() {
        val releaser = RecordingGrantReleaser().apply { failOn = "content://d/a" }
        val recents = store(releaser = releaser)
        recents.record(safeDocument("content://d/a", "a.md"))

        recents.remove("content://d/a")

        assertTrue(recents.list().isEmpty())
        assertTrue(releaser.released.isEmpty())
    }

    @Test
    fun configWriteFailureDoesNotThrow() {
        val files = FakeConfigFiles().apply { failWrite = true }
        val recents = store(files = files)

        recents.record(safeDocument("content://d/a", "a.md"))

        assertEquals(listOf("content://d/a"), recents.list().map { it.uri })
    }

    @Test
    fun opaqueProviderUriUsesTheDisplayNameNotTheUriTail() {
        val uri = "content://com.android.providers.downloads.documents/document/msf%3A1000000123"
        val files = FakeConfigFiles()
        val recents = store(files = files)

        recents.record(safeDocument(uri, "年度报告.md"))

        assertEquals("年度报告.md", recents.list().single().displayName)

        val persisted = JSONArray(files.lastWritten!!).getJSONObject(0)
        assertEquals("the URI is stored as the opaque id", uri, persisted.getString(AppConfigCodec.KEY_URI))
        assertEquals("年度报告.md", persisted.getString(AppConfigCodec.KEY_DISPLAY_NAME))
        assertFalse(
            "the display name must not fall back to the URI tail",
            persisted.getString(AppConfigCodec.KEY_DISPLAY_NAME) == "msf%3A1000000123",
        )
    }

    @Test
    fun staleLegacyConfigIsLoadedAndUpgradedOnTheNextWrite() {
        val files = FakeConfigFiles(content = """["content://d/old"]""")
        val recents = store(files = files)

        assertEquals(listOf("content://d/old"), recents.list().map { it.uri })

        recents.record(safeDocument("content://d/new", "new.md"))

        val persisted = JSONArray(files.lastWritten!!)
        assertEquals(2, persisted.length())
        assertEquals("content://d/new", persisted.getJSONObject(0).getString(AppConfigCodec.KEY_URI))
        assertTrue(
            "the legacy bare string must not survive as an object entry without a uri",
            persisted.getJSONObject(1).getString(AppConfigCodec.KEY_URI) == "content://d/old",
        )
    }
}
