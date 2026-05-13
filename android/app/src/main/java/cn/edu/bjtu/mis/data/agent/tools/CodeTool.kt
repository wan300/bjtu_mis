package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.agent.runtime.RuntimeManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CodeTool(
    private val workspaceManager: WorkspaceManager,
    private val runtimeManager: RuntimeManager,
) {
    fun tools(): List<AgentTool> = listOf(RunJsTool(), RunPythonTool())

    private inner class RunJsTool : AgentTool {
        override val name = "code.run_js"
        override val description = "Run a small ECMAScript snippet in AndroidX JavaScriptSandbox. No Node.js, npm, file system, network, or shell."
        override val parameters = objectSchema(
            "code" to stringSchema("JavaScript body. Use return to return a JSON-serializable result."),
            "input" to objectSchema(),
            "timeoutSeconds" to integerSchema(minimum = 1, maximum = 30),
            required = listOf("code"),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult {
            val result = runtimeManager.runJs(
                code = arguments.requiredString("code"),
                input = arguments.objectOrEmpty("input"),
                timeoutSeconds = arguments.int("timeoutSeconds", 5),
            )
            return ToolResult(result.result, stdout = result.stdout, stderr = result.stderr)
        }
    }

    private inner class RunPythonTool : AgentTool {
        override val name = "code.run_python"
        override val description = "Run a small Python snippet with restricted builtins and controlled workspace helpers. No shell or runtime pip install."
        override val parameters = objectSchema(
            "code" to stringSchema("Python code. Set variable result to return a JSON-serializable value."),
            "input" to objectSchema(),
            "timeoutSeconds" to integerSchema(minimum = 1, maximum = 60),
            required = listOf("code"),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult {
            val result = runtimeManager.runPython(
                code = arguments.requiredString("code"),
                input = arguments.objectOrEmpty("input"),
                workspaceRoot = workspaceManager.root(taskId),
                timeoutSeconds = arguments.int("timeoutSeconds", 10),
            )
            return ToolResult(
                output = buildJsonObject {
                    result.result.forEach { (key, value) -> put(key, value) }
                },
                stdout = result.stdout,
                stderr = result.stderr,
            )
        }
    }
}
