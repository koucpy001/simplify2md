package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge bindings for autosave drafts (plan todo 14), the Android counterpart
 * of `mdview/app.go:752-840`.
 *
 * Registers `SaveDraft`, `LoadDraft`, `ListDrafts` and `ClearDraft` — all four
 * are already members of the frozen [BridgeMethods.WHITELIST], so this class
 * adds no bridge function and cannot widen the dispatch surface.
 *
 * The key is validated by [DraftStore] before any IO, so an illegal key
 * (including one containing `../` or path separators) rejects the JS Promise
 * and writes nothing.
 */
class DraftBindings(private val store: DraftStore) {

    /** `SaveDraft(key, content)`. */
    fun saveDraft(key: String, content: String) {
        store.save(key, content)
    }

    /** `LoadDraft(key)`. */
    fun loadDraft(key: String): String = store.load(key)

    /** `ListDrafts()`: `[{key, modTime}]`, newest first (`DraftInfoLike[]`). */
    fun listDrafts(): JSONArray {
        val array = JSONArray()
        for (draft in store.list()) {
            array.put(
                JSONObject()
                    .put(KEY_KEY, draft.key)
                    .put(KEY_MOD_TIME, draft.modTime),
            )
        }
        return array
    }

    /** `ClearDraft(key)`. */
    fun clearDraft(key: String) {
        store.clear(key)
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.SAVE_DRAFT) { args ->
            saveDraft(args.optString(0, ""), args.optString(1, ""))
            null
        }
        bridge.registerHandler(BridgeMethods.LOAD_DRAFT) { args ->
            loadDraft(args.optString(0, ""))
        }
        bridge.registerHandler(BridgeMethods.LIST_DRAFTS) { listDrafts() }
        bridge.registerHandler(BridgeMethods.CLEAR_DRAFT) { args ->
            clearDraft(args.optString(0, ""))
            null
        }
    }

    companion object {
        const val KEY_KEY = "key"
        const val KEY_MOD_TIME = "modTime"
    }
}
