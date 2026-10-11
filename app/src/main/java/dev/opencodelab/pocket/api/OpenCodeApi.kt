package dev.opencodelab.pocket.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

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
                "error" -> "❌ ${part.text}".takeIf { part.text.isNotBlank() }
                else -> null
            }
        }.joinToString("\n\n")
}

/**
 * OpenCode v2 Server API 客户端。
 * 路径与格式按 v2.0.22 实测 + openapi.json (docs/openapi-v2.json) 编写。
 * 所有返回统一包 {data: ...}，注意解包。
 */
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
            throw OpenCodeApiException("无法连接 $baseUrl。请先启动内置运行时或 Termux 中的 opencode serve。原因：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

    /** 健康检查：v2 无 /global/health，用 /api/info 探测。 */
    suspend fun health(): String {
        val json = JSONObject(request("GET", "/api/info", timeoutMs = 5_000))
        return json.optString("version", "v2")
    }

    /** 当前工作目录：v2 用 /api/project 的 canonical。 */
    suspend fun currentPath(): String {
        val json = JSONObject(request("GET", "/api/project"))
        val arr = json.optJSONArray("data") ?: return "未知目录"
        val first = arr.optJSONObject(0) ?: return "未知目录"
        return first.optString("canonical", "未知目录")
    }

    suspend fun listSessions(): List<OpenCodeSession> {
        val json = JSONObject(request("GET", "/api/session"))
        val array = json.optJSONArray("data") ?: JSONArray()
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

    suspend fun createSession(title: String, model: ChatModel? = null): OpenCodeSession {
        val body = JSONObject().put("title", title.take(80).ifBlank { "新会话" })
        if (model != null) {
            body.put("model", JSONObject()
                .put("id", model.modelID)
                .put("providerID", model.providerID))
        }
        val json = JSONObject(request("POST", "/api/session", body))
        val item = json.optJSONObject("data") ?: json
        return OpenCodeSession(item.getString("id"), item.optString("title", "新会话"))
    }

    /** 切换会话模型。 */
    suspend fun setModel(sessionId: String, model: ChatModel) {
        val body = JSONObject().put("model", JSONObject()
            .put("id", model.modelID)
            .put("providerID", model.providerID))
        request("POST", "/api/session/${encodePath(sessionId)}/model", body)
    }

    /** v2 独立 models 端点：GET /api/model。 */
    suspend fun listModels(): List<ChatModel> {
        val json = JSONObject(request("GET", "/api/model"))
        val array = json.optJSONArray("data") ?: JSONArray()
        val result = mutableListOf<ChatModel>()
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            val providerID = m.optString("providerID")
            val modelID = m.optString("modelID")
            if (providerID.isBlank() || modelID.isBlank()) continue
            val name = m.optString("name").ifBlank { modelID }
            result += ChatModel(providerID, modelID, "$name ($providerID)")
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /** v2 消息格式：user{type,text} / assistant{type,content[],finish,error,time.completed} / idle{type,outcome} */
    suspend fun listMessages(sessionId: String, limit: Int = 80): List<ChatMessage> {
        val json = JSONObject(request("GET", "/api/session/${encodePath(sessionId)}/message?limit=$limit"))
        val array = json.optJSONArray("data") ?: JSONArray()
        return buildList {
            // v2 按新到旧返回，反转为旧到新
            for (i in array.length() - 1 downTo 0) {
                val item = array.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "user" -> {
                        val text = item.optString("text")
                        if (text.isNotBlank()) {
                            add(ChatMessage(
                                id = item.optString("id", "user-$i"),
                                role = "user",
                                parts = listOf(ChatPart("text", text = text)),
                                completed = true
                            ))
                        }
                    }
                    "assistant" -> {
                        val id = item.optString("id", "asst-$i")
                        val time = item.optJSONObject("time")
                        val completed = time != null && !time.isNull("completed")
                        val parts = parseAssistantContent(item.optJSONArray("content"))
                        val err = item.optJSONObject("error")
                        val allParts = if (err != null) {
                            parts + ChatPart("error", text = err.optString("message").ifBlank { "执行出错" })
                        } else parts
                        if (allParts.isNotEmpty()) {
                            add(ChatMessage(id, "assistant", allParts, completed))
                        }
                    }
                    "idle" -> {
                        // idle 只标记轮次结束，不显示为消息
                    }
                }
            }
        }
    }

    private fun parseAssistantContent(content: JSONArray?): List<ChatPart> {
        if (content == null) return emptyList()
        return buildList {
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (part.optString("type")) {
                    "text" -> {
                        val text = part.optString("text")
                        if (text.isNotBlank()) add(ChatPart("text", text = text))
                    }
                    "tool" -> {
                        val state = part.optString("state", part.optJSONObject("state")?.optString("status").orEmpty())
                        add(ChatPart("tool", tool = part.optString("tool", "tool"), toolStatus = state))
                    }
                    "file" -> {
                        val name = part.optString("filename", part.optString("path"))
                        if (name.isNotBlank()) add(ChatPart("text", text = "文件：$name"))
                    }
                    "todo" -> {
                        // 待办列表，简化显示
                        add(ChatPart("text", text = "待办已更新"))
                    }
                }
            }
        }
    }

    /** v2 发消息：POST /api/session/{id}/prompt {text}，异步投递 inbox。 */
    suspend fun sendPrompt(sessionId: String, text: String) {
        val body = JSONObject().put("text", text)
        request("POST", "/api/session/${encodePath(sessionId)}/prompt", body, timeoutMs = 10_000)
    }

    /** v2 中止：POST /api/session/{id}/interrupt。 */
    suspend fun abort(sessionId: String) {
        request("POST", "/api/session/${encodePath(sessionId)}/interrupt", JSONObject(), timeoutMs = 5_000)
    }

    /**
     * v2 轮询：GET /api/session/active 返回 {data: {ses_xxx: {...}}}。
     * 正在运行的会话会出现在 map 里；任务完成後消失。
     * 返回 true = 会话仍在忙。
     */
    suspend fun isSessionBusy(sessionId: String): Boolean {
        val json = JSONObject(request("GET", "/api/session/active", timeoutMs = 5_000))
        val data = json.optJSONObject("data") ?: return false
        return data.has(sessionId) && !data.isNull(sessionId)
    }

    private fun encodePath(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val n = byte.toInt() and 0xff
        val c = n.toChar()
        if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.') c.toString() else "%" + "%02X".format(n)
    }
}

class OpenCodeApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
