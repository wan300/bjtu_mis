package cn.edu.bjtu.mis.data.sync

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.app.NotificationManagerCompat
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.thirdparty.AndroidKeystoreThirdPartyKvCipher
import kotlinx.serialization.encodeToString
import java.io.File

/** Process-wide controller shared by native tasks and the isolated plugin provider. */
object AndroidSessionKeepAlive {
    private var instance: SessionKeepAliveController? = null
    @Volatile var foreground: Boolean = false
        private set
    @Volatile var running: Boolean = false
    @Volatile var sessionState: String = "unknown"
    var onForeground: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    @Synchronized fun controller(context: Context): SessionKeepAliveController {
        instance?.let { return it }
        val app = context.applicationContext as Application
        val store = KeepAliveFileStore(File(app.filesDir, "plugin-session-keepalive"))
        return SessionKeepAliveController(
            load = store::load,
            save = store::save,
            wall = System::currentTimeMillis,
            elapsed = SystemClock::elapsedRealtime,
            bootId = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).toString(),
        ).also {
            instance = it
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private val resumed = mutableSetOf<Activity>()
                override fun onActivityResumed(activity: Activity) {
                    resumed += activity
                    foreground = true
                    onForeground?.invoke()
                }
                override fun onActivityPaused(activity: Activity) {
                    resumed -= activity
                    foreground = resumed.isNotEmpty()
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) { resumed -= activity }
            })
        }
    }

    fun requireStartAllowed(context: Context, backgroundRuntime: Boolean) {
        if (backgroundRuntime || !foreground) throw KeepAliveRejected("foreground_required")
        if (!notificationsAvailable(context)) throw KeepAliveRejected("notifications_unavailable")
    }

    fun notificationsAvailable(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("session_keep_alive")
        return channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun changed() { onChanged?.invoke() }
}

/** A durable barrier prevents recovery of stale leases after any interrupted/failed write. */
internal class KeepAliveFileStore(root: File) {
    private val file = AtomicFile(File(root, "state.bin"))
    private val barrier = File(root, "pending-write")
    private val cipher = AndroidKeystoreThirdPartyKvCipher("bjtu_mis_plugin_keepalive_key")
    private val aad = "plugin-session-keepalive-v1".toByteArray()

    fun load(): KeepAliveSnapshot {
        if (barrier.exists()) throw IllegalStateException("Keep-alive state requires recovery")
        if (!file.baseFile.exists()) return KeepAliveSnapshot()
        return AppJson.decodeFromString<KeepAliveSnapshot>(
            cipher.decrypt(file.readFully(), aad).toString(Charsets.UTF_8),
        )
    }

    fun save(value: KeepAliveSnapshot) {
        file.baseFile.parentFile!!.mkdirs()
        barrier.outputStream().use { it.write(1); it.fd.sync() }
        val stream = file.startWrite()
        try {
            stream.write(cipher.encrypt(AppJson.encodeToString(value).toByteArray(), aad))
            file.finishWrite(stream)
            check(barrier.delete()) { "Keep-alive state requires recovery" }
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
