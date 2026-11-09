package com.fraty.ai.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("fraty_local", Context.MODE_PRIVATE)

    fun loadMessages(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                ChatMessage(item.getString("role"), item.getString("content"), item.optLong("createdAt"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMessages(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(JSONObject().put("role", message.role).put("content", message.content).put("createdAt", message.createdAt))
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    fun backendUrl(): String = prefs.getString(KEY_URL, "") ?: ""
    fun saveBackendUrl(value: String) = prefs.edit().putString(KEY_URL, value.trim()).apply()
    fun appToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""
    fun saveAppToken(value: String) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    private companion object {
        const val KEY_MESSAGES = "messages_v1"
        const val KEY_URL = "backend_url"
        const val KEY_TOKEN = "backend_token"
    }
}
