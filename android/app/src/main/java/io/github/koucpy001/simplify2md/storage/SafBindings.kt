package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeCancelledException
import io.github.koucpy001.simplify2md.bridge.BridgeException
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import io.github.koucpy001.simplify2md.encoding.EncodingCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge bindings for the SAF flow (plan todo 10).
 *
 * Registers `OpenFile`, `ReadFileAt` and `PickSavePath`. All three are already
 * members of the frozen [BridgeMethods.WHITELIST]; [Bridge.registerHandler]
 * refuses anything else, so this class cannot widen the dispatch surface.
 *
 * Result shape: `OpenFile` and `ReadFileAt` return the same JSON object, which
 * the `@bridge` shim's `OpenResultLike` consumes:
 *
 * ```
 * { path, content, encoding, newline, name, readonly }
 * ```
 *
 * `path` is the document URI on Android, `name` is the provider DISPLAY_NAME
 * (falsy when absent) and `readonly` is the missing-FLAG_SUPPORTS_WRITE state.
 * `ReadFileAt` re-derives `name`/`readonly` through [SafStore.describe], so
 * reopening from recents keeps both.
 *
 * `PickSavePath` mirrors the desktop Go contract (`mdview/app.go:243`): a user
 * cancel returns `""` rather than rejecting, and the frontend treats empty as
 * "nothing chosen". Only `OpenFile` rejects `cancelled`.
 */
class SafBindings(
    private val store: SafStore,
    private val reader: DocumentContentReader,
    private val recents: RecentsSink = NoRecents,
    private val onDocumentLoaded: (String) -> Unit = {},
) {

    /** `OpenFile`: pick a document, then read and decode it. */
    suspend fun openFile(): JSONObject {
        val doc = store.openDocument()
        val json = load(doc)
        remember(doc)
        onDocumentLoaded(doc.uri)
        return json
    }

    /**
     * `ReadFileAt`: re-resolve the URI (name + read-only) and read it.
     *
     * The entry is remembered only after the read succeeded, so a document that
     * can no longer be opened is never recorded nor marked as the current one.
     */
    suspend fun readFileAt(uri: String): JSONObject {
        if (uri.isBlank()) throw BridgeException("empty path")
        val doc = store.describe(uri)
        val json = load(doc)
        remember(doc)
        onDocumentLoaded(doc.uri)
        return json
    }

    /**
     * `PickSavePath`: `ACTION_CREATE_DOCUMENT` for save-as. Returns the created
     * URI, or `""` on cancel to match the desktop binding.
     *
     * Save-as records the created document immediately (mirroring the desktop
     * `SaveFile` -> `recordRecent`), so the frontend's post-save
     * `refreshRecents()` finds the entry and can show its DISPLAY_NAME instead
     * of the opaque URI.
     */
    suspend fun pickSavePath(defaultName: String): String = try {
        val doc = store.createDocument(defaultName.ifBlank { SafStore.UNTITLED_NAME })
        remember(doc)
        doc.uri
    } catch (_: BridgeCancelledException) {
        ""
    }

    /**
     * Marks [doc] as the currently open document (so recents release logic can
     * skip its grant) and records it with the provider's DISPLAY_NAME — never
     * the URI's last segment, which is junk for opaque providers.
     */
    private fun remember(doc: SafDocument) {
        recents.markCurrent(doc.uri)
        recents.record(doc)
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.OPEN_FILE) { openFile() }
        bridge.registerHandler(BridgeMethods.READ_FILE_AT) { args: JSONArray ->
            readFileAt(args.optString(0, ""))
        }
        bridge.registerHandler(BridgeMethods.PICK_SAVE_PATH) { args: JSONArray ->
            pickSavePath(args.optString(0, ""))
        }
    }

    private fun load(doc: SafDocument): JSONObject {
        val bytes = reader.read(doc.uri)
        val decoded = EncodingCodec.decode(bytes)
        return JSONObject()
            .put(KEY_PATH, doc.uri)
            .put(KEY_CONTENT, decoded.content)
            .put(KEY_ENCODING, decoded.encoding)
            .put(KEY_NEWLINE, EncodingCodec.detectNewline(bytes))
            .put(KEY_NAME, doc.name)
            .put(KEY_READONLY, doc.readonly)
    }

    companion object {
        const val KEY_PATH = "path"
        const val KEY_CONTENT = "content"
        const val KEY_ENCODING = "encoding"
        const val KEY_NEWLINE = "newline"
        const val KEY_NAME = "name"
        const val KEY_READONLY = "readonly"
    }
}
