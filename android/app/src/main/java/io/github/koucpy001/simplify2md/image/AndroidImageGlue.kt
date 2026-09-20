package io.github.koucpy001.simplify2md.image

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android glue for relative-image resolution (plan todo 13). Every Android type
 * lives here; the decisions live in the pure-JVM `image/` package
 * (`ImageTreeAssociation`, `ImageTreeStore`, `RelativeImageTreeCoordinator`,
 * `RelativeImageResolver`).
 *
 * The `DocumentsContract` calls the plan names — `getTreeDocumentId`,
 * `getDocumentId`, `buildDocumentUriUsingTree` — are all here, so the
 * documentId-segment association rule (never raw URI prefix) is grep-verifiable
 * in this file while the comparison itself stays JVM-testable.
 */

/** Fixed request code for the one pre-registered tree picker (todo 13c). */
const val TREE_GRANT_REQUEST_CODE: Int = 0x5AF1

/**
 * Persists / releases / lists tree grants through `ContentResolver`.
 *
 * [takePersistable] must throw when the provider cannot persist (the store
 * degrades to a session grant); [persistedTreeUris] filters
 * `persistedUriPermissions` for tree-type grants with `DocumentsContract.isTreeUri`
 * — the canonical check, not a raw URI-prefix comparison.
 */
class AndroidTreeGrantPersistence(private val resolver: ContentResolver) : TreeGrantPersistence {

    override fun takePersistable(treeUri: String) {
        resolver.takePersistableUriPermission(Uri.parse(treeUri), TREE_GRANT_FLAGS)
    }

    override fun release(treeUri: String) {
        resolver.releasePersistableUriPermission(Uri.parse(treeUri), TREE_GRANT_FLAGS)
    }

    override fun persistedTreeUris(): List<String> =
        resolver.persistedUriPermissions
            .asSequence()
            .map { it.uri }
            .filter { DocumentsContract.isTreeUri(it) }
            .map { it.toString() }
            .toList()

    companion object {
        /** Read + write on the tree; the picker intent requests the same bits. */
        val TREE_GRANT_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/**
 * Metadata of a child document behind a `buildDocumentUriUsingTree` URI.
 *
 * A single `ContentResolver` query reads SIZE and MIME_TYPE; a provider that
 * does not expose a column yields null for that field (the resolver falls back
 * to the extension-based MIME). A query failure means "not exists" — the
 * resolver answers with the explicit placeholder instead of crashing.
 */
class AndroidImageDocumentReader(private val resolver: ContentResolver) : ImageDocumentReader {

    override fun exists(uri: String): Boolean = query(uri)?.use { it.moveToFirst() } ?: false

    override fun size(uri: String): Long? = query(uri)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
    }

    override fun mimeType(uri: String): String? = query(uri)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
    }

    private fun query(uri: String): Cursor? = try {
        resolver.query(Uri.parse(uri), PROJECTION, null, null, null)
    } catch (_: Exception) {
        null
    }

    private companion object {
        val PROJECTION = arrayOf(
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

/**
 * The `ACTION_OPEN_DOCUMENT_TREE` launcher, independent of the single SAF picker
 * slot (plan todo 13c). A pending tree prompt neither blocks nor is blocked by
 * an OpenFile/PickSavePath picker.
 *
 * Process death: the picker is pre-registered under the fixed
 * [TREE_GRANT_REQUEST_CODE]. When a result arrives with no pending suspension
 * (the process was recreated while the prompt was open), [onActivityResult]
 * forwards it to [lateResultHandler] — the coordinator's `onLauncherResult`,
 * which only persists the grant so the next `LoadImageForSrc` resolves.
 */
class AndroidTreeLauncher(
    private val activity: Activity,
    private val requestCode: Int = TREE_GRANT_REQUEST_CODE,
) : TreeGrantLauncher {

    // Written from the caller coroutine, read on the main thread in onActivityResult.
    @Volatile
    private var pending: CompletableDeferred<TreeGrantResult>? = null

    private var lateResultHandler: ((TreeGrantResult) -> Unit)? = null

    override suspend fun launchTreePicker(): TreeGrantResult = await(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Persistable is requested up front; takePersistableUriPermission is
            // wrapped in try/catch by ImageTreeStore and degrades to session scope.
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        },
    )

    private suspend fun await(intent: Intent): TreeGrantResult {
        val deferred = CompletableDeferred<TreeGrantResult>()
        pending = deferred
        return try {
            // startActivityForResult is an Activity call; marshal it to the main
            // thread because bridge handlers run on Dispatchers.IO.
            withContext(Dispatchers.Main) { activity.startActivityForResult(intent, requestCode) }
            deferred.await()
        } catch (_: ActivityNotFoundException) {
            TreeGrantResult.Cancelled
        } finally {
            pending = null
        }
    }

    /**
     * Routes an activity result. Returns true when it belonged to this launcher
     * (including the "process was recreated, nothing pending" case, which is
     * forwarded to the late-result handler rather than dropped).
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != this.requestCode) return false
        val outcome = TreeGrantResultMapper.toResult(
            resultOk = resultCode == Activity.RESULT_OK,
            uri = data?.data?.toString(),
        )
        val deferred = pending
        if (deferred != null) {
            deferred.complete(outcome)
        } else {
            lateResultHandler?.invoke(outcome)
        }
        return true
    }

    /** Receives results that arrived with no pending suspension (process death). */
    fun setLateResultHandler(handler: (TreeGrantResult) -> Unit) {
        lateResultHandler = handler
    }
}

/** Pure mapping from a raw tree-picker activity result to [TreeGrantResult]. */
object TreeGrantResultMapper {
    fun toResult(resultOk: Boolean, uri: String?): TreeGrantResult =
        if (resultOk && !uri.isNullOrBlank()) TreeGrantResult.Granted(uri) else TreeGrantResult.Cancelled
}

/** `DocumentsContract.buildDocumentUriUsingTree`, injected into the resolver. */
class AndroidChildDocumentUriBuilder : ChildDocumentUriBuilder {
    override fun build(treeUri: String, childDocumentId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), childDocumentId).toString()
}

/** `DocumentsContract.getDocumentId`, null-safe (blank / non-document URIs). */
fun documentIdOf(uri: String): String? = runCatching {
    if (uri.isBlank()) null else DocumentsContract.getDocumentId(Uri.parse(uri))
}.getOrNull()

/** `DocumentsContract.getTreeDocumentId`, null-safe. */
fun treeDocumentIdOf(uri: String): String? = runCatching {
    if (uri.isBlank()) null else DocumentsContract.getTreeDocumentId(Uri.parse(uri))
}.getOrNull()

/** `Uri.authority`, null-safe. */
fun authorityOf(uri: String): String? = runCatching {
    if (uri.isBlank()) null else Uri.parse(uri).authority
}.getOrNull()