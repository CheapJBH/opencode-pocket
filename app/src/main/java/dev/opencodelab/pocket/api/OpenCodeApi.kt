package dev.opencodelab.pocket.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Base64

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

data class OpenCodeSession(
    val id: String,
    val title: String,
    val updatedAt: Long = 0L
)

data class ChatModel(
    val providerID: String,
    val modelID: String,
    val label: String
) {
    override fun toString(): String = label
}

data class ChatPart(
    val type: String,
    val text: String = "",
    val tool: String = "",
    val toolStatus: String = ""
)

data class ChatMessage(
    val id: String,
    val role: String,
    val parts: List<ChatPart>,
    val completed: Boolean
) {
    val displayText: String
        get() = parts.mapNotNull { part ->
            when (part.type) {
                "text" -> part.text.takeIf { it.isNotBlank() }
                "tool" -> {
                    val label = part.tool.ifBlank { "tool" }
                    val detail = when (part.toolStatus.lowercase()) {
                        "running", "pending" -> "执行中"
                        "completed" -> "已完成"
                        "error" -> "失败"
                        else -> part.toolStatus
                    }
                    "⌁ $label · $detail"
                }
                "file" -> part.text.takeIf { it.isNotBlank() }?.let { "文件：$it" }
                else -> null
            }
        }.joinToString("\n\n")
}

/** A deliberately small client for OpenCode's documented local HTTP API. */
class OpenCodeApi(baseUrl: String = DEFAULT_BASE_URL, private val password: String? = null) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:4096"
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = 15_000
    ): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_500
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (!password.isNullOrBlank()) {
                val credentials = Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $credentials")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("message") }.getOrNull().orEmpty()
                throw OpenCodeApiException("OpenCode API 返回 HTTP $code${if (detail.isNotBlank()) "：$detail" else if (response.isNotBlank()) "：$response" else ""}")
            }
            return response
        } catch (e: OpenCodeApiException) {
            throw e
        } catch (e: Exception) {
            throw OpenCodeApiException("无法连接 $baseUrl。请先启动 Termux 中的 opencode serve。原因：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun health(): String {
        val json = JSONObject(request("GET", "/global/health", timeoutMs = 5_000))
        if (!json.optBoolean("healthy", false)) throw OpenCodeApiException("OpenCode 服务未报告 healthy=true")
        return json.optString("version", "版本未知")
    }

    suspend fun currentPath(): String {
        val json = JSONObject(request("GET", "/path"))
        return json.optString("directory", json.optString("path", "未知目录"))
    }

    suspend fun listSessions(): List<OpenCodeSession> {
        val array = JSONArray(request("GET", "/session"))
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val time = item.optJSONObject("time")
                add(OpenCodeSession(id, item.optString("title").ifBlank { "未命名会话" }, time?.optLong("updated", 0L) ?: 0L))
            }
        }
    }

    suspend fun createSession(title: String): OpenCodeSession {
        val body = JSONObject().put("title", title.take(80).ifBlank { "新会话" })
        val item = JSONObject(request("POST", "/session", body))
        return OpenCodeSession(item.getString("id"), item.optString("title", "新会话"))
    }

    suspend fun listModels(): List<ChatModel> {
        val root = JSONObject(request("GET", "/config/providers"))
        val providers = root.optJSONArray("providers") ?: JSONArray()
        val defaultMap = root.optJSONObject("default") ?: JSONObject()
        val result = mutableListOf<ChatModel>()
        for (i in 0 until providers.length()) {
            val provider = providers.optJSONObject(i) ?: continue
            val providerId = provider.optString("id", provider.optString("providerID"))
            if (providerId.isBlank()) continue
            val models = provider.optJSONObject("models") ?: continue
            val defaultModel = defaultMap.optString(providerId)
            val keys = models.keys()
            while (keys.hasNext()) {
                val modelId = keys.next()
                val model = models.optJSONObject(modelId) ?: JSONObject()
                val name = model.optString("name").ifBlank { modelId }
                val suffix = if (modelId == defaultModel) " · 默认" else ""
                result += ChatModel(providerId, modelId, "$name ($providerId)$suffix")
            }
        }
        return result.sortedWith(compareBy<ChatModel> { !it.label.contains("默认") }.thenBy { it.label.lowercase() })
    }

    suspend fun listMessages(sessionId: String, limit: Int = 80): List<ChatMessage> {
        val array = JSONArray(request("GET", "/session/${encodePath(sessionId)}/message?limit=$limit"))
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val info = item.optJSONObject("info") ?: continue
                val role = info.optString("role")
                if (role != "user" && role != "assistant") continue
                val id = info.optString("id", "$role-$i")
                val partsJson = item.optJSONArray("parts") ?: JSONArray()
                val parts = buildList {
                    for (j in 0 until partsJson.length()) {
                        val part = partsJson.optJSONObject(j) ?: continue
                        val type = part.optString("type")
                        if (type == "text") {
                            add(ChatPart(type, text = part.optString("text")))
                        } else if (type == "tool") {
                            val state = part.optJSONObject("state")
                            add(ChatPart(type, tool = part.optString("tool", "tool"), toolStatus = state?.optString("status").orEmpty()))
                        } else if (type == "file") {
                            add(ChatPart(type, text = part.optString("filename", part.optString("url"))))
                        }
                    }
                }
                val time = info.optJSONObject("time")
                val completed = time?.has("completed") == true && !time.isNull("completed")
                add(ChatMessage(id, role, parts, completed))
            }
        }
    }

    suspend fun sendPrompt(sessionId: String, prompt: String, model: ChatModel?) {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject().put("parts", parts)
        if (model != null) body.put("model", JSONObject().put("providerID", model.providerID).put("modelID", model.modelID))
        request("POST", "/session/${encodePath(sessionId)}/prompt_async", body, timeoutMs = 10_000)
    }

    suspend fun abort(sessionId: String) {
        request("POST", "/session/${encodePath(sessionId)}/abort", JSONObject(), timeoutMs = 5_000)
    }

    suspend fun sessionStatus(sessionId: String): String {
        val root = JSONObject(request("GET", "/session/status", timeoutMs = 5_000))
        val status = root.optJSONObject(sessionId) ?: return "unknown"
        return status.optString("type", status.optString("status", "unknown"))
    }

    private fun encodePath(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val n = byte.toInt() and 0xff
        val c = n.toChar()
        if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.') c.toString() else "%" + "%02X".format(n)
    }
}

class OpenCodeApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
