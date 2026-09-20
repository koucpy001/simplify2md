package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.BridgeCancelledException
import io.github.koucpy001.simplify2md.bridge.BridgeException
import io.github.koucpy001.simplify2md.bridge.PickerSlot

/**
 * Storage Access Framework core for open / save-as (plan todo 10).
 *
 * Android has no filesystem paths for a user-chosen document: it hands out a
 * `content://` URI plus a revocable grant. This file owns every *decision* that
 * can be made without a device, so all of it runs under plain JVM JUnit:
 *
 *  - a picker cancel (or a success with an empty data URI) becomes
 *    [BridgeCancelledException], whose message is exactly `cancelled` and
 *    matches the desktop Go `errors.New("cancelled")` (`mdview/app.go:172`) and
 *    the frontend's `/cancelled/i` handling (`App.vue:538`);
 *  - a document whose provider does not advertise write support is marked
 *    read-only, so save never calls `openOutputStream` on it;
 *  - persisting a URI grant is best-effort: providers that cannot persist it
 *    throw, and the failure degrades to a session grant instead of crashing.
 *
 * The Android-specific pieces (intent construction with the three grant flags,
 * `ContentResolver` queries, `takePersistableUriPermission`) live in
 * `AndroidSaf.kt` behind the small interfaces declared here, which is what lets
 * this class stay free of Android types. The literal flag combination and the
 * `COLUMN_FLAGS` / `FLAG_SUPPORTS_WRITE` read are grep-verifiable there.
 */

/** Whether a URI grant survives process death. */
enum class GrantLevel {
    /** `takePersistableUriPermission` succeeded; a recents reopen works after restart. */
    PERSISTED,

    /** Persisting failed (or was not attempted); the grant dies with the Activity. */
    SESSION,
}

/**
 * A document resolved through SAF. Pure data: the URI is an opaque handle, never
 * a path, and [name] always comes from the provider's DISPLAY_NAME — never from
 * the URI's last segment (opaque providers return junk such as `msf%3A123`).
 */
data class SafDocument(
    val uri: String,
    val name: String,
    val readonly: Boolean,
    val grant: GrantLevel,
) {
    companion object {
        /** What the UI gets when the provider exposes no DISPLAY_NAME. */
        const val UNKNOWN_NAME = ""
    }
}

/** A picker result reduced to Android-free values. */
sealed interface SafPickOutcome {
    /** The user backed out, or the result carried no URI. */
    data object Cancelled : SafPickOutcome

    /** The user chose a document; [uri] is the `content://` handle. */
    data class Chosen(val uri: String) : SafPickOutcome
}

/** Starts the system document picker and suspends until the user returns. */
interface SafLauncher {
    /** `ACTION_OPEN_DOCUMENT`; sees [SafPickOutcome.Cancelled] on back-out. */
    suspend fun openDocument(): SafPickOutcome

    /** `ACTION_CREATE_DOCUMENT` for save-as / first save of an untitled document. */
    suspend fun createDocument(defaultName: String): SafPickOutcome
}

/**
 * Provider metadata. Android implements it with a single `ContentResolver`
 * query; a fake implements it in tests.
 */
interface DocumentMetadataReader {
    /** `OpenableColumns.DISPLAY_NAME`, or null when the provider has none. */
    fun displayName(uri: String): String?

    /**
     * True only when `DocumentsContract.Document.COLUMN_FLAGS` carries
     * `FLAG_SUPPORTS_WRITE`. A provider that does not report the column at all
     * answers false, so the document is treated as read-only — the safe default.
     */
    fun supportsWrite(uri: String): Boolean
}

/**
 * Best-effort persister for URI grants.
 *
 * Implementations must throw (typically `SecurityException`) when the provider
 * offers no persistable grant; [SafStore] catches that and degrades to
 * [GrantLevel.SESSION].
 */
interface UriPermissionStore {
    /**
     * Persists the grant for [uri]. [write] requests read+write; false requests
     * read-only (used for a document the provider marked read-only, where a
     * write grant was never issued and would make the call throw).
     */
    fun takePersistable(uri: String, write: Boolean)
}

/** Reads the raw bytes behind a document URI (`ContentResolver.openInputStream`). */
fun interface DocumentContentReader {
    fun read(uri: String): ByteArray
}

/**
 * Pure mapping from a raw activity-result to [SafPickOutcome].
 *
 * Extracted so "cancel" and "empty data" are JVM-testable without Robolectric:
 * the Android glue only turns `resultCode`/`data.data` into these two plain
 * values, and every branch decision lives here.
 */
object SafResultMapper {

    /**
     * @param resultOk  `resultCode == Activity.RESULT_OK`
     * @param uri       `data?.data?.toString()`, or null when data/URI is absent
     */
    fun toOutcome(resultOk: Boolean, uri: String?): SafPickOutcome =
        if (resultOk && !uri.isNullOrBlank()) SafPickOutcome.Chosen(uri) else SafPickOutcome.Cancelled
}

/**
 * The open / save-as state machine.
 *
 * Every picker call takes the single global [PickerSlot] shared with
 * `PickSavePath` (plan todo 5(h)): a second concurrent request is rejected at
 * once rather than stacked. The slot is released when the launcher returns,
 * including on cancellation and on a thrown exception.
 */
class SafStore(
    private val pickerSlot: PickerSlot,
    private val launcher: SafLauncher,
    private val metadata: DocumentMetadataReader,
    private val permissions: UriPermissionStore,
    private val onSessionGrant: (SafDocument) -> Unit = {},
) {

    /**
     * `ACTION_OPEN_DOCUMENT`. Cancels reject with the stable `cancelled` token;
     * a chosen document is resolved and its grant persisted (best-effort).
     */
    suspend fun openDocument(): SafDocument {
        val uri = requireChosen(launch { launcher.openDocument() })
        return documentOf(uri, persist = true)
    }

    /**
     * `ACTION_CREATE_DOCUMENT` for save-as. The created URI's read+write grant
     * is persisted best-effort; a cancel rejects with `cancelled` (the
     * `PickSavePath` binding maps that to the desktop's empty-string contract).
     */
    suspend fun createDocument(defaultName: String): SafDocument {
        val uri = requireChosen(launch { launcher.createDocument(defaultName) })
        return documentOf(uri, persist = true)
    }

    /**
     * Re-resolves [uri] without opening a picker: this is what `ReadFileAt`
     * uses to rebuild the full result (name + read-only state) when reopening
     * from recents. Skipping this re-query would silently drop both the file
     * name and the read-only protection (plan review M1).
     */
    fun describe(uri: String): SafDocument = documentOf(uri, persist = false)

    private suspend fun launch(block: suspend () -> SafPickOutcome): SafPickOutcome {
        if (!pickerSlot.tryAcquire()) throw BridgeException(PICKER_BUSY_TOKEN)
        return try {
            block()
        } finally {
            pickerSlot.release()
        }
    }

    private fun requireChosen(outcome: SafPickOutcome): String = when (outcome) {
        SafPickOutcome.Cancelled -> throw BridgeCancelledException()
        is SafPickOutcome.Chosen -> outcome.uri.ifBlank { throw BridgeCancelledException() }
    }

    private fun documentOf(uri: String, persist: Boolean): SafDocument {
        // Provider data is untrusted: a query that throws must not crash the
        // app, and the safe fallback for an unknown document is read-only.
        val name = runCatching { metadata.displayName(uri) }.getOrNull()?.trim().orEmpty()
        val readonly = !runCatching { metadata.supportsWrite(uri) }.getOrDefault(false)
        val grant = if (persist) persistGrant(uri, write = !readonly) else GrantLevel.SESSION
        val doc = SafDocument(uri = uri, name = name, readonly = readonly, grant = grant)
        if (persist && grant == GrantLevel.SESSION) onSessionGrant(doc)
        return doc
    }

    /**
     * Persists the grant, degrading to a session grant when the provider cannot
     * offer one. This is the try/catch the plan requires: some providers do not
     * return the persistable bit, and `takePersistableUriPermission` then throws
     * instead of granting.
     */
    private fun persistGrant(uri: String, write: Boolean): GrantLevel = try {
        permissions.takePersistable(uri, write)
        GrantLevel.PERSISTED
    } catch (_: Exception) {
        GrantLevel.SESSION
    }

    companion object {
        /** Concurrent SAF picker already in flight (mirrors the frozen single-slot rule). */
        const val PICKER_BUSY_TOKEN = "picker-busy"

        /** Default filename used for an untitled document. */
        const val UNTITLED_NAME = "未标题.md"
    }
}
