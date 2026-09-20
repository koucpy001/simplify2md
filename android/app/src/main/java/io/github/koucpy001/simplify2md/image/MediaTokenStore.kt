package io.github.koucpy001.simplify2md.image

import java.util.UUID

/**
 * Token table backing the local `/media/<token>` streaming endpoint (plan todo
 * 13e). `LoadImageForSrc` never returns base64 on Android: it registers the
 * resolved child document URI under an opaque token and hands the frontend a
 * `https://appassets.androidplatform.net/media/<token>` URL. The WebView then
 * requests that URL and `MediaWebViewPathHandler` streams the bytes from the
 * `ContentResolver` (so a low-end device never materializes a ~43 MB base64 JS
 * string for a 32 MB image).
 *
 * The table is bounded (LRU, 64 by default) and cleared when the Activity is
 * destroyed: a long session scrolling many documents must not accumulate
 * unbounded document URIs, and stale tokens must not survive a restart.
 */
class MediaTokenStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {

    /** One entry: the document URI a token streams and its Content-Type. */
    data class Entry(val token: String, val uri: String, val mime: String)

    private val lock = Any()

    // accessOrder=true makes iteration order least-recently-used first, so the
    // eviction below drops the oldest entry. resolve() counts as a use.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {}

    /** Registers [uri] and returns its new token, evicting the LRU entry over cap. */
    fun register(uri: String, mime: String): String {
        val token = tokenFactory()
        synchronized(lock) {
            entries[token] = Entry(token, uri, mime)
            val iterator = entries.entries.iterator()
            while (entries.size > capacity && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return token
    }

    /** The entry for [token], or null when unknown / evicted. */
    fun resolve(token: String): Entry? = synchronized(lock) { entries[token] }

    /** Activity destroy: drop every token so no stale URI outlives the session. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
