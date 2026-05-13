package cn.edu.bjtu.mis.data.agent.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentPythonService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messenger = Messenger(IncomingHandler())

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_RUN_PYTHON) {
                super.handleMessage(msg)
                return
            }
            val replyTo = msg.replyTo ?: return
            val data = msg.data
            serviceScope.launch {
                val response = Bundle()
                runCatching {
                    val py = ensurePython()
                    val module = py.getModule("agent_runner")
                    module.callAttr(
                        "run_code",
                        data.getString(KEY_CODE).orEmpty(),
                        data.getString(KEY_INPUT_JSON).orEmpty(),
                        data.getString(KEY_WORKSPACE_ROOT).orEmpty(),
                    ).toString()
                }.onSuccess { raw ->
                    response.putString(KEY_RAW_RESULT, raw)
                }.onFailure { error ->
                    response.putString(KEY_ERROR, error.message ?: "Python service failed")
                }
                replyTo.send(Message.obtain(null, MSG_PYTHON_RESULT).apply { this.data = response })
            }
        }
    }

    private fun ensurePython(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }
        return Python.getInstance()
    }

    companion object {
        const val MSG_RUN_PYTHON = 1
        const val MSG_PYTHON_RESULT = 2
        const val KEY_CODE = "code"
        const val KEY_INPUT_JSON = "input_json"
        const val KEY_WORKSPACE_ROOT = "workspace_root"
        const val KEY_RAW_RESULT = "raw_result"
        const val KEY_ERROR = "error"
    }
}
