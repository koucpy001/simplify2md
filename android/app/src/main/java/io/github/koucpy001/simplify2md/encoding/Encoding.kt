package io.github.koucpy001.simplify2md.encoding

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Encoding and newline semantics for the Android port.
 *
 * This is a line-by-line port of the desktop Go implementation
 * (`mdview/app.go:459-546`) with two deliberate, documented differences, both
 * driven by the plan's byte-fidelity contract:
 *
 * 1. **Byte-fidelity is a distinct label.** The desktop returns `utf-8` for
 *    anything that is neither valid UTF-8 nor a confident CJK detection (random
 *    binary included). One `utf-8` label cannot express both a real UTF-8 file
 *    (`café` -> `C3 A9`) and a raw-byte fallback (a lone `E9`), so this port uses
 *    [ISO_8859_1] for the fallback and maps each byte to the codepoint of the
 *    same value (`String(b, ISO_8859_1)`). U+FFFD is never inserted.
 * 2. **Encoding is strict, never lossy.** [encodeContent] configures every
 *    [java.nio.charset.CharsetEncoder] with `REPORT`; an unrepresentable
 *    codepoint throws [EncodingException] carrying a stable token instead of the
 *    JDK default `REPLACE`, which would silently write `?`.
 *
 * Everything here is pure JVM: no Android types, no third-party charset
 * library. On OpenJDK and Android the charsets are provided by the platform
 * (ICU on device, `jdk.charsets` on the desktop JVM), so the identical code
 * runs under plain JUnit.
 */
object EncodingCodec {

    /** Labels that may travel end-to-end (OpenResult.encoding -> SaveFile.encoding). */
    const val UTF_8 = "utf-8"
    const val GB18030 = "gb18030"
    const val BIG5 = "big5"
    const val ISO_8859_1 = "iso-8859-1"

    /**
     * Stable token carried by [EncodingException] when content cannot be
     * represented in the target charset. The frontend maps it to the
     * "另存为 UTF-8" remedy (see `mdview/frontend/src/lib/encoding-token.ts`).
     */
    const val UNMAPPABLE_TOKEN = "encoding-unmappable"

    /**
     * Stable token for an encoding label that is not one of the four known
     * values. Labels crossing the bridge are untrusted input, so an unknown
     * label is rejected rather than silently written as UTF-8 (the desktop
     * default branch would); writing bytes under a label that does not describe
     * them is the silent corruption this port exists to prevent.
     */
    const val UNKNOWN_LABEL_TOKEN = "unknown-encoding"

    /** The four labels this codec produces and accepts. */
    val KNOWN_ENCODINGS: Set<String> = setOf(UTF_8, GB18030, BIG5, ISO_8859_1)

    private val UTF8_CHARSET: Charset = Charsets.UTF_8
    private val GB18030_CHARSET: Charset = Charset.forName("GB18030")
    private val BIG5_CHARSET: Charset = Charset.forName("Big5")
    private val LATIN1_CHARSET: Charset = Charsets.ISO_8859_1

    private const val CR = 13
    private const val LF = 10

    /** A decoded document plus the label that must be handed back on save. */
    data class Decoded(val content: String, val encoding: String)

    /** A failed strict encode, carrying a stable machine-readable [token]. */
    class EncodingException(val token: String, detail: String) :
        Exception("$token: $detail")

    // ---- encoding detection / conversion (app.go:459-521) -------------------

    /**
     * Port of Go `detectEncoding` (`app.go:459-486`).
     *
     * Valid UTF-8 wins. Otherwise the Big5 and GB18030 probes run only when the
     * input has no NUL byte (the Go `bytes.IndexByte(b, 0) == -1` guard at
     * `app.go:471` and `:479`) and the strictly decoded text contains a Han
     * character ([containsCJK], `app.go:488-496`). Anything else is the
     * byte-fidelity fallback [ISO_8859_1].
     *
     * **Probe order is deliberately Big5-first.** The Go original runs `chardet`
     * before the probes, so a Big5 file is identified statistically before the
     * GB18030 sanity probe can swallow it. `chardet` is intentionally not ported
     * (the plan forbids third-party detectors), and GB18030 is a near-superset
     * that strictly decodes most byte sequences — including Big5 text — so a
     * GB18030-first order would mis-detect every Big5 file and re-encode it with
     * different bytes on save. Big5 is the more restrictive charset, so a strict
     * Big5 decode succeeding is the stronger signal and must be tried first.
     * Equivalence is asserted only over the shared static fixture corpus; no
     * "equivalent to chardet" claim is made.
     */
    fun detectEncoding(b: ByteArray): String {
        if (isValidUtf8(b)) return UTF_8
        // Big5 probe first: GB18030 strictly decodes Big5 bytes too, so a
        // GB18030-first order would mis-detect Big5 files (see class comment).
        if (!hasNul(b)) {
            val big5 = decodeStrict(b, BIG5_CHARSET)
            if (big5 != null && !big5.contains('\uFFFD') && containsCJK(big5)) return BIG5
        }
        if (!hasNul(b)) {
            val gb = decodeStrict(b, GB18030_CHARSET)
            if (gb != null && !gb.contains('\uFFFD') && containsCJK(gb)) return GB18030
        }
        return ISO_8859_1
    }

    /**
     * Port of Go `containsCJK` (`app.go:488-496`): at least one Han character
     * in 0x3400-0x9FFF or 0xF900-0xFAFF. Iterates code points (Go iterates
     * runes), so a surrogate pair is never misread as two BMP values.
     */
    fun containsCJK(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if ((cp in 0x3400..0x9FFF) || (cp in 0xF900..0xFAFF)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Decodes [b] detected as [enc], mirroring Go `decodeBytes`
     * (`app.go:498-521`). A successful CJK/UTF-8 decode returns the text; any
     * failure or unknown label returns the byte-fidelity mapping of [ISO_8859_1]
     * (each byte -> the codepoint of the same value), never U+FFFD.
     */
    fun decodeBytes(b: ByteArray, enc: String): String {
        val decoded = when (enc) {
            UTF_8 -> decodeStrict(b, UTF8_CHARSET)
            GB18030 -> decodeStrict(b, GB18030_CHARSET)
            BIG5 -> decodeStrict(b, BIG5_CHARSET)
            else -> null
        }
        return decoded ?: latin1Decode(b)
    }

    /**
     * Detect-then-decode in one step. If the detected label cannot in fact be
     * decoded (defensive: detection and decode use the same strict decoder, so
     * this only triggers for a hand-crafted label path), the returned label is
     * downgraded to [ISO_8859_1] alongside the byte-fidelity content, so the
     * pair stays self-consistent and a later save cannot corrupt the file.
     */
    fun decode(b: ByteArray): Decoded {
        val detected = detectEncoding(b)
        val content = when (detected) {
            GB18030 -> decodeStrict(b, GB18030_CHARSET)
            BIG5 -> decodeStrict(b, BIG5_CHARSET)
            UTF_8 -> decodeStrict(b, UTF8_CHARSET)
            else -> null
        }
        return if (content != null) Decoded(content, detected)
        else Decoded(latin1Decode(b), ISO_8859_1)
    }

    /**
     * Encodes [content] under the label [enc] with the strict, REPORT-configured
     * encoder. Dispatches exactly as Go `encodeContent` (`app.go:511-521`) for
     * the four known labels; unlike Go's permissive `default`, an unknown label
     * is rejected.
     *
     * @throws EncodingException [UNMAPPABLE_TOKEN] when a codepoint cannot be
     *         represented (this is the "never write `?`" guarantee), or
     *         [UNKNOWN_LABEL_TOKEN] for an unrecognised label.
     */
    fun encodeContent(content: String, enc: String): ByteArray = when (enc) {
        UTF_8 -> encodeStrict(content, UTF8_CHARSET)
        GB18030 -> encodeStrict(content, GB18030_CHARSET)
        BIG5 -> encodeStrict(content, BIG5_CHARSET)
        ISO_8859_1 -> encodeStrict(content, LATIN1_CHARSET)
        else -> throw EncodingException(UNKNOWN_LABEL_TOKEN, "unsupported encoding label: $enc")
    }

    // ---- newline detection / conversion (app.go:528-546) --------------------

    /**
     * Port of Go `detectNewline` (`app.go:528-535`): the dominant style wins, so
     * an LF file with one stray CRLF keeps LF. A tie goes to LF.
     */
    fun detectNewline(b: ByteArray): String {
        var crlf = 0
        var totalLf = 0
        for (i in b.indices) {
            if (b[i].toInt() == LF) {
                totalLf++
                if (i > 0 && b[i - 1].toInt() == CR) crlf++
            }
        }
        val lf = totalLf - crlf
        return if (crlf > lf) "crlf" else "lf"
    }

    /**
     * Port of Go `applyNewline` (`app.go:540-546`): the editor keeps LF
     * internally, so a CRLF document is re-expanded on save and an LF document
     * passes through untouched.
     */
    fun applyNewline(content: String, newline: String): String {
        if (newline == "crlf") {
            return content.replace("\r\n", "\n").replace("\n", "\r\n")
        }
        return content
    }

    // ---- internals ----------------------------------------------------------

    /** Strict UTF-8 validity, replacing the Go `utf8.Valid` check. */
    private fun isValidUtf8(b: ByteArray): Boolean = decodeStrict(b, UTF8_CHARSET) != null

    /** Go `bytes.IndexByte(b, 0) == -1` guard. */
    private fun hasNul(b: ByteArray): Boolean = b.any { it.toInt() == 0 }

    /**
     * Decodes fully or returns null. REPORT on malformed input and unmappable
     * characters turns every partial/broken sequence into a thrown
     * [CharacterCodingException] instead of a silent U+FFFD.
     */
    private fun decodeStrict(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Byte -> same-value codepoint mapping; the byte-fidelity fallback. */
    private fun latin1Decode(b: ByteArray): String = String(b, LATIN1_CHARSET)

    private fun encodeStrict(content: String, cs: Charset): ByteArray {
        val encoder = cs.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val buf = encoder.encode(CharBuffer.wrap(content))
            ByteArray(buf.remaining()).also { buf.get(it) }
        } catch (_: CharacterCodingException) {
            throw EncodingException(UNMAPPABLE_TOKEN, "cannot represent content as ${cs.name()}")
        }
    }
}
