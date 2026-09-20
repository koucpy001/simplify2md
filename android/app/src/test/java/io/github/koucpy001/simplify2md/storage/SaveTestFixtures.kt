package io.github.koucpy001.simplify2md.storage

import java.io.IOException

/**
 * Shared fakes for the save/reconcile JVM tests. Every Android boundary is
 * replaced here, so the state machine runs without Robolectric.
 */

class FakeBackupFileSystem : BackupFileSystem {

    val blobs = LinkedHashMap<String, ByteArray>()
    val writesLog = mutableListOf<Pair<String, ByteArray>>()
    val deleted = mutableListOf<String>()
    val renamed = mutableListOf<Pair<String, String>>()

    /** When non-null, any `writeAtomic` whose name contains this fails. */
    var failWriteOn: String? = null

    override fun list(): List<String> = blobs.keys.sorted()

    override fun exists(name: String): Boolean = blobs.containsKey(name)

    override fun read(name: String): ByteArray? = blobs[name]?.copyOf()

    override fun writeAtomic(name: String, bytes: ByteArray) {
        if (failWriteOn != null && name.contains(failWriteOn!!)) throw IOException("write failed: $name")
        blobs[name] = bytes.copyOf()
        writesLog.add(name to bytes.copyOf())
    }

    override fun delete(name: String) {
        blobs.remove(name)
        deleted.add(name)
    }

    override fun rename(from: String, to: String) {
        val value = blobs.remove(from) ?: throw IOException("missing: $from")
        blobs[to] = value
        renamed.add(from to to)
    }
}

class FakeDocumentReader(var current: ByteArray? = null) : DocumentReader {

    var failRead = false

    /** Fail the N-th `read` call (1-based); 0 disables. */
    var failReadAfter = 0

    var failFingerprint = false

    private var reads = 0

    override fun read(uri: String): ByteArray? {
        reads++
        if (failRead || (failReadAfter > 0 && reads >= failReadAfter)) throw IOException("read failed")
        return current?.copyOf()
    }

    override fun fingerprint(uri: String): DocumentFingerprint? {
        if (failFingerprint) throw IOException("fingerprint failed")
        val bytes = current ?: return null
        return DocumentFingerprint(bytes.size.toLong(), Sha256.hex(bytes))
    }
}

class FakeDocumentWriter(var mode: WriteMode = WriteMode.TRUNCATE_WT) : DocumentWriter {

    val writes = mutableListOf<ByteArray>()
    val truncations = mutableListOf<Long>()
    var writesToFail = 0
    var failOpen = false

    /** Simulates the target changing on disk after a successful write. */
    var onWrite: ((ByteArray) -> Unit)? = null

    override fun openWrite(uri: String): OpenedWrite {
        if (failOpen) throw IOException("open failed")
        return OpenedWrite(
            mode,
            object : DocumentWriteChannel {
                override fun write(bytes: ByteArray) {
                    if (writesToFail > 0) {
                        writesToFail--
                        throw IOException("write failed")
                    }
                    writes.add(bytes.copyOf())
                    onWrite?.invoke(bytes)
                }

                override fun truncate(size: Long) {
                    truncations.add(size)
                }

                override fun flush() = Unit

                override fun sync() = Unit

                override fun close() = Unit
            },
        )
    }
}

/** Builds the exact on-disk journal bytes a crashed save would leave behind. */
fun journalBytes(
    uri: String,
    state: JournalState,
    expectedLen: Long,
    expectedSha: String,
    observedLen: Long? = null,
    observedSha: String? = null,
): ByteArray = SaveJournalCodec
    .encode(SaveJournal(uri, state, expectedLen, expectedSha, observedLen, observedSha))
    .toByteArray(Charsets.UTF_8)
