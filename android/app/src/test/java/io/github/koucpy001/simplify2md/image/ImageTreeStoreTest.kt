package io.github.koucpy001.simplify2md.image

import io.github.koucpy001.simplify2md.storage.GrantLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Granted-tree store rules (plan todo 13): LRU cap with non-active eviction,
 * persisted-tree association, session denial, and persist-degradation.
 */
class ImageTreeStoreTest {

    private class FakePersistence(
        var persisted: List<String> = emptyList(),
        var throwOnTake: Boolean = false,
    ) : TreeGrantPersistence {
        val taken = mutableListOf<String>()
        val released = mutableListOf<String>()

        override fun takePersistable(treeUri: String) {
            if (throwOnTake) throw SecurityException("provider has no persistable grant")
            taken.add(treeUri)
        }

        override fun release(treeUri: String) {
            released.add(treeUri)
        }

        override fun persistedTreeUris(): List<String> = persisted
    }

    private fun treeDocIdOf(uri: String): String? = uri.substringAfterLast('/').replace("%3A", ":")

    private fun authorityOf(uri: String): String? = "com.android.externalstorage.documents"

    private fun store(
        persistence: FakePersistence = FakePersistence(),
        maxTrees: Int = ImageTreeStore.MAX_TREES,
        onSessionGrant: (TreeGrant) -> Unit = {},
    ) = ImageTreeStore(persistence, ::treeDocIdOf, ::authorityOf, maxTrees, onSessionGrant)

    @Test
    fun registerTreePersistsAndReturnsGrant() {
        val persistence = FakePersistence()
        val grant = store(persistence).registerTree("content://auth/tree/primary%3ANotes", "doc")
        assertEquals("primary:Notes", grant.treeDocId)
        assertEquals(GrantLevel.PERSISTED, grant.level)
        assertEquals(listOf("content://auth/tree/primary%3ANotes"), persistence.taken)
    }

    @Test
    fun persistFailureDegradesToSessionAndNotifies() {
        val degraded = mutableListOf<TreeGrant>()
        val persistence = FakePersistence(throwOnTake = true)
        val grant = store(persistence, onSessionGrant = { degraded.add(it) }).registerTree(
            "content://auth/tree/primary%3ANotes",
            "doc",
        )
        assertEquals(GrantLevel.SESSION, grant.level)
        assertEquals(listOf(grant), degraded)
    }

    @Test
    fun lruCapEvictsOldestNonActiveTree() {
        val persistence = FakePersistence()
        val s = store(persistence, maxTrees = 2)
        s.registerTree("content://auth/tree/primary%3AA", "docA")
        s.registerTree("content://auth/tree/primary%3AB", "docB")
        s.registerTree("content://auth/tree/primary%3AC", "docC")
        assertEquals(listOf("content://auth/tree/primary%3AA"), persistence.released)
        assertNull(s.findTreeForDocument("primary:A/note.md"))
        assertNotNull(s.findTreeForDocument("primary:B/note.md"))
        assertNotNull(s.findTreeForDocument("primary:C/note.md"))
    }

    @Test
    fun activeTreeIsNeverEvicted() {
        val persistence = FakePersistence()
        val s = store(persistence, maxTrees = 2)
        // A is the oldest entry AND the active tree: B and C arrive as late picker
        // results (activeDocumentUri = null) so neither steals the active marker
        // from A. This is the state the LRU cap must protect even though A is the
        // oldest tree — distinct from `lruCapEvictsOldestNonActiveTree`, where the
        // oldest tree is non-active and does get evicted.
        s.registerTree("content://auth/tree/primary%3AA", "docA")
        s.registerTree("content://auth/tree/primary%3AB", null)
        s.registerTree("content://auth/tree/primary%3AC", null)
        // A (active, oldest) survives; the cap evicts the newer non-active B.
        assertEquals(listOf("content://auth/tree/primary%3AB"), persistence.released)
        assertNotNull(s.findTreeForDocument("primary:A/note.md"))
        assertNotNull(s.findTreeForDocument("primary:C/note.md"))
    }

    @Test
    fun findTreeForDocumentMatchesPersistedGrantAtStartup() {
        val persistence = FakePersistence(persisted = listOf("content://auth/tree/primary%3ANotes"))
        val grant = store(persistence).findTreeForDocument("primary:Notes/note.md")
        assertNotNull(grant)
        assertEquals("primary:Notes", grant?.treeDocId)
        assertEquals(GrantLevel.PERSISTED, grant?.level)
    }

    @Test
    fun findTreeForDocumentReturnsNullForUnknownDocument() {
        assertNull(store().findTreeForDocument("primary:Other/note.md"))
        assertNull(store().findTreeForDocument(null))
        assertNull(store().findTreeForDocument(""))
    }

    @Test
    fun sessionDenialIsSetAndCleared() {
        val s = store()
        assertTrue(!s.isSessionDenied("content://doc/1"))
        s.denySession("content://doc/1")
        assertTrue(s.isSessionDenied("content://doc/1"))
        s.clearSessionDenied("content://doc/1")
        assertTrue(!s.isSessionDenied("content://doc/1"))
    }

    @Test
    fun findTreeForDocumentMarksGrantActiveAndRefreshesLru() {
        val persistence = FakePersistence()
        val s = store(persistence, maxTrees = 2)
        s.registerTree("content://auth/tree/primary%3AA", "docA")
        s.registerTree("content://auth/tree/primary%3AB", "docB")
        // Using A makes it the most-recently-used; registering C then evicts B.
        assertNotNull(s.findTreeForDocument("primary:A/note.md"))
        s.registerTree("content://auth/tree/primary%3AC", "docC")
        assertEquals(listOf("content://auth/tree/primary%3AB"), persistence.released)
        assertNotNull(s.findTreeForDocument("primary:A/note.md"))
        assertNotNull(s.findTreeForDocument("primary:C/note.md"))
    }
}