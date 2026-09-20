package io.github.koucpy001.simplify2md.storage

import io.github.koucpy001.simplify2md.bridge.BridgeException
import io.github.koucpy001.simplify2md.bridge.PickerSlot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafBindingsTest {

    private class FakeLauncher(
        var open: SafPickOutcome = SafPickOutcome.Cancelled,
        var create: SafPickOutcome = SafPickOutcome.Cancelled,
    ) : SafLauncher {
        val createdNames = mutableListOf<String>()

        override suspend fun openDocument(): SafPickOutcome = open

        override suspend fun createDocument(defaultName: String): SafPickOutcome {
            createdNames.add(defaultName)
            return create
        }
    }

    private class FakeMetadata(
        var name: String? = null,
        var writable: Boolean = false,
    ) : DocumentMetadataReader {
        override fun displayName(uri: String): String? = name

        override fun supportsWrite(uri: String): Boolean = writable
    }

    private class FakePermissions : UriPermissionStore {
        val calls = mutableListOf<String>()

        override fun takePersistable(uri: String, write: Boolean) {
            calls.add("$uri|$write")
        }
    }

    private class FakeContent(private val bytes: ByteArray) : DocumentContentReader {
        override fun read(uri: String): ByteArray = bytes
    }

    private fun bindings(
        launcher: SafLauncher = FakeLauncher(),
        metadata: DocumentMetadataReader = FakeMetadata(),
        permissions: UriPermissionStore = FakePermissions(),
        content: ByteArray = "hello".toByteArray(Charsets.UTF_8),
    ): SafBindings = SafBindings(
        SafStore(PickerSlot(), launcher, metadata, permissions),
        FakeContent(content),
    )

    @Test
    fun openFileReturnsTheSameShapeAsReadFileAtWithNameAndReadonly() {
        val json: JSONObject = runBlocking {
            bindings(
                launcher = FakeLauncher(open = SafPickOutcome.Chosen("content://cloud/1")),
                metadata = FakeMetadata(name = "cloud.md", writable = false),
                content = "café".toByteArray(Charsets.UTF_8),
            ).openFile()
        }

        assertEquals("content://cloud/1", json.getString(SafBindings.KEY_PATH))
        assertEquals("café", json.getString(SafBindings.KEY_CONTENT))
        assertEquals("utf-8", json.getString(SafBindings.KEY_ENCODING))
        assertEquals("lf", json.getString(SafBindings.KEY_NEWLINE))
        assertEquals("cloud.md", json.getString(SafBindings.KEY_NAME))
        assertTrue(json.getBoolean(SafBindings.KEY_READONLY))
    }

    @Test
    fun readFileAtReDerivesNameAndReadonlyFromTheProvider() {
        val json: JSONObject = runBlocking {
            bindings(metadata = FakeMetadata(name = "reopened.md", writable = false))
                .readFileAt("content://d/reopened")
        }

        assertEquals("reopened.md", json.getString(SafBindings.KEY_NAME))
        assertTrue("reopened recents must keep the read-only protection", json.getBoolean(SafBindings.KEY_READONLY))
    }

    @Test
    fun readFileAtDetectsCrlfNewline() {
        val json: JSONObject = runBlocking {
            bindings(content = "a\r\nb".toByteArray(Charsets.UTF_8)).readFileAt("content://d/1")
        }
        assertEquals("crlf", json.getString(SafBindings.KEY_NEWLINE))
    }

    @Test
    fun readFileAtRejectsBlankUri() {
        var thrown: BridgeException? = null
        try {
            runBlocking { bindings().readFileAt("   ") }
        } catch (e: BridgeException) {
            thrown = e
        }
        assertEquals("empty path", thrown?.token)
    }

    @Test
    fun pickSavePathReturnsTheCreatedUri() {
        val permissions = FakePermissions()
        val uri = runBlocking {
            bindings(
                launcher = FakeLauncher(create = SafPickOutcome.Chosen("content://d/new.md")),
                metadata = FakeMetadata(name = "new.md", writable = true),
                permissions = permissions,
            ).pickSavePath("未标题.md")
        }
        assertEquals("content://d/new.md", uri)
        assertEquals(listOf("content://d/new.md|true"), permissions.calls)
    }

    @Test
    fun pickSavePathReturnsEmptyOnCancelToMatchTheDesktopContract() {
        val uri = runBlocking { bindings(launcher = FakeLauncher(create = SafPickOutcome.Cancelled)).pickSavePath("x.md") }
        assertEquals("", uri)
    }

    @Test
    fun pickSavePathFallsBackToTheUntitledName() {
        val launcher = FakeLauncher(create = SafPickOutcome.Cancelled)
        runBlocking { bindings(launcher = launcher).pickSavePath("  ") }
        assertEquals(listOf(SafStore.UNTITLED_NAME), launcher.createdNames)
    }
}
