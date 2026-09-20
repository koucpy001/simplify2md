package io.github.koucpy001.simplify2md.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure association / path rules for relative-image resolution (plan todo 13).
 *
 * The two URI forms the plan names — the document URI
 * (`content://…/document/<docId>`) and the tree URI (`content://…/tree/<treeDocId>`)
 * — are reduced to their documentIds by the Android glue; this object compares
 * those plain strings. Association is ALWAYS by documentId segment, never by raw
 * URI prefix (a document URI carries no `/tree/` segment, so prefix matching
 * would fail on the ordinary "open a file, then display its images" path).
 */
class ImageTreeAssociationTest {

    private val external = "com.android.externalstorage.documents"
    private val cloud = "com.google.android.apps.docs.storage"

    private fun candidate(treeDocId: String, authority: String = external) =
        ImageTreeAssociation.TreeCandidate(
            treeUri = "content://$authority/tree/${treeDocId.replace(":", "%3A")}",
            treeDocId = treeDocId,
            authority = authority,
        )

    // ---- association: both URI forms ----------------------------------------

    @Test
    fun documentInsideTreeAssociates() {
        // Document URI form: content://…/document/primary%3ANotes%2Fnote.md
        // Tree URI form:     content://…/tree/primary%3ANotes
        val match = ImageTreeAssociation.associateTree(
            "primary:Notes/note.md",
            listOf(candidate("primary:Notes")),
        )
        assertEquals("primary:Notes", match?.treeDocId)
    }

    @Test
    fun documentAtTreeRootAssociates() {
        val match = ImageTreeAssociation.associateTree(
            "primary:Notes",
            listOf(candidate("primary:Notes")),
        )
        assertEquals("primary:Notes", match?.treeDocId)
    }

    @Test
    fun longestTreeDocIdWinsAmongMultipleCandidates() {
        val match = ImageTreeAssociation.associateTree(
            "primary:Notes/sub/note.md",
            listOf(candidate("primary"), candidate("primary:Notes")),
        )
        assertEquals("primary:Notes", match?.treeDocId)
    }

    @Test
    fun treeDocIdBoundaryDoesNotMatchSibling() {
        // `Docs` must never match `Docs2` (the plan's explicit boundary).
        val match = ImageTreeAssociation.associateTree(
            "primary:Docs2/note.md",
            listOf(candidate("primary:Docs")),
        )
        assertNull(match)
    }

    @Test
    fun unrelatedTreeDoesNotAssociate() {
        val match = ImageTreeAssociation.associateTree(
            "primary:Other/note.md",
            listOf(candidate("primary:Notes")),
        )
        assertNull(match)
    }

    @Test
    fun opaqueProviderCandidateIsSkipped() {
        val match = ImageTreeAssociation.associateTree(
            "msf:123/note.md",
            listOf(candidate("msf:123", authority = cloud)),
        )
        assertNull("an opaque documentId provider is not associable", match)
    }

    @Test
    fun emptyTreeDocIdNeverMatches() {
        val match = ImageTreeAssociation.associateTree(
            "primary:Notes/note.md",
            listOf(candidate("")),
        )
        assertNull(match)
    }

    // ---- provider classification --------------------------------------------

    @Test
    fun externalStorageIsPathType() {
        assertEquals(
            ImageTreeAssociation.ProviderKind.PATH,
            ImageTreeAssociation.classifyProvider(external),
        )
    }

    @Test
    fun everythingElseIsOpaque() {
        assertEquals(ImageTreeAssociation.ProviderKind.OPAQUE, ImageTreeAssociation.classifyProvider(cloud))
        assertEquals(ImageTreeAssociation.ProviderKind.OPAQUE, ImageTreeAssociation.classifyProvider(null))
        assertEquals(ImageTreeAssociation.ProviderKind.OPAQUE, ImageTreeAssociation.classifyProvider(""))
    }

    // ---- system-forbidden documents -----------------------------------------

    @Test
    fun downloadDirectoryIsForbidden() {
        assertTrue(ImageTreeAssociation.isSystemForbiddenDocument("primary:Download/foo.md"))
        assertTrue(ImageTreeAssociation.isSystemForbiddenDocument("primary:Download"))
    }

    @Test
    fun androidDataAndObbAreForbidden() {
        assertTrue(ImageTreeAssociation.isSystemForbiddenDocument("primary:Android/data/x"))
        assertTrue(ImageTreeAssociation.isSystemForbiddenDocument("primary:Android/obb/x"))
    }

    @Test
    fun storageRootIsForbidden() {
        assertTrue(ImageTreeAssociation.isSystemForbiddenDocument("primary:"))
    }

    @Test
    fun ordinaryDirectoryIsNotForbidden() {
        assertFalse(ImageTreeAssociation.isSystemForbiddenDocument("primary:Notes/note.md"))
        assertFalse(ImageTreeAssociation.isSystemForbiddenDocument("primary:Notes"))
    }

    // ---- child documentId construction (the prompt-injection guard) ---------

    @Test
    fun childDocumentIdResolvesInsideDocumentDirectory() {
        val child = ImageTreeAssociation.buildChildDocumentId(
            "primary:Notes/note.md",
            "primary:Notes",
            "images/foo.png",
        )
        assertEquals("primary:Notes/images/foo.png", child)
    }

    @Test
    fun childDocumentIdResolvesRelativeImageRoot() {
        val child = ImageTreeAssociation.buildChildDocumentId(
            "primary:Notes/note.md",
            "primary:Notes",
            "assets/img/foo.png",
        )
        assertEquals("primary:Notes/assets/img/foo.png", child)
    }

    @Test
    fun traversalAboveTreeIsRejected() {
        val child = ImageTreeAssociation.buildChildDocumentId(
            "primary:Notes/note.md",
            "primary:Notes",
            "../../etc/passwd",
        )
        assertNull("a hostile ../ src must never escape the granted tree", child)
    }

    @Test
    fun traversalWithinTreeIsAllowed() {
        val child = ImageTreeAssociation.buildChildDocumentId(
            "primary:Notes/sub/note.md",
            "primary:Notes",
            "../img/foo.png",
        )
        assertEquals("primary:Notes/img/foo.png", child)
    }

    @Test
    fun volumeMismatchIsRejected() {
        val child = ImageTreeAssociation.buildChildDocumentId(
            "primary:Notes/note.md",
            "secondary:Notes",
            "images/foo.png",
        )
        assertNull(child)
    }

    @Test
    fun dotSegmentsAreCollapsed() {
        val child = ImageTreeAssociation.buildChildDocumentId(
            "primary:Notes/note.md",
            "primary:Notes",
            "./images/./foo.png",
        )
        assertEquals("primary:Notes/images/foo.png", child)
    }

    // ---- src normalization (adversarial inputs) -----------------------------

    @Test
    fun absoluteUrlIsDetected() {
        assertTrue(ImageTreeAssociation.isAbsoluteUrl("https://example.com/x.png"))
        assertTrue(ImageTreeAssociation.isAbsoluteUrl("http://example.com/x.png"))
        assertTrue(ImageTreeAssociation.isAbsoluteUrl("data:image/png;base64,AAAA"))
        assertTrue(ImageTreeAssociation.isAbsoluteUrl("blob:https://x/y"))
        assertFalse(ImageTreeAssociation.isAbsoluteUrl("images/foo.png"))
    }

    @Test
    fun absoluteFilesystemPathIsDetected() {
        assertTrue(ImageTreeAssociation.isAbsoluteFilesystemPath("/etc/passwd"))
        assertTrue(ImageTreeAssociation.isAbsoluteFilesystemPath("C:\\Users\\me\\x.png"))
        assertTrue(ImageTreeAssociation.isAbsoluteFilesystemPath("C:/Users/me/x.png"))
        assertTrue(ImageTreeAssociation.isAbsoluteFilesystemPath("\\\\server\\share\\x.png"))
        assertFalse(ImageTreeAssociation.isAbsoluteFilesystemPath("images/foo.png"))
    }

    @Test
    fun normalizeRelativeStripsQueryAndFragment() {
        assertEquals("images/foo.png", ImageTreeAssociation.normalizeRelative("images/foo.png?w=100#frag"))
    }

    @Test
    fun normalizeRelativePercentDecodes() {
        assertEquals("images/a b.png", ImageTreeAssociation.normalizeRelative("images/a%20b.png"))
    }

    @Test
    fun normalizeRelativeRejectsAbsoluteForms() {
        assertNull(ImageTreeAssociation.normalizeRelative("/abs/x.png"))
        assertNull(ImageTreeAssociation.normalizeRelative("//example.com/x.png"))
        assertNull(ImageTreeAssociation.normalizeRelative("C:\\x.png"))
        assertNull(ImageTreeAssociation.normalizeRelative("https://example.com/x.png"))
        assertNull(ImageTreeAssociation.normalizeRelative(""))
        assertNull(ImageTreeAssociation.normalizeRelative("   "))
        assertNull(ImageTreeAssociation.normalizeRelative("images/foo.png\u0000"))
    }

    @Test
    fun combineImageRootJoinsWithSlash() {
        assertEquals("images/foo.png", ImageTreeAssociation.combineImageRoot("", "images/foo.png"))
        assertEquals("assets/foo.png", ImageTreeAssociation.combineImageRoot("assets", "foo.png"))
        assertEquals("assets/foo.png", ImageTreeAssociation.combineImageRoot("assets/", "foo.png"))
        assertEquals("assets/foo.png", ImageTreeAssociation.combineImageRoot("assets\\", "foo.png"))
    }
}