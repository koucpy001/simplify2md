package io.github.koucpy001.simplify2md.storage

import java.io.IOException

/**
 * In-memory autosave directory for the draft JVM tests. ModTimes are explicit so
 * ordering can be exercised without touching the real clock (which is what made
 * the Go test backdate files with `os.Chtimes`).
 */
class FakeDraftFileSystem : DraftFileSystem {

    private class Entry(
        var content: String,
        var modTimeMs: Long,
        val isDirectory: Boolean,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /** Every `write` in call order, including rejected escape attempts. */
    val writes = mutableListOf<Pair<String, String>>()

    var failWrite = false

    /** Pre-populates an entry; [isDirectory] simulates a subdirectory. */
    fun put(
        name: String,
        content: String = "",
        modTimeMs: Long = 0L,
        isDirectory: Boolean = false,
    ) {
        entries[name] = Entry(content, modTimeMs, isDirectory)
    }

    fun names(): Set<String> = entries.keys.toSet()

    fun contentOf(name: String): String? = entries[name]?.content

    override fun ensureDirectory() = Unit

    override fun read(name: String): String? =
        entries[name]?.takeUnless { it.isDirectory }?.content

    override fun write(name: String, content: String) {
        if (failWrite) throw IOException("autosave write failed")
        entries[name] = Entry(content, modTimeMs = 0L, isDirectory = false)
        writes.add(name to content)
    }

    override fun delete(name: String) {
        entries.remove(name)
    }

    override fun list(): List<DraftFile> =
        entries.map { (name, entry) -> DraftFile(name, entry.modTimeMs, entry.isDirectory) }
}
