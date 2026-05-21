package cn.edu.bjtu.mis.data.agent.runtime

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.agent.model.RuntimeCapability
import cn.edu.bjtu.mis.data.agent.model.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
        )
    }

    suspend fun runJs(code: String, input: JsonObject, timeoutSeconds: Int): CodeRunResult = withContext(Dispatchers.IO) {
        if (!JavaScriptSandbox.isSupported()) {
            return@withContext runtimeError("runtime_unavailable", "JavaScriptSandbox is not supported on this device")
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
            runtimeError("timeout", "JS execution timed out")
        } catch (error: Throwable) {
            runtimeError("sandbox_crashed", error.message ?: "JS sandbox execution failed")
        } finally {
            sandbox?.close()
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
}
