package io.github.koucpy001.simplify2md.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shaping for the bridge protocol.
 *
 * Requests carry positional arguments as a JSON array string
 * (`__bridgeCall(requestId, method, argsJson)`). Results are JSON-encoded before
 * they cross back into JS, where the shim `JSON.parse`s them. Arguments and
 * results are both handled with `org.json`, which Android provides at runtime;
 * JVM unit tests supply a real `org.json` implementation on the test classpath.
 */
object BridgeCodec {

    /** Parses `argsJson`. A blank or `null` payload means "no arguments". */
    fun parseArgs(argsJson: String?): Result<JSONArray> {
        val raw = argsJson?.trim()
        if (raw.isNullOrEmpty() || raw == "null") return Result.success(JSONArray())
        return try {
            Result.success(JSONArray(raw))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("invalid bridge arguments: ${e.message}", e))
        }
    }

    /**
     * Encodes a handler result. Handlers must return JSON-friendly values
     * ([JSONObject], [JSONArray], [String], [Number], [Boolean] or null); a
     * value with no value resolves to the JSON literal `null`.
     */
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        else -> quote(value.toString())
    }

    /**
     * Produces a JSON string literal safe to embed in an `evaluateJavascript`
     * call. U+2028/U+2029 are valid inside JSON strings but are line terminators
     * in older JS engines, so they are emitted as escapes.
     */
    fun quote(value: String): String =
        JSONObject.quote(value)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
}
