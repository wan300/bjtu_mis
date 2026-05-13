package cn.edu.bjtu.mis.data.homework

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.model.ModuleKeys

class HomeworkReminderNotifier(
    context: Context,
) : HomeworkReminderNotificationSender {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun send(content: HomeworkReminderContent): Boolean {
        if (!canNotify()) return false
        createNotificationChannel()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_HOMEWORK_REMINDERS)
            .setSmallIcon(R.drawable.ic_menu_24)
            .setContentTitle(content.title)
            .setContentText(content.contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setContentIntent(openHomeworkPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(content.candidates.size)
            .build()

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_HOMEWORK_REMINDER, notification)
            true
        }.getOrDefault(false)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    private fun openHomeworkPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROUTE, ModuleKeys.Homework)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN_HOMEWORK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HOMEWORK_REMINDERS,
                "作业提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "作业截止时间临近时提醒"
            },
        )
    }

    private companion object {
        const val CHANNEL_HOMEWORK_REMINDERS = "homework_reminders"
        const val NOTIFICATION_ID_HOMEWORK_REMINDER = 2301
        const val REQUEST_OPEN_HOMEWORK = 3301
    }
}
