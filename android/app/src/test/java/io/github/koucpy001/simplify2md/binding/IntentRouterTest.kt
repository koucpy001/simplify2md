package io.github.koucpy001.simplify2md.binding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cell-by-cell coverage of the plan todo 18 delivery matrix (review NEW-1):
 * every action x cold/warm combination must have a handler, the same URI must
 * never be delivered twice, and EXTRA_STREAM beats EXTRA_TEXT.
 */
class IntentRouterTest {

    private val fileUri = "content://com.example/doc/a.md"
    private val streamUri = "content://com.example/stream/b.md"

    private fun payload(
        action: String? = null,
        dataUri: String? = null,
        extraText: String? = null,
        extraStreamUri: String? = null,
    ) = IntentPayload(action, dataUri, extraText, extraStreamUri)

    // ---- cold start (process newly created) ----

    @Test
    fun coldActionViewReturnsTheFileUriExactlyOnce() {
        assertEquals(
            IntentRoute.ColdFile(fileUri),
            IntentRouter.route(IntentPayload(action = "android.intent.action.VIEW", dataUri = fileUri), warmStart = false),
        )
    }

    @Test
    fun coldActionEditTakesTheSamePathAsView() {
        assertEquals(
            IntentRoute.ColdFile(fileUri),
            IntentRouter.route(IntentPayload(action = "android.intent.action.EDIT", dataUri = fileUri), warmStart = false),
        )
    }

    @Test
    fun coldSendWithStreamOpensTheFileLikeView() {
        assertEquals(
            IntentRoute.ColdFile(streamUri),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraStreamUri = streamUri),
                warmStart = false,
            ),
        )
    }

    @Test
    fun coldSendWithPlainTextIsBufferedNotQueued() {
        assertEquals(
            IntentRoute.ColdText("hello"),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraText = "hello"),
                warmStart = false,
            ),
        )
    }

    @Test
    fun coldSendWithBothCarriersDeliversStreamOnly() {
        assertEquals(
            IntentRoute.ColdFile(streamUri),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraText = "ignored text", extraStreamUri = streamUri),
                warmStart = false,
            ),
        )
    }

    // ---- warm start (onNewIntent under singleTask) ----

    @Test
    fun warmActionViewEntersTheQueueAsOpenPath() {
        assertEquals(
            IntentRoute.WarmFile(fileUri),
            IntentRouter.route(IntentPayload(action = "android.intent.action.VIEW", dataUri = fileUri), warmStart = true),
        )
    }

    @Test
    fun warmActionEditEntersTheQueueAsOpenPath() {
        assertEquals(
            IntentRoute.WarmFile(fileUri),
            IntentRouter.route(IntentPayload(action = "android.intent.action.EDIT", dataUri = fileUri), warmStart = true),
        )
    }

    @Test
    fun warmSendWithStreamEntersTheQueueAsOpenPath() {
        assertEquals(
            IntentRoute.WarmFile(streamUri),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraStreamUri = streamUri),
                warmStart = true,
            ),
        )
    }

    @Test
    fun warmSendWithPlainTextEntersTheQueueAsOpenText() {
        assertEquals(
            IntentRoute.WarmText("hello"),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraText = "hello"),
                warmStart = true,
            ),
        )
    }

    @Test
    fun warmSendWithBothCarriersDeliversStreamOnly() {
        assertEquals(
            IntentRoute.WarmFile(streamUri),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraText = "ignored text", extraStreamUri = streamUri),
                warmStart = true,
            ),
        )
    }

    // ---- adversarial / malformed inputs ----

    @Test
    fun nullActionIsIgnored() {
        assertEquals(IntentRoute.Ignore, IntentRouter.route(IntentPayload(action = null, dataUri = fileUri), warmStart = false))
    }

    @Test
    fun viewWithNullDataUriIsIgnored() {
        assertEquals(
            IntentRoute.Ignore,
            IntentRouter.route(IntentPayload(action = "android.intent.action.VIEW", dataUri = null), warmStart = false),
        )
    }

    @Test
    fun javascriptSchemeUriIsNeverTreatedAsAFile() {
        assertEquals(
            IntentRoute.Ignore,
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.VIEW", dataUri = "javascript:alert(1)"),
                warmStart = false,
            ),
        )
    }

    @Test
    fun unknownSchemeUriIsIgnored() {
        assertEquals(
            IntentRoute.Ignore,
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.VIEW", dataUri = "foo://bar/baz.md"),
                warmStart = true,
            ),
        )
    }

    @Test
    fun fileSchemeUriIsAcceptedAsAFile() {
        assertEquals(
            IntentRoute.ColdFile("file:///sdcard/a.md"),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.VIEW", dataUri = "file:///sdcard/a.md"),
                warmStart = false,
            ),
        )
    }

    @Test
    fun emptyExtraTextIsIgnored() {
        assertEquals(
            IntentRoute.Ignore,
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraText = ""),
                warmStart = false,
            ),
        )
    }

    @Test
    fun sendWithoutAnyCarrierIsIgnored() {
        assertEquals(
            IntentRoute.Ignore,
            IntentRouter.route(IntentPayload(action = "android.intent.action.SEND"), warmStart = true),
        )
    }

    @Test
    fun textWithLineSeparatorsAndLoneSurrogateIsDeliveredVerbatim() {
        val hostile = "a\u2028b\u2029c\uD83D"
        assertEquals(
            IntentRoute.WarmText(hostile),
            IntentRouter.route(
                IntentPayload(action = "android.intent.action.SEND", extraText = hostile),
                warmStart = true,
            ),
        )
    }

    @Test
    fun twoRapidWarmOpensBothRouteAndTheLastOneWins() {
        val first = IntentRouter.route(
            IntentPayload(action = "android.intent.action.VIEW", dataUri = "content://a/1.md"),
            warmStart = true,
        )
        val second = IntentRouter.route(
            IntentPayload(action = "android.intent.action.VIEW", dataUri = "content://a/2.md"),
            warmStart = true,
        )
        assertEquals(IntentRoute.WarmFile("content://a/1.md"), first)
        assertEquals(IntentRoute.WarmFile("content://a/2.md"), second)
    }
}
