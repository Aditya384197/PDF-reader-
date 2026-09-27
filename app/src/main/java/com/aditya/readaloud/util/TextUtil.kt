package com.aditya.readaloud.util

import java.util.Locale

object TextUtil {
    data class SpeechChunk(val start: Int, val end: Int, val text: String)

    fun speechText(raw: String): String = raw
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex(" *\n *"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    fun makeChunks(text: String, maxLength: Int): List<SpeechChunk> {
        if (text.isBlank()) return emptyList()
        val limit = maxLength.coerceIn(800, 3500)
        val chunks = mutableListOf<SpeechChunk>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val candidates = listOf(
                    text.lastIndexOf('.', end - 1, start),
                    text.lastIndexOf('!', end - 1, start),
                    text.lastIndexOf('?', end - 1, start),
                    text.lastIndexOf('।', end - 1, start),
                    text.lastIndexOf('\n', end - 1, start),
                    text.lastIndexOf(' ', end - 1, start)
                )
                val boundary = candidates.maxOrNull() ?: -1
                if (boundary > start + limit / 2) end = boundary + 1
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) {
                val first = text.indexOf(part, start).takeIf { it >= start } ?: start
                val last = first + part.length
                chunks += SpeechChunk(first, last, part)
                start = last
            } else {
                start = end
            }
        }
        return chunks
    }

    fun containsDevanagari(text: String): Boolean = text.any { it.code in 0x0900..0x097F }
    fun autoLocale(text: String): Locale = if (containsDevanagari(text)) Locale("hi", "IN") else Locale("en", "IN")

    fun currentWordRange(text: String, start: Int, end: Int): IntRange? {
        if (text.isEmpty()) return null
        val s = start.coerceIn(0, text.length - 1)
        val e = end.coerceIn(s + 1, text.length)
        return s until e
    }
}
