package com.troy.orcaplayer.player.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object ArabicText {
    private val arabicLetter = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val leadingPunct = Regex("^([\\p{Punct}،؟؛…\\s]+)(.*)$")

    fun containsArabic(s: String) = arabicLetter.containsMatchIn(s)

    /**
     * "Fix punctuation": some Arabic files have punctuation moved to the start of the line
     * (".مرحبا" instead of "مرحبا."). Moves it back to the end of each Arabic line.
     */
    fun fixPunctuation(text: String): String = text.lines().joinToString("\n") { line ->
        if (!containsArabic(line)) return@joinToString line
        val m = leadingPunct.find(line) ?: return@joinToString line
        val punct = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (punct.isEmpty() || rest.isEmpty()) line else "$rest$punct"
    }

    /** True when the bytes are valid UTF-8. */
    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * Decodes subtitle bytes. [preferred] is the user's manual choice ("auto" or a charset name).
     * [detected] is what a detector (ICU on Android) guessed. Without a usable guess, invalid UTF-8
     * falls back to Windows-1256, the usual encoding of Arabic SRT files.
     */
    fun decode(bytes: ByteArray, preferred: String = "auto", detected: String? = null): String {
        val bomStripped = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
            else -> bytes
        }
        if (preferred != "auto") {
            return String(bomStripped, runCatching { Charset.forName(preferred) }.getOrDefault(Charsets.UTF_8))
        }
        if (isValidUtf8(bomStripped)) return String(bomStripped, Charsets.UTF_8)
        val guess = detected?.takeIf { it.isNotBlank() && !it.equals("UTF-8", true) }
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        // ICU often reports ISO-8859-1 for Arabic Windows-1256 text; prefer 1256 in that case.
        val charset = when {
            guess == null -> Charset.forName("windows-1256")
            guess.name().equals("ISO-8859-1", true) -> Charset.forName("windows-1256")
            else -> guess
        }
        return String(bomStripped, charset)
    }

    val manualEncodings = listOf("auto", "UTF-8", "windows-1256", "ISO-8859-6", "UTF-16", "windows-1252", "ISO-8859-1")
}
