package com.masterllm.app.openclaw

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
        registerTool(
            "web_search",
            "Search the web for current information. Use when you need to look up facts, current events, health information, or anything beyond your training data.",
            mapOf("query" to "string (required)")
        ) { params ->
            val query = params["query"] ?: return@registerTool "Error: query required"
            try {
                webSearch(query)
            } catch (e: Exception) {
                "Error searching: ${e.message}"
            }
        }
        registerTool("fetch_url", "Fetch text content from a URL", mapOf("url" to "string (required)")) { params ->
            val urlStr = params["url"] ?: return@registerTool "Error: url required"
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.inputStream.bufferedReader().use { it.readText().take(8000) }
            } catch (e: Exception) { "Error: ${e.message}" }
        }
        registerTool("current_time", "Get current date and time", emptyMap()) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        }
    }

    private suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "text/html")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true

        val html = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return@withContext "No results found"
        } finally {
            conn.disconnect()
        }

        parseSearchResults(html, query)
    }

    private fun parseSearchResults(html: String, query: String): String {
        val results = mutableListOf<SearchResult>()

        val linkPattern = Regex("""<a rel="nofollow" class="result__a" href="([^"]*)"[^>]*>([\s\S]*?)</a>""")
        val snippetPattern = Regex("""<a class="result__snippet"[^>]*>([\s\S]*?)</a>""")
        val snippets = snippetPattern.findAll(html).toList()

        for ((index, match) in linkPattern.findAll(html).withIndex()) {
            if (results.size >= 5) break
            val rawUrl = match.groupValues[1]
            val title = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .trim()

            val snippet = if (index < snippets.size) {
                snippets[index].groupValues[1]
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#x27;", "'")
                    .replace("&#39;", "'")
                    .trim()
            } else ""

            val actualUrl = decodeDdgUrl(rawUrl)

            if (title.isNotBlank() && actualUrl.isNotBlank()) {
                results.add(SearchResult(title, snippet, actualUrl))
            }
        }

        if (results.isEmpty()) {
            return "No search results found for: $query"
        }

        return buildString {
            appendLine("Search results for: \"$query\"")
            appendLine()
            results.forEachIndexed { i, result ->
                appendLine("${i + 1}. ${result.title}")
                if (result.snippet.isNotBlank()) appendLine("   ${result.snippet}")
                appendLine("   URL: ${result.url}")
                appendLine()
            }
        }
    }

    private fun decodeDdgUrl(rawUrl: String): String {
        val uddgPattern = Regex("""[?&]uddg=([^&]+)""")
        val match = uddgPattern.find(rawUrl)
        return if (match != null) {
            java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        } else {
            rawUrl
        }
    }

    private data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String,
    )

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

    fun buildSystemPrompt(): String = buildString {
        appendLine("You are Solace Agent \u2014 a compassionate AI assistant running on Android with Gemma 4 E2B.")
        appendLine("You have access to the following tools:")
        appendLine()
        appendLine("<tools>")
        appendLine(buildToolsJson())
        appendLine("</tools>")
        appendLine()
        appendLine("When you need to use a tool, respond with EXACTLY:")
        val tc = "tool_call"
        val fn = "function"
        val pn = "parameter"
        appendLine("<$tc>")
        appendLine("<$fn=tool_name>")
        appendLine("<$pn=param_name>")
        appendLine("value")
        appendLine("</$pn>")
        appendLine("</$fn>")
        appendLine("</$tc>")
        appendLine()
        appendLine("After the tool returns, I will send you the result. Then decide the next step.")
        appendLine("If no tool is needed, respond normally.")
    }
}
