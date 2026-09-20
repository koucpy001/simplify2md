package io.github.koucpy001.simplify2md.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `UpdateChecker` parity with `App.CheckForUpdate`
 * (`mdview/app.go:642-692`, `mdview/app_update_test.go:61-206`).
 *
 * The HTTP boundary is faked ([RecordingUpdateHttpClient]); no test touches the
 * network, so timeouts/connectivity can never make this suite flaky.
 */
class UpdateCheckerTest {

    private class RecordingUpdateHttpClient(
        private var response: UpdateHttpResponse = UpdateHttpResponse(200, null),
    ) : UpdateHttpClient {
        var calls = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()

        override fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
            calls++
            lastUrl = url
            lastHeaders = headers
            return response
        }
    }

    private fun json(tag: String?, htmlUrl: String?): String {
        val tagField = tag?.let { "\"tag_name\":${quote(it)}" } ?: "\"other\":1"
        val urlField = htmlUrl?.let { "\"html_url\":${quote(it)}" } ?: ""
        return "{${listOf(tagField, urlField).filter { it.isNotEmpty() }.joinToString(",")}}"
    }

    private fun quote(value: String): String = org.json.JSONObject.quote(value)

    private fun checkerFor(
        http: RecordingUpdateHttpClient,
        version: String,
    ): UpdateChecker = UpdateChecker(http, currentVersion = { version })

    @Test
    fun devBuildShortCircuitsWithoutAnyNetworkCall() {
        val http = RecordingUpdateHttpClient(
            UpdateHttpResponse(200, json("v9.9.9", "https://github.com/koucpy001/simplify2md/releases/tag/v9.9.9")),
        )
        val checker = checkerFor(http, "dev")

        val info = checker.check()

        assertEquals(UpdateInfo.NONE, info)
        assertEquals(0, http.calls)
    }

    @Test
    fun requestCarriesTheGithubHeadersAndTheDefaultUrl() {
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, json("1.0.0", null)))
        val checker = checkerFor(http, "1.0.0")

        checker.check()

        assertEquals(UpdateChecker.DEFAULT_UPDATE_URL, http.lastUrl)
        assertEquals("simplify2md", http.lastHeaders["User-Agent"])
        assertEquals("application/vnd.github+json", http.lastHeaders["Accept"])
    }

    @Test
    fun newerTagReportsAnUpdateAndKeepsTheWhitelistedUrl() {
        val url = "https://github.com/koucpy001/simplify2md/releases/tag/v1.2.3"
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, json("v1.2.3", url)))
        val checker = checkerFor(http, "1.0.0")

        val info = checker.check()

        assertTrue(info.hasUpdate)
        assertEquals("v1.2.3", info.latestTag)
        assertEquals(url, info.htmlURL)
    }

    @Test
    fun upToDateTagReportsNoUpdate() {
        val http = RecordingUpdateHttpClient(
            UpdateHttpResponse(200, json("1.2.3", "https://github.com/koucpy001/simplify2md/releases/tag/1.2.3")),
        )
        val checker = checkerFor(http, "1.2.3")

        assertFalse(checker.check().hasUpdate)
        assertEquals(1, http.calls)
    }

    @Test
    fun malformedTagIsNoUpdateNotAnError() {
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, json("not-a-version", null)))
        val checker = checkerFor(http, "1.0.0")

        assertEquals(UpdateInfo.NONE, checker.check())
    }

    @Test
    fun hugeTagIsNoUpdateNotAnError() {
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, json("99999999999999999999", null)))
        val checker = checkerFor(http, "1.0.0")

        assertEquals(UpdateInfo.NONE, checker.check())
    }

    @Test
    fun missingTagNameIsNoUpdate() {
        val http = RecordingUpdateHttpClient(
            UpdateHttpResponse(200, json(null, "https://github.com/koucpy001/simplify2md/releases/x")),
        )
        val checker = checkerFor(http, "1.0.0")

        assertEquals(UpdateInfo.NONE, checker.check())
    }

    @Test
    fun nonWhitelistedHtmlUrlsAreDropped() {
        val bad = listOf(
            "http://github.com/koucpy001/simplify2md/releases/x",
            "javascript:alert(1)",
            "https://evil.example.com/simplify2md/releases/x",
            "https://evil.com/github.com/koucpy001/simplify2md/releases/x",
            "",
        )
        for (url in bad) {
            val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, json("9.9.9", url)))
            val checker = checkerFor(http, "1.0.0")

            val info = checker.check()

            assertTrue("tag 9.9.9 should still report an update for $url", info.hasUpdate)
            assertEquals("url $url must be rejected", "", info.htmlURL)
        }
    }

    @Test
    fun rawFileLookalikeUrlIsDropped() {
        val http = RecordingUpdateHttpClient(
            UpdateHttpResponse(200, json("9.9.9", "file:///etc/passwd")),
        )
        val checker = checkerFor(http, "1.0.0")

        assertEquals("", checker.check().htmlURL)
    }

    @Test
    fun nonTwoHundredStatusThrows() {
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(403, null))
        val checker = checkerFor(http, "1.0.0")

        try {
            checker.check()
            fail("403 must throw (no panic)")
        } catch (e: UpdateCheckException) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    @Test
    fun malformedJsonBodyThrows() {
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, "this is not json"))
        val checker = checkerFor(http, "1.0.0")

        try {
            checker.check()
            fail("malformed JSON must throw (no panic)")
        } catch (_: UpdateCheckException) {
            // expected
        }
    }

    @Test
    fun emptyBodyThrows() {
        val http = RecordingUpdateHttpClient(UpdateHttpResponse(200, ""))
        val checker = checkerFor(http, "1.0.0")

        try {
            checker.check()
            fail("empty body must throw (no panic)")
        } catch (_: UpdateCheckException) {
            // expected
        }
    }
}
