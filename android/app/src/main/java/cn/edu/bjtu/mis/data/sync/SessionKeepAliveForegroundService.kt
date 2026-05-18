package cn.edu.bjtu.mis.data.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.model.AutoLoginStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class SessionKeepAliveForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var keepAliveJob: Job? = null
    private var startedAtMillis = 0L

    private val sessionRepository
        get() = (application as BjtuMisApplication).container.sessionRepository

    override fun onCreate() {
        super.onCreate()
        serviceCreated.set(true)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            leaseRegistry.clear()
            stopKeepAlive()
            stopSelf()
            return START_NOT_STICKY
        }

        return syncKeepAliveState(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        leaseRegistry.clear()
        stopKeepAlive()
        stopSelf(startId)
    }

    override fun onDestroy() {
        keepAliveJob?.cancel()
        serviceScope.cancel()
        serviceCreated.set(false)
        super.onDestroy()
    }

    private fun syncKeepAliveState(startId: Int? = null): Int {
        if (!leaseRegistry.isActive()) {
            stopKeepAlive()
            if (startId == null) {
                stopSelf()
            } else {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (startedAtMillis == 0L) startedAtMillis = SystemClock.elapsedRealtime()
        startForegroundCompat(buildNotification("${activeReasonText()}，正在维持 MIS 后台连接"))
        ensureKeepAliveLoop()
        return START_STICKY
    }

    private fun ensureKeepAliveLoop() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = serviceScope.launch {
            while (isActive) {
                if (!leaseRegistry.isActive()) {
                    stopSelf()
                    break
                }

                if (SystemClock.elapsedRealtime() - startedAtMillis >= MAX_FOREGROUND_RUNTIME_MS) {
                    leaseRegistry.clear()
                    updateNotification("保活已达到前台服务时长上限")
                    stopSelf()
                    break
                }

                var shouldStop = false
                val message = runCatching { sessionRepository.recoverSession() }
                    .fold(
                        onSuccess = { result ->
                            when {
                                result.status == AutoLoginStatus.Ready && result.attempts > 0 ->
                                    "MIS 已自动重新登录，最近保活 ${formatNow()}"
                                result.status == AutoLoginStatus.Ready ->
                                    "MIS 会话可用，最近保活 ${formatNow()}"
                                result.attempts == 0 -> {
                                    shouldStop = true
                                    "未找到可用登录态"
                                }
                                else -> "自动重新登录失败：${result.message ?: "未知错误"}"
                            }
                        },
                        onFailure = { error ->
                            "MIS 连接校验失败：${error.message ?: "未知错误"}"
                        },
                    )
                updateNotification(message)
                if (shouldStop) {
                    leaseRegistry.clear()
                    stopSelf()
                    break
                }
                delay(KEEP_ALIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        stopForegroundCompat()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle("BJTU MIS 后台连接")
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_menu_24, "停止保活", servicePendingIntent(ACTION_STOP, REQUEST_STOP))
            .build()

    private fun activeReasonText(): String {
        val reasons = leaseRegistry.snapshot().map { it.reason }.distinct()
        val hasAgent = REASON_AGENT in reasons
        val hasCourseSelection = REASON_COURSE_SELECTION in reasons
        return when {
            hasAgent && hasCourseSelection -> "Agent 和抢课进行中"
            hasAgent -> "Agent 执行中"
            hasCourseSelection -> "抢课进行中"
            reasons.isNotEmpty() -> "任务进行中"
            else -> "按需任务进行中"
        }
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, SessionKeepAliveForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP_ALIVE, "MIS 后台连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "维持 BJTU MIS 会话并定期校验连接"
            },
        )
    }

    private fun formatNow(): String =
        TIME_FORMATTER.format(Instant.now())

    companion object {
        private const val ACTION_SYNC = "cn.edu.bjtu.mis.session_keep_alive.SYNC"
        private const val ACTION_STOP = "cn.edu.bjtu.mis.session_keep_alive.STOP"

        const val REASON_AGENT = "agent"
        const val REASON_COURSE_SELECTION = "course_selection"

        private const val CHANNEL_KEEP_ALIVE = "session_keep_alive"
        private const val NOTIFICATION_ID = 2201
        private const val REQUEST_OPEN_APP = 3201
        private const val REQUEST_STOP = 3202

        private const val KEEP_ALIVE_INTERVAL_MS = 20L * 60L * 1000L
        private const val MAX_FOREGROUND_RUNTIME_MS = 5L * 60L * 60L * 1000L + 30L * 60L * 1000L

        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

        private val leaseRegistry = KeepAliveLeaseRegistry()
        private val serviceCreated = AtomicBoolean(false)

        fun acquire(context: Context, reason: String = REASON_AGENT, token: String) {
            val normalizedToken = token.trim().takeIf { it.isNotBlank() } ?: return
            val normalizedReason = reason.trim().takeIf { it.isNotBlank() } ?: REASON_AGENT
            leaseRegistry.acquire(normalizedToken, normalizedReason)
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, SessionKeepAliveForegroundService::class.java).setAction(ACTION_SYNC),
            )
        }

        fun release(context: Context, token: String) {
            val normalizedToken = token.trim().takeIf { it.isNotBlank() } ?: return
            if (!leaseRegistry.release(normalizedToken)) return

            val appContext = context.applicationContext
            if (leaseRegistry.isActive()) {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, SessionKeepAliveForegroundService::class.java).setAction(ACTION_SYNC),
                )
            } else if (serviceCreated.get()) {
                appContext.startService(Intent(appContext, SessionKeepAliveForegroundService::class.java).setAction(ACTION_SYNC))
            }
        }

        fun stop(context: Context) {
            leaseRegistry.clear()
            if (serviceCreated.get()) {
                context.applicationContext.startService(
                    Intent(context.applicationContext, SessionKeepAliveForegroundService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
