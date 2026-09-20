package io.github.koucpy001.simplify2md.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCodecTest {

    @Test
    fun parsesPositionalArguments() {
        val args = BridgeCodec.parseArgs("[\"a\",1,true]").getOrThrow()
        assertEquals(3, args.length())
        assertEquals("a", args.getString(0))
        assertEquals(1, args.getInt(1))
        assertTrue(args.getBoolean(2))
    }

    @Test
    fun blankAndJsonNullMeanNoArguments() {
        assertEquals(0, BridgeCodec.parseArgs("").getOrThrow().length())
        assertEquals(0, BridgeCodec.parseArgs(null).getOrThrow().length())
        assertEquals(0, BridgeCodec.parseArgs("  null ").getOrThrow().length())
    }

    @Test
    fun malformedArgumentsFail() {
        assertTrue(BridgeCodec.parseArgs("[not json").isFailure)
        assertTrue(BridgeCodec.parseArgs("{\"a\":1}").isFailure)
    }

    @Test
    fun encodesVoidAndScalars() {
        assertEquals("null", BridgeCodec.encode(null))
        assertEquals("42", BridgeCodec.encode(42))
        assertEquals("true", BridgeCodec.encode(true))
    }

    @Test
    fun stringsRoundTripThroughQuote() {
        val original = "a\"b\\c\nd\u0000e"
        assertEquals(original, JSONArray("[${BridgeCodec.quote(original)}]").getString(0))
    }

    @Test
    fun quoteEscapesLineSeparatorsForOldJsEngines() {
        val quoted = BridgeCodec.quote("x\u2028y\u2029z")
        assertTrue(quoted.contains("\\u2028"))
        assertTrue(quoted.contains("\\u2029"))
        assertEquals("x\u2028y\u2029z", JSONArray("[$quoted]").getString(0))
    }

    @Test
    fun encodesJsonContainersVerbatim() {
        val obj = JSONObject().put("a", 1).put("b", "two")
        assertEquals(1, JSONObject(BridgeCodec.encode(obj)).getInt("a"))
        val arr = JSONArray().put(1).put(2)
        assertEquals(2, JSONArray(BridgeCodec.encode(arr)).length())
    }
}
