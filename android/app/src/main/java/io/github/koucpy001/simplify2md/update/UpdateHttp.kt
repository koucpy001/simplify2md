package io.github.koucpy001.simplify2md.update

/** One HTTP GET result; [body] is null unless the request returned 200. */
data class UpdateHttpResponse(val statusCode: Int, val body: String?)

/**
 * The network boundary for the update check.
 *
 * Abstracted behind an interface so the parsing / version comparison / URL
 * whitelist logic is pure-JVM testable without real network access — the same
 * seam pattern used by `ExternalUrlLauncher`, `SafStore` and `RecoveryPrompt`.
 * The production implementation is [HttpUrlConnectionUpdateClient]; tests inject
 * a fake that returns canned responses and counts calls.
 */
fun interface UpdateHttpClient {
    fun get(url: String, headers: Map<String, String>): UpdateHttpResponse
}
