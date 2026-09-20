package io.github.koucpy001.simplify2md.update

import io.github.koucpy001.simplify2md.bridge.Bridge
import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONObject

/**
 * Bridge binding for `CheckForUpdate` (plan todo 15).
 *
 * [BridgeMethods.CHECK_FOR_UPDATE] is already a member of the frozen whitelist,
 * so this class registers an existing name and adds no bridge function and no
 * event. The result uses the unified `UpdateInfoLike` shape
 * (`{hasUpdate, latestTag, htmlURL}`) the frontend expects from both platforms.
 */
class UpdateBindings(private val checker: UpdateChecker) {

    /** `CheckForUpdate`: runs the check once and returns the `UpdateInfoLike` JSON. */
    fun checkForUpdate(): JSONObject {
        val info = checker.check()
        return JSONObject()
            .put(KEY_HAS_UPDATE, info.hasUpdate)
            .put(KEY_LATEST_TAG, info.latestTag)
            .put(KEY_HTML_URL, info.htmlURL)
    }

    fun registerOn(bridge: Bridge) {
        bridge.registerHandler(BridgeMethods.CHECK_FOR_UPDATE) { checkForUpdate() }
    }

    companion object {
        const val KEY_HAS_UPDATE = "hasUpdate"
        const val KEY_LATEST_TAG = "latestTag"
        const val KEY_HTML_URL = "htmlURL"
    }
}
