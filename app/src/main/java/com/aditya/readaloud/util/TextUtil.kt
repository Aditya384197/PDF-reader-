package com.aditya.readaloud.util

import java.util.Locale
import kotlin.math.roundToInt

object TextUtil {
    data class SpeechChunk(val start: Int, val end: Int, val text: String)

    fun speechText(raw: String): String = raw
        .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), " ")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex(" *\n *"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    fun makeChunks(text: String, maxLength: Int): List<SpeechChunk> {
        if (text.isBlank()) return emptyList()
        val limit = maxLength.coerceIn(700, 2200)
        val chunks = mutableListOf<SpeechChunk>()
        var cursor = 0
        while (cursor < text.length) {
            var end = minOf(cursor + limit, text.length)
            if (end < text.length) {
                val candidates = listOf(
                    text.lastIndexOf('.', end - 1),
                    text.lastIndexOf('!', end - 1),
                    text.lastIndexOf('?', end - 1),
                    text.lastIndexOf('।', end - 1),
                    text.lastIndexOf('…', end - 1),
                    text.lastIndexOf('\n', end - 1),
                    text.lastIndexOf(' ', end - 1)
                )
                val boundary = candidates.maxOrNull() ?: -1
                if (boundary > cursor + limit / 2) end = boundary + 1
            }
            val rawPart = text.substring(cursor, end)
            val trimmed = rawPart.trim()
            if (trimmed.isEmpty()) {
                cursor = end
                continue
            }
            val leading = rawPart.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val actualStart = cursor + leading
            val actualEnd = actualStart + trimmed.length
            chunks += SpeechChunk(actualStart, actualEnd, trimmed)
            cursor = actualEnd
        }
        return chunks
    }

    fun containsDevanagari(text: String): Boolean = text.any { it.code in 0x0900..0x097F }

    fun autoLocale(text: String): Locale =
        if (containsDevanagari(text)) Locale("hi", "IN") else Locale("en", "IN")

    fun rangeAtProgress(text: String, fraction: Float): IntRange? {
        if (text.isBlank()) return null
        val safeFraction = fraction.coerceIn(0f, 0.9999f)
        val approx = (text.length * safeFraction).roundToInt().coerceIn(0, text.length - 1)
        var start = approx
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = approx
        while (end < text.length && !text[end].isWhitespace()) end++
        if (end <= start) end = minOf(start + 1, text.length)
        return start until end
    }
}
