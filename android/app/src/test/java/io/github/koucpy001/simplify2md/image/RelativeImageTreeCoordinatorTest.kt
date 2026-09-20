package io.github.koucpy001.simplify2md.image

import io.github.koucpy001.simplify2md.bridge.BridgeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The (c) pending state machine for relative images (plan todo 13c, review D9).
 *
 * Every rule is JVM-tested with the launcher abstracted behind [TreeGrantLauncher]:
 *  - same document re-enqueue fires exactly ONE prompt;
 *  - a grant resolves every pending waiter at once;
 *  - a cancel rejects every waiter at once and sets the session denial once;
 *  - a launcher result arriving with an empty pending queue only persists the grant;
 *  - a document switch rejects the previous generation's pending waiters.
 */
class RelativeImageTreeCoordinatorTest {

    private class CountingLauncher(var result: TreeGrantResult = TreeGrantResult.Cancelled) : TreeGrantLauncher {
        var launches = 0
        override suspend fun launchTreePicker(): TreeGrantResult {
            launches++
            return result
        }
    }

    private class GateLauncher : TreeGrantLauncher {
        val started = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<TreeGrantResult>()
        var launches = 0
        override suspend fun launchTreePicker(): TreeGrantResult {
            launches++
            started.complete(Unit)
            return release.await()
        }

        fun finish(result: TreeGrantResult) {
            release.complete(result)
        }
    }

    private class FakePersistence : TreeGrantPersistence {
        override fun takePersistable(treeUri: String) = Unit
        override fun release(treeUri: String) = Unit
        override fun persistedTreeUris(): List<String> = emptyList()
    }

    private fun treeDocIdOf(uri: String): String? = uri.substringAfterLast('/').replace("%3A", ":")

    private fun authorityOf(uri: String): String? = "com.android.externalstorage.documents"

    private fun store() = ImageTreeStore(FakePersistence(), ::treeDocIdOf, ::authorityOf)

    private fun assertToken(token: String, block: suspend () -> Unit) {
        var thrown: BridgeException? = null
        try {
            runBlocking { block() }
        } catch (e: BridgeException) {
            thrown = e
        }
        assertEquals(token, thrown?.token)
    }

    @Test
    fun sameDocumentReenqueueFiresOnlyOnePrompt() = runBlocking {
        val launcher = CountingLauncher(TreeGrantResult.Granted("content://auth/tree/primary%3ANotes"))
        val coordinator = RelativeImageTreeCoordinator(store(), launcher)
        val a = async { coordinator.awaitTree("doc1") }
        val b = async { coordinator.awaitTree("doc1") }
        val ga = a.await()
        val gb = b.await()
        assertEquals(1, launcher.launches)
        assertEquals("primary:Notes", ga.treeDocId)
        assertEquals("primary:Notes", gb.treeDocId)
    }

    @Test
    fun grantResolvesAllPendingAtOnce() = runBlocking {
        val launcher = CountingLauncher(TreeGrantResult.Granted("content://auth/tree/primary%3ANotes"))
        val coordinator = RelativeImageTreeCoordinator(store(), launcher)
        val results = (1..3).map {
            async { coordinator.awaitTree("doc1") }
        }.map { it.await() }
        assertEquals(1, launcher.launches)
        assertEquals(3, results.size)
        assertTrue(results.all { it.treeDocId == "primary:Notes" })
    }

    @Test
    fun cancelRejectsAllAndSetsSessionDenialOnce() = runBlocking {
        val launcher = CountingLauncher(TreeGrantResult.Cancelled)
        val s = store()
        val coordinator = RelativeImageTreeCoordinator(s, launcher)
        val a = async { runCatching { coordinator.awaitTree("doc1") } }
        val b = async { runCatching { coordinator.awaitTree("doc1") } }
        val ra = a.await()
        val rb = b.await()
        assertTrue(ra.isFailure)
        assertTrue(rb.isFailure)
        assertEquals(ImageTokens.TREE_CANCELLED, (ra.exceptionOrNull() as BridgeException).token)
        assertEquals(ImageTokens.TREE_CANCELLED, (rb.exceptionOrNull() as BridgeException).token)
        assertEquals(1, launcher.launches)
        assertTrue("the session denial must be set exactly once", s.isSessionDenied("doc1"))
        // A later call throws immediately without launching another prompt.
        assertToken(ImageTokens.TREE_CANCELLED) { coordinator.awaitTree("doc1") }
        assertEquals(1, launcher.launches)
    }

    @Test
    fun launcherResultWithEmptyQueueOnlyPersists() {
        val launcher = CountingLauncher()
        val s = store()
        val coordinator = RelativeImageTreeCoordinator(s, launcher)
        coordinator.onLauncherResult(TreeGrantResult.Granted("content://auth/tree/primary%3ANotes"))
        assertEquals(0, launcher.launches)
        assertNotNull("the grant must be persisted for the next LoadImageForSrc", s.findTreeForDocument("primary:Notes/note.md"))
    }

    @Test
    fun cancelledLateResultWithEmptyQueueDoesNothing() {
        val launcher = CountingLauncher()
        val s = store()
        val coordinator = RelativeImageTreeCoordinator(s, launcher)
        coordinator.onLauncherResult(TreeGrantResult.Cancelled)
        assertEquals(0, launcher.launches)
        assertTrue(!s.isSessionDenied("doc1"))
    }

    @Test
    fun documentSwitchRejectsPendingWaiters() = runBlocking {
        val launcher = GateLauncher()
        val coordinator = RelativeImageTreeCoordinator(store(), launcher)
        val a = async { runCatching { coordinator.awaitTree("doc1") } }
        launcher.started.await()
        coordinator.onDocumentLoaded("doc2")
        // Release the in-flight picker before awaiting the leader (same order as
        // grantAfterDocumentSwitchResolvesOnlyCurrentGeneration): the leader is
        // suspended inside the launcher, so awaiting it first would deadlock.
        launcher.finish(TreeGrantResult.Granted("content://auth/tree/primary%3ANotes"))
        val result = a.await()
        assertTrue(result.isFailure)
        assertEquals(ImageTokens.SUPERSEDED, (result.exceptionOrNull() as BridgeException).token)
    }

    @Test
    fun documentSwitchClearsSessionDenialForReopenedDocument() {
        val s = store()
        s.denySession("doc1")
        val coordinator = RelativeImageTreeCoordinator(s, CountingLauncher())
        coordinator.onDocumentLoaded("doc1")
        assertTrue("reopening a document clears its session denial", !s.isSessionDenied("doc1"))
    }

    @Test
    fun sessionDeniedDocumentThrowsImmediatelyWithoutPrompt() = runBlocking {
        val launcher = CountingLauncher()
        val s = store()
        s.denySession("doc1")
        val coordinator = RelativeImageTreeCoordinator(s, launcher)
        assertToken(ImageTokens.TREE_CANCELLED) { coordinator.awaitTree("doc1") }
        assertEquals(0, launcher.launches)
    }

    @Test
    fun grantAfterDocumentSwitchResolvesOnlyCurrentGeneration() = runBlocking {
        val launcher = GateLauncher()
        val coordinator = RelativeImageTreeCoordinator(store(), launcher)
        val stale = async { runCatching { coordinator.awaitTree("doc1") } }
        launcher.started.await()
        coordinator.onDocumentLoaded("doc2")
        // A new waiter for the same document after the switch is a fresh generation.
        val fresh = async { runCatching { coordinator.awaitTree("doc1") } }
        launcher.finish(TreeGrantResult.Granted("content://auth/tree/primary%3ANotes"))
        assertTrue(stale.await().isFailure)
        val freshResult = fresh.await()
        assertTrue(freshResult.isSuccess)
        assertEquals("primary:Notes", freshResult.getOrNull()?.treeDocId)
        // The stale leader's result settled the fresh waiter; the fresh leader
        // must NOT fire a second picker (empty waiters -> skip).
        assertEquals(1, launcher.launches)
    }
}