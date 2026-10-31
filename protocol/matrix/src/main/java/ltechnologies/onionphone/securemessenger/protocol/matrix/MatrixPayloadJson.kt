package ltechnologies.onionphone.securemessenger.protocol.matrix

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers for Matrix message [payloadJson] mutations (reactions, relation flags).
 */
internal object MatrixPayloadJson {

    /** Deduping merge of [emoji] into an existing reactions list. */
    fun mergeReactionEmoji(existing: List<String>, emoji: String): List<String> {
        val key = emoji.trim()
        if (key.isEmpty()) return existing
        return if (key in existing) existing else existing + key
    }

    fun reactionsFromPayload(payloadJson: String?): List<String> {
        if (payloadJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(payloadJson).optJSONArray("reactions") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    fun mergeReaction(payloadJson: String?, emoji: String): String {
        val merged = mergeReactionEmoji(reactionsFromPayload(payloadJson), emoji)
        val json = runCatching { JSONObject(payloadJson ?: "{}") }.getOrElse { JSONObject() }
        val arr = JSONArray()
        merged.forEach { arr.put(it) }
        json.put("reactions", arr)
        return json.toString()
    }

    fun withFlag(payloadJson: String?, key: String, value: Boolean = true): String {
        val json = runCatching { JSONObject(payloadJson ?: "{}") }.getOrElse { JSONObject() }
        json.put(key, value)
        return json.toString()
    }

    fun putString(payloadJson: String?, key: String, value: String): String {
        val json = runCatching { JSONObject(payloadJson ?: "{}") }.getOrElse { JSONObject() }
        json.put(key, value)
        return json.toString()
    }
}
