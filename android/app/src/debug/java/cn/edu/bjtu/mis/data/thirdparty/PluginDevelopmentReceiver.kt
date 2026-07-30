package cn.edu.bjtu.mis.data.thirdparty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PluginDevelopmentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        if (!pluginId.matches(Regex("^[a-z][a-z0-9_.-]{2,63}$"))) return
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        if (port !in 1024..65535) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("$pluginId.enabled", enabled)
            .putInt("$pluginId.port", port)
            .apply()
    }

    companion object {
        const val ACTION = "cn.edu.bjtu.mis.debug.PLUGIN_DEVELOPMENT"
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_PORT = "port"
        const val EXTRA_ENABLED = "enabled"
        const val PREFERENCES = "bjtu-plugin-development"
        const val DEFAULT_PORT = 5173
    }
}
