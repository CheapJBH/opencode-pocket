package dev.opencodelab.pocket

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
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
import dev.opencodelab.pocket.runtime.RuntimeManager
import dev.opencodelab.pocket.runtime.ServeHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Canvas = Color(0xFF000000)
private val Panel = Color(0xFF0E0E0E)
private val PanelRaised = Color(0xFF1C1C1C)
private val Border = Color(0xFF262626)
private val Ink = Color(0xFFEDEDED)
private val SoftInk = Color(0xFF8A8A8A)
private val Accent = Color(0xFFFFFFFF)
private val Success = Color(0xFFB5B5B5)
private val Danger = Color(0xFFE57373)

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
    var workdir by remember { mutableStateOf(prefs.getString("workdir", null) ?: RuntimeManager.defaultWorkDir(context).absolutePath) }
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var modelMenuLoading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ChatModel>>(emptyList()) }
    var selectedModel by remember { mutableStateOf<ChatModel?>(null) }
    var mode by remember { mutableStateOf(prefs.getString("agent_mode", "build") ?: "build") }
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
                "安全提醒：检测到无密码的本地 OpenCode Server。同一台手机上的其他应用也可能尝试连接该端口；建议停止它，并由 Opencode 重新启动受密码保护的服务。"
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
                // v2: 会话在 active 表里 = 仍在忙
                val busy = withContext(Dispatchers.IO) { api.isSessionBusy(id) }
                failedPolls = 0
                if (busy) {
                    sawBusy = true
                    idleCount = 0
                } else if (sawBusy) {
                    idleCount++
                    if (idleCount >= 2) submitting = false
                } else if (ticks > 18) {
                    // Fallback：server 可能在首次轮询前已完成，看消息是否 complete
                    val currentMessages = withContext(Dispatchers.IO) { api.listMessages(id) }
                    if (currentMessages.lastOrNull { it.role == "assistant" }?.completed == true) submitting = false
                }
                reloadMessages(id)
            } catch (_: Exception) {
                failedPolls++
                statusText = "连接暂时中断；任务是否继续请稍后刷新查看"
                if (failedPolls >= 8) {
                    submitting = false
                    errorText = "连续多次无法读取 OpenCode 状态。任务数据仍保存在会话中，请重新连接后刷新。"
                }
            }
        }
    }

    suspend fun createNewSession(title: String, targetApi: OpenCodeApi = api): String {
        val session = withContext(Dispatchers.IO) { targetApi.createSession(title, selectedModel, agent = mode) }
        activeSessionId = session.id
        prefs.edit().putString("session_id", session.id).apply()
        sessions = withContext(Dispatchers.IO) { api.listSessions() }
        messages.clear()
        return session.id
    }

    /** 打开即用：没有可用会话就自动建一个，失败不抛（只打状态）。 */
    suspend fun ensureSession(targetApi: OpenCodeApi = api) {
        val savedId = activeSessionId
        if (savedId != null && sessions.any { it.id == savedId }) return
        try {
            createNewSession("新会话", targetApi)
            statusText = "就绪 · 已自动创建新会话，直接输入开聊"
        } catch (_: Exception) {
            statusText = "已连接 · 会话列表为空，发消息时会自动创建"
        }
    }

    fun connectOrStartEngine() {
        scope.launch {
            loading = true
            errorText = ""
            try {
                // 1. 先试已有服务（App 上次启动的 serve 可能还在）
                try {
                    val currentVersion = withContext(Dispatchers.IO) { api.health() }
                    connected = true
                    serverVersion = currentVersion
                    activePath = withContext(Dispatchers.IO) { api.currentPath() }
                    sessions = withContext(Dispatchers.IO) { api.listSessions() }
                    models = withContext(Dispatchers.IO) { api.listModels() }
                    restoreSavedModel()
                    statusText = "已连接 · $activePath"
                    val savedId = activeSessionId
                    if (savedId != null && sessions.any { it.id == savedId }) {
                        reloadMessages(savedId)
                    } else {
                        ensureSession()
                    }
                    return@launch
                } catch (_: Exception) {
                    // 无服务，继续走启动流程
                }
                // 2. 检查/安装内置运行时
                if (!RuntimeManager.isInstalled(context)) {
                    statusText = "首次启动，正在准备内置运行时…"
                    try {
                        withContext(Dispatchers.IO) {
                            RuntimeManager.install(context) { _, stage ->
                                statusText = stage
                            }
                        }
                    } catch (e: Exception) {
                        errorText = "运行时准备失败：${e.message}。请检查网络后重试。"
                        return@launch
                    }
                }
                // 3. 检查模型配置
                if (!RuntimeManager.hasConfig(context)) {
                    errorText = "还没有配置模型。请在设置里填写 OpenRouter API Key。"
                    showSettings = true
                    return@launch
                }
                // 4. 启动 serve
                statusText = "正在启动本地 OpenCode…"
                val launchPassword = serverPassword
                    ?: RuntimeManager.newPassword().also {
                        serverPassword = it
                        prefs.edit().putString("server_password", it).apply()
                    }
                val protectedApi = OpenCodeApi(password = launchPassword)
                try {
                    val workDir = java.io.File(workdir).apply { mkdirs() }
                    val proc = withContext(Dispatchers.IO) {
                        RuntimeManager.startServe(context, workDir, launchPassword)
                    }
                    ServeHolder.process = proc
                } catch (e: Exception) {
                    errorText = "启动失败：${e.message ?: e.javaClass.simpleName}"
                    return@launch
                }
                // 5. 轮询等 serve 就绪
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
                        statusText = "本地 OpenCode 已启动 · $activePath"
                        errorText = ""
                        val savedId = activeSessionId
                        if (savedId != null && sessions.any { it.id == savedId }) {
                            val loaded = withContext(Dispatchers.IO) { protectedApi.listMessages(savedId) }
                            messages.clear(); messages.addAll(loaded)
                        } else {
                            ensureSession(protectedApi)
                        }
                        started = true
                        break
                    } catch (_: Exception) {
                        // 等待 serve 启动
                    }
                }
                if (!started) {
                    connected = false
                    errorText = "服务 18 秒内未就绪。请重试，或检查存储空间是否充足。"
                }
            } finally {
                loading = false
            }
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
                withContext(Dispatchers.IO) { api.sendPrompt(sessionId, prompt) }
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

    LaunchedEffect(Unit) {
        connectOrStartEngine()
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
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        ModalNavigationDrawer(
            drawerState = drawerState,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Canvas) {
                Column(
                    Modifier.fillMaxHeight().width(280.dp).background(Canvas).padding(horizontal = 14.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("opencode", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { drawerState.close() } }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "关闭侧栏", tint = SoftInk, modifier = Modifier.size(18.dp))
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            drawerState.close()
                            if (connected) {
                                try {
                                    val id = createNewSession("新会话")
                                    activeSessionId = id
                                    submitting = false
                                } catch (e: Exception) { errorText = e.message ?: "创建会话失败" }
                            } else errorText = "请先连接本地 OpenCode"
                        }
                    }, contentPadding = PaddingValues(vertical = 8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp)); Text("+ 新会话", color = Ink, fontSize = 13.sp)
                    }
                    Divider(color = Border)
                    Text("会话 · ${sessions.size}", color = SoftInk, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(vertical = 6.dp))
                    if (sessions.isEmpty()) {
                        Text("尚无会话。", color = SoftInk, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(sessions, key = { it.id }) { session ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        activeSessionId = session.id
                                        prefs.edit().putString("session_id", session.id).apply()
                                        submitting = false
                                        scope.launch {
                                            drawerState.close()
                                            runCatching { reloadMessages(session.id) }.onFailure { errorText = it.message ?: "读取会话失败" }
                                        }
                                    }.padding(horizontal = 6.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(session.title, color = if (session.id == activeSessionId) Ink else SoftInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(session.id.take(16) + "…", color = SoftInk.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (session.id == activeSessionId) Box(Modifier.size(6.dp).background(Ink))
                                }
                            }
                        }
                    }
                }
                }
            }
        ) {
        Scaffold(
            containerColor = Canvas,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Success else SoftInk))
                                Text("opencode", fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Ink)
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
                            scope.launch { drawerState.open() }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showModels = true; if (connected && models.isEmpty()) {
                            scope.launch { modelMenuLoading = true; models = runCatching { withContext(Dispatchers.IO) { api.listModels() } }.getOrDefault(emptyList()); modelMenuLoading = false }
                        } }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                            Text(selectedModel?.label ?: "默认模型", color = SoftInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("·", color = Border, fontSize = 11.sp)
                        TextButton(onClick = { mode = "build"; prefs.edit().putString("agent_mode", "build").apply() }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                            Text("Build", color = if (mode == "build") Ink else SoftInk, fontSize = 11.sp, fontWeight = if (mode == "build") FontWeight.SemiBold else FontWeight.Normal)
                        }
                        TextButton(onClick = { mode = "plan"; prefs.edit().putString("agent_mode", "plan").apply() }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                            Text("Plan", color = if (mode == "plan") Ink else SoftInk, fontSize = 11.sp, fontWeight = if (mode == "plan") FontWeight.SemiBold else FontWeight.Normal)
                        }
                        Spacer(Modifier.weight(1f))
                        if (submitting) {
                            IconButton(onClick = { stopPrompt() }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Stop, contentDescription = "停止任务", tint = Danger)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Panel).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("描述你想完成的代码任务…", color = SoftInk, fontSize = 14.sp) },
                            maxLines = 6,
                            shape = RoundedCornerShape(4.dp),
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
                            modifier = Modifier.padding(bottom = 2.dp).size(42.dp).clip(RoundedCornerShape(4.dp)).background(if (draft.isNotBlank() || submitting) Accent else PanelRaised)
                        ) {
                            Icon(if (submitting) Icons.Default.Stop else Icons.Default.Send, contentDescription = if (submitting) "停止" else "发送", tint = if (draft.isNotBlank() || submitting) Color(0xFF000000) else SoftInk)
                        }
                    }
                    Text("本地 API：127.0.0.1:4096   ·   内置运行时，无需 Termux", color = SoftInk.copy(alpha = 0.75f), fontSize = 10.sp)
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
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(errorText, color = Danger, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { errorText = "" }, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.Close, contentDescription = "关闭错误", tint = SoftInk) }
                        }
                        Divider(color = Border)
                        Spacer(Modifier.height(2.dp))
                    }
                }

                if (messages.isEmpty()) {
                    item {
                        Spacer(Modifier.height(56.dp))
                        Text(if (connected) "输入任务开始" else "正在连接…", color = SoftInk, fontSize = 13.sp)
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
    }

    if (showSettings) {
        var draftWorkdir by remember(workdir) { mutableStateOf(workdir) }
        var draftKey by remember { mutableStateOf("") }
        var keySaved by remember { mutableStateOf(RuntimeManager.hasConfig(context)) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("本地运行设置", color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("内置 OpenCode ${RuntimeManager.RUNTIME_VERSION}，运行时已打进安装包，无需下载。Server 只监听 127.0.0.1:4096，随机密码保护。", color = SoftInk, fontSize = 12.sp, lineHeight = 17.sp)
                    OutlinedTextField(
                        value = draftWorkdir,
                        onValueChange = { draftWorkdir = it },
                        label = { Text("工作目录") },
                        placeholder = { Text("默认 App 私有目录") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                    )
                    Text("修改目录后需重启服务生效（点右上角刷新按钮）。", color = SoftInk, fontSize = 11.sp, lineHeight = 16.sp)
                    Divider(color = Border)
                    Text("模型配置 · OpenRouter", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (keySaved) {
                        Text("已保存 OpenRouter Key（默认 deepseek-chat）。如需更换请重新填写。", color = Success, fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = draftKey,
                        onValueChange = { draftKey = it },
                        label = { Text("OpenRouter API Key") },
                        placeholder = { Text("sk-or-v1-…") },
                        singleLine = true
                    )
                    Button(onClick = {
                        val k = draftKey.trim()
                        if (k.isBlank()) { errorText = "Key 不能为空" }
                        else {
                            try {
                                RuntimeManager.writeConfig(context, k)
                                keySaved = true
                                draftKey = ""
                                errorText = ""
                                statusText = "模型已配置，正在启动…"
                                showSettings = false
                                connectOrStartEngine()
                            } catch (e: Exception) { errorText = "保存失败：${e.message}" }
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PanelRaised, contentColor = Ink)) {
                        Text("保存 Key")
                    }
                    Divider(color = Border)
                    Text("后台运行", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("任务开始后 App 会使用前台服务与部分唤醒锁保持运行。请允许本 App 后台运行并忽略电池优化。", color = SoftInk, fontSize = 12.sp, lineHeight = 17.sp)
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
                        Text("打开 Opencode 应用权限")
                    }

                }
            },
            confirmButton = {
                TextButton(onClick = {
                    workdir = draftWorkdir.trim().ifBlank { RuntimeManager.defaultWorkDir(context).absolutePath }
                    prefs.edit().putString("workdir", workdir).apply()
                    showSettings = false
                }) { Text("保存", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消", color = SoftInk) } },
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
                        Text("没有读到模型列表。请确认服务已连接（模型来自内置运行时）。", color = SoftInk, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
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
    val bubbleColor = if (user) PanelRaised else Color.Transparent
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "你" else "Opencode", color = if (user) Accent else SoftInk, fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = if (user) 5.dp else 16.dp, bottomStart = if (user) 16.dp else 5.dp),
            border = null,
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(if (user) Modifier.padding(horizontal = 14.dp, vertical = 12.dp) else Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val text = message.displayText
                if (text.isNotBlank()) Text(text, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
                if (text.isBlank() && message.role == "assistant") Text("收到执行结果…", color = SoftInk, fontSize = 12.sp)
            }
        }
    }
}
