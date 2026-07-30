package cn.edu.bjtu.mis.data.thirdparty

import android.util.Log
import java.util.ArrayDeque

data class PluginDiagnosticEvent(
    val timestampMillis: Long,
    val level: String,
    val category: String,
    val pluginId: String,
    val requestId: String?,
    val capability: String?,
    val method: String?,
    val code: String?,
    val durationMs: Long?,
)

/**
 * Bounded, redacted diagnostics. Request parameters, response bodies, URLs,
 * credentials and identifiers other than plugin/request IDs are never stored.
 */
class PluginDiagnostics(
    private val pluginId: String,
    private val capacity: Int = 200,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val events = ArrayDeque<PluginDiagnosticEvent>()

    @Synchronized
    fun record(
        level: String,
        category: String,
        requestId: String? = null,
        capability: String? = null,
        method: String? = null,
        code: String? = null,
        durationMs: Long? = null,
    ) {
        val event = PluginDiagnosticEvent(
            timestampMillis = nowMillis(),
            level = level,
            category = category,
            pluginId = pluginId,
            requestId = requestId?.take(128),
            capability = capability?.take(128),
            method = method?.take(128),
            code = code?.take(80),
            durationMs = durationMs,
        )
        if (events.size >= capacity) events.removeFirst()
        events.addLast(event)
        val line = "plugin=$pluginId category=$category request=${event.requestId} " +
            "capability=${event.capability} method=${event.method} code=${event.code} " +
            "durationMs=${event.durationMs}"
        when (level) {
            "error" -> Log.e(TAG, line)
            "warning" -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
    }

    @Synchronized
    fun snapshot(): List<PluginDiagnosticEvent> = events.toList()

    private companion object {
        const val TAG = "BjtuPluginRuntime"
    }
}
