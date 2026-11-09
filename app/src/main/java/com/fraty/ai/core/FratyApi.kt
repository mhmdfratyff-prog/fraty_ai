package com.fraty.ai.core

import com.fraty.ai.history.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class FratyApi(private val baseUrl: String, private val appToken: String) {
    suspend fun sendChat(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://")) {
            "أدخل عنوان خادم HTTPS صالحًا من الإعدادات أولًا."
        }
        val payloadMessages = JSONArray()
        messages.takeLast(30).forEach { payloadMessages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject().put("messages", payloadMessages).toString()
        val connection = (URL("$root/api/v1/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (appToken.isNotBlank()) setRequestProperty("X-App-Token", appToken)
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                throw IllegalStateException(parsed.optString("error", "تعذر الاتصال بالخادم (HTTP $status)."))
            }
            parsed.optString("message").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("استجابة الخادم لا تحتوي على message.")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun generateMedia(kind: String, prompt: String): String = withContext(Dispatchers.IO) {
        require(kind == "images" || kind == "videos" || kind == "projects") { "نوع الطلب غير مدعوم." }
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://")) { "أدخل عنوان خادم HTTPS صالحًا من الإعدادات أولًا." }
        val connection = (URL("$root/api/v1/$kind").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 210_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (appToken.isNotBlank()) setRequestProperty("X-App-Token", appToken)
        }
        try {
            val payload = JSONObject().put("prompt", prompt).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                throw IllegalStateException(parsed.optString("error", "تعذر إنشاء الوسائط (HTTP $status)."))
            }
            val message = parsed.optString("message").takeIf { it.isNotBlank() }
            val mediaUrl = parsed.optString("url").takeIf { it.isNotBlank() }
            when {
                message != null && mediaUrl != null -> "$message\n$mediaUrl"
                message != null -> message
                mediaUrl != null -> mediaUrl
                parsed.optString("jobId").isNotBlank() -> "تم بدء المهمة. رقمها: ${parsed.optString("jobId") }"
                else -> throw IllegalStateException("استجابة الخادم لا تحتوي على نتيجة أو رابط تنزيل.")
            }
        } finally {
            connection.disconnect()
        }
    }
}
