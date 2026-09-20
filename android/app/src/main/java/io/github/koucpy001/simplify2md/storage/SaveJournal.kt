package io.github.koucpy001.simplify2md.storage

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Save reliability primitives (plan todo 11).
 *
 * Android SAF document URIs cannot be written with "temp file + rename", so the
 * desktop's file-level atomic write (`mdview/app.go:262-289`) has no Android
 * equivalent. Instead a save is made recoverable by a small on-disk state
 * machine over `filesDir/backup/`:
 *
 *  1. copy the target's *current on-disk bytes* into `<hash>.bak`
 *     (`<hash> = SHA-256(target URI string)`);
 *  2. write `<hash>.json` with state `writing`, the expected length and a
 *     **full** `expectedSha256` of the in-memory encoded result;
 *  3. write the target, flush and `sync()`;
 *  4. immediately read the target back, rewrite the journal atomically to state
 *     `written` (recording the observed hash/length), then delete backup and
 *     journal.
 *
 * `writing` and `written` are distinct states on purpose: if the process dies
 * mid-save the journal is still `writing` and the next Activity start
 * reconciles it (`ReconcileEngine`). A full SHA-256 is used, never a partial or
 * three-segment checksum. This file is pure JVM: no Android types, so the codec
 * and the path/hash rules run under plain JUnit.
 */

/** Lower-case hex SHA-256. The only integrity primitive used by the journal. */
object Sha256 {

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Journal state. The exact wire strings are part of the on-disk format and are
 * locked by the unit tests; `writing` means a save was in flight, `written`
 * means the target was written and verified but the cleanup did not finish.
 */
enum class JournalState(val wire: String) {
    WRITING("writing"),
    WRITTEN("written"),
    ;

    companion object {
        fun fromWire(value: String): JournalState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One save's recovery record.
 *
 * @param uri            target document URI (the SAF `content://` handle)
 * @param state          [JournalState.WRITING] or [JournalState.WRITTEN]
 * @param expectedLen    byte length of the in-memory encoded result
 * @param expectedSha256 **full** SHA-256 of the in-memory encoded result
 * @param observedLen    length read back from the target after a successful
 *                       write (only when [state] is [JournalState.WRITTEN])
 * @param observedSha256 hash read back from the target after a successful write
 */
data class SaveJournal(
    val uri: String,
    val state: JournalState,
    val expectedLen: Long,
    val expectedSha256: String,
    val observedLen: Long? = null,
    val observedSha256: String? = null,
)

/**
 * JSON codec for [SaveJournal]. A journal that cannot be parsed is treated as
 * corrupt by the reconciler (backup kept, journal renamed), so [decode] returns
 * null rather than throwing.
 */
object SaveJournalCodec {

    const val KEY_URI = "uri"
    const val KEY_STATE = "state"
    const val KEY_EXPECTED_LEN = "expectedLen"
    const val KEY_EXPECTED_SHA = "expectedSha256"
    const val KEY_OBSERVED_LEN = "observedLen"
    const val KEY_OBSERVED_SHA = "observedSha256"

    fun encode(journal: SaveJournal): String {
        val json = JSONObject()
            .put(KEY_URI, journal.uri)
            .put(KEY_STATE, journal.state.wire)
            .put(KEY_EXPECTED_LEN, journal.expectedLen)
            .put(KEY_EXPECTED_SHA, journal.expectedSha256)
        journal.observedLen?.let { json.put(KEY_OBSERVED_LEN, it) }
        journal.observedSha256?.let { json.put(KEY_OBSERVED_SHA, it) }
        return json.toString()
    }

    fun decode(json: String): SaveJournal? = try {
        val obj = JSONObject(json)
        val state = JournalState.fromWire(obj.getString(KEY_STATE))
        val uri = obj.getString(KEY_URI)
        val expectedSha = obj.getString(KEY_EXPECTED_SHA)
        if (state == null || uri.isBlank() || expectedSha.isBlank()) {
            null
        } else {
            SaveJournal(
                uri = uri,
                state = state,
                expectedLen = obj.getLong(KEY_EXPECTED_LEN),
                expectedSha256 = expectedSha,
                observedLen = if (obj.has(KEY_OBSERVED_LEN)) obj.getLong(KEY_OBSERVED_LEN) else null,
                observedSha256 = obj.optString(KEY_OBSERVED_SHA).takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Names inside `filesDir/backup/`. The name is keyed by the SHA-256 of the URI
 * string, so a re-save of the same document overwrites the same slot instead of
 * piling up backups.
 *
 * `filesDir` is deliberate (plan todo 11b): `cacheDir` may be reclaimed by the
 * system at any time, which would destroy exactly the bytes a recovery needs.
 */
object BackupPaths {

    const val DIR_NAME = "backup"
    const val BAK_SUFFIX = ".bak"
    const val JOURNAL_SUFFIX = ".json"
    const val CORRUPT_SUFFIX = ".corrupt"
    const val KEPT_SUFFIX = ".kept"

    fun hashFor(uri: String): String = Sha256.hex(uri.toByteArray(Charsets.UTF_8))

    fun backupName(uri: String): String = hashFor(uri) + BAK_SUFFIX

    fun journalName(uri: String): String = hashFor(uri) + JOURNAL_SUFFIX

    /** `<hash>.json` -> `<hash>.json.corrupt` (bad journal kept for inspection). */
    fun corruptName(journalFileName: String): String = journalFileName + CORRUPT_SUFFIX

    /** `<hash>.bak` -> `<hash>.kept` (the "keep both" outcome of the dialog). */
    fun keptName(backupFileName: String): String = backupFileName.removeSuffix(BAK_SUFFIX) + KEPT_SUFFIX

    /** `<hash>.bak` -> `<hash>.json`, used to pair orphan backups. */
    fun journalNameOf(backupFileName: String): String =
        backupFileName.removeSuffix(BAK_SUFFIX) + JOURNAL_SUFFIX

    /** `<hash>.json` -> `<hash>`, the hash prefix shared by the pair. */
    fun hashOfJournal(journalFileName: String): String = journalFileName.removeSuffix(JOURNAL_SUFFIX)

    /** `<hash>.bak` -> `<hash>`. */
    fun hashOfBackup(backupFileName: String): String = backupFileName.removeSuffix(BAK_SUFFIX)
}
