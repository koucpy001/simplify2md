package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeException
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import io.github.koucpy001.simplify2md.encoding.EncodingCodec
import org.json.JSONArray

/**
 * `SaveFile` binding for the SAF write path (plan todo 11).
 *
 * All encoding and newline conversion happens **in memory first**; only the
 * resulting bytes reach [SaveStore]. An unrepresentable codepoint is rejected
 * with the stable `encoding-unmappable` token (never written as `?`), which the
 * frontend maps to the "另存为 UTF-8" remedy (`lib/encoding-token.ts`).
 *
 * [Bridge.registerHandler] refuses any method outside the frozen
 * [BridgeMethods.WHITELIST]; `SaveFile` is already a member, so registering it
 * cannot widen the dispatch surface and adds no bridge function.
 */
class SaveBindings(
    private val store: SaveStore,
    /**
     * Opens the self-write window (plan todo 19) before the write starts,
     * mirroring the Go watcher's `lastSelfWriteNs` mark at the top of
     * `SaveFile` (`mdview/app.go:226`). A resume during or just after our own
     * save is therefore never mistaken for an external change. Defaults to a
     * no-op so the save semantics are unchanged when recents are not wired.
     */
    private val onSelfWrite: () -> Unit = {},
    /**
     * Write gate porting the desktop `canWrite` whitelist (`mdview/app.go:222`):
     * a URI the provider will not take a write for (read-only document, or a
     * foreign URI the app holds no grant for) must be rejected BEFORE the save
     * state machine starts. Otherwise the backup + journal it writes before the
     * write fails are deliberately kept, and the next launch's reconciliation
     * finds a `writing` journal whose expected hash never matches — a spurious
     * three-choice recovery dialog for a save that was refused. Defaults to
     * "permitted" so unwired tests keep the plain save semantics.
     */
    private val canWrite: (path: String) -> Boolean = { true },
) {

    suspend fun saveFile(path: String, content: String, encoding: String, newline: String) {
        if (path.isBlank()) throw BridgeException("empty path")
        if (!canWrite(path)) throw BridgeException(WRITE_NOT_PERMITTED)
        onSelfWrite()
        val encoded = try {
            EncodingCodec.encodeContent(EncodingCodec.applyNewline(content, newline), encoding)
        } catch (e: EncodingCodec.EncodingException) {
            // Preserve the stable token so the frontend can offer the remedy.
            throw BridgeException(e.token, e.message ?: e.token)
        }
        store.save(path, encoded)
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.SAVE_FILE) { args: JSONArray ->
            saveFile(
                path = args.optString(0, ""),
                content = args.optString(1, ""),
                encoding = args.optString(2, ""),
                newline = args.optString(3, ""),
            )
            null
        }
    }

    companion object {
        /**
         * Mirrors the desktop Go error string verbatim (`mdview/app.go:224`:
         * `errors.New("path not permitted")`) so the frontend's existing save
         * failure handling behaves identically on both platforms.
         */
        const val WRITE_NOT_PERMITTED = "path not permitted"
    }
}
