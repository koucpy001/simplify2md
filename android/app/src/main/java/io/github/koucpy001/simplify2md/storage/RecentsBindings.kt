package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge bindings for the recents model (plan todo 12).
 *
 * Registers `GetRecents`, `RemoveRecent` and `ClearRecents` — all three are
 * already members of the frozen [BridgeMethods.WHITELIST], so this class adds
 * no bridge function.
 *
 * `GetRecents` returns the unified `RecentEntryLike[]` shape, `{id, name}[]`:
 * `id` is the opaque URI/path used for routing and `name` is the provider
 * DISPLAY_NAME (Android) or path basename (desktop shim). The URI's last
 * segment is never used as a name — opaque providers return junk such as
 * `msf%3A1000000123`.
 */
class RecentsBindings(private val recents: RecentsStore) {

    /** `GetRecents`: `[{id, name}]`, most recent first. */
    fun getRecents(): JSONArray {
        val array = JSONArray()
        for (entry in recents.list()) {
            array.put(
                JSONObject()
                    .put(KEY_ID, entry.uri)
                    .put(KEY_NAME, entry.displayName),
            )
        }
        return array
    }

    /** `RemoveRecent`: drop one entry, releasing its grant unless it is open. */
    fun removeRecent(id: String) {
        if (id.isBlank()) return
        recents.remove(id)
    }

    /** `ClearRecents`: drop every entry, keeping the currently open grant. */
    fun clearRecents() {
        recents.clear()
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.GET_RECENTS) { getRecents() }
        bridge.registerHandler(BridgeMethods.REMOVE_RECENT) { args ->
            removeRecent(args.optString(0, ""))
            null
        }
        bridge.registerHandler(BridgeMethods.CLEAR_RECENTS) {
            clearRecents()
            null
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
    }
}
