package io.github.koucpy001.simplify2md.storage

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the foreground refresh policy (plan todo 19).
 *
 * The three plan branches map to tests here, and the data-loss guard asserts on
 * the RECORDED IO calls (no `reRead` on a dirty document), not merely on the
 * returned action. No Android type, no Robolectric, no clock.
 */
class ForegroundRefreshTest {

    private val uri = "content://com.example/doc/a.md"

    private class FakeCurrent(
        var uri: String? = null,
        var dirty: Boolean = false,
    ) : CurrentDocument {
        override fun uri(): String? = uri
        override fun isDirty(): Boolean = dirty
    }

    /** Records every IO call in order so tests can assert on the call list. */
    private class RecordingIo(
        var selfWriteActive: Boolean = false,
        var readable: Boolean = true,
        var readThrows: Boolean = false,
    ) : ForegroundRefreshIo {
        val calls = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val notified = mutableListOf<String>()
        var emits = 0

        override fun isSelfWriteWindowActive(): Boolean {
            calls += "selfWrite"
            return selfWriteActive
        }

        override fun canRead(uri: String): Boolean {
            calls += "canRead"
            return readable
        }

        override fun reRead(uri: String) {
            calls += "reRead"
            reads += uri
            if (readThrows) throw IOException("document deleted")
        }

        override fun emitFileChanged() {
            calls += "emit"
            emits += 1
        }

        override fun removeRecent(uri: String) {
            calls += "removeRecent"
            removed += uri
        }

        override fun notifyUnavailable(uri: String) {
            calls += "notify"
            notified += uri
        }
    }

    private fun policy(current: FakeCurrent, io: RecordingIo) = ForegroundRefreshPolicy(current, io)

    // ---- branch 1: dirty == false -> re-read ----

    @Test
    fun cleanDocumentIsReReadOnResume() {
        val io = RecordingIo()
        val action = policy(FakeCurrent(uri = uri, dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.RELOADED, action)
        assertEquals(listOf(uri), io.reads)
        assertEquals(1, io.emits)
        assertTrue(io.removed.isEmpty())
        assertTrue(io.notified.isEmpty())
    }

    @Test
    fun cleanDocumentResumeNeverRemovesOrPrompts() {
        val io = RecordingIo()
        policy(FakeCurrent(uri = uri, dirty = false), io).onResume()

        assertFalse(io.calls.contains("removeRecent"))
        assertFalse(io.calls.contains("notify"))
    }

    // ---- branch 2: dirty == true -> no read, emit exactly once ----

    @Test
    fun dirtyDocumentIsNotReReadAndEmitsFileChangedExactlyOnce() {
        val io = RecordingIo()
        val action = policy(FakeCurrent(uri = uri, dirty = true), io).onResume()

        assertEquals(ForegroundRefreshAction.PROMPTED, action)
        assertTrue(io.reads.isEmpty())
        assertEquals(1, io.emits)
        assertEquals(listOf("selfWrite", "emit"), io.calls)
    }

    @Test
    fun dirtyDocumentResumeDoesNotMutateTheEditorBuffer() {
        // Data-loss guard: the recorded IO call list proves the policy never
        // reads the document and never touches recents when the editor is dirty.
        val io = RecordingIo()
        policy(FakeCurrent(uri = uri, dirty = true), io).onResume()

        assertEquals(listOf("selfWrite", "emit"), io.calls)
        assertFalse(io.calls.contains("reRead"))
        assertFalse(io.calls.contains("canRead"))
        assertFalse(io.calls.contains("removeRecent"))
        assertFalse(io.calls.contains("notify"))
        assertTrue(io.removed.isEmpty())
        assertTrue(io.notified.isEmpty())
    }

    // ---- branch 3: read failure -> RemoveRecent + user told ----

    @Test
    fun deletedDocumentIsRemovedAndTheUserIsTold() {
        val io = RecordingIo(readable = true, readThrows = true)
        val action = policy(FakeCurrent(uri = uri, dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.UNAVAILABLE, action)
        assertEquals(listOf("selfWrite", "canRead", "reRead", "removeRecent", "notify"), io.calls)
        assertEquals(listOf(uri), io.removed)
        assertEquals(listOf(uri), io.notified)
        // Never emit a reload/inspect event for a document that cannot be read.
        assertEquals(0, io.emits)
    }

    @Test
    fun revokedGrantIsRemovedWithoutAttemptingARead() {
        val io = RecordingIo(readable = false)
        val action = policy(FakeCurrent(uri = uri, dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.UNAVAILABLE, action)
        assertEquals(listOf("selfWrite", "canRead", "removeRecent", "notify"), io.calls)
        assertTrue(io.reads.isEmpty())
        assertEquals(0, io.emits)
    }

    @Test
    fun uriResolvingToADirectoryIsTreatedAsUnavailable() {
        // A document URI that now points at a directory fails the read; the same
        // branch must fire instead of crashing or emitting a reload.
        val io = RecordingIo(readable = true, readThrows = true)
        val action = policy(FakeCurrent(uri = "content://com.example/tree/", dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.UNAVAILABLE, action)
        assertEquals(listOf("content://com.example/tree/"), io.removed)
    }

    @Test
    fun zeroByteDocumentIsStillReadable() {
        // A valid but empty document must not be mistaken for a read failure.
        val io = RecordingIo(readable = true, readThrows = false)
        val action = policy(FakeCurrent(uri = uri, dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.RELOADED, action)
        assertEquals(1, io.emits)
        assertTrue(io.removed.isEmpty())
    }

    // ---- self-write window (stale state: resume right after our own save) ----

    @Test
    fun resumeInsideTheSelfWriteWindowIsIgnored() {
        val io = RecordingIo(selfWriteActive = true)
        val action = policy(FakeCurrent(uri = uri, dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.SELF_WRITE, action)
        assertEquals(listOf("selfWrite"), io.calls)
        assertTrue(io.reads.isEmpty())
        assertEquals(0, io.emits)
    }

    @Test
    fun selfWriteWindowWinsEvenWhenTheDocumentIsDirty() {
        // An in-flight save keeps the editor dirty; the resume must still not
        // prompt for our own write.
        val io = RecordingIo(selfWriteActive = true)
        val action = policy(FakeCurrent(uri = uri, dirty = true), io).onResume()

        assertEquals(ForegroundRefreshAction.SELF_WRITE, action)
        assertEquals(0, io.emits)
    }

    // ---- no document ----

    @Test
    fun untitledDocumentResumeDoesNothing() {
        val io = RecordingIo()
        val action = policy(FakeCurrent(uri = null, dirty = true), io).onResume()

        assertEquals(ForegroundRefreshAction.NO_DOCUMENT, action)
        assertTrue(io.calls.isEmpty())
    }

    @Test
    fun blankUriResumeDoesNothing() {
        val io = RecordingIo()
        val action = policy(FakeCurrent(uri = "   ", dirty = false), io).onResume()

        assertEquals(ForegroundRefreshAction.NO_DOCUMENT, action)
        assertTrue(io.calls.isEmpty())
    }

    // ---- self-write window mechanics (the 500 ms desktop boundary) ----

    @Test
    fun selfWriteWindowIsActiveImmediatelyAfterMarkAndExpires() {
        var nowNs = 1_000_000_000L
        val window = SelfWriteWindow(windowMs = 500L, now = { nowNs })

        assertFalse(window.isActive())
        window.mark()
        assertTrue(window.isActive())

        nowNs += 499L * 1_000_000L
        assertTrue("still inside the window at +499ms", window.isActive())

        nowNs = 1_000_000_000L + 500L * 1_000_000L
        assertFalse("boundary is exclusive at exactly 500ms", window.isActive())
    }

    @Test
    fun secondMarkRestartsTheWindow() {
        var nowNs = 0L
        val window = SelfWriteWindow(windowMs = 500L, now = { nowNs })
        window.mark()
        nowNs += 400L * 1_000_000L
        window.mark()
        nowNs += 400L * 1_000_000L
        assertTrue("re-marking extends the window", window.isActive())
    }
}
