package io.github.koucpy001.simplify2md.update

import java.net.HttpURLConnection
import java.net.URI

/**
 * Production [UpdateHttpClient] built on [HttpURLConnection] — the platform
 * stack, with no third-party HTTP client dependency.
 *
 * Both connect and read timeouts are set explicitly. An unbounded wait on a
 * flaky network is a hang, not a slow check (plan review N6), so the call is
 * bounded at [DEFAULT_CONNECT_TIMEOUT_MS] / [DEFAULT_READ_TIMEOUT_MS].
 *
 * The call is blocking and is only ever invoked off the main thread: the bridge
 * dispatch runs handler bodies on `Dispatchers.IO` (`bridge/Bridge.kt`). No
 * polling and no background work are involved.
 */
class HttpUrlConnectionUpdateClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : UpdateHttpClient {

    override fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setConnectTimeout(connectTimeoutMs)
            connection.setReadTimeout(readTimeoutMs)
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            val status = connection.responseCode
            val body = if (status == HTTP_OK) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
            return UpdateHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
        const val HTTP_OK = 200
    }
}
