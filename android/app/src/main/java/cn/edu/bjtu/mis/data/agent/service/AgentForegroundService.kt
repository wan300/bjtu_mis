package cn.edu.bjtu.mis.data.agent.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var observeJob: Job? = null
    private var currentTaskId: String? = null

    private val repository
        get() = (application as BjtuMisApplication).container.agentRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: currentTaskId
                if (taskId != null) serviceScope.launch { repository.cancel(taskId) }
                runJob?.cancel()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                if (taskId.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                currentTaskId = taskId
                startForegroundCompat(buildNotification("正在启动 Agent", taskId))
                observeTask(taskId)
                if (runJob?.isActive != true) {
                    runJob = serviceScope.launch {
                        repository.runTask(taskId)
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runJob?.cancel()
        observeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeTask(taskId: String) {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            repository.observeTask(taskId).collectLatest { task ->
                val text = when (task?.status) {
                    "queued" -> "Agent 等待运行"
                    "running" -> "Agent 正在协助作业"
                    "succeeded" -> "Agent 已完成"
                    "failed" -> "Agent 失败：${task.errorMessage.orEmpty().take(40)}"
                    "canceled" -> "Agent 已取消"
                    else -> "Agent 状态更新"
                }
                NotificationManagerCompat.from(this@AgentForegroundService).notify(NOTIFICATION_ID, buildNotification(text, taskId))
            }
        }
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

    private fun buildNotification(text: String, taskId: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle("作业 Agent")
            .setContentText(text)
            .setContentIntent(openAgentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_menu_24, "取消", servicePendingIntent(ACTION_STOP, taskId, REQUEST_STOP))
            .build()

    private fun openAgentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROUTE, ModuleKeys.Agent)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, REQUEST_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun servicePendingIntent(action: String, taskId: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AgentForegroundService::class.java).setAction(action).putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_AGENT, "作业 Agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示作业 Agent 运行状态"
            },
        )
    }

    companion object {
        private const val ACTION_START = "cn.edu.bjtu.mis.agent.START"
        private const val ACTION_STOP = "cn.edu.bjtu.mis.agent.STOP"
        private const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_AGENT = "agent_running"
        private const val NOTIFICATION_ID = 2301
        private const val REQUEST_OPEN = 3301
        private const val REQUEST_STOP = 3302

        fun start(context: Context, taskId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentForegroundService::class.java).setAction(ACTION_START).putExtra(EXTRA_TASK_ID, taskId),
            )
        }

        fun stop(context: Context, taskId: String) {
            context.startService(Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TASK_ID, taskId))
        }
    }
}
