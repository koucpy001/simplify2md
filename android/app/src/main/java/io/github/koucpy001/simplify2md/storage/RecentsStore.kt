package io.github.koucpy001.simplify2md.storage

/**
 * Persistence for the recents model (plan todo 12). All IO goes through this
 * interface so [RecentsStore] runs under plain JVM JUnit: the Android
 * implementation writes `filesDir/config.json` (see `AndroidRecents.kt`), a
 * fake keeps the bytes in memory in tests.
 */
interface ConfigFileSystem {
    /** The current config text, or null when the file does not exist yet. */
    fun read(): String?

    /** Replaces the config content. Called after every mutation. */
    fun write(json: String)
}

/**
 * Releases a persisted SAF URI grant. Entries that are plain paths (desktop
 * shape) have no grant; a provider that never issued a persistable grant also
 * throws, so callers must treat a failure as best-effort.
 */
fun interface UriGrantReleaser {
    fun release(uri: String)
}

/**
 * The seam the SAF flow uses to keep recents and the "currently open document"
 * in sync (plan todo 12). Recording happens only after a document was actually
 * read, so a failed open or a cancelled picker never pollutes the list.
 */
interface RecentsSink {
    /** Records [document] as the most recent entry (provider display name). */
    fun record(document: SafDocument)

    /** Marks [uri] as the document currently open in the editor. */
    fun markCurrent(uri: String)
}

/** No-op sink: the desktop-shaped default when recents are not wired. */
object NoRecents : RecentsSink {
    override fun record(document: SafDocument) = Unit

    override fun markCurrent(uri: String) = Unit
}

/**
 * URI-based recents with permission release (plan todo 12).
 *
 * Invariants, each backed by a JVM test:
 *
 *  - `record` prepends the entry, de-duplicates by URI and caps the list; the
 *    entry evicted by the cap is the only one released.
 *  - `remove`/`clear` release the dropped entries' grants **except the currently
 *    open document**: releasing that grant mid-session would revoke write
 *    permission, breaking save, foreground refresh and image resolution.
 *  - a release failure (provider has no persistable grant, or a non-SAF path)
 *    never crashes and never blocks the list mutation.
 *  - a config write failure is swallowed: recents are convenience state, not
 *    something that may fail an open.
 *
 * The list is most-recent-first, matching the desktop Go config
 * (`mdview/app.go:recordRecent`).
 */
class RecentsStore(
    private val files: ConfigFileSystem,
    private val releaser: UriGrantReleaser,
    private val maxEntries: Int = MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) : RecentsSink {

    private var entries: List<RecentEntry> = AppConfigCodec.decode(files.read())
    private var currentUri: String? = null

    /** Most recent first. */
    @Synchronized
    fun list(): List<RecentEntry> = entries

    /** The document currently open in the editor, if any. */
    @Synchronized
    fun current(): String? = currentUri

    @Synchronized
    override fun markCurrent(uri: String) {
        currentUri = uri
    }

    @Synchronized
    override fun record(document: SafDocument) {
        val updated = RecentEntry(
            uri = document.uri,
            displayName = document.name,
            lastOpened = now(),
            readonly = document.readonly,
        )
        val next = (listOf(updated) + entries.filterNot { it.uri == document.uri })
        if (next.size > maxEntries) {
            // Only the entries genuinely evicted by the cap release their grant.
            next.drop(maxEntries).forEach { releaseUnlessCurrent(it.uri) }
            entries = next.take(maxEntries)
        } else {
            entries = next
        }
        persist()
    }

    /**
     * Drops [uri] from the list. The grant is released only when an entry was
     * genuinely removed, and never for the currently open document.
     */
    @Synchronized
    fun remove(uri: String) {
        if (entries.none { it.uri == uri }) return
        entries = entries.filterNot { it.uri == uri }
        releaseUnlessCurrent(uri)
        persist()
    }

    /**
     * Wipes the list. Every dropped entry releases its grant except the
     * currently open document (plan todo 12b: clearing must skip the current
     * URI, or the session loses write permission).
     */
    @Synchronized
    fun clear() {
        val dropped = entries.map { it.uri }
        entries = emptyList()
        dropped.forEach { releaseUnlessCurrent(it) }
        persist()
    }

    private fun releaseUnlessCurrent(uri: String) {
        // (a) never release the currently-open document; (c) release only here,
        // i.e. only for entries actually dropped from the persisted list.
        if (uri == currentUri) return
        runCatching { releaser.release(uri) }
    }

    private fun persist() {
        val json = AppConfigCodec.encode(entries)
        runCatching { files.write(json) }
    }

    companion object {
        /** Same cap as the desktop `recordRecent` loop (`mdview/app.go:590`). */
        const val MAX_ENTRIES = 10
    }
}
