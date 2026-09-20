package io.github.koucpy001.simplify2md.binding

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONArray

/**
 * Wires the `BrowserOpenURL` binding (plan todo 8).
 *
 * Registration goes through [Bridge.registerHandler], which asserts membership
 * in [BridgeMethods.WHITELIST]; the dispatch surface therefore cannot widen.
 * The handler always returns `null` because the desktop `BrowserOpenURL` is
 * void — a blocked or handler-less URL is logged and resolved, never rejected,
 * so the page's fire-and-forget call cannot surface an unhandled rejection.
 *
 * The URL is untrusted input from the page; [ExternalLinks] decides what may be
 * launched. Nothing from the payload is interpreted as a command, path or
 * intent: only the scheme is inspected, and only `http`/`https`/`mailto` reach
 * `Intent.ACTION_VIEW`.
 */
class ExternalLinksBindings(
    private val externalLinks: ExternalLinks,
    private val onOutcome: (ExternalLinkOutcome) -> Unit = {},
) {

    /** Runs the policy for [url] and reports the outcome; always returns null. */
    fun openFromBridge(url: String?): Any? {
        onOutcome(externalLinks.open(url))
        return null
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.BROWSER_OPEN_URL) { args: JSONArray ->
            openFromBridge(args.optString(0, ""))
        }
    }
}
