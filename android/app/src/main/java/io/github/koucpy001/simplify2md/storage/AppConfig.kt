package io.github.koucpy001.simplify2md.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recently opened document (plan todo 12).
 *
 * [uri] is an opaque handle: on Android it is a `content://` SAF document URI,
 * on desktop a filesystem path. It is **never** parsed for a filename — an
 * opaque provider yields junk such as `msf%3A123`, so [displayName] always comes
 * from the provider's DISPLAY_NAME (Android) or the path basename (desktop).
 */
data class RecentEntry(
    val uri: String,
    val displayName: String,
    val lastOpened: Long,
    val readonly: Boolean,
)

/**
 * The on-disk recents shape: a JSON array of
 * `{uri, displayName, lastOpened, readonly}`.
 *
 * Decoding is deliberately tolerant because the file is user-visible state that
 * may be truncated, hand-edited, or written by an older build. A malformed file
 * yields an empty list rather than an exception (a corrupt config must not brick
 * startup), entries without a non-blank `uri` are dropped, and a legacy
 * array-of-strings shape (bare paths) is upgraded to entries with an empty
 * display name.
 */
object AppConfigCodec {

    const val KEY_URI = "uri"
    const val KEY_DISPLAY_NAME = "displayName"
    const val KEY_LAST_OPENED = "lastOpened"
    const val KEY_READONLY = "readonly"

    fun encode(entries: List<RecentEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put(KEY_URI, entry.uri)
                    .put(KEY_DISPLAY_NAME, entry.displayName)
                    .put(KEY_LAST_OPENED, entry.lastOpened)
                    .put(KEY_READONLY, entry.readonly),
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<RecentEntry> {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<RecentEntry>(array.length())
        for (i in 0 until array.length()) {
            val entry = entryOf(array.opt(i)) ?: continue
            out.add(entry)
        }
        return out
    }

    private fun entryOf(element: Any?): RecentEntry? = when (element) {
        // Legacy shape: a bare array of path strings -> no display name known.
        is String -> element.takeIf { it.isNotBlank() }
            ?.let { RecentEntry(uri = it, displayName = "", lastOpened = 0L, readonly = false) }

        is JSONObject -> {
            val uri = element.optString(KEY_URI, "").trim()
            if (uri.isEmpty()) {
                null
            } else {
                RecentEntry(
                    uri = uri,
                    displayName = element.optString(KEY_DISPLAY_NAME, ""),
                    lastOpened = element.optLong(KEY_LAST_OPENED, 0L),
                    readonly = element.optBoolean(KEY_READONLY, false),
                )
            }
        }

        else -> null
    }
}

/** Location of the single app config file. */
object AppConfig {
    /** Relative to `filesDir`; created on first write. */
    const val FILE_NAME = "config.json"
}
