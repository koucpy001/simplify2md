package io.github.koucpy001.simplify2md.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Android glue for the save state machine (plan todo 11b-d, g).
 *
 * Grep-verifiable contract:
 *  - `openFileDescriptor(uri, "wt")` is tried first; if it throws, the code
 *    falls back to `"w"` and reports [WriteMode.LEGACY_W], which makes the
 *    caller issue an explicit `FileChannel.truncate(encoded.size)` — `"w"` does
 *    not truncate and a short write would leave the old tail behind;
 *  - after `flush()` the descriptor's `FileDescriptor.sync()` is called
 *    best-effort;
 *  - read-back/reconcile fingerprints are streamed through a `MessageDigest`
 *    (a full SHA-256, never a partial/segmented checksum);
 *  - backups and journals live under `filesDir/backup/`, never `cacheDir`, and
 *    are written atomically (sibling temp file + rename).
 *
 * `cacheDir` is intentionally absent: the system may reclaim it, which is
 * exactly the storage a crash-recovery snapshot must not use.
 */

/** [`BackupFileSystem`] over `filesDir/backup/` with temp + rename writes. */
class AndroidBackupFileSystem(filesDir: File) : BackupFileSystem {

    private val dir = File(filesDir, BackupPaths.DIR_NAME)

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    override fun list(): List<String> = dir.list()?.toList() ?: emptyList()

    override fun exists(name: String): Boolean = File(dir, name).exists()

    override fun read(name: String): ByteArray? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return FileInputStream(file).use { it.readBytes() }
    }

    override fun writeAtomic(name: String, bytes: ByteArray) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot rename $tmp to $target")
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun rename(from: String, to: String) {
        val source = File(dir, from)
        val target = File(dir, to)
        if (!source.renameTo(target)) {
            throw IOException("cannot rename $from to $to")
        }
    }
}

/**
 * [`DocumentReader`] + [`DocumentWriter`] over a `ContentResolver`.
 *
 * `read` treats a missing document (`FileNotFoundException`) as null so the
 * state machine can distinguish "absent" from "provider error"; other failures
 * propagate and abort the save before the target is touched.
 */
class AndroidSaveDocumentIo(private val resolver: ContentResolver) : DocumentReader, DocumentWriter {

    override fun read(uri: String): ByteArray? = try {
        resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }

    override fun fingerprint(uri: String): DocumentFingerprint? = try {
        resolver.openInputStream(Uri.parse(uri))?.use { input -> fingerprintOf(input) }
    } catch (_: FileNotFoundException) {
        null
    }

    override fun openWrite(uri: String): OpenedWrite {
        val parsed = Uri.parse(uri)
        // "wt" truncates at open. Some providers reject it; only then fall back
        // to "w", which the caller must compensate for with truncate().
        val truncating = try {
            resolver.openFileDescriptor(parsed, "wt")
        } catch (_: Exception) {
            null
        }
        if (truncating != null) {
            return OpenedWrite(WriteMode.TRUNCATE_WT, ParcelWriteChannel(truncating))
        }
        val legacy = resolver.openFileDescriptor(parsed, "w")
            ?: throw IOException("cannot open $uri for write")
        return OpenedWrite(WriteMode.LEGACY_W, ParcelWriteChannel(legacy))
    }

    private fun fingerprintOf(input: InputStream): DocumentFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var len = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            len += read
        }
        return DocumentFingerprint(len, Sha256.hex(digest.digest()))
    }

    private class ParcelWriteChannel(private val pfd: ParcelFileDescriptor) : DocumentWriteChannel {

        private val out = FileOutputStream(pfd.fileDescriptor)

        override fun write(bytes: ByteArray) {
            out.write(bytes)
        }

        override fun truncate(size: Long) {
            out.channel.truncate(size)
        }

        override fun flush() {
            out.flush()
        }

        override fun sync() {
            // Best-effort durability barrier; some providers do not support it.
            try {
                pfd.fileDescriptor.sync()
            } catch (_: Exception) {
                // Ignore: the write itself already succeeded.
            }
        }

        override fun close() {
            try {
                out.close()
            } finally {
                pfd.close()
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
