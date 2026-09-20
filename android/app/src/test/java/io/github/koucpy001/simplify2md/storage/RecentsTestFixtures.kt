package io.github.koucpy001.simplify2md.storage

import java.io.IOException

/** In-memory `config.json` replacement for the recents JVM tests. */
class FakeConfigFiles(var content: String? = null) : ConfigFileSystem {

    var lastWritten: String? = null
    var failWrite = false
    val writes = mutableListOf<String>()

    override fun read(): String? = content

    override fun write(json: String) {
        if (failWrite) throw IOException("config write failed")
        lastWritten = json
        content = json
        writes.add(json)
    }
}

/** Records which grants a release call targeted. */
class RecordingGrantReleaser : UriGrantReleaser {

    val released = mutableListOf<String>()
    var failOn: String? = null

    override fun release(uri: String) {
        if (uri == failOn) throw SecurityException("no persistable grant for $uri")
        released.add(uri)
    }
}

fun safeDocument(
    uri: String,
    name: String = "",
    readonly: Boolean = false,
): SafDocument = SafDocument(uri = uri, name = name, readonly = readonly, grant = GrantLevel.PERSISTED)
