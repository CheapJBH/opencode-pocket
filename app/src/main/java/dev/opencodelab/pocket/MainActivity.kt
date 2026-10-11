package dev.opencodelab.pocket

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import dev.opencodelab.pocket.api.ChatMessage
import dev.opencodelab.pocket.api.ChatModel
import dev.opencodelab.pocket.api.ChatPart
import dev.opencodelab.pocket.api.OpenCodeApi
import dev.opencodelab.pocket.api.OpenCodeApiException
import dev.opencodelab.pocket.api.OpenCodeSession
import dev.opencodelab.pocket.runtime.AgentMonitorService
import dev.opencodelab.pocket.runtime.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Canvas = Color(0xFF101114)
private val Panel = Color(0xFF191A20)
private val PanelRaised = Color(0xFF22242B)
private val Border = Color(0xFF30323A)
private val Ink = Color(0xFFF4F5F8)
private val SoftInk = Color(0xFFA5A8B3)
private val Accent = Color(0xFFC4CEFF)
private val Success = Color(0xFF87D7B1)
private val Danger = Color(0xFFFFA6A6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1501)
        }
        setContent { OpenCodePocketScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenCodePocketScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { context.getSharedPreferences("pocket-settings", Context.MODE_PRIVATE) }
    var serverPassword by remember { mutableStateOf(prefs.getString("server_password", null)) }
    val api = remember(serverPassword) { OpenCodeApi(password = serverPassword) }
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(false) }
    var serverVersion by remember { mutableStateOf("") }
    var activePath by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("正在检查本地 OpenCode…") }
    var errorText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var activeSessionId by remember { mutableStateOf<String?>(prefs.getString("session_id", null)) }
    var draft by remember { mutableStateOf("") }
    var workdir by remember { mutableStateOf(prefs.getString("workdir", TermuxBridge.DEFAULT_WORKDIR) ?: TermuxBridge.DEFAULT_WORKDIR) }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var modelMenuLoading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ChatModel>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<ChatModel?>(null) }
    var sessions by remember { mutableStateOf<List<OpenCodeSession>>(emptyList()) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    val reloadMessages: suspend (String) -> Unit = { id ->
        val loaded = withContext(Dispatchers.IO) { api.listMessages(id) }
        messages.clear()
        messages.addAll(loaded)
    }

    val restoreSavedModel: () -> Unit = {
        val storedProvider = prefs.getString("model_provider", null)
        val storedModel = prefs.getString("model_id", null)
        selectedModel = if (storedProvider != null && storedModel != null) {
            models.firstOrNull { it.providerID == storedProvider && it.modelID == storedModel }
        } else null
    }

    val refreshConnection: suspend (Boolean) -> Unit = { showFailures ->
        try {
            val version = withContext(Dispatchers.IO) { api.health() }
            connected = true
            serverVersion = version
            activePath = withContext(Dispatchers.IO) { api.currentPath() }
            statusText = if (serverPassword.isNullOrBlank()) {
                "已连接 · 当前 Server 未启用密码保护"
            } else {
                "本地运行中 · 已启用 API 密码保护 · $activePath"
            }
            errorText = if (serverPassword.isNullOrBlank()) {
                "安全提醒：检测到无密码的本地 OpenCode Server。同一台手机上的其他应用也可能尝试连接该端口；建议停止它，并由 OpenCode Pocket 重新启动受密码保护的服务。"
            } else ""
            sessions = withContext(Dispatchers.IO) { api.listSessions() }
            models = withContext(Dispatchers.IO) { api.listModels() }
            restoreSavedModel()
            val currentId = activeSessionId
            if (currentId != null && sessions.any { it.id == currentId }) {
                reloadMessages(currentId)
            } else if (currentId != null) {
                activeSessionId = null
                prefs.edit().remove("session_id").apply()
                messages.clear()
            }
        } catch (e: Exception) {
            connected = false
            serverVersion = ""
            statusText = "尚未连接本地 OpenCode"
            if (showFailures) errorText = e.message ?: "连接本地 OpenCode 失败"
        }
    }

    LaunchedEffect(Unit) {
        refreshConnection(false)
    }

    LaunchedEffect(activeSessionId, submitting) {
        val id = activeSessionId ?: return@LaunchedEffect
        if (!submitting) return@LaunchedEffect
        var sawBusy = false
        var idleCount = 0
        var failedPolls = 0
        var ticks = 0
        while (isActive && submitting && ticks < 900) {
            delay(1_700)
            ticks++
            try {
                val status = withContext(Dispatchers.IO) { api.sessionStatus(id) }.lowercase()
                failedPolls = 0
                if (status == "busy" || status == "running" || status == "retry") {
                    sawBusy = true
                    idleCount = 0
                } else if (sawBusy) {
                    idleCount++
                    if (idleCount >= 2) submitting = false
                } else if (ticks > 18) {
                    // Fallback when the server finishes before the first status poll.
                    val currentMessages = withContext(Dispatchers.IO) { api.listMessages(id) }
                    if (currentMessages.lastOrNull { it.role == "assistant" }?.completed == true) submitting = false
                }
                reloadMessages(id)
            } catch (_: Exception) {
                failedPolls++
                statusText = "连接暂时中断；任务是否继续请检查 Termux 通知"
                if (failedPolls >= 8) {
                    submitting = false
                    errorText = "连续多次无法读取 OpenCode 状态。任务数据仍保存在 OpenCode 会话中，请检查 Termux 服务后重新连接。"
                }
            }
        }
    }

    suspend fun createNewSession(title: String): String {
        val session = withContext(Dispatchers.IO) { api.createSession(title) }
        activeSessionId = session.id
        prefs.edit().putString("session_id", session.id).apply()
        sessions = withContext(Dispatchers.IO) { api.listSessions() }
        messages.clear()
        return session.id
    }

    fun connectOrStartEngine() {
        scope.launch {
            loading = true
            errorText = ""
            try {
                val currentVersion = withContext(Dispatchers.IO) { api.health() }
                connected = true
                serverVersion = currentVersion
                activePath = withContext(Dispatchers.IO) { api.currentPath() }
                sessions = withContext(Dispatchers.IO) { api.listSessions() }
                models = withContext(Dispatchers.IO) { api.listModels() }
                restoreSavedModel()
                statusText = if (serverPassword.isNullOrBlank()) {
                    "已连接 · 当前 Server 未启用密码保护"
                } else {
                    "已连接 · API 密码保护已启用 · $activePath"
                }
                if (serverPassword.isNullOrBlank()) {
                    errorText = "安全提醒：当前 Server 没有 API 密码保护。同一台手机上的其他应用也可能尝试连接它；建议停止该进程后由 App 启动受保护的服务。"
                }
                if (activePath.isNotBlank() && workdir.trim() != activePath && workdir.trim() != "~/") {
                    errorText = (if (errorText.isNotBlank()) "$errorText\n\n" else "") + "当前服务已在 $activePath 运行；修改项目目录不会切换现有进程。请先在 Termux 停止旧服务，再重新启动。"
                }
                val savedId = activeSessionId
                if (savedId != null && sessions.any { it.id == savedId }) reloadMessages(savedId)
            } catch (_: Exception) {
                // If an old saved password no longer matches, allow explicit connection to a manually
                // started no-password server, but warn the user instead of pretending it is protected.
                if (!serverPassword.isNullOrBlank()) {
                    val openApi = OpenCodeApi()
                    try {
                        val version = withContext(Dispatchers.IO) { openApi.health() }
                        serverPassword = null
                        prefs.edit().remove("server_password").apply()
                        connected = true
                        serverVersion = version
                        activePath = withContext(Dispatchers.IO) { openApi.currentPath() }
                        sessions = withContext(Dispatchers.IO) { openApi.listSessions() }
                        models = withContext(Dispatchers.IO) { openApi.listModels() }
                        restoreSavedModel()
                        statusText = "已连接 · 当前 Server 未启用密码保护"
                        errorText = "安全提醒：当前服务没有 API 密码保护。同一台手机上的其他应用也可能尝试访问它。建议在 Termux 停止该服务，再点启动按钮由 App 启动受保护的服务。"
                        val savedId = activeSessionId
                        if (savedId != null && sessions.any { it.id == savedId }) {
                            val loaded = withContext(Dispatchers.IO) { openApi.listMessages(savedId) }
                            messages.clear(); messages.addAll(loaded)
                        }
                        loading = false
                        return@launch
                    } catch (_: Exception) {
                        // Continue with normal runtime checks and attempt to start a protected server.
                    }
                }
                if (!TermuxBridge.isInstalled(context)) {
                    errorText = "没有检测到 Termux。请安装 Termux 与 Android/Bionic 版 OpenCode 后再连接。"
                    loading = false
                    return@launch
                }
                val granted = ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    errorText = "请先在系统设置中给 OpenCode Pocket 授予“在 Termux 环境中运行命令”权限，并在 Termux 的 termux.properties 中启用 allow-external-apps。"
                    loading = false
                    return@launch
                }
                val launchPassword = serverPassword ?: (java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", ""))
                serverPassword = launchPassword
                prefs.edit().putString("server_password", launchPassword).apply()
                val protectedApi = OpenCodeApi(password = launchPassword)
                try {
                    TermuxBridge.runBackgroundCommand(
                        context,
                        "${TermuxBridge.PREFIX}/bin/termux-wake-lock",
                        workdir = workdir,
                        label = "OpenCode Pocket 保持唤醒",
                        description = "在本地 OpenCode 任务期间请求 Termux CPU 唤醒锁。"
                    )
                    TermuxBridge.runBackgroundCommand(
                        context,
                        "${TermuxBridge.PREFIX}/bin/env",
                        arrayOf(
                            "OPENCODE_SERVER_USERNAME=opencode",
                            "OPENCODE_SERVER_PASSWORD=$launchPassword",
                            "${TermuxBridge.PREFIX}/bin/opencode",
                            "serve", "--hostname", "127.0.0.1", "--port", "4096"
                        ),
                        workdir,
                        "启动受密码保护的本地 OpenCode Server",
                        "绑定 127.0.0.1:4096，使用随机 API 密码供 OpenCode Pocket 本机访问。"
                    )
                } catch (e: Exception) {
                    errorText = "通过 Termux 启动失败：${e.message ?: e.javaClass.simpleName}。请检查 RUN_COMMAND 权限和 allow-external-apps。"
                    loading = false
                    return@launch
                }
                var started = false
                for (attempt in 0 until 20) {
                    delay(900)
                    try {
                        val version = withContext(Dispatchers.IO) { protectedApi.health() }
                        connected = true
                        serverVersion = version
                        activePath = withContext(Dispatchers.IO) { protectedApi.currentPath() }
                        sessions = withContext(Dispatchers.IO) { protectedApi.listSessions() }
                        models = withContext(Dispatchers.IO) { protectedApi.listModels() }
                        restoreSavedModel()
                        statusText = "本地 OpenCode 已启动并启用密码保护 · $activePath"
                        errorText = ""
                        val savedId = activeSessionId
                        if (savedId != null && sessions.any { it.id == savedId }) {
                            val loaded = withContext(Dispatchers.IO) { protectedApi.listMessages(savedId) }
                            messages.clear(); messages.addAll(loaded)
                        }
                        started = true
                        break
                    } catch (_: Exception) {
                        // Keep polling the loopback endpoint while Termux starts the server.
                    }
                }
                if (!started) {
                    connected = false
                    errorText = "已请求 Termux 启动服务，但 18 秒内没有检测到 API。请打开 Termux 检查 opencode serve 的错误日志、RUN_COMMAND 权限和端口 4096。"
                }
            }
            loading = false
        }
    }

    fun submitPrompt() {
        val prompt = draft.trim()
        if (prompt.isBlank() || loading || submitting) return
        if (!connected) {
            errorText = "请先连接或启动本地 OpenCode。"
            return
        }
        scope.launch {
            loading = true
            errorText = ""
            var promptAccepted = false
            try {
                val sessionId = activeSessionId ?: createNewSession(prompt.take(50))
                withContext(Dispatchers.IO) { api.sendPrompt(sessionId, prompt, selectedModel) }
                promptAccepted = true
                draft = ""
                submitting = true
                statusText = "任务已提交 · Agent 正在执行"
                startForegroundService(
                    context,
                    Intent(context, AgentMonitorService::class.java)
                        .putExtra(AgentMonitorService.EXTRA_SESSION_ID, sessionId)
                        .putExtra(AgentMonitorService.EXTRA_SERVER_PASSWORD, serverPassword)
                )
                runCatching { reloadMessages(sessionId) }
                    .onFailure { statusText = "消息已提交；聊天记录正在等待同步" }
                runCatching { sessions = withContext(Dispatchers.IO) { api.listSessions() } }
            } catch (e: Exception) {
                errorText = if (promptAccepted) {
                    "消息已提交，但后续刷新失败：${e.message ?: e.javaClass.simpleName}。请重新连接并刷新会话。"
                } else {
                    e.message ?: "消息发送失败"
                }
                if (!promptAccepted) submitting = false
            } finally {
                loading = false
            }
        }
    }

    fun stopPrompt() {
        val id = activeSessionId ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.abort(id) }
                context.stopService(Intent(context, AgentMonitorService::class.java))
                submitting = false
                statusText = "已请求停止当前任务"
                reloadMessages(id)
            } catch (e: Exception) {
                errorText = e.message ?: "停止任务失败"
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Canvas,
            surface = Panel,
            surfaceVariant = PanelRaised,
            primary = Accent,
            onPrimary = Color(0xFF141722),
            onBackground = Ink,
            onSurface = Ink,
            secondary = SoftInk,
            outline = Border
        )
    ) {
        Scaffold(
            containerColor = Canvas,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Success else SoftInk))
                                Text("OpenCode", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
                            }
                            Text(
                                if (connected) activePath.ifBlank { "本地 Agent 已连接" } else "Android · 本地开发工作区",
                                color = SoftInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (connected) {
                                scope.launch { sessions = runCatching { withContext(Dispatchers.IO) { api.listSessions() } }.getOrDefault(sessions) }
                            }
                            showSessions = true
                        }) { Icon(Icons.Default.Menu, contentDescription = "会话列表", tint = Ink) }
                    },
                    actions = {
                        IconButton(onClick = { connectOrStartEngine() }, enabled = !loading) {
                            if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                            else Icon(if (connected) Icons.Default.Refresh else Icons.Default.FolderOpen, contentDescription = "连接或启动本地 OpenCode", tint = if (connected) Success else Accent)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置", tint = SoftInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
                )
            },
            bottomBar = {
                Column(
                    Modifier.fillMaxWidth().background(Canvas).navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { showModels = true; if (connected && models.isEmpty()) {
                            scope.launch { modelMenuLoading = true; models = runCatching { withContext(Dispatchers.IO) { api.listModels() } }.getOrDefault(emptyList()); modelMenuLoading = false }
                        } }, label = { Text(selectedModel?.label ?: "OpenCode 默认模型", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        AssistChip(onClick = { showSettings = true }, label = { Text("${if (submitting) "任务运行中" else "本地模式"} · 设置", fontSize = 11.sp) })
                        Spacer(Modifier.weight(1f))
                        if (submitting) {
                            IconButton(onClick = { stopPrompt() }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Stop, contentDescription = "停止任务", tint = Danger)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("描述你想完成的代码任务…", color = SoftInk, fontSize = 14.sp) },
                            maxLines = 6,
                            shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Ink,
                                unfocusedTextColor = Ink,
                                cursorColor = Accent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedPlaceholderColor = SoftInk,
                                unfocusedPlaceholderColor = SoftInk
                            )
                        )
                        IconButton(
                            onClick = { if (submitting) stopPrompt() else submitPrompt() },
                            enabled = draft.isNotBlank() || submitting,
                            modifier = Modifier.padding(bottom = 2.dp).size(42.dp).clip(CircleShape).background(if (draft.isNotBlank() || submitting) Accent else PanelRaised)
                        ) {
                            Icon(if (submitting) Icons.Default.Stop else Icons.Default.Send, contentDescription = if (submitting) "停止" else "发送", tint = if (draft.isNotBlank() || submitting) Color(0xFF141722) else SoftInk)
                        }
                    }
                    Text("本地 API：127.0.0.1:4096   ·   后台执行需允许 Termux 与本 App 后台运行", color = SoftInk.copy(alpha = 0.75f), fontSize = 10.sp)
                }
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WORKSPACE", color = SoftInk, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(if (connected) "API $serverVersion" else "未连接", color = if (connected) Success else SoftInk, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(statusText, color = SoftInk, fontSize = 11.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(9.dp))
                    if (errorText.isNotBlank()) {
                        Surface(color = Color(0xFF351D24), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6D303D))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("需要处理", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Danger)
                                    Spacer(Modifier.height(3.dp))
                                    Text(errorText, color = Ink, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                                IconButton(onClick = { errorText = "" }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Close, contentDescription = "关闭错误", tint = SoftInk) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (messages.isEmpty()) {
                    item {
                        Spacer(Modifier.height(22.dp))
                        Text("让 Agent 来处理。", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = Ink, lineHeight = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("直接描述任务。OpenCode 将在当前 Termux 工作目录中读写文件、运行工具，并把真实执行记录返回到聊天里。", color = SoftInk, fontSize = 14.sp, lineHeight = 21.sp)
                        Spacer(Modifier.height(24.dp))
                        Text("可以从这些任务开始", color = SoftInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                    }
                    items(listOf(
                        "分析当前项目结构，告诉我从哪里开始。",
                        "检查项目里的错误，先给出修复计划。",
                        "做一次移动端 UI 适配检查，并修改明显的问题。"
                    )) { suggestion ->
                        Surface(
                            color = Panel,
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth().clickable { draft = suggestion }
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(suggestion, modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
                                Icon(Icons.Default.ChevronRight, contentDescription = "填入输入框", tint = SoftInk)
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Panel.copy(alpha = 0.7f), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("第一次使用？", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("先在 Termux 安装 Android 适配版 OpenCode，再点右上角连接按钮。当前项目还没有捏造任何模型回复。", color = SoftInk, fontSize = 12.sp, lineHeight = 18.sp)
                                TextButton(onClick = { showSettings = true }, contentPadding = PaddingValues(0.dp)) { Text("打开本地运行设置 →", color = Accent) }
                            }
                        }
                    }
                } else {
                    items(messages, key = { it.id }) { message -> MessageBubble(message) }
                    if (submitting) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                                Text("OpenCode 正在执行；工具日志会随会话更新。", color = SoftInk, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        var draftWorkdir by remember(workdir) { mutableStateOf(workdir) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("本地运行设置", color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("OpenCode Server 固定监听 127.0.0.1:4096。App 启动的服务会设置随机 API 密码；如果连接到手动启动的无密码服务，会出现安全提醒。", color = SoftInk, fontSize = 12.sp, lineHeight = 17.sp)
                    OutlinedTextField(
                        value = draftWorkdir,
                        onValueChange = { draftWorkdir = it },
                        label = { Text("Termux 工作目录") },
                        placeholder = { Text("~/projects/my-app") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                    )
                    Text("此目录只会在启动新服务时使用。若服务已连接，修改目录后需要先在 Termux 终止旧的 opencode serve，再重新启动。", color = SoftInk, fontSize = 11.sp, lineHeight = 16.sp)
                    Divider(color = Border)
                    Text("后台运行", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("任务开始后 App 会使用前台服务与部分唤醒锁；启动本地服务时也会请求 Termux 的 termux-wake-lock。请同时允许 Termux 与本 App 后台运行。", color = SoftInk, fontSize = 12.sp, lineHeight = 17.sp)
                    Button(onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                        } catch (e: Exception) { errorText = "无法打开忽略电池优化页面：${e.message}" }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = Ink)) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("允许本 App 忽略电池优化")
                    }
                    Button(onClick = {
                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        catch (e: Exception) { errorText = "无法打开本 App 权限设置：${e.message}" }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = Ink)) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("打开 OpenCode Pocket 应用权限")
                    }
                    Button(onClick = {
                        try { TermuxBridge.openTermuxAppInfo(context) } catch (e: Exception) { errorText = "无法打开 Termux 设置：${e.message}" }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = Ink)) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("打开 Termux 电池与后台设置")
                    }
                    Text("还要在 Termux 中将 allow-external-apps 设为 true，并给本 App 授予“在 Termux 环境中运行命令”权限。详情见项目 README。", color = SoftInk, fontSize = 11.sp, lineHeight = 16.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    workdir = draftWorkdir.trim().ifBlank { TermuxBridge.DEFAULT_WORKDIR }
                    prefs.edit().putString("workdir", workdir).apply()
                    showSettings = false
                }) { Text("保存", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消", color = SoftInk) } },
            containerColor = Panel
        )
    }

    if (showSessions) {
        AlertDialog(
            onDismissRequest = { showSessions = false },
            title = { Text("会话", color = Ink) },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    TextButton(onClick = {
                        showSessions = false
                        if (connected) scope.launch {
                            try {
                                val id = createNewSession("新会话")
                                activeSessionId = id
                                submitting = false
                            } catch (e: Exception) { errorText = e.message ?: "创建会话失败" }
                        } else errorText = "请先连接本地 OpenCode"
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Accent)
                        Spacer(Modifier.width(8.dp)); Text("新建会话", color = Accent)
                    }
                    Divider(color = Border)
                    if (sessions.isEmpty()) {
                        Text("尚无会话，连接本地 OpenCode 后会显示真实会话列表。", color = SoftInk, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
                    } else {
                        LazyColumn {
                            items(sessions, key = { it.id }) { session ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                        activeSessionId = session.id
                                        prefs.edit().putString("session_id", session.id).apply()
                                        showSessions = false
                                        submitting = false
                                        scope.launch { runCatching { reloadMessages(session.id) }.onFailure { errorText = it.message ?: "读取会话失败" } }
                                    }.padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(session.title, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(session.id, color = SoftInk, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (session.id == activeSessionId) Icon(Icons.Default.Check, contentDescription = "当前会话", tint = Success, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSessions = false }) { Text("关闭", color = Accent) } },
            containerColor = Panel
        )
    }

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("选择模型", color = Ink) },
            text = {
                Column(Modifier.heightIn(max = 430.dp)) {
                    TextButton(onClick = {
                        selectedModel = null
                        prefs.edit().remove("model_provider").remove("model_id").apply()
                        showModels = false
                    }) { Text("使用 OpenCode 默认模型", color = Accent) }
                    Divider(color = Border)
                    if (modelMenuLoading) {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                    } else if (models.isEmpty()) {
                        Text("没有读到模型列表。请先在 Termux 中配置 OpenCode Provider，并确认服务已连接。", color = SoftInk, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
                    } else {
                        LazyColumn {
                            items(models, key = { "${it.providerID}/${it.modelID}" }) { model ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        selectedModel = model
                                        prefs.edit().putString("model_provider", model.providerID).putString("model_id", model.modelID).apply()
                                        showModels = false
                                    }.padding(vertical = 11.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(model.label, color = Ink, fontSize = 12.sp)
                                        Text("${model.providerID}/${model.modelID}", color = SoftInk, fontSize = 10.sp)
                                    }
                                    if (model.providerID == selectedModel?.providerID && model.modelID == selectedModel?.modelID) Icon(Icons.Default.Check, contentDescription = "已选择", tint = Success, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("关闭", color = Accent) } },
            containerColor = Panel
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    val bubbleColor = if (user) Color(0xFF293044) else Panel
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "你" else "OPENCODE", color = if (user) Accent else SoftInk, fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = if (user) 5.dp else 16.dp, bottomStart = if (user) 16.dp else 5.dp),
            border = if (user) null else androidx.compose.foundation.BorderStroke(1.dp, Border.copy(alpha = 0.8f)),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val text = message.displayText
                if (text.isNotBlank()) Text(text, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
                if (text.isBlank() && message.role == "assistant") Text("收到执行结果…", color = SoftInk, fontSize = 12.sp)
            }
        }
    }
}
