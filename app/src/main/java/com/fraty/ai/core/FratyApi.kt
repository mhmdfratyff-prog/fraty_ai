package com.fraty.ai.core

import com.fraty.ai.history.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    suspend fun buildApp(prompt: String): String = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://")) { "أدخل عنوان خادم HTTPS صالحًا من الإعدادات أولًا." }
        val start = (URL("$root/api/v1/builds").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (appToken.isNotBlank()) setRequestProperty("X-App-Token", appToken)
        }
        val jobId: String
        try {
            start.outputStream.use { it.write(JSONObject().put("prompt", prompt).toString().toByteArray(Charsets.UTF_8)) }
            val status = start.responseCode
            val stream = if (status in 200..299) start.inputStream else start.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw IllegalStateException(parsed.optString("error", "تعذر بدء بناء التطبيق (HTTP $status)."))
            jobId = parsed.optString("jobId").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("لم يُرجع الخادم معرّف مهمة البناء.")
        } finally { start.disconnect() }

        repeat(180) {
            delay(5_000)
            val poll = (URL("$root/api/v1/builds/$jobId").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                if (appToken.isNotBlank()) setRequestProperty("X-App-Token", appToken)
            }
            try {
                val status = poll.responseCode
                val stream = if (status in 200..299) poll.inputStream else poll.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val parsed = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                if (status !in 200..299) throw IllegalStateException(parsed.optString("error", "تعذر متابعة البناء (HTTP $status)."))
                when (parsed.optString("state")) {
                    "completed" -> {
                        val message = parsed.optString("message", "اكتمل بناء APK.")
                        val url = parsed.optString("url")
                        if (url.isBlank()) throw IllegalStateException("اكتمل البناء لكن رابط APK غير موجود.")
                        return@withContext "$message\n$url"
                    }
                    "failed" -> throw IllegalStateException(parsed.optString("error", "تعذر بناء APK."))
                }
            } finally { poll.disconnect() }
        }
        throw IllegalStateException("ما زال البناء جاريًا. أعد المحاولة؛ قد يكون الخادم أو عامل البناء قد توقف.")
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
