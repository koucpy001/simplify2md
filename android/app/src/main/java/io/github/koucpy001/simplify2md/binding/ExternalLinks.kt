package io.github.koucpy001.simplify2md.binding

/**
 * Platform launcher for an external URL.
 *
 * The Android implementation starts `Intent.ACTION_VIEW` and reports `false`
 * when no activity can handle it (`ActivityNotFoundException`); the difference
 * between "opened" and "no handler" is a plain [Boolean] so this decision layer
 * carries no Android type and runs under plain JVM JUnit.
 */
fun interface ExternalUrlLauncher {
    /**
     * @return true when an activity handled the URL, false when none exists.
     *         Implementations must not throw for a missing handler.
     */
    fun launch(url: String): Boolean
}

/** Outcome of an attempt to open an external URL. */
sealed interface ExternalLinkOutcome {
    /** An activity handled and started the URL. */
    data class Opened(val scheme: String) : ExternalLinkOutcome

    /** The scheme is allowed but no installed app can handle it (no crash). */
    data class NoHandler(val scheme: String) : ExternalLinkOutcome

    /** The scheme is not in [ExternalLinks.ALLOWED_SCHEMES] and was never launched. */
    data class Blocked(val scheme: String) : ExternalLinkOutcome

    /** The value is empty or has no parseable scheme. */
    data class Invalid(val reason: String) : ExternalLinkOutcome
}

/**
 * `BrowserOpenURL` policy.
 *
 * The URL originates from the page (link clicks and the update-check tip), so it
 * is untrusted input. Only `http`, `https` and `mailto` are forwarded to the
 * platform launcher; every other scheme — including `javascript:`, `intent:`,
 * `file:` and `data:` — is [ExternalLinkOutcome.Blocked], which is how a scheme
 * with no handler (for example `foo://bar`) is guaranteed not to crash the app.
 *
 * A missing *browser* for an allowed scheme is not an error either: the launcher
 * reports it as [ExternalUrlLauncher.launch] returning `false`, which becomes
 * [ExternalLinkOutcome.NoHandler]. The handler still resolves (the desktop
 * `BrowserOpenURL` returns void), so the fire-and-forget call never rejects.
 */
class ExternalLinks(private val launcher: ExternalUrlLauncher) {

    fun open(rawUrl: String?): ExternalLinkOutcome {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return ExternalLinkOutcome.Invalid("empty url")
        val scheme = schemeOf(url) ?: return ExternalLinkOutcome.Invalid("missing or malformed scheme")
        if (scheme !in ALLOWED_SCHEMES) return ExternalLinkOutcome.Blocked(scheme)
        return if (launcher.launch(url)) {
            ExternalLinkOutcome.Opened(scheme)
        } else {
            ExternalLinkOutcome.NoHandler(scheme)
        }
    }

    companion object {
        /** Schemes the app is willing to hand to the system. */
        val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto")

        /**
         * Extracts the lower-cased scheme per the RFC 3986 grammar
         * (`ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":"`), or null when the
         * value does not start with a well-formed scheme.
         */
        fun schemeOf(url: String): String? {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            val scheme = url.substring(0, colon)
            if (!scheme.first().isLetter()) return null
            if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
            return scheme.lowercase()
        }
    }
}
