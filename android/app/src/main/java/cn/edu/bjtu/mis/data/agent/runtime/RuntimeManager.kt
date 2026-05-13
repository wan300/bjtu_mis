package cn.edu.bjtu.mis.data.agent.runtime

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.javascriptengine.JavaScriptSandbox
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.agent.model.RuntimeCapability
import cn.edu.bjtu.mis.data.agent.model.RuntimeStatus
import cn.edu.bjtu.mis.data.agent.service.AgentPythonService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class CodeRunResult(
    val ok: Boolean,
    val result: JsonObject,
    val stdout: String? = null,
    val stderr: String? = null,
    val error: String? = null,
)

class RuntimeManager(
    private val context: Context,
) {
    suspend fun capabilities(): List<RuntimeCapability> = withContext(Dispatchers.IO) {
        listOf(
            RuntimeCapability(
                name = "JavaScriptSandbox",
                status = if (JavaScriptSandbox.isSupported()) RuntimeStatus.Available else RuntimeStatus.Unavailable,
                limitations = listOf("ECMAScript only", "No Node.js APIs", "No file system", "No network", "No shell"),
            ),
            RuntimeCapability(
                name = "Chaquopy Python",
                status = runCatching {
                    RuntimeStatus.Available
                }.getOrDefault(RuntimeStatus.Unavailable),
                limitations = listOf("No runtime pip install", "Restricted imports", "No shell", "No credentials"),
            ),
        )
    }

    suspend fun runJs(code: String, input: JsonObject, timeoutSeconds: Int): CodeRunResult = withContext(Dispatchers.IO) {
        if (!JavaScriptSandbox.isSupported()) {
            return@withContext runtimeError("runtime_unavailable", "当前设备不支持 JavaScriptSandbox")
        }
        val timeout = timeoutSeconds.coerceIn(1, 30).toLong()
        val inputJson = AppJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), input)
        val wrapped = """
            (async () => {
              const __logs = [];
              const console = {
                log: (...args) => __logs.push(args.map((item) => {
                  try { return typeof item === 'string' ? item : JSON.stringify(item); }
                  catch (_) { return String(item); }
                }).join(' '))
              };
              const input = JSON.parse(${jsString(inputJson)});
              let result = null;
              try {
                const __user = async (input) => {
                  ${code}
                };
                result = await __user(input);
                return JSON.stringify({ ok: true, result, stdout: __logs.join('\n') });
              } catch (error) {
                return JSON.stringify({ ok: false, error: String(error), stdout: __logs.join('\n') });
              }
            })()
        """.trimIndent()
        var sandbox: JavaScriptSandbox? = null
        try {
            sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get(timeout, TimeUnit.SECONDS)
            val isolate = sandbox.createIsolate()
            val raw = isolate.evaluateJavaScriptAsync(wrapped).get(timeout, TimeUnit.SECONDS)
            isolate.close()
            parseCodeResult(raw)
        } catch (error: TimeoutException) {
            runtimeError("timeout", "JS 执行超时")
        } catch (error: Throwable) {
            runtimeError("sandbox_crashed", error.message ?: "JS sandbox 执行失败")
        } finally {
            sandbox?.close()
        }
    }

    suspend fun runPython(code: String, input: JsonObject, workspaceRoot: File, timeoutSeconds: Int): CodeRunResult = withContext(Dispatchers.IO) {
        val timeout = timeoutSeconds.coerceIn(1, 60).toLong()
        try {
            val inputJson = AppJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), input)
            val raw = withTimeout(timeout * 1000L) {
                runPythonInService(code, inputJson, workspaceRoot.absolutePath)
            }
            parseCodeResult(raw)
        } catch (error: TimeoutException) {
            runtimeError("timeout", "Python 执行超时")
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            runtimeError("timeout", "Python 执行超时")
        } catch (error: Throwable) {
            runtimeError("runtime_error", error.message ?: "Python 执行失败")
        }
    }

    private fun parseCodeResult(raw: String): CodeRunResult {
        val obj = AppJson.parseToJsonElement(raw).jsonObject
        val ok = obj["ok"]?.let { it.toString() == "true" } ?: false
        val stdout = obj["stdout"]?.toString()?.trim('"')?.take(64 * 1024)
        val stderr = obj["stderr"]?.toString()?.trim('"')?.take(64 * 1024)
        val result = buildJsonObject {
            put("ok", ok)
            obj["result"]?.let { put("result", it) }
            obj["error"]?.let { put("error", it) }
        }
        return CodeRunResult(ok = ok, result = result, stdout = stdout, stderr = stderr, error = obj["error"]?.toString())
    }

    private fun runtimeError(code: String, message: String): CodeRunResult =
        CodeRunResult(
            ok = false,
            result = buildJsonObject {
                put("ok", false)
                put("error", code)
                put("message", message)
            },
            stderr = message,
            error = code,
        )

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private suspend fun runPythonInService(code: String, inputJson: String, workspaceRoot: String): String {
        val deferred = CompletableDeferred<String>()
        val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != AgentPythonService.MSG_PYTHON_RESULT) {
                    super.handleMessage(msg)
                    return
                }
                val error = msg.data.getString(AgentPythonService.KEY_ERROR)
                if (!error.isNullOrBlank()) {
                    deferred.completeExceptionally(IllegalStateException(error))
                } else {
                    deferred.complete(msg.data.getString(AgentPythonService.KEY_RAW_RESULT).orEmpty())
                }
            }
        })
        var serviceMessenger: Messenger? = null
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceMessenger = Messenger(service)
                val bundle = Bundle().apply {
                    putString(AgentPythonService.KEY_CODE, code)
                    putString(AgentPythonService.KEY_INPUT_JSON, inputJson)
                    putString(AgentPythonService.KEY_WORKSPACE_ROOT, workspaceRoot)
                }
                serviceMessenger?.send(Message.obtain(null, AgentPythonService.MSG_RUN_PYTHON).apply {
                    data = bundle
                    replyTo = replyMessenger
                })
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("Python service disconnected"))
                }
            }
        }
        val intent = Intent(context.applicationContext, AgentPythonService::class.java)
        if (!context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("无法连接 Python service")
        }
        return try {
            deferred.await()
        } finally {
            runCatching { context.applicationContext.unbindService(connection) }
        }
    }
}
