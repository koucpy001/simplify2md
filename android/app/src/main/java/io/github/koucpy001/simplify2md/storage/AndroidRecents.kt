package io.github.koucpy001.simplify2md.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Android glue for the recents model (plan todo 12). Every Android type lives
 * here; the rules live in `RecentsStore.kt`, which is why all of the release
 * logic is exercised by plain JVM tests.
 */

/**
 * `filesDir/config.json` as a [ConfigFileSystem]. Writes use a sibling temp file
 * plus rename so a crash mid-write cannot leave a half-written config; if the
 * rename fails the direct write is the fallback.
 */
class AndroidConfigFileSystem(filesDir: File) : ConfigFileSystem {

    private val file = File(filesDir, AppConfig.FILE_NAME)

    override fun read(): String? = runCatching {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    override fun write(json: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
    }
}

/**
 * Releases persisted SAF grants with `releasePersistableUriPermission`.
 *
 * A provider may have issued only a read grant, so releasing read+write throws;
 * the read-only release is then attempted. Anything still failing propagates to
 * [RecentsStore]'s `runCatching`, because a non-SAF URI (desktop-shaped entry) or
 * an already-revoked grant must never break the list mutation.
 */
class AndroidUriGrantReleaser(private val resolver: ContentResolver) : UriGrantReleaser {

    override fun release(uri: String) {
        val parsed = Uri.parse(uri)
        try {
            resolver.releasePersistableUriPermission(parsed, READ_WRITE_FLAGS)
        } catch (_: SecurityException) {
            resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val READ_WRITE_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
