package io.github.koucpy001.simplify2md.image

/**
 * Pure relative-image path and SAF tree-association rules (plan todo 13).
 *
 * Everything here is Android-free. The `DocumentsContract` calls that turn a URI
 * into a documentId live in `AndroidImageGlue.kt` (`getDocumentId` /
 * `getTreeDocumentId` / `buildDocumentUriUsingTree`); this object only compares
 * the resulting plain strings, which is what makes the whole (b)/(c)/(d) decision
 * matrix runnable under plain JVM JUnit.
 *
 * Association is ALWAYS by documentId segment — never by raw URI prefix. A
 * document URI is `content://authority/document/<docId>` and carries no `/tree/`
 * segment, so `…/document/…` vs `…/tree/…` prefix matching fails on the ordinary
 * "open a file, then display its images" path.
 */
object ImageTreeAssociation {

    /**
     * The only provider whose `documentId` is a real path. `ACTION_OPEN_DOCUMENT_TREE`
     * on local storage yields it. Cloud / media providers use opaque handles, so
     * appending path segments to them is a guess — the plan forbids pretending
     * support, and those sources go to the explicit "unsupported" placeholder.
     */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Authorities that expose a browse root the system refuses to grant as a
     * tree (`Download/`). Relative images from these sources cannot be resolved.
     */
    val SYSTEM_FORBIDDEN_AUTHORITIES: Set<String> = setOf(
        "com.android.providers.downloads.documents",
    )

    /** How a provider's documentId behaves: a real path, or an opaque handle. */
    enum class ProviderKind { PATH, OPAQUE }

    /** A granted tree, reduced to the plain values the rules compare. */
    data class TreeCandidate(
        val treeUri: String,
        val treeDocId: String,
        val authority: String?,
    )

    private val ABSOLUTE_URL = Regex("^(?:https?|data|blob):", RegexOption.IGNORE_CASE)
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/]")

    private val FORBIDDEN_PREFIXES = listOf("Download", "Android/data", "Android/obb")

    /** (a) `http(s):` / `data:` / `blob:` are loaded by the WebView, not the bridge. */
    fun isAbsoluteUrl(src: String): Boolean = ABSOLUTE_URL.containsMatchIn(src)

    /** Windows drive / UNC / POSIX-absolute: never resolvable through a SAF tree. */
    fun isAbsoluteFilesystemPath(src: String): Boolean =
        src.startsWith("/") || src.startsWith("\\\\") || WINDOWS_ABSOLUTE.containsMatchIn(src)

    /** Only the local external-storage provider is path-type (see the class doc). */
    fun classifyProvider(authority: String?): ProviderKind =
        if (authority == EXTERNAL_STORAGE_AUTHORITY) ProviderKind.PATH else ProviderKind.OPAQUE

    /**
     * Normalizes a documentId that may still carry the structural percent
     * escapes a seam handed back (`%2F` for the path separator, `%3A` for the
     * volume separator).
     *
     * `DocumentsContract.getDocumentId` returns an already-decoded id, but the
     * documentId comes from an injected seam, so every comparison decodes first.
     * Without this, `primary:Notes%2Fnote.md` never associates with the
     * `primary:Notes` tree (branch (b) silently degrades to a prompt) and
     * `primary:Download%2Fnote.md` never matches the forbidden `Download/`
     * prefix (branch (d) answers the wrong token).
     */
    fun decodeDocumentId(id: String): String {
        if (!id.contains('%')) return id
        return id
            .replace("%2F", "/").replace("%2f", "/")
            .replace("%3A", ":").replace("%3a", ":")
    }

    /**
     * Picks the granted tree that contains [documentId]: `docId == treeDocId ||
     * docId.startsWith(treeDocId + "/")`, longest treeDocId wins on multiple
     * candidates. The boundary matters — `<treeDocId>X` must not match
     * `<treeDocId>` (so `Docs` never matches `Docs2`). Opaque candidates are
     * skipped, so a cloud tree grant is not associable.
     */
    fun associateTree(documentId: String, candidates: List<TreeCandidate>): TreeCandidate? =
        candidates
            .asSequence()
            .filter { classifyProvider(it.authority) == ProviderKind.PATH }
            .filter { isWithinTree(documentId, it.treeDocId) }
            .maxByOrNull { it.treeDocId.length }

    private fun isWithinTree(documentId: String, treeDocId: String): Boolean =
        treeDocId.isNotEmpty() && (documentId == treeDocId || documentId.startsWith("$treeDocId/"))

    /**
     * True when [documentId] sits at a storage root or a system-forbidden tree
     * (`Download/`, `Android/data`, `Android/obb`). `ACTION_OPEN_DOCUMENT_TREE`
     * refuses to grant those, so the resolver must answer with the "denied"
     * placeholder instead of prompting.
     */
    fun isSystemForbiddenDocument(documentId: String): Boolean {
        if (!documentId.contains(':')) return false
        val volume = documentId.substringBefore(':')
        if (volume.isEmpty()) return false
        val path = documentId.substringAfter(':')
        if (path.isEmpty()) return true
        return FORBIDDEN_PREFIXES.any { path == it || path.startsWith("$it/") }
    }

    /** Same policy applied to a tree root's documentId. */
    fun isSystemForbiddenTree(treeDocId: String): Boolean = isSystemForbiddenDocument(treeDocId)

    /**
     * Builds the child documentId for [relativePath] under the document's own
     * directory, then verifies it is still inside [treeDocId]. Returns null for
     * any escape (`..` above the tree), a volume mismatch, or an empty result.
     *
     * This boundary check is the prompt-injection guard: a hostile `src` such as
     * `../../../../etc/passwd` is resolved lexically and then rejected unless the
     * result is provably inside the user-granted tree, so it can never reach
     * `ContentResolver` outside that tree.
     */
    fun buildChildDocumentId(documentId: String, treeDocId: String, relativePath: String): String? {
        if (!documentId.contains(':')) return null
        val volume = documentId.substringBefore(':')
        if (volume.isEmpty()) return null

        val baseDir = documentId.substringAfter(':').substringBeforeLast('/', "")
        val segments = ArrayList<String>()
        if (baseDir.isNotEmpty()) segments.addAll(baseDir.split('/'))
        for (raw in relativePath.split('/')) {
            when (raw) {
                "", "." -> continue
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.size - 1)
                }
                else -> segments.add(raw)
            }
        }
        if (segments.isEmpty()) return null
        val childPath = segments.joinToString("/")

        val treeVolume = treeDocId.substringBefore(':', missingDelimiterValue = "")
        val treePath = treeDocId.substringAfter(':', missingDelimiterValue = "")
        if (volume != treeVolume) return null
        val inside = treePath.isEmpty() || childPath == treePath || childPath.startsWith("$treePath/")
        if (!inside) return null
        return "$volume:$childPath"
    }

    /**
     * Normalizes an untrusted `src` into a relative path: strips a query string
     * and fragment, rejects absolute / protocol-relative / Windows-absolute
     * forms, and percent-decodes (only `%XX`, never `+`) so `images/a%20b.png`
     * resolves. The caller MUST still boundary-check against the granted tree.
     */
    fun normalizeRelative(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val path = trimmed.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return null
        if (path.startsWith("/")) return null
        if (isAbsoluteFilesystemPath(path)) return null
        if (isAbsoluteUrl(path)) return null
        if (path.contains('\u0000')) return null
        return percentDecode(path)
    }

    /** Joins the front-matter image root with `src` before normalization. */
    fun combineImageRoot(imageRoot: String, src: String): String {
        val root = imageRoot.trim()
        if (root.isEmpty()) return src
        return root.trimEnd('/', '\\') + "/" + src
    }

    private fun percentDecode(value: String): String? {
        if (!value.contains('%')) return value
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
