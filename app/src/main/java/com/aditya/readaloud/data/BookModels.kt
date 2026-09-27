package com.aditya.readaloud.data

import org.json.JSONObject

/** Persistent metadata for one imported book. */
data class BookMeta(
    val id: String,
    val title: String,
    val fileName: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val importedAt: Long,
    val lastOpenedAt: Long,
    val lastPage: Int = 0,
    val lastSpeed: Float = 1.0f,
    val languageMode: String = "auto",
    val voiceName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("fileName", fileName)
        put("pageCount", pageCount)
        put("sizeBytes", sizeBytes)
        put("importedAt", importedAt)
        put("lastOpenedAt", lastOpenedAt)
        put("lastPage", lastPage)
        put("lastSpeed", lastSpeed.toDouble())
        put("languageMode", languageMode)
        put("voiceName", voiceName ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): BookMeta = BookMeta(
            id = json.getString("id"),
            title = json.getString("title"),
            fileName = json.getString("fileName"),
            pageCount = json.getInt("pageCount"),
            sizeBytes = json.getLong("sizeBytes"),
            importedAt = json.getLong("importedAt"),
            lastOpenedAt = json.optLong("lastOpenedAt", json.getLong("importedAt")),
            lastPage = json.optInt("lastPage", 0),
            lastSpeed = json.optDouble("lastSpeed", 1.0).toFloat(),
            languageMode = json.optString("languageMode", "auto"),
            voiceName = if (json.isNull("voiceName")) null else json.optString("voiceName").takeIf { it.isNotBlank() }
        )
    }
}
