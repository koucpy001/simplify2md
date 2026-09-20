package io.github.koucpy001.simplify2md.storage

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config JSON shape gate (plan todo 12): `[{uri, displayName, lastOpened,
 * readonly}]`, tolerant of a corrupt or older file.
 */
class AppConfigCodecTest {

    @Test
    fun roundTripsEveryField() {
        val entries = listOf(
            RecentEntry("content://d/1", "报告.md", 1_700_000_000_000L, readonly = true),
            RecentEntry("/docs/note.md", "note.md", 1_600_000_000_000L, readonly = false),
        )

        val decoded = AppConfigCodec.decode(AppConfigCodec.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun encodesTheExactDocumentedKeys() {
        val json = JSONArray(
            AppConfigCodec.encode(listOf(RecentEntry("content://d/1", "报告.md", 42L, true))),
        )
        val entry = json.getJSONObject(0)

        assertEquals("content://d/1", entry.getString(AppConfigCodec.KEY_URI))
        assertEquals("报告.md", entry.getString(AppConfigCodec.KEY_DISPLAY_NAME))
        assertEquals(42L, entry.getLong(AppConfigCodec.KEY_LAST_OPENED))
        assertTrue(entry.getBoolean(AppConfigCodec.KEY_READONLY))
    }

    @Test
    fun missingOrCorruptConfigYieldsAnEmptyList() {
        assertEquals(emptyList<RecentEntry>(), AppConfigCodec.decode(null))
        assertEquals(emptyList<RecentEntry>(), AppConfigCodec.decode(""))
        assertEquals(emptyList<RecentEntry>(), AppConfigCodec.decode("  "))
        assertEquals(emptyList<RecentEntry>(), AppConfigCodec.decode("not json at all"))
        assertEquals(emptyList<RecentEntry>(), AppConfigCodec.decode("{\"recentFiles\":[]}"))
    }

    @Test
    fun olderEntryShapeWithoutOptionalFieldsIsUpgraded() {
        val decoded = AppConfigCodec.decode(
            """[{"uri":"content://d/1","displayName":"a.md"},{"uri":"content://d/2"}]""",
        )

        assertEquals(2, decoded.size)
        assertEquals(RecentEntry("content://d/1", "a.md", 0L, false), decoded[0])
        assertEquals(RecentEntry("content://d/2", "", 0L, false), decoded[1])
    }

    @Test
    fun legacyArrayOfStringsIsUpgradedToEntries() {
        val decoded = AppConfigCodec.decode("""["content://d/1","/docs/note.md"]""")

        assertEquals(
            listOf(
                RecentEntry("content://d/1", "", 0L, false),
                RecentEntry("/docs/note.md", "", 0L, false),
            ),
            decoded,
        )
    }

    @Test
    fun entriesWithoutAUsableUriAreDropped() {
        val decoded = AppConfigCodec.decode(
            """[{"displayName":"nameless"},{"uri":"   "},{"uri":"content://d/ok","displayName":"ok.md"}]""",
        )

        assertEquals(listOf(RecentEntry("content://d/ok", "ok.md", 0L, false)), decoded)
    }

    @Test
    fun nonObjectElementsAreIgnored() {
        val decoded = AppConfigCodec.decode("""[42,true,null,{"uri":"content://d/ok"}]""")

        assertEquals(listOf(RecentEntry("content://d/ok", "", 0L, false)), decoded)
    }
}
