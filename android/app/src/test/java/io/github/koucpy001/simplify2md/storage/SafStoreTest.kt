package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.BridgeCancelledException
import io.github.koucpy001.simplify2md.bridge.BridgeException
import io.github.koucpy001.simplify2md.bridge.PickerSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafStoreTest {

    private class FakeLauncher(
        var open: SafPickOutcome = SafPickOutcome.Cancelled,
        var create: SafPickOutcome = SafPickOutcome.Cancelled,
    ) : SafLauncher {
        val createdDefaultNames = mutableListOf<String>()

        override suspend fun openDocument(): SafPickOutcome = open

        override suspend fun createDocument(defaultName: String): SafPickOutcome {
            createdDefaultNames.add(defaultName)
            return create
        }
    }

    private class FakeMetadata(
        var name: String? = null,
        var writable: Boolean = false,
        var throwOnQuery: Boolean = false,
    ) : DocumentMetadataReader {
        override fun displayName(uri: String): String? {
            if (throwOnQuery) throw SecurityException("denied")
            return name
        }

        override fun supportsWrite(uri: String): Boolean {
            if (throwOnQuery) throw SecurityException("denied")
            return writable
        }
    }

    private class FakePermissions(var throwOnTake: Boolean = false) : UriPermissionStore {
        val calls = mutableListOf<Pair<String, Boolean>>()

        override fun takePersistable(uri: String, write: Boolean) {
            calls.add(uri to write)
            if (throwOnTake) throw SecurityException("provider has no persistable grant")
        }
    }

    private fun store(
        launcher: SafLauncher,
        metadata: DocumentMetadataReader = FakeMetadata(),
        permissions: UriPermissionStore = FakePermissions(),
        slot: PickerSlot = PickerSlot(),
        onSessionGrant: (SafDocument) -> Unit = {},
    ) = SafStore(slot, launcher, metadata, permissions, onSessionGrant)

    private fun assertCancelled(block: suspend () -> Unit) {
        var thrown: BridgeCancelledException? = null
        try {
            runBlocking { block() }
        } catch (e: BridgeCancelledException) {
            thrown = e
        }
        assertTrue("expected BridgeCancelledException", thrown != null)
        assertEquals("cancelled", thrown!!.message)
    }

    @Test
    fun cancelledPickerRejectsWithCancelledToken() {
        assertCancelled { store(FakeLauncher(open = SafPickOutcome.Cancelled)).openDocument() }
    }

    @Test
    fun resultOkWithEmptyDataRejectsAsCancelled() {
        assertCancelled { store(FakeLauncher(open = SafPickOutcome.Chosen(""))).openDocument() }
    }

    @Test
    fun resultMapperTreatsNotOkBlankAndNullAsCancelled() {
        assertEquals(SafPickOutcome.Cancelled, SafResultMapper.toOutcome(resultOk = false, uri = "content://d/1"))
        assertEquals(SafPickOutcome.Cancelled, SafResultMapper.toOutcome(resultOk = true, uri = null))
        assertEquals(SafPickOutcome.Cancelled, SafResultMapper.toOutcome(resultOk = true, uri = ""))
        assertEquals(SafPickOutcome.Cancelled, SafResultMapper.toOutcome(resultOk = true, uri = "   "))
        assertEquals(
            SafPickOutcome.Chosen("content://d/1"),
            SafResultMapper.toOutcome(resultOk = true, uri = "content://d/1"),
        )
    }

    @Test
    fun chosenUriUsesProviderDisplayName() {
        val doc = runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://d/1")),
                metadata = FakeMetadata(name = "note.md", writable = true),
            ).openDocument()
        }
        assertEquals("content://d/1", doc.uri)
        assertEquals("note.md", doc.name)
    }

    @Test
    fun missingDisplayNameFallsBackToEmpty() {
        val doc = runBlocking {
            store(FakeLauncher(open = SafPickOutcome.Chosen("content://d/1")), metadata = FakeMetadata(name = null))
                .openDocument()
        }
        assertEquals(SafDocument.UNKNOWN_NAME, doc.name)
    }

    @Test
    fun documentWithoutWriteSupportIsReadonly() {
        val doc = runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://cloud/1")),
                metadata = FakeMetadata(name = "cloud.md", writable = false),
            ).openDocument()
        }
        assertTrue("a provider without FLAG_SUPPORTS_WRITE must be readonly", doc.readonly)
    }

    @Test
    fun writableDocumentIsNotReadonly() {
        val doc = runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://d/1")),
                metadata = FakeMetadata(name = "note.md", writable = true),
            ).openDocument()
        }
        assertFalse(doc.readonly)
    }

    @Test
    fun persistFailureDegradesToSessionGrantWithoutCrashing() {
        val degraded = mutableListOf<SafDocument>()
        val permissions = FakePermissions(throwOnTake = true)
        val doc = runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://d/1")),
                metadata = FakeMetadata(name = "note.md", writable = true),
                permissions = permissions,
                onSessionGrant = { degraded.add(it) },
            ).openDocument()
        }
        assertEquals(GrantLevel.SESSION, doc.grant)
        assertEquals(listOf(doc), degraded)
        assertEquals(listOf("content://d/1" to true), permissions.calls)
    }

    @Test
    fun persistSuccessIsPersisted() {
        val permissions = FakePermissions()
        val doc = runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://d/1")),
                metadata = FakeMetadata(name = "note.md", writable = true),
                permissions = permissions,
            ).openDocument()
        }
        assertEquals(GrantLevel.PERSISTED, doc.grant)
        assertEquals(listOf("content://d/1" to true), permissions.calls)
    }

    @Test
    fun readonlyDocumentPersistsReadOnlyGrant() {
        val permissions = FakePermissions()
        runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://cloud/1")),
                metadata = FakeMetadata(name = "cloud.md", writable = false),
                permissions = permissions,
            ).openDocument()
        }
        assertEquals(listOf("content://cloud/1" to false), permissions.calls)
    }

    @Test
    fun createDocumentPersistsReadWriteAndCarriesDefaultName() {
        val permissions = FakePermissions()
        val launcher = FakeLauncher(create = SafPickOutcome.Chosen("content://d/new"))
        val doc = runBlocking {
            store(
                launcher,
                metadata = FakeMetadata(name = "new.md", writable = true),
                permissions = permissions,
            ).createDocument("未标题.md")
        }
        assertEquals("content://d/new", doc.uri)
        assertEquals(listOf("未标题.md"), launcher.createdDefaultNames)
        assertEquals(listOf("content://d/new" to true), permissions.calls)
    }

    @Test
    fun describeReQueriesNameAndReadonlyWithoutPersisting() {
        val permissions = FakePermissions()
        val doc = store(
            FakeLauncher(),
            metadata = FakeMetadata(name = "reopened.md", writable = false),
            permissions = permissions,
        ).describe("content://d/reopened")
        assertEquals("reopened.md", doc.name)
        assertTrue(doc.readonly)
        assertEquals(GrantLevel.SESSION, doc.grant)
        assertTrue("describe must not touch persisted permissions", permissions.calls.isEmpty())
    }

    @Test
    fun metadataQueryFailureIsReadonlyAndDoesNotCrash() {
        val doc = runBlocking {
            store(
                FakeLauncher(open = SafPickOutcome.Chosen("content://weird/1")),
                metadata = FakeMetadata(throwOnQuery = true),
            ).openDocument()
        }
        assertEquals(SafDocument.UNKNOWN_NAME, doc.name)
        assertTrue(doc.readonly)
    }

    @Test
    fun concurrentPickerIsRejectedAndTheSlotIsReleasedAfterward() {
        val slot = PickerSlot()
        val store = store(FakeLauncher(open = SafPickOutcome.Chosen("content://d/1")), slot = slot)
        assertTrue(slot.tryAcquire())

        var thrown: BridgeException? = null
        try {
            runBlocking { store.openDocument() }
        } catch (e: BridgeException) {
            thrown = e
        }
        assertEquals(SafStore.PICKER_BUSY_TOKEN, thrown?.token)

        slot.release()
        runBlocking { store.openDocument() }
        assertFalse("the slot must be released after the picker returns", slot.isPending)
    }
}
