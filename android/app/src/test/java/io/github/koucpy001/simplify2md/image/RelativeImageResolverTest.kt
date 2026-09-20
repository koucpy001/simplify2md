package io.github.koucpy001.simplify2md.image

import io.github.koucpy001.simplify2md.bridge.BridgeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The (a)(b)(c)(d)(e) decision matrix of `LoadImageForSrc` (plan todo 13).
 *
 * The resolver is pure JVM: the Android boundaries (`ContentResolver`,
 * `DocumentsContract`, the tree picker) are injected behind the small seams
 * declared in `ImageResolver.kt`.
 */
class RelativeImageResolverTest {

    private class FakePersistence : TreeGrantPersistence {
        override fun takePersistable(treeUri: String) = Unit
        override fun release(treeUri: String) = Unit
        override fun persistedTreeUris(): List<String> = emptyList()
    }

    private class FakeReader(
        var exists: Boolean = true,
        var size: Long? = 100,
        var mime: String? = "image/png",
    ) : ImageDocumentReader {
        override fun exists(uri: String): Boolean = exists
        override fun size(uri: String): Long? = size
        override fun mimeType(uri: String): String? = mime
    }

    private class CountingLauncher(var result: TreeGrantResult = TreeGrantResult.Cancelled) : TreeGrantLauncher {
        var launches = 0
        override suspend fun launchTreePicker(): TreeGrantResult {
            launches++
            return result
        }
    }

    private val external = "com.android.externalstorage.documents"
    private val cloud = "com.google.android.apps.docs.storage"

    private fun treeDocIdOf(uri: String): String? = uri.substringAfterLast('/').replace("%3A", ":")

    private fun documentIdOf(uri: String): String? = uri.substringAfterLast('/').replace("%3A", ":")

    private fun store() = ImageTreeStore(FakePersistence(), ::treeDocIdOf, { external })

    private fun resolver(
        treeStore: ImageTreeStore = store(),
        coordinator: RelativeImageTreeCoordinator? = null,
        reader: ImageDocumentReader = FakeReader(),
        authority: String = external,
        maxBytes: Long = RelativeImageResolver.MAX_IMAGE_BYTES,
    ) = RelativeImageResolver(
        treeStore = treeStore,
        coordinator = coordinator ?: RelativeImageTreeCoordinator(treeStore, CountingLauncher()),
        reader = reader,
        tokens = MediaTokenStore(),
        documentIdOf = ::documentIdOf,
        authorityOf = { authority },
        buildChildUri = ChildDocumentUriBuilder { treeUri, childId -> "content://auth/tree/$childId" },
        endpointBase = "https://appassets.androidplatform.net/media/",
        maxBytes = maxBytes,
    )

    private val mdUri = "content://$external/document/primary%3ANotes%2Fnote.md"

    private fun assertToken(token: String, block: suspend () -> Unit) {
        var thrown: BridgeException? = null
        try {
            runBlocking { block() }
        } catch (e: BridgeException) {
            thrown = e
        }
        assertEquals(token, thrown?.token)
    }

    // ---- (a) absolute URLs ------------------------------------------------

    @Test
    fun absoluteUrlPassesThroughUnchanged() = runBlocking {
        val result = resolver().resolve("https://example.com/x.png", mdUri, "")
        assertEquals("https://example.com/x.png", result.url)
    }

    @Test
    fun dataUrlPassesThroughUnchanged() = runBlocking {
        val result = resolver().resolve("data:image/png;base64,AAAA", mdUri, "")
        assertEquals("data:image/png;base64,AAAA", result.url)
    }

    // ---- (b) relative + granted tree --------------------------------------

    @Test
    fun relativeWithGrantedTreeReturnsTokenUrl() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        val result = resolver(s).resolve("images/foo.png", mdUri, "")
        assertTrue(result.url.startsWith("https://appassets.androidplatform.net/media/"))
        assertEquals("image/png", result.mime)
    }

    @Test
    fun relativeWithRelativeImageRootResolvesInsideDocumentDirectory() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        val result = resolver(s).resolve("foo.png", mdUri, "images")
        assertTrue(result.url.startsWith("https://appassets.androidplatform.net/media/"))
    }

    @Test
    fun missingFileIsNotInTreePlaceholder() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        assertToken(ImageTokens.NOT_IN_TREE) {
            resolver(s, reader = FakeReader(exists = false)).resolve("images/foo.png", mdUri, "")
        }
    }

    @Test
    fun traversalOutsideTreeIsNotInTreePlaceholder() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        assertToken(ImageTokens.NOT_IN_TREE) {
            resolver(s).resolve("../../etc/passwd", mdUri, "")
        }
    }

    @Test
    fun oversizedImageIsTooLargePlaceholder() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        assertToken(ImageTokens.TOO_LARGE) {
            resolver(s, reader = FakeReader(size = 7L * 1024 * 1024)).resolve("images/foo.png", mdUri, "")
        }
    }

    @Test
    fun exactlySixMegabytesIsAllowed() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        val result = resolver(s, reader = FakeReader(size = 6L * 1024 * 1024))
            .resolve("images/foo.png", mdUri, "")
        assertTrue(result.url.startsWith("https://appassets.androidplatform.net/media/"))
    }

    // ---- (c) relative + no granted tree -----------------------------------

    @Test
    fun relativeWithoutTreeSuspendsThenResolves() = runBlocking {
        val launcher = CountingLauncher(TreeGrantResult.Granted("content://$external/tree/primary%3ANotes"))
        val s = store()
        val coordinator = RelativeImageTreeCoordinator(s, launcher)
        val result = resolver(s, coordinator).resolve("images/foo.png", mdUri, "")
        assertTrue(result.url.startsWith("https://appassets.androidplatform.net/media/"))
        assertEquals(1, launcher.launches)
    }

    @Test
    fun cancelledTreePromptRejectsWithTreeCancelled() = runBlocking {
        val launcher = CountingLauncher(TreeGrantResult.Cancelled)
        val s = store()
        val coordinator = RelativeImageTreeCoordinator(s, launcher)
        assertToken(ImageTokens.TREE_CANCELLED) {
            resolver(s, coordinator).resolve("images/foo.png", mdUri, "")
        }
        assertTrue(s.isSessionDenied(mdUri))
    }

    // ---- (d) unsupported / forbidden / malformed --------------------------

    @Test
    fun opaqueProviderIsUnsupportedPlaceholder() = runBlocking {
        val cloudUri = "content://$cloud/document/msf%3A123"
        assertToken(ImageTokens.UNSUPPORTED_PROVIDER) {
            resolver(authority = cloud).resolve("images/foo.png", cloudUri, "")
        }
    }

    @Test
    fun downloadAuthorityIsDeniedPlaceholder() = runBlocking {
        val downloadUri = "content://com.android.providers.downloads.documents/document/msf%3A123"
        assertToken(ImageTokens.DENIED) {
            resolver(authority = "com.android.providers.downloads.documents")
                .resolve("images/foo.png", downloadUri, "")
        }
    }

    @Test
    fun downloadDocumentIdIsDeniedPlaceholder() = runBlocking {
        val downloadUri = "content://$external/document/primary%3ADownload%2Fnote.md"
        assertToken(ImageTokens.DENIED) {
            resolver().resolve("images/foo.png", downloadUri, "")
        }
    }

    @Test
    fun windowsAbsoluteImageRootIsNotInTreePlaceholder() = runBlocking {
        assertToken(ImageTokens.NOT_IN_TREE) {
            resolver().resolve("images/foo.png", mdUri, "C:\\Users\\me\\docs")
        }
    }

    @Test
    fun emptySrcIsNotInTreePlaceholder() = runBlocking {
        assertToken(ImageTokens.NOT_IN_TREE) { resolver().resolve("", mdUri, "") }
    }

    @Test
    fun protocolRelativeSrcIsNotInTreePlaceholder() = runBlocking {
        assertToken(ImageTokens.NOT_IN_TREE) { resolver().resolve("//example.com/x.png", mdUri, "") }
    }

    @Test
    fun absoluteFilesystemSrcIsNotInTreePlaceholder() = runBlocking {
        assertToken(ImageTokens.NOT_IN_TREE) { resolver().resolve("/etc/passwd", mdUri, "") }
    }

    @Test
    fun queryStringAndFragmentAreStripped() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        val result = resolver(s).resolve("images/foo.png?w=100#frag", mdUri, "")
        assertTrue(result.url.startsWith("https://appassets.androidplatform.net/media/"))
    }

    @Test
    fun percentEncodedRelativeNameResolves() = runBlocking {
        val s = store()
        s.registerTree("content://$external/tree/primary%3ANotes", "doc")
        val result = resolver(s).resolve("images/a%20b.png", mdUri, "")
        assertTrue(result.url.startsWith("https://appassets.androidplatform.net/media/"))
    }

    @Test
    fun untitledDocumentWithRelativeImageIsUnsupportedPlaceholder() = runBlocking {
        assertToken(ImageTokens.UNSUPPORTED_PROVIDER) {
            resolver().resolve("images/foo.png", "", "")
        }
    }
}