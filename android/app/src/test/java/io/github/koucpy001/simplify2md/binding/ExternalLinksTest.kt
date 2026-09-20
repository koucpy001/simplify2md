package io.github.koucpy001.simplify2md.binding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinksTest {

    private val launched = mutableListOf<String>()

    private fun links(result: Boolean = true): ExternalLinks =
        ExternalLinks(
            ExternalUrlLauncher { url ->
                launched.add(url)
                result
            },
        )

    @Test
    fun emptyOrNullUrlIsInvalidAndNeverLaunched() {
        val links = links()
        assertEquals(ExternalLinkOutcome.Invalid("empty url"), links.open(""))
        assertEquals(ExternalLinkOutcome.Invalid("empty url"), links.open("   "))
        assertEquals(ExternalLinkOutcome.Invalid("empty url"), links.open(null))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun urlWithoutAWellFormedSchemeIsInvalid() {
        val links = links()
        assertEquals(ExternalLinkOutcome.Invalid("missing or malformed scheme"), links.open("example.com/page"))
        assertEquals(ExternalLinkOutcome.Invalid("missing or malformed scheme"), links.open(":nonsense"))
        assertEquals(ExternalLinkOutcome.Invalid("missing or malformed scheme"), links.open("1http://x"))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun unknownSchemeIsBlockedWithoutLaunching() {
        val links = links()
        assertEquals(ExternalLinkOutcome.Blocked("foo"), links.open("foo://bar"))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun dangerousSchemesAreBlockedWithoutLaunching() {
        val links = links()
        assertEquals(ExternalLinkOutcome.Blocked("javascript"), links.open("javascript:alert(1)"))
        assertEquals(ExternalLinkOutcome.Blocked("file"), links.open("file:///etc/passwd"))
        assertEquals(ExternalLinkOutcome.Blocked("intent"), links.open("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(ExternalLinkOutcome.Blocked("data"), links.open("data:text/html,<script>x</script>"))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun allowedSchemesAreLaunched() {
        val links = links()
        assertEquals(ExternalLinkOutcome.Opened("http"), links.open("http://example.com"))
        assertEquals(ExternalLinkOutcome.Opened("https"), links.open("https://example.com/a?b=c#d"))
        assertEquals(ExternalLinkOutcome.Opened("mailto"), links.open("mailto:user@example.com"))
        assertEquals(3, launched.size)
    }

    @Test
    fun schemeIsLowerCasedAndUrlIsTrimmed() {
        val links = links()
        assertEquals(ExternalLinkOutcome.Opened("https"), links.open("  HTTPS://Example.COM/x  "))
        assertEquals(listOf("HTTPS://Example.COM/x"), launched)
    }

    @Test
    fun missingHandlerForAnAllowedSchemeIsReportedInsteadOfCrashing() {
        val links = links(result = false)
        assertEquals(ExternalLinkOutcome.NoHandler("https"), links.open("https://example.com"))
        assertEquals(listOf("https://example.com"), launched)
    }

    @Test
    fun schemeOfFollowsTheRfcGrammar() {
        assertEquals("https", ExternalLinks.schemeOf("https://x"))
        assertEquals("a+b-c.d", ExternalLinks.schemeOf("a+b-c.d://x"))
        assertNull(ExternalLinks.schemeOf("no-colon"))
        assertNull(ExternalLinks.schemeOf(":x"))
        assertNull(ExternalLinks.schemeOf("ht tp://x"))
        assertNull(ExternalLinks.schemeOf(""))
    }
}
