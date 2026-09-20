package io.github.koucpy001.simplify2md.storage

import java.security.MessageDigest

/**
 * A draft-store failure. The message is technical (English) and reaches the JS
 * Promise as the rejection reason, mirroring the Go `fmt.Errorf` string from
 * `mdview/app.go:754` / `:812`.
 */
class DraftException(message: String) : Exception(message)

/**
 * Metadata for one stored draft, shaped for the `ListDrafts` bridge reply
 * (`{key, modTime}`), matching the Go `DraftInfo` in `mdview/app.go:731-734`.
 * [modTime] is epoch **seconds** (`info.ModTime().Unix()`), not milliseconds.
 */
data class DraftInfo(val key: String, val modTime: Long)

/**
 * One entry in the autosave directory. [isDirectory] is carried explicitly so
 * [DraftStore] can skip directories exactly like the Go loop's `e.IsDir()`
 * (`mdview/app.go:785`) while the fake filesystem can still simulate one.
 */
data class DraftFile(val name: String, val modTimeMs: Long, val isDirectory: Boolean = false)

/**
 * The autosave-directory boundary. All IO goes through this interface so
 * [DraftStore] runs under plain JVM JUnit; the Android implementation writes
 * `filesDir/autosave/` (see `AndroidDrafts.kt`), a fake keeps entries in memory.
 *
 * Deliberately not shaped like `os.UserConfigDir`: Android drafts live in
 * app-private `filesDir`, never in a config directory, SAF tree, synced folder
 * or `cacheDir` (the system may evict cache).
 */
interface DraftFileSystem {
    /** Creates the autosave directory when absent. */
    fun ensureDirectory()

    /** The draft text, or null when the file does not exist. */
    fun read(name: String): String?

    /** Replaces the draft file's content. */
    fun write(name: String, content: String)

    /** Deletes the file; a missing file is a no-op (idempotent clear). */
    fun delete(name: String)

    /** Directory entries (files and directories), unsorted. */
    fun list(): List<DraftFile>
}

/**
 * The canonical draft-key identity (plan todo 14).
 *
 * A draft key is `sha1(uriString)` as 40 lowercase hex characters, or the
 * literal `untitled` when there is no document URI. The same derivation lives
 * in the frontend (`App.vue:451-461`: `crypto.subtle` SHA-1 over the UTF-8
 * bytes, `b.toString(16).padStart(2, '0')` per byte), and both are stable
 * across sessions and collision-free for the full 160-bit digest.
 *
 * Display names or any other user text are **never** part of the key: the key
 * is the only thing that reaches the filesystem, so user text cannot influence
 * a path.
 */
object DraftKey {

    /** The key used for an unnamed document. */
    const val UNTITLED = "untitled"

    /** `sha1(uri)` for a named document, `untitled` for an empty URI. */
    fun of(uri: String): String = if (uri.isEmpty()) UNTITLED else sha1Hex(uri)

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

/**
 * Draft CRUD over an app-private directory (plan todo 14), ported from
 * `mdview/app.go:736-840`.
 *
 * Invariants, each backed by a JVM test:
 *
 *  - the key must match `^[0-9a-f]{40}$|^untitled$`; anything else is rejected
 *    before any IO, so a payload like `../../etc/passwd` cannot escape the
 *    autosave directory;
 *  - the on-disk name is always `<key>.md`, derived from the validated key;
 *  - [list] skips directories, non-`.md` files and invalid keys, and orders by
 *    modification time newest-first. Ties are broken by key ascending so the
 *    order is deterministic (Go's `sort.Slice` left ties unspecified);
 *  - a missing directory lists as empty; [clear] of a missing key is a no-op.
 */
class DraftStore(private val files: DraftFileSystem) {

    fun save(key: String, content: String) {
        requireValidKey(key)
        files.ensureDirectory()
        files.write(fileName(key), content)
    }

    fun load(key: String): String {
        requireValidKey(key)
        return files.read(fileName(key)) ?: throw DraftException("draft not found: $key")
    }

    /** All drafts, newest first; never throws for a missing directory. */
    fun list(): List<DraftInfo> =
        files.list()
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { it.name.endsWith(SUFFIX) }
            .mapNotNull { entry ->
                val key = entry.name.dropLast(SUFFIX.length)
                if (isValidKey(key)) DraftListRow(key, entry.modTimeMs) else null
            }
            // modTime-descending, as in the Go original; the key tiebreak only
            // removes the Go version's non-determinism for equal modTimes.
            .sortedWith(compareByDescending<DraftListRow> { it.modTimeMs }.thenBy { it.key })
            .map { DraftInfo(it.key, it.modTimeMs / 1000L) }
            .toList()

    /** Deletes one draft; a missing key is a no-op, not an error. */
    fun clear(key: String) {
        requireValidKey(key)
        files.delete(fileName(key))
    }

    private fun requireValidKey(key: String) {
        if (!isValidKey(key)) throw DraftException("invalid draft key \"$key\"")
    }

    private fun fileName(key: String): String = key + SUFFIX

    private data class DraftListRow(val key: String, val modTimeMs: Long)

    companion object {
        /** Relative to `filesDir`; the only directory drafts are ever written to. */
        const val DIR_NAME = "autosave"

        const val SUFFIX = ".md"

        /**
         * The sole gate on where a draft may be written (`mdview/app.go:77`):
         * a 40-hex sha1 or the literal `untitled`. Uppercase hex, partial
         * lengths and any separator are all rejected.
         */
        val KEY_PATTERN: Regex = Regex("^[0-9a-f]{40}$|^untitled$")

        fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)
    }
}
