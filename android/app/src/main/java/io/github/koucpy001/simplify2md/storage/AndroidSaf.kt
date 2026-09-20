package io.github.koucpy001.simplify2md.storage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.koucpy001.simplify2md.bridge.BridgeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android glue for SAF (plan todo 10). Every Android type lives here; the
 * decisions live in `SafStore.kt`.
 *
 * Flag contract (asserted by the task-10 evidence grep):
 *  - `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT` both request
 *    `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION |
 *    FLAG_GRANT_PERSISTABLE_URI_PERMISSION`. Without the persistable bit
 *    `takePersistableUriPermission` throws and the grant is silently downgraded
 *    to session scope, which breaks "reopen from recents after restart".
 *  - The chosen URI's metadata is read from `COLUMN_FLAGS` /
 *    `FLAG_SUPPORTS_WRITE`; a document without the write bit is read-only.
 *
 * Process death: the picker is pre-registered under the fixed
 * [SAF_PICK_REQUEST_CODE]. `onActivityResult` routes the result back to the
 * single outstanding suspension; if the process was recreated in between, the
 * pending slot is empty and the result is simply dropped (a fresh picker can
 * still be started). Nothing is registered at pick time, which is what would
 * lose the callback across a process death.
 */

/** Fixed request code for the one pre-registered SAF picker. */
const val SAF_PICK_REQUEST_CODE: Int = 0x5AF0

/**
 * `SafLauncher` over `Activity.startActivityForResult`.
 *
 * `android.app.Activity` is used deliberately: pulling in `androidx.activity`
 * just for `registerForActivityResult` would add a dependency the plan forbids.
 * The pre-registration requirement is met by the compile-time request code plus
 * routing in [onActivityResult] — no launcher is registered lazily at pick time.
 */
class AndroidSafLauncher(
    private val activity: Activity,
    private val requestCode: Int = SAF_PICK_REQUEST_CODE,
) : SafLauncher {

    // Written from the caller coroutine, read on the main thread in onActivityResult.
    @Volatile
    private var pending: CompletableDeferred<SafPickOutcome>? = null

    override suspend fun openDocument(): SafPickOutcome = await(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Several MIME types are only honoured together with a wildcard type
            // plus EXTRA_MIME_TYPES. application/octet-stream covers providers
            // that do not classify Markdown at all.
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, MARKDOWN_MIME_TYPES)
            addFlags(SAF_GRANT_FLAGS)
        },
    )

    override suspend fun createDocument(defaultName: String): SafPickOutcome = await(
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MARKDOWN_MIME
            putExtra(Intent.EXTRA_TITLE, defaultName)
            addFlags(SAF_GRANT_FLAGS)
        },
    )

    private suspend fun await(intent: Intent): SafPickOutcome {
        val deferred = CompletableDeferred<SafPickOutcome>()
        pending = deferred
        return try {
            // startActivityForResult is an Activity call; marshal it to the main
            // thread because bridge handlers run on Dispatchers.IO.
            withContext(Dispatchers.Main) { activity.startActivityForResult(intent, requestCode) }
            deferred.await()
        } catch (_: ActivityNotFoundException) {
            throw BridgeException("no-document-provider")
        } finally {
            pending = null
        }
    }

    /**
     * Routes an activity result. Returns true when it belonged to this picker
     * (including the "process was recreated, nothing pending" case, which is
     * consumed and ignored rather than forwarded).
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != this.requestCode) return false
        val deferred = pending ?: return true
        deferred.complete(
            SafResultMapper.toOutcome(
                resultOk = resultCode == Activity.RESULT_OK,
                uri = data?.data?.toString(),
            ),
        )
        return true
    }

    companion object {
        /**
         * Read + write + persistable, requested together on every picker. See
         * the class comment for why the persistable bit is non-negotiable.
         */
        val SAF_GRANT_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        const val MARKDOWN_MIME = "text/markdown"

        val MARKDOWN_MIME_TYPES: Array<String> = arrayOf(
            "text/markdown",
            "text/x-markdown",
            "text/plain",
            "application/octet-stream",
        )
    }
}

/**
 * `ContentResolver` metadata reader.
 *
 * DISPLAY_NAME and COLUMN_FLAGS are queried together; a provider that does not
 * expose COLUMN_FLAGS yields -1 from `getColumnIndex` and the document is
 * treated as read-only, so `openOutputStream` is never attempted on a URI that
 * may not permit it.
 */
class AndroidDocumentMetadataReader(private val resolver: ContentResolver) : DocumentMetadataReader {

    override fun displayName(uri: String): String? = query(uri)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
    }

    override fun supportsWrite(uri: String): Boolean = query(uri)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use false
        val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        if (index < 0 || cursor.isNull(index)) {
            false
        } else {
            cursor.getInt(index) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
        }
    } ?: false

    private fun query(uri: String): Cursor? = try {
        resolver.query(Uri.parse(uri), PROJECTION, null, null, null)
    } catch (_: Exception) {
        null
    }

    private companion object {
        val PROJECTION = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}

/**
 * Persists document grants with `takePersistableUriPermission`.
 *
 * Read-only documents (a provider that never issued the write grant) are
 * persisted read-only; asking for write on those would throw and needlessly
 * downgrade a perfectly persistable read grant to session scope.
 */
class AndroidUriPermissionStore(private val resolver: ContentResolver) : UriPermissionStore {

    override fun takePersistable(uri: String, write: Boolean) {
        val mode = if (write) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        resolver.takePersistableUriPermission(Uri.parse(uri), mode)
    }
}

/** Streams the bytes behind a document URI. */
class AndroidDocumentContentReader(private val resolver: ContentResolver) : DocumentContentReader {

    override fun read(uri: String): ByteArray =
        resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            ?: throw BridgeException("document-unreadable")
}
