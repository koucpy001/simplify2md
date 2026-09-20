package io.github.koucpy001.simplify2md.storage

import java.io.Closeable

/**
 * Injectable IO boundaries for the save state machine (plan todo 11h).
 *
 * Every Android boundary is abstracted here so `SaveStore`, `ReconcileEngine`
 * and `ReconcileCoordinator` run under plain JVM JUnit with fakes — the same
 * pattern as `SafStore`/`ExternalUrlLauncher`. The real `ContentResolver` glue
 * lives in `AndroidSaveIo.kt` (grep-verifiable there: `"wt"`, `truncate`,
 * `FileDescriptor.sync`).
 */

/** Full-content fingerprint of a document: its byte length and full SHA-256. */
data class DocumentFingerprint(val len: Long, val sha256: String)

/**
 * Reads the target document behind a URI.
 *
 * `read` materialises the bytes (needed for the backup); `fingerprint` streams
 * the target so a read-back/reconcile hash never has to buffer the whole file.
 */
interface DocumentReader {
    /** Current on-disk bytes, or null when the document does not exist. */
    fun read(uri: String): ByteArray?

    /** Full-length + full-hash fingerprint, or null when the document is absent. */
    fun fingerprint(uri: String): DocumentFingerprint?
}

/**
 * Whether the opened descriptor truncates at open time.
 *
 * `"wt"` truncates, so a shorter write cannot leave the old tail behind.
 * `"w"` is the legacy fallback and does NOT guarantee truncation, which is why
 * the caller must issue an explicit [DocumentWriteChannel.truncate] after a
 * short write (plan todo 11c).
 */
enum class WriteMode { TRUNCATE_WT, LEGACY_W }

/** One open write target. [close] releases the file descriptor. */
interface DocumentWriteChannel : Closeable {
    fun write(bytes: ByteArray)

    /** Truncates the file to [size] bytes; required on the [WriteMode.LEGACY_W] path. */
    fun truncate(size: Long)

    fun flush()

    /** Best-effort durability barrier (`FileDescriptor.sync`); implementations may no-op. */
    fun sync()
}

/** An opened write target plus the mode it was opened with. */
data class OpenedWrite(val mode: WriteMode, val channel: DocumentWriteChannel)

/** Opens a document for a full overwrite. */
interface DocumentWriter {
    fun openWrite(uri: String): OpenedWrite
}

/**
 * Files inside `filesDir/backup/`. All writes must be atomic (temp + rename);
 * `list` returns bare file names, never paths.
 */
interface BackupFileSystem {
    fun list(): List<String>

    fun exists(name: String): Boolean

    /** Bytes of a backup/journal file, or null when it does not exist. */
    fun read(name: String): ByteArray?

    /** Atomically replaces [name] (write a sibling temp file, then rename). */
    fun writeAtomic(name: String, bytes: ByteArray)

    fun delete(name: String)

    fun rename(from: String, to: String)
}

/**
 * Writes [bytes] through [writer] and, when the descriptor was opened in the
 * non-truncating legacy mode, truncates explicitly to the written length.
 *
 * Shared by the save path and by the "restore from backup" path so both obey
 * the same truncation rule. On the `"wt"` path the truncation happened at open;
 * on the `"w"` path this call is the only thing standing between a shorter write
 * and a stale tail that corrupts the document.
 */
internal fun writeTargetBytes(writer: DocumentWriter, uri: String, bytes: ByteArray) {
    val opened = writer.openWrite(uri)
    opened.channel.use { channel ->
        channel.write(bytes)
        // "wt" truncates at open; "w" does not, so a shorter write would leave
        // the previous tail in place (plan todo 11c).
        if (opened.mode == WriteMode.LEGACY_W) channel.truncate(bytes.size.toLong())
        channel.flush()
        channel.sync()
    }
}
