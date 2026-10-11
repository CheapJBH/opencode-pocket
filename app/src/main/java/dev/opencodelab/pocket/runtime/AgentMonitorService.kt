package dev.opencodelab.pocket.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.opencodelab.pocket.MainActivity
import dev.opencodelab.pocket.api.OpenCodeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: String = ""
    private var api = OpenCodeApi()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        api = OpenCodeApi(password = intent?.getStringExtra(EXTRA_SERVER_PASSWORD))
        startForeground(NOTIFICATION_ID, notification("OpenCode 后台任务", "正在连接本地 Agent…", ongoing = true))
        if (sessionId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        monitorJob?.cancel()
        acquireWakeLock()
        monitorJob = serviceScope.launch {
            var sawBusy = false
            var idleSamples = 0
            var attempts = 0
            var errorCount = 0
            while (isActive && attempts < MAX_POLLS) {
                attempts++
                try {
                    // v2：会话在 active 表里 = 仍在忙
                    val busy = api.isSessionBusy(sessionId)
                    errorCount = 0
                    if (busy) {
                        sawBusy = true
                        idleSamples = 0
                        updateNotification("OpenCode 正在执行", "切换 App 或锁屏后仍会继续监测。", ongoing = true)
                    } else if (sawBusy) {
                        idleSamples++
                        if (idleSamples >= 2) {
                            updateNotification("OpenCode 任务已结束", "结果已保存在本地会话中。", ongoing = false)
                            delay(2_000)
                            stopSelf()
                            return@launch
                        }
                    } else if (attempts >= INITIAL_WAIT_POLLS) {
                        // 从未见过忙：查一次消息，有 completed 的 assistant 消息也算结束
                        val msgs = api.listMessages(sessionId, 5)
                        if (msgs.lastOrNull { it.role == "assistant" }?.completed == true) {
                            updateNotification("OpenCode 任务已结束", "结果已保存在本地会话中。", ongoing = false)
                            delay(2_000)
                            stopSelf()
                            return@launch
                        }
                        if (attempts >= INITIAL_WAIT_POLLS + 6) {
                            updateNotification("OpenCode 任务状态未知", "打开 App 刷新会话。", ongoing = false)
                            delay(2_000)
                            stopSelf()
                            return@launch
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    errorCount++
                    updateNotification("OpenCode 连接暂时中断", "本地服务不可达，正在重试…", ongoing = true)
                    if (errorCount >= 8) {
                        updateNotification("后台监测已停止", "本地服务连续 8 次不可达。请重新打开 App。", ongoing = false)
                        delay(2_000)
                        stopSelf()
                        return@launch
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
            updateNotification("后台监测已停止", "请打开 App 检查本地服务及任务状态。", ongoing = false)
            delay(2_000)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:OpenCodeAgentMonitor")
        if (wakeLock?.isHeld != true) {
            // Bounded to the maximum monitor lifetime; this is intentionally not an indefinite lock.
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OpenCode 任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示本地 OpenCode Agent 后台任务状态"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun notification(title: String, text: String, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private suspend fun updateNotification(title: String, text: String, ongoing: Boolean) = withContext(Dispatchers.Main) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(title, text, ongoing))
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SESSION_ID = "dev.opencodelab.pocket.SESSION_ID"
        const val EXTRA_SERVER_PASSWORD = "dev.opencodelab.pocket.SERVER_PASSWORD"
        private const val CHANNEL_ID = "opencode_agent_running"
        private const val NOTIFICATION_ID = 4109
        private const val POLL_INTERVAL_MS = 4_000L
        private const val INITIAL_WAIT_POLLS = 12
        private const val MAX_POLLS = 10_800 // up to ~12 hours; foreground-service rules still apply.
    }
}
