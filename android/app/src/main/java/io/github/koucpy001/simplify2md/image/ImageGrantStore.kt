package io.github.koucpy001.simplify2md.image

import io.github.koucpy001.simplify2md.bridge.BridgeException
import io.github.koucpy001.simplify2md.storage.GrantLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Folder-tree grant registry plus the (c) pending state machine for relative
 * images (plan todo 13). Pure JVM: the `ContentResolver` calls live behind
 * [TreeGrantPersistence], and the `ACTION_OPEN_DOCUMENT_TREE` round-trip behind
 * [TreeGrantLauncher], so every rule below runs under plain JUnit.
 */

/** A SAF tree grant, persisted or degraded to session scope. */
data class TreeGrant(
    val treeUri: String,
    val treeDocId: String,
    val authority: String?,
    val level: GrantLevel,
)

/**
 * Persistence seam for tree grants.
 *
 * [takePersistable] must throw when the provider cannot persist, so
 * [ImageTreeStore] can degrade to [GrantLevel.SESSION] instead of crashing. The
 * LRU eviction releases the oldest non-active tree through [release] to stay
 * clear of the system's persisted-grant limit (128 on older releases, 512 on
 * API 30+), which otherwise throws `SecurityException`.
 */
interface TreeGrantPersistence {
    fun takePersistable(treeUri: String)
    fun release(treeUri: String)

    /** Persisted tree-type grants from `persistedUriPermissions`, already filtered. */
    fun persistedTreeUris(): List<String>
}

/** Result of one `ACTION_OPEN_DOCUMENT_TREE` round-trip, reduced to plain values. */
sealed interface TreeGrantResult {
    data class Granted(val treeUri: String) : TreeGrantResult
    data object Cancelled : TreeGrantResult
}

/**
 * The tree picker. Separate from the single SAF picker slot (OpenFile /
 * PickSavePath): a pending tree prompt neither blocks nor is blocked by them.
 */
fun interface TreeGrantLauncher {
    suspend fun launchTreePicker(): TreeGrantResult
}

/**
 * Granted-tree store: last-known grants, LRU cap, persisted-tree association and
 * the per-session "tree denied" marker.
 *
 * Invariants (each JVM-tested):
 *  - association is by documentId segment with longest-treeDocId wins (see
 *    [ImageTreeAssociation.associateTree]); a cloud/opaque grant is not usable;
 *  - the LRU cap evicts and releases only non-active trees, and the currently
 *    active tree is never evicted (losing it mid-session would break every
 *    later image resolve);
 *  - a session-denied document stops prompting until it is reopened;
 *  - persisting a grant is best-effort and degrades to session scope.
 */
class ImageTreeStore(
    private val persistence: TreeGrantPersistence,
    private val treeDocIdOf: (String) -> String?,
    private val authorityOf: (String) -> String?,
    private val maxTrees: Int = MAX_TREES,
    private val onSessionGrant: (TreeGrant) -> Unit = {},
) {

    private val lock = Any()

    // LRU: least-recently-used first (re-inserted on use / register).
    private val grants = LinkedHashMap<String, TreeGrant>()
    private val sessionDenied = HashSet<String>()
    private var activeTreeUri: String? = null

    /**
     * Records a freshly granted tree. [activeDocumentUri] is the document that
     * triggered the prompt (null for a late result arriving after process death),
     * used to pin the tree against eviction.
     */
    fun registerTree(treeUri: String, activeDocumentUri: String?): TreeGrant {
        val level = try {
            persistence.takePersistable(treeUri)
            GrantLevel.PERSISTED
        } catch (_: Exception) {
            GrantLevel.SESSION
        }
        val grant = TreeGrant(treeUri, treeDocIdOf(treeUri).orEmpty(), authorityOf(treeUri), level)

        val evicted = ArrayList<TreeGrant>()
        synchronized(lock) {
            grants.remove(treeUri)
            grants[treeUri] = grant
            if (activeDocumentUri != null) activeTreeUri = treeUri
            while (grants.size > maxTrees) {
                val oldest = grants.entries.firstOrNull { it.key != activeTreeUri } ?: break
                grants.remove(oldest.key)
                evicted.add(oldest.value)
            }
        }
        evicted.forEach { releaseQuietly(it.treeUri) }
        if (level == GrantLevel.SESSION) onSessionGrant(grant)
        return grant
    }

    /**
     * The usable tree for [documentId], if any: in-memory grants plus persisted
     * trees (so association survives a restart). Returns null when none matches
     * or the provider is opaque.
     */
    fun findTreeForDocument(documentId: String?): TreeGrant? {
        if (documentId.isNullOrEmpty()) return null
        val decoded = ImageTreeAssociation.decodeDocumentId(documentId)
        val candidates = allKnownGrants()
        val match = ImageTreeAssociation.associateTree(
            decoded,
            candidates.map { ImageTreeAssociation.TreeCandidate(it.treeUri, it.treeDocId, it.authority) },
        ) ?: return null
        val grant = candidates.firstOrNull { it.treeUri == match.treeUri } ?: return null
        synchronized(lock) {
            activeTreeUri = grant.treeUri
            grants.remove(grant.treeUri)
            grants[grant.treeUri] = grant
        }
        return grant
    }

    fun isSessionDenied(documentUri: String): Boolean =
        synchronized(lock) { documentUri in sessionDenied }

    /** The user cancelled the tree prompt for [documentUri]: stop re-prompting. */
    fun denySession(documentUri: String) {
        synchronized(lock) { sessionDenied.add(documentUri) }
    }

    /** Cleared only when [documentUri] is reopened (plan todo 13, review D7). */
    fun clearSessionDenied(documentUri: String) {
        synchronized(lock) { sessionDenied.remove(documentUri) }
    }

    private fun allKnownGrants(): List<TreeGrant> {
        val inMemory = synchronized(lock) { grants.values.toList() }
        val persisted = persistence.persistedTreeUris().map { uri ->
            TreeGrant(uri, treeDocIdOf(uri).orEmpty(), authorityOf(uri), GrantLevel.PERSISTED)
        }
        return (inMemory + persisted).distinctBy { it.treeUri }
    }

    private fun releaseQuietly(treeUri: String) {
        runCatching { persistence.release(treeUri) }
    }

    companion object {
        /** Plan todo 13 (review D6): LRU cap, e.g. <= 8. */
        const val MAX_TREES = 8
    }
}

/**
 * The (c) pending state machine: a relative image with no granted tree must NOT
 * reject immediately (an immediate reject makes the frontend cache `''` forever,
 * so the image could never recover). Instead the request is parked in a queue
 * keyed by document URI and ONE deduped tree prompt is fired. A grant resolves
 * every waiter for that document at once (the images appear without reopening
 * the document); a cancel rejects them all with `image-tree-cancelled` and sets
 * the session denial exactly once.
 *
 * Cross-document invalidation: every waiter carries the current generation. A
 * document switch (`onDocumentLoaded`) bumps the generation and rejects every
 * pending request, so a late result from the previous document can never be
 * matched against the new document's nodes.
 */
class RelativeImageTreeCoordinator(
    private val store: ImageTreeStore,
    private val launcher: TreeGrantLauncher,
) {

    private val lock = Any()
    private val waiters = HashMap<String, MutableList<Waiter>>()

    // A granted tree is cached per document URI so a re-enqueue after the grant
    // still returns it without firing a second picker. Without this, callers
    // that arrive after the leader already resolved each become a fresh leader
    // (the pending list was consumed by `settle`), so one document could raise
    // one prompt per call.
    private val granted = HashMap<String, TreeGrant>()

    // The system runs one activity-result picker at a time; this serializes the
    // prompts of several documents without blocking the SAF picker slot.
    private val promptMutex = Mutex()
    private var generation = 0L

    private data class Waiter(val deferred: CompletableDeferred<TreeGrant>, val generation: Long)

    /**
     * Suspends until [documentUri] has a usable tree grant. The first caller for
     * a document becomes the leader and runs the prompt; later callers wait on
     * their deferred, so repeated enqueues fire exactly one prompt. Throws
     * `image-tree-cancelled` immediately when the document is session-denied.
     */
    suspend fun awaitTree(documentUri: String): TreeGrant {
        synchronized(lock) { granted[documentUri] }?.let { return it }
        if (store.isSessionDenied(documentUri)) throw BridgeException(ImageTokens.TREE_CANCELLED)

        var leader = false
        val waiter = synchronized(lock) {
            if (store.isSessionDenied(documentUri)) return@synchronized null
            val list = waiters.getOrPut(documentUri) { mutableListOf() }
            val created = Waiter(CompletableDeferred(), generation)
            list.add(created)
            if (list.size == 1) leader = true
            created
        } ?: throw BridgeException(ImageTokens.TREE_CANCELLED)

        if (leader) {
            promptMutex.withLock { runPrompt(documentUri) }
        }
        return waiter.deferred.await()
    }

    private suspend fun runPrompt(documentUri: String) {
        if (store.isSessionDenied(documentUri)) {
            settle(documentUri, null)
            return
        }
        // A stale leader's in-flight prompt may have settled this document while
        // we waited for the mutex (document switched away and back): with no
        // waiters left there is nothing to prompt for, so skip the picker.
        if (synchronized(lock) { waiters[documentUri].isNullOrEmpty() }) return
        val result = try {
            launcher.launchTreePicker()
        } catch (_: BridgeException) {
            settle(documentUri, null)
            return
        }
        when (result) {
            is TreeGrantResult.Granted -> {
                val grant = store.registerTree(result.treeUri, documentUri)
                store.clearSessionDenied(documentUri)
                synchronized(lock) { granted[documentUri] = grant }
                settle(documentUri, grant)
            }
            TreeGrantResult.Cancelled -> {
                // Denial is set exactly once here, then every waiter rejects.
                store.denySession(documentUri)
                settle(documentUri, null)
            }
        }
    }

    /**
     * A picker result that arrived with no waiter (process was recreated while
     * the prompt was open). Only the grant is persisted; the next
     * `LoadImageForSrc` resolves normally.
     */
    fun onLauncherResult(result: TreeGrantResult): TreeGrant? = when (result) {
        is TreeGrantResult.Granted -> store.registerTree(result.treeUri, null)
        TreeGrantResult.Cancelled -> null
    }

    /**
     * Document switch: bump the generation and reject every pending waiter from
     * the previous generation with `image-superseded`, then clear the reopened
     * document's session denial.
     */
    fun onDocumentLoaded(documentUri: String) {
        val stale = synchronized(lock) {
            generation += 1
            val all = waiters.values.flatten()
            waiters.clear()
            all
        }
        stale.forEach { it.deferred.completeExceptionally(BridgeException(ImageTokens.SUPERSEDED)) }
        store.clearSessionDenied(documentUri)
    }

    private fun settle(documentUri: String, grant: TreeGrant?) {
        val (current, stale) = synchronized(lock) {
            val list = waiters.remove(documentUri).orEmpty()
            list.partition { it.generation == generation }
        }
        current.forEach { waiter ->
            if (grant != null) waiter.deferred.complete(grant)
            else waiter.deferred.completeExceptionally(BridgeException(ImageTokens.TREE_CANCELLED))
        }
        stale.forEach {
            it.deferred.completeExceptionally(BridgeException(ImageTokens.SUPERSEDED))
        }
    }
}
