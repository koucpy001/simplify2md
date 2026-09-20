package io.github.koucpy001.simplify2md.image

import io.github.koucpy001.simplify2md.bridge.BridgeException

/**
 * Stable rejection tokens for relative-image failures (plan todo 13f). The
 * frontend maps each to a Chinese status hint in `lib/image-token.ts`; the
 * token set is asserted by `npx tsx test-token-mapping.ts`. None of them is a
 * retry entry point — recovery is driven by the tree-grant suspend/resume flow
 * (the bridge export set is frozen).
 */
object ImageTokens {
    const val NOT_IN_TREE = "image-not-in-tree"
    const val UNSUPPORTED_PROVIDER = "image-unsupported-provider"
    const val TOO_LARGE = "image-too-large"
    const val DENIED = "image-denied"
    const val TREE_CANCELLED = "image-tree-cancelled"

    /** Internal: a document switch invalidated the pending request. */
    const val SUPERSEDED = "image-superseded"
}

/** Metadata of a document behind a URI (existence / size / MIME). */
interface ImageDocumentReader {
    fun exists(uri: String): Boolean
    fun size(uri: String): Long?
    fun mimeType(uri: String): String?
}

/** `DocumentsContract.buildDocumentUriUsingTree(...)`, injected by the glue. */
fun interface ChildDocumentUriBuilder {
    fun build(treeUri: String, childDocumentId: String): String
}

/** What `LoadImageForSrc` returns: a token URL the WebView streams, never base64. */
data class ResolvedImage(val url: String, val mime: String)

/** Extension → MIME fallback when the provider reports nothing useful. */
object ImageMimeTypes {
    private val BY_EXTENSION = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "avif" to "image/avif",
    )

    fun fromName(name: String): String =
        BY_EXTENSION[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}

/**
 * Resolves `LoadImageForSrc(src, mdUri, imageRoot)` on Android (plan todo 13).
 *
 * Decision matrix, each branch commented at the site:
 *  (a) absolute `http(s):` / `data:` URL → returned untouched; the WebView loads
 *      it directly and the frontend normally never calls the bridge for it.
 *  (b) relative + a granted tree → a child document URI is built with
 *      `DocumentsContract.buildDocumentUriUsingTree` inside the granted tree and
 *      served through the `/media/<token>` endpoint. This only works for
 *      path-type `documentId` providers (the local `ExternalStorageProvider`).
 *  (c) relative + no granted tree → suspend in [RelativeImageTreeCoordinator]
 *      until exactly one (per-document deduped) tree grant arrives, then resolve.
 *  (d) unsupported / out-of-tree / too-large / system-forbidden → an explicit
 *      token rejection; never a blank image.
 *
 * A successful resolve returns `{url, mime}` with a token endpoint; base64 is
 * never produced on Android.
 */
class RelativeImageResolver(
    private val treeStore: ImageTreeStore,
    private val coordinator: RelativeImageTreeCoordinator,
    private val reader: ImageDocumentReader,
    private val tokens: MediaTokenStore,
    private val documentIdOf: (String) -> String?,
    private val authorityOf: (String) -> String?,
    private val buildChildUri: ChildDocumentUriBuilder,
    private val endpointBase: String,
    private val maxBytes: Long = MAX_IMAGE_BYTES,
) {

    suspend fun resolve(src: String, mdUri: String, imageRoot: String): ResolvedImage {
        val raw = src.trim()
        if (raw.isEmpty()) throw BridgeException(ImageTokens.NOT_IN_TREE)

        // (a) absolute URL: the WebView owns it. Returning it unchanged keeps the
        // bridge harmless if the frontend ever routes one here.
        if (ImageTreeAssociation.isAbsoluteUrl(raw)) return ResolvedImage(url = raw, mime = "")
        if (ImageTreeAssociation.isAbsoluteFilesystemPath(raw)) throw BridgeException(ImageTokens.NOT_IN_TREE)

        // (d) combine the (possibly Windows-absolute) front-matter root; a
        // non-relative result is rejected before any URI is built.
        val combined = ImageTreeAssociation.combineImageRoot(imageRoot, raw)
        val relative = ImageTreeAssociation.normalizeRelative(combined)
            ?: throw BridgeException(ImageTokens.NOT_IN_TREE)

        val documentId = documentIdOf(mdUri)?.let(ImageTreeAssociation::decodeDocumentId)

        // (d) no usable documentId (untitled document / non-document URI): no
        // provider to resolve against and nothing to associate a tree with, so
        // answer the unsupported placeholder instead of prompting.
        if (documentId.isNullOrEmpty()) throw BridgeException(ImageTokens.UNSUPPORTED_PROVIDER)

        // (b) an existing grant (in memory or persisted) resolves immediately.
        treeStore.findTreeForDocument(documentId)?.let { grant ->
            return withinTree(relative, documentId, grant)
        }

        // (d) no grant yet: classify the reason before deciding whether to prompt.
        val authority = authorityOf(mdUri)
        if (authority != null && authority in ImageTreeAssociation.SYSTEM_FORBIDDEN_AUTHORITIES) {
            throw BridgeException(ImageTokens.DENIED)
        }
        if (ImageTreeAssociation.isSystemForbiddenDocument(documentId)) {
            throw BridgeException(ImageTokens.DENIED)
        }
        // Opaque provider: never pretend support, and never prompt (the picker
        // cannot grant a usable tree for an opaque documentId).
        if (ImageTreeAssociation.classifyProvider(authority) == ImageTreeAssociation.ProviderKind.OPAQUE) {
            throw BridgeException(ImageTokens.UNSUPPORTED_PROVIDER)
        }
        // A previous cancel this session must not re-prompt on every scroll.
        if (treeStore.isSessionDenied(mdUri)) throw BridgeException(ImageTokens.TREE_CANCELLED)

        // (c) suspend until one tree grant resolves every waiter for this document.
        val grant = coordinator.awaitTree(mdUri)
        return withinTree(relative, documentId, grant)
    }

    private fun withinTree(relative: String, documentId: String?, grant: TreeGrant): ResolvedImage {
        if (ImageTreeAssociation.classifyProvider(grant.authority) == ImageTreeAssociation.ProviderKind.OPAQUE) {
            throw BridgeException(ImageTokens.UNSUPPORTED_PROVIDER)
        }
        if (ImageTreeAssociation.isSystemForbiddenTree(grant.treeDocId)) {
            throw BridgeException(ImageTokens.DENIED)
        }
        if (documentId.isNullOrEmpty()) throw BridgeException(ImageTokens.NOT_IN_TREE)

        val childDocumentId = ImageTreeAssociation.buildChildDocumentId(documentId, grant.treeDocId, relative)
            ?: throw BridgeException(ImageTokens.NOT_IN_TREE)
        val childUri = buildChildUri.build(grant.treeUri, childDocumentId)

        // A file moved out of the granted directory is not resolvable: return the
        // explicit placeholder instead of letting the WebView show a broken image.
        if (!runCatching { reader.exists(childUri) }.getOrDefault(false)) {
            throw BridgeException(ImageTokens.NOT_IN_TREE)
        }
        val size = runCatching { reader.size(childUri) }.getOrNull()
        if (size != null && size > maxBytes) throw BridgeException(ImageTokens.TOO_LARGE)

        val mime = runCatching { reader.mimeType(childUri) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ImageMimeTypes.fromName(relative)
        val token = tokens.register(childUri, mime)
        return ResolvedImage(url = endpointBase + token, mime = mime)
    }

    companion object {
        /** Android cap: over this and the image becomes a placeholder. */
        const val MAX_IMAGE_BYTES: Long = 6L * 1024 * 1024
    }
}
