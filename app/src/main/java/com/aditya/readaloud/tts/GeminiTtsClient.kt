package com.aditya.readaloud.tts

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object GeminiTtsClient {
    private const val MODEL = "gemini-3.8-flash-tts"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

    suspend fun synthesize(apiKey: String, text: String, voiceName: String): ByteArray = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key दर्ज नहीं है" }
        require(text.isNotBlank()) { "वाचन के लिए पाठ खाली है" }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }

        val annotation = JSONObject().put(
            "type", "speech_metadata"
        ).put(
            "style",
            "Warm, natural Indian male audiobook narrator. Calm, expressive and human. " +
                "Use natural pauses and clear pronunciation. Read the transcript verbatim; " +
                "do not summarize, add, translate or explain anything."
        )

        val inputText = JSONObject()
            .put("type", "text")
            .put("text", text)
            .put("annotations", JSONArray().put(annotation))

        val request = JSONObject()
            .put("model", MODEL)
            .put(
                "input", JSONArray().put(
                    JSONObject()
                        .put("type", "user_input")
                        .put("content", JSONArray().put(inputText))
                )
            )
            .put("response_format", JSONObject().put("type", "audio"))
            .put(
                "generation_config", JSONObject().put(
                    "speech_config", JSONArray().put(
                        JSONObject().put("voice", voiceName)
                    )
                )
            )

        connection.outputStream.use { it.write(request.toString().toByteArray(StandardCharsets.UTF_8)) }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }
                .getOrDefault("")
            throw IOException(if (message.isBlank()) "Gemini TTS HTTP $code" else message)
        }

        val encoded = findAudioData(JSONObject(body))
            ?: throw IOException("Gemini TTS ने ऑडियो नहीं लौटाया")
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        toWavIfNeeded(bytes)
    }

    private fun findAudioData(json: JSONObject): String? {
        val steps = json.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val item = content.optJSONObject(j) ?: continue
                    val data = item.optString("data")
                    val type = item.optString("type")
                    if (data.isNotBlank() && (type.isBlank() || type.contains("audio", true))) return data
                }
            }
        }

        val candidates = json.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data")
                if (data.isNotBlank()) return data
            }
        }
        return null
    }

    private fun toWavIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))) {
            return bytes
        }
        val sampleRate = 24_000
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val out = ByteArrayOutputStream(bytes.size + 44)
        fun le(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le(36 + bytes.size)
        out.write("WAVEfmt ".toByteArray())
        le(16)
        out.write(1)
        out.write(0)
        out.write(channels)
        out.write(0)
        le(sampleRate)
        le(byteRate)
        out.write(blockAlign)
        out.write(0)
        out.write(bits)
        out.write(0)
        out.write("data".toByteArray())
        le(bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }
}
