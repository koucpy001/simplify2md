package io.github.koucpy001.simplify2md.storage

import java.io.File

/**
 * `filesDir/autosave/` as a [DraftFileSystem] (plan todo 14).
 *
 * App-private `filesDir` only — **not** `cacheDir` (the system may evict it),
 * **not** SAF, and **not** a synced/config directory. The directory is created
 * lazily on the first save, matching the Go `os.MkdirAll` call.
 *
 * The name handed in is already `<validated-key>.md` from [DraftStore], so it
 * cannot contain a separator; the file is still resolved through the single
 * autosave directory, never the raw name.
 */
class AndroidDraftFileSystem(filesDir: File) : DraftFileSystem {

    private val dir = File(filesDir, DraftStore.DIR_NAME)

    override fun ensureDirectory() {
        if (!dir.exists()) dir.mkdirs()
    }

    override fun read(name: String): String? {
        val file = File(dir, name)
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    override fun write(name: String, content: String) {
        ensureDirectory()
        File(dir, name).writeText(content, Charsets.UTF_8)
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun list(): List<DraftFile> =
        (dir.listFiles() ?: emptyArray()).map {
            DraftFile(name = it.name, modTimeMs = it.lastModified(), isDirectory = it.isDirectory)
        }
}
