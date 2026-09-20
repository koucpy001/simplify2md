package io.github.koucpy001.simplify2md.binding

/**
 * Platform-neutral shape of a launch intent (plan todo 18).
 *
 * The Android `Intent` cannot be constructed under plain JVM JUnit (no
 * Robolectric), so [MainActivity] extracts only the fields this router needs
 * into [IntentPayload] and the routing decision itself stays pure and testable
 * — the established pattern (`ExternalUrlLauncher`, `SafStore`, `RecoveryPrompt`,
 * `UpdateHttpClient`).
 */
data class IntentPayload(
    val action: String? = null,
    /** `intent.dataString` — null for ACTION_SEND and fileless launches. */
    val dataUri: String? = null,
    /** `EXTRA_TEXT` as a String, or null when absent / not a String. */
    val extraText: String? = null,
    /**
     * `EXTRA_STREAM` as a URI string, or null when absent or not a URI (the
     * extractor drops any non-Uri extra so a malformed share can never leak
     * arbitrary text into the document path).
     */
    val extraStreamUri: String? = null,
)

/** Where a launch intent must be delivered. */
sealed interface IntentRoute {
    /** Cold-start file URI -> `StartupFileGate` (consume-once `GetStartupFile`). */
    data class ColdFile(val uri: String) : IntentRoute

    /** Cold-start plain text -> Kotlin-side buffer, dispatched as `mdview:open-text` after bridge-ready. */
    data class ColdText(val text: String) : IntentRoute

    /** Warm-start (`onNewIntent`) file URI -> event queue -> `mdview:open-path`. */
    data class WarmFile(val uri: String) : IntentRoute

    /** Warm-start plain text -> event queue -> `mdview:open-text`. */
    data class WarmText(val text: String) : IntentRoute

    /** No handler: the intent is silently ignored, never crashed on. */
    data object Ignore : IntentRoute
}

/**
 * The single-delivery routing matrix (plan todo 18c-e, review NEW-1).
 *
 * Cold-start FILE intents (VIEW / EDIT / SEND+EXTRA_STREAM) do NOT enter the
 * event queue — they are consumed by `GetStartupFile()` (consume-once), so the
 * same URI can never be delivered twice. Cold-start PURE-TEXT intents are
 * buffered separately by Kotlin and dispatched as `mdview:open-text` after
 * bridge-ready (otherwise the first "share text to app" would be silently
 * lost). ONLY `onNewIntent` (warm start) enters the queue.
 *
 * When an intent carries BOTH `EXTRA_STREAM` and `EXTRA_TEXT`, `EXTRA_STREAM`
 * wins and `EXTRA_TEXT` is ignored — hard-coded priority, never both carriers.
 *
 * `ACTION_EDIT` and `ACTION_VIEW` take the SAME path (edit/preview is the
 * frontend's view-mode decision).
 *
 * Untrusted-input policy: a file URI is only accepted with a `content:` or
 * `file:` scheme. Anything else (`javascript:`, `data:`, unknown schemes, null
 * data, empty EXTRA_TEXT, a non-URI EXTRA_STREAM) is [IntentRoute.Ignore] —
 * shared text is delivered as document CONTENT through the JSON-encoded
 * `mdview:open-text` payload, never executed or interpreted as a path.
 */
object IntentRouter {

    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ACTION_EDIT = "android.intent.action.EDIT"
    private const val ACTION_SEND = "android.intent.action.SEND"

    /** Only SAF-style document URIs and legacy file paths are accepted as files. */
    private val FILE_SCHEMES = setOf("content", "file")

    fun route(payload: IntentPayload, warmStart: Boolean): IntentRoute = when (payload.action) {
        ACTION_VIEW, ACTION_EDIT -> fileRoute(payload.dataUri, warmStart)
        ACTION_SEND ->
            // Hard-coded priority: EXTRA_STREAM beats EXTRA_TEXT.
            fileRoute(payload.extraStreamUri, warmStart).takeUnless { it is IntentRoute.Ignore }
                ?: textRoute(payload.extraText, warmStart)
        else -> IntentRoute.Ignore
    }

    private fun fileRoute(uri: String?, warmStart: Boolean): IntentRoute {
        val scheme = uri?.trim()?.substringBefore(':')?.lowercase()
        if (uri.isNullOrBlank() || scheme !in FILE_SCHEMES) return IntentRoute.Ignore
        return if (warmStart) IntentRoute.WarmFile(uri.trim()) else IntentRoute.ColdFile(uri.trim())
    }

    private fun textRoute(text: String?, warmStart: Boolean): IntentRoute {
        // Empty EXTRA_TEXT is dropped; whitespace-only text is a legitimate share.
        if (text.isNullOrEmpty()) return IntentRoute.Ignore
        return if (warmStart) IntentRoute.WarmText(text) else IntentRoute.ColdText(text)
    }
}
