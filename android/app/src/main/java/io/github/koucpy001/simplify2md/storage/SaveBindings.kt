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
) {

    suspend fun saveFile(path: String, content: String, encoding: String, newline: String) {
        if (path.isBlank()) throw BridgeException("empty path")
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
}
