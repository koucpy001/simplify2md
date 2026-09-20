package io.github.koucpy001.simplify2md.update

import org.json.JSONException
import org.json.JSONObject

/**
 * Result of an update check, shaped to match the desktop `UpdateInfo`
 * (`mdview/app.go:629-633`) and the frontend `UpdateInfoLike`.
 *
 * [htmlURL] is only ever the whitelisted GitHub releases URL; the backend never
 * opens it — the frontend calls `BrowserOpenURL` on it, and that path is itself
 * policy-gated (`binding/ExternalLinks.kt`).
 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestTag: String,
    val htmlURL: String,
) {
    companion object {
        /** "No update, nothing to do" — also the `dev` short-circuit result. */
        val NONE = UpdateInfo(hasUpdate = false, latestTag = "", htmlURL = "")
    }
}

/**
 * A failed check the frontend may surface (manual path) or swallow (auto path).
 * Malformed *tags* are deliberately not this: they are "no update".
 */
class UpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Ports `App.CheckForUpdate` (`mdview/app.go:642-692`).
 *
 * Fail-soft by design: a malformed tag, an unparseable running version, or a
 * missing field all become [UpdateInfo.NONE] rather than an error. A non-200
 * status or malformed JSON body throws [UpdateCheckException], matching the Go
 * errors the frontend already handles (silent on auto-start, visible on manual).
 *
 * A `"dev"` build short-circuits **before** any network call: no request is
 * made at all ([currentVersion] is the only thing read).
 */
class UpdateChecker(
    private val http: UpdateHttpClient,
    private val currentVersion: () -> String,
    private val baseUrl: String = DEFAULT_UPDATE_URL,
) {

    fun check(): UpdateInfo {
        val version = currentVersion()
        if (version == DEV_VERSION) return UpdateInfo.NONE

        val response = http.get(baseUrl, REQUEST_HEADERS)
        if (response.statusCode != HTTP_OK) {
            throw UpdateCheckException("update check returned status ${response.statusCode}")
        }

        val payload = try {
            JSONObject(response.body ?: "")
        } catch (e: JSONException) {
            throw UpdateCheckException("malformed update response", e)
        }
        val tag = payload.optString(KEY_TAG_NAME)
        val rawUrl = payload.optString(KEY_HTML_URL)

        val hasUpdate = try {
            Semver.greaterThan(tag, version)
        } catch (_: SemverException) {
            // A version we can't compare is "no update", not an error, so a
            // malformed upstream tag never nags the user (mdview/app.go:676-681).
            return UpdateInfo.NONE
        }

        return UpdateInfo(
            hasUpdate = hasUpdate,
            latestTag = tag,
            // Strictly whitelist the release URL: anything not under our
            // releases namespace is dropped, so an arbitrary (possibly hostile)
            // html_url from the API can never reach the opener.
            htmlURL = if (rawUrl.startsWith(SAFE_URL_PREFIX)) rawUrl else "",
        )
    }

    companion object {
        const val DEV_VERSION = "dev"
        const val DEFAULT_UPDATE_URL =
            "https://api.github.com/repos/koucpy001/simplify2md/releases/latest"

        /** The only URL prefix the backend keeps; matches `mdview/app.go:69,686`. */
        const val SAFE_URL_PREFIX = "https://github.com/koucpy001/simplify2md/releases"

        const val USER_AGENT = "simplify2md"
        const val ACCEPT = "application/vnd.github+json"
        const val USER_AGENT_HEADER = "User-Agent"
        const val ACCEPT_HEADER = "Accept"

        const val KEY_TAG_NAME = "tag_name"
        const val KEY_HTML_URL = "html_url"
        const val HTTP_OK = 200

        val REQUEST_HEADERS: Map<String, String> =
            mapOf(USER_AGENT_HEADER to USER_AGENT, ACCEPT_HEADER to ACCEPT)
    }
}
