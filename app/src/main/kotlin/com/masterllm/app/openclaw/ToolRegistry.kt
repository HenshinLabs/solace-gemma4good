package com.masterllm.app.openclaw

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class OpenClawTool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>,
    val executor: suspend (Map<String, String>) -> String,
)

data class ToolCall(
    val name: String,
    val params: Map<String, String>,
)

data class ToolResult(
    val toolCall: ToolCall,
    val output: String,
    val isError: Boolean = false,
)

@Singleton
class ToolRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tools = mutableListOf<OpenClawTool>()

    init {
        registerDefaultTools()
    }

    private fun registerDefaultTools() {
        registerTool("fetch_url", "Fetch text content from a URL", mapOf("url" to "string (required)")) { params ->
            val url = params["url"] ?: return@registerTool "Error: url required"
            try { URL(url).readText().take(8000) } catch (e: Exception) { "Error: ${e.message}" }
        }
        registerTool("read_file", "Read contents of a file on device", mapOf("path" to "string (required)")) { params ->
            val path = params["path"] ?: return@registerTool "Error: path required"
            val file = File(path)
            if (!file.exists()) return@registerTool "File not found: $path"
            if (!file.canRead()) return@registerTool "Permission denied: $path"
            try { file.readText().take(8000) } catch (e: Exception) { "Error: ${e.message}" }
        }
        registerTool("list_files", "List files in a directory", mapOf("path" to "string (required)")) { params ->
            val path = params["path"] ?: return@registerTool "Error: path required"
            val dir = File(path)
            if (!dir.isDirectory) return@registerTool "Not a directory: $path"
            try { dir.list()?.joinToString("\n")?.take(2000) ?: "Empty directory" } catch (e: Exception) { "Error: ${e.message}" }
        }
        registerTool("run_shell", "Execute a shell command", mapOf("command" to "string (required)")) { params ->
            val cmd = params["command"] ?: return@registerTool "Error: command required"
            try {
                val p = Runtime.getRuntime().exec(cmd)
                val out = p.inputStream.bufferedReader().readText().take(5000)
                val err = p.errorStream.bufferedReader().readText().take(2000)
                buildString { if (out.isNotEmpty()) append("STDOUT:\n$out"); if (err.isNotEmpty()) { if (isNotEmpty()) append("\n\n"); append("STDERR:\n$err") }; if (isEmpty()) append("(no output)") }
            } catch (e: Exception) { "Error: ${e.message}" }
        }
        registerTool("send_notification", "Send device notification", mapOf("title" to "string (required)", "message" to "string (required)")) { params ->
            val title = params["title"] ?: return@registerTool "Error: title required"
            val msg = params["message"] ?: return@registerTool "Error: message required"
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(android.app.NotificationChannel("openclaw", "OpenClaw", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                }
                nm.notify(System.currentTimeMillis().toInt(), android.app.Notification.Builder(context, "openclaw").setContentTitle(title).setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info).build())
                "Notification sent"
            } catch (e: Exception) { "Error: ${e.message}" }
        }
        registerTool("current_time", "Get current date and time", emptyMap()) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        }
    }

    private fun registerTool(name: String, description: String, parameters: Map<String, String>, executor: suspend (Map<String, String>) -> String) {
        register(OpenClawTool(name, description, parameters, executor))
    }

    fun register(tool: OpenClawTool) {
        tools.removeAll { it.name == tool.name }
        tools.add(tool)
        Log.d("ToolRegistry", "Registered tool: ${tool.name}")
    }

    fun getTool(name: String): OpenClawTool? = tools.find { it.name == name }

    fun getAllTools(): List<OpenClawTool> = tools.toList()

    fun buildToolsJson(): String {
        return tools.joinToString(",\n") { tool ->
            """  {"name":"${tool.name}","description":"${tool.description}","parameters":${tool.parameters}}"""
        }
    }

    fun buildSystemPrompt(): String = """
You are OpenClaw Agent — an AI assistant running on Android with Gemma 4 E2B.
You have access to the following tools:

<tools>
${buildToolsJson()}
</tools>

When you need to use a tool, respond with EXACTLY:
<tool_call>
<function=tool_name>
<parameter=param_name>
value
</parameter>
</function>
</tool_call>

After the tool returns, I will send you the result. Then decide the next step.
If no tool is needed, respond normally.
""".trimIndent()
}


