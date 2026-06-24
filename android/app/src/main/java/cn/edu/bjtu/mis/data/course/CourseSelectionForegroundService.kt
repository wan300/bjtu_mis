package cn.edu.bjtu.mis.data.course

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.sync.SessionKeepAliveForegroundService
import cn.edu.bjtu.mis.model.CourseSelectionReplaceRule
import cn.edu.bjtu.mis.model.CourseSelectionRunConfig
import cn.edu.bjtu.mis.model.CourseSelectionRunState
import cn.edu.bjtu.mis.model.CourseSelectionSuccessAlert
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CourseSelectionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private var lastCaptchaNotificationId: String? = null
    private val notifiedSuccessAlertIds = mutableSetOf<Long>()
    private var keepAliveHeld = false

    private val runner: CourseSelectionRunner
        get() = (application as BjtuMisApplication).container.courseSelectionRunner

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runner.stop()
                if (!runner.state.value.running) stopSelf()
            }
            ACTION_SUBMIT_CAPTCHA -> {
                val captcha = intent.getStringExtra(EXTRA_CAPTCHA).orEmpty()
                if (captcha.isNotBlank()) runner.submitCaptcha(captcha)
            }
            ACTION_CANCEL_CAPTCHA -> {
                runner.cancelCaptcha()
            }
            else -> {
                val config = intent?.toRunConfig()
                if (config == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                synchronized(notifiedSuccessAlertIds) {
                    notifiedSuccessAlertIds.clear()
                    notifiedSuccessAlertIds += runner.state.value.successAlerts.map { it.eventId }
                }
                startForegroundCompat(buildRunningNotification(runner.state.value))
                observeRunner()
                val started = runner.start(config)
                if (started || runner.state.value.running) acquireKeepAlive()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        releaseKeepAlive()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeRunner() {
        if (observerJob != null) return
        observerJob = serviceScope.launch {
            runner.state.collectLatest { state ->
                updateNotifications(state)
            }
        }
    }

    private fun updateNotifications(state: CourseSelectionRunState) {
        val notificationManager = NotificationManagerCompat.from(this)
        notifySuccessAlerts(notificationManager, state.successAlerts)
        if (!state.running) {
            releaseKeepAlive()
            notificationManager.cancel(NOTIFICATION_ID_RUNNING)
            notificationManager.cancel(NOTIFICATION_ID_CAPTCHA)
            stopForegroundCompat()
            stopSelf()
            return
        }

        acquireKeepAlive()
        notifySafely(notificationManager, NOTIFICATION_ID_RUNNING, buildRunningNotification(state))
        val challengeId = state.awaitingCaptcha?.challengeId
        if (challengeId == null) {
            lastCaptchaNotificationId = null
            notificationManager.cancel(NOTIFICATION_ID_CAPTCHA)
        } else if (challengeId != lastCaptchaNotificationId) {
            lastCaptchaNotificationId = challengeId
            notifySafely(notificationManager, NOTIFICATION_ID_CAPTCHA, buildCaptchaNotification(state))
            vibrateStrongly()
        }
    }

    private fun acquireKeepAlive() {
        if (keepAliveHeld) return
        SessionKeepAliveForegroundService.acquire(
            context = this,
            reason = SessionKeepAliveForegroundService.REASON_COURSE_SELECTION,
            token = KEEP_ALIVE_TOKEN,
        )
        keepAliveHeld = true
    }

    private fun releaseKeepAlive() {
        if (!keepAliveHeld) return
        SessionKeepAliveForegroundService.release(this, KEEP_ALIVE_TOKEN)
        keepAliveHeld = false
    }

    private fun buildRunningNotification(state: CourseSelectionRunState) =
        NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle(if (state.awaitingCaptcha != null) "抢课等待验证码" else "抢课正在后台运行")
            .setContentText(runningNotificationText(state))
            .setContentIntent(openCourseSelectionPendingIntent(REQUEST_OPEN_RUNNING))
            .setOngoing(state.running && !state.stopping)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_menu_24, "停止抢课", servicePendingIntent(ACTION_STOP, REQUEST_STOP))
            .build()

    private fun buildCaptchaNotification(state: CourseSelectionRunState) =
        NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle("抢课需要验证码")
            .setContentText(state.awaitingCaptchaCourse?.courseName ?: "请返回 BJTU MIS 输入验证码")
            .setContentIntent(openCourseSelectionPendingIntent(REQUEST_OPEN_CAPTCHA))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

    private fun buildSuccessNotification(alert: CourseSelectionSuccessAlert) =
        NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle("抢课成功")
            .setContentText(alert.courseName)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${alert.courseName}\n${alert.message}"),
            )
            .setContentIntent(openCourseSelectionPendingIntent(REQUEST_OPEN_SUCCESS))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

    private fun runningNotificationText(state: CourseSelectionRunState): String =
        when {
            state.stopping -> "正在停止，当前请求完成后退出"
            state.awaitingCaptcha != null -> "请点按通知返回应用输入验证码"
            state.doneKeys.isNotEmpty() -> "已完成 ${state.doneKeys.size} 门，继续尝试剩余课程"
            else -> "请保持网络连接，可返回应用查看日志"
        }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID_RUNNING, notification)
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

    private fun notifySuccessAlerts(
        notificationManager: NotificationManagerCompat,
        alerts: List<CourseSelectionSuccessAlert>,
    ) {
        val unseenAlerts = synchronized(notifiedSuccessAlertIds) {
            alerts
                .filter { it.eventId !in notifiedSuccessAlertIds }
                .sortedBy { it.eventId }
                .also { unseen -> notifiedSuccessAlertIds += unseen.map { it.eventId } }
        }
        unseenAlerts.forEach { alert ->
            notifySafely(notificationManager, successNotificationId(alert.eventId), buildSuccessNotification(alert))
            vibrateStrongly()
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(
        notificationManager: NotificationManagerCompat,
        notificationId: Int,
        notification: Notification,
    ) {
        if (!canPostNotifications()) return
        runCatching { notificationManager.notify(notificationId, notification) }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun successNotificationId(eventId: Long): Int =
        NOTIFICATION_ID_SUCCESS_BASE + (eventId % NOTIFICATION_ID_SUCCESS_BUCKETS).toInt()

    private fun vibrateStrongly() {
        val timings = longArrayOf(0L, 250L, 120L, 350L, 120L, 500L)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    private fun openCourseSelectionPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROUTE, ModuleKeys.CourseSelection)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, CourseSelectionForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "抢课后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示抢课前台服务运行状态"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "抢课关键提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "抢课需要验证码或成功时提醒"
            },
        )
    }

    private fun Intent.toRunConfig(): CourseSelectionRunConfig? {
        val keys = getStringArrayListExtra(EXTRA_TARGET_KEYS).orEmpty()
        val names = getStringArrayListExtra(EXTRA_TARGET_NAMES).orEmpty()
        val targets = keys.mapIndexedNotNull { index, key ->
            val name = names.getOrNull(index).orEmpty()
            if (key.isBlank()) null else CourseSelectionTarget(key, name.ifBlank { key })
        }
        val ruleIds = getStringArrayListExtra(EXTRA_REPLACE_RULE_IDS).orEmpty()
        val replaceTargetKeys = getStringArrayListExtra(EXTRA_REPLACE_TARGET_KEYS).orEmpty()
        val replaceTargetNames = getStringArrayListExtra(EXTRA_REPLACE_TARGET_NAMES).orEmpty()
        val replaceDropKeys = getStringArrayListExtra(EXTRA_REPLACE_DROP_KEYS).orEmpty()
        val replaceDropNames = getStringArrayListExtra(EXTRA_REPLACE_DROP_NAMES).orEmpty()
        val replaceRules = replaceTargetKeys.mapIndexedNotNull { index, targetKey ->
            val dropKey = replaceDropKeys.getOrNull(index).orEmpty()
            if (targetKey.isBlank() || dropKey.isBlank()) {
                null
            } else {
                val targetName = replaceTargetNames.getOrNull(index).orEmpty().ifBlank { targetKey }
                val dropName = replaceDropNames.getOrNull(index).orEmpty().ifBlank { dropKey }
                val id = ruleIds.getOrNull(index).orEmpty().ifBlank { "$targetKey->$dropKey" }
                CourseSelectionReplaceRule(
                    id = id,
                    target = CourseSelectionTarget(targetKey, targetName),
                    drop = CourseSelectionTarget(dropKey, dropName),
                )
            }
        }
        if (targets.isEmpty() && replaceRules.isEmpty()) return null
        return CourseSelectionRunConfig(
            targets = targets,
            replaceRules = replaceRules,
            retryIntervalMillis = getLongExtra(EXTRA_INTERVAL_MS, 2_000L),
            maxRounds = getIntExtra(EXTRA_MAX_ROUNDS, 100),
        )
    }

    companion object {
        private const val ACTION_START = "cn.edu.bjtu.mis.course_selection.START"
        private const val ACTION_STOP = "cn.edu.bjtu.mis.course_selection.STOP"
        private const val ACTION_SUBMIT_CAPTCHA = "cn.edu.bjtu.mis.course_selection.SUBMIT_CAPTCHA"
        private const val ACTION_CANCEL_CAPTCHA = "cn.edu.bjtu.mis.course_selection.CANCEL_CAPTCHA"

        private const val EXTRA_TARGET_KEYS = "target_keys"
        private const val EXTRA_TARGET_NAMES = "target_names"
        private const val EXTRA_REPLACE_RULE_IDS = "replace_rule_ids"
        private const val EXTRA_REPLACE_TARGET_KEYS = "replace_target_keys"
        private const val EXTRA_REPLACE_TARGET_NAMES = "replace_target_names"
        private const val EXTRA_REPLACE_DROP_KEYS = "replace_drop_keys"
        private const val EXTRA_REPLACE_DROP_NAMES = "replace_drop_names"
        private const val EXTRA_INTERVAL_MS = "interval_ms"
        private const val EXTRA_MAX_ROUNDS = "max_rounds"
        private const val EXTRA_CAPTCHA = "captcha"

        private const val CHANNEL_RUNNING = "course_selection_running"
        private const val CHANNEL_ALERTS = "course_selection_alerts"
        private const val NOTIFICATION_ID_RUNNING = 2101
        private const val NOTIFICATION_ID_CAPTCHA = 2102
        private const val NOTIFICATION_ID_SUCCESS_BASE = 2110
        private const val NOTIFICATION_ID_SUCCESS_BUCKETS = 900
        private const val REQUEST_OPEN_RUNNING = 3101
        private const val REQUEST_OPEN_CAPTCHA = 3102
        private const val REQUEST_STOP = 3103
        private const val REQUEST_OPEN_SUCCESS = 3104
        private const val KEEP_ALIVE_TOKEN = "course_selection"

        fun start(context: Context, config: CourseSelectionRunConfig) {
            val intent = Intent(context, CourseSelectionForegroundService::class.java)
                .setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_TARGET_KEYS, ArrayList(config.targets.map { it.key }))
                .putStringArrayListExtra(EXTRA_TARGET_NAMES, ArrayList(config.targets.map { it.courseName }))
                .putStringArrayListExtra(EXTRA_REPLACE_RULE_IDS, ArrayList(config.replaceRules.map { it.id }))
                .putStringArrayListExtra(EXTRA_REPLACE_TARGET_KEYS, ArrayList(config.replaceRules.map { it.target.key }))
                .putStringArrayListExtra(EXTRA_REPLACE_TARGET_NAMES, ArrayList(config.replaceRules.map { it.target.courseName }))
                .putStringArrayListExtra(EXTRA_REPLACE_DROP_KEYS, ArrayList(config.replaceRules.map { it.drop.key }))
                .putStringArrayListExtra(EXTRA_REPLACE_DROP_NAMES, ArrayList(config.replaceRules.map { it.drop.courseName }))
                .putExtra(EXTRA_INTERVAL_MS, config.retryIntervalMillis)
                .putExtra(EXTRA_MAX_ROUNDS, config.maxRounds)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CourseSelectionForegroundService::class.java).setAction(ACTION_STOP))
        }

        fun submitCaptcha(context: Context, captcha: String) {
            context.startService(
                Intent(context, CourseSelectionForegroundService::class.java)
                    .setAction(ACTION_SUBMIT_CAPTCHA)
                    .putExtra(EXTRA_CAPTCHA, captcha),
            )
        }

        fun cancelCaptcha(context: Context) {
            context.startService(Intent(context, CourseSelectionForegroundService::class.java).setAction(ACTION_CANCEL_CAPTCHA))
        }
    }
}
