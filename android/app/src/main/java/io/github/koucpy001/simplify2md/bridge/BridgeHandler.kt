package io.github.koucpy001.simplify2md.bridge

import org.json.JSONArray

/**
 * A bridge method implementation. Invoked off the JavaBridge thread on
 * `Dispatchers.IO`; throwing rejects the JS Promise with the exception's message.
 */
fun interface BridgeHandler {
    suspend fun handle(args: JSONArray): Any?
}

/**
 * A user cancel. The message must stay "cancelled" to preserve Go semantics:
 * the frontend tests `/cancelled/i` and treats a cancel as a no-op rather than
 * an error that would clear the editor.
 */
class BridgeCancelledException(
    message: String = MESSAGE,
) : Exception(message) {
    companion object {
        const val MESSAGE = "cancelled"
    }
}

/**
 * Rejection carrying a stable token the frontend can map to a user action
 * (for example `encoding-unmappable`); the token is also the default message.
 */
class BridgeException(
    val token: String,
    message: String = token,
) : Exception(message)
