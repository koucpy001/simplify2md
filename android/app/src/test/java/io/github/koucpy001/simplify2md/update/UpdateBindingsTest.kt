package io.github.koucpy001.simplify2md.update

import io.github.koucpy001.simplify2md.bridge.BridgeMethods
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `CheckForUpdate` bridge result shape (plan todo 15). */
class UpdateBindingsTest {

    private val http = UpdateHttpClient { _, _ ->
        UpdateHttpResponse(
            200,
            """{"tag_name":"v1.2.3","html_url":"https://github.com/koucpy001/simplify2md/releases/tag/v1.2.3"}""",
        )
    }

    @Test
    fun resultUsesTheUnifiedCamelCaseShape() {
        val bindings = UpdateBindings(UpdateChecker(http, currentVersion = { "1.0.0" }))

        val result: JSONObject = bindings.checkForUpdate()

        assertEquals(true, result.getBoolean(UpdateBindings.KEY_HAS_UPDATE))
        assertEquals("v1.2.3", result.getString(UpdateBindings.KEY_LATEST_TAG))
        assertEquals(
            "https://github.com/koucpy001/simplify2md/releases/tag/v1.2.3",
            result.getString(UpdateBindings.KEY_HTML_URL),
        )
    }

    @Test
    fun devBuildEncodesAsNoUpdate() {
        val bindings = UpdateBindings(UpdateChecker(http, currentVersion = { "dev" }))

        val result = bindings.checkForUpdate()

        assertEquals(false, result.getBoolean(UpdateBindings.KEY_HAS_UPDATE))
        assertEquals("", result.getString(UpdateBindings.KEY_LATEST_TAG))
        assertEquals("", result.getString(UpdateBindings.KEY_HTML_URL))
    }

    @Test
    fun checkForUpdateIsAnExistingWhitelistedName() {
        assertTrue(BridgeMethods.isKnown(BridgeMethods.CHECK_FOR_UPDATE))
    }
}
