package cn.edu.bjtu.mis.data.agent.search

import cn.edu.bjtu.mis.data.agent.tools.AgentTool
import cn.edu.bjtu.mis.data.agent.tools.ToolResult
import cn.edu.bjtu.mis.data.agent.tools.errorOutput
import cn.edu.bjtu.mis.data.agent.tools.integerSchema
import cn.edu.bjtu.mis.data.agent.tools.int
import cn.edu.bjtu.mis.data.agent.tools.objectSchema
import cn.edu.bjtu.mis.data.agent.tools.requiredString
import cn.edu.bjtu.mis.data.agent.tools.stringSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SearchTool(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    fun tools(): List<AgentTool> = listOf(QueryTool(), FetchPageTool())

    private inner class QueryTool : AgentTool {
        override val name = "search.query"
        override val description = "Search the web using DuckDuckGo HTML and return title, URL, and snippet."
        override val parameters = objectSchema(
            "query" to stringSchema("Search query."),
            "limit" to integerSchema(minimum = 1, maximum = 10),
            required = listOf("query"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val limit = arguments.int("limit", 5).coerceIn(1, 10)
            val query = URLEncoder.encode(arguments.requiredString("query"), "UTF-8")
            val request = Request.Builder()
                .url("https://duckduckgo.com/html/?q=$query")
                .header("User-Agent", "Mozilla/5.0 (Android Agent)")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return@withContext ToolResult(errorOutput("http_error", "HTTP ${it.code}"))
                val doc = Jsoup.parse(it.body?.string().orEmpty(), "https://duckduckgo.com")
                val results = doc.select(".result").mapNotNull { result ->
                    val link = result.selectFirst(".result__a") ?: return@mapNotNull null
                    val title = link.text().trim()
                    val href = link.attr("abs:href").ifBlank { link.attr("href") }
                    if (title.isBlank() || href.isBlank()) return@mapNotNull null
                    buildJsonObject {
                        put("title", title)
                        put("url", href)
                        put("snippet", result.selectFirst(".result__snippet")?.text().orEmpty())
                    }
                }.take(limit)
                ToolResult(buildJsonObject {
                    put("ok", true)
                    put("results", JsonArray(results))
                })
            }
        }
    }

    private inner class FetchPageTool : AgentTool {
        override val name = "search.fetch_page"
        override val description = "Fetch one HTML page and extract readable text. Does not download attachments."
        override val parameters = objectSchema(
            "url" to stringSchema("HTTP or HTTPS URL."),
            required = listOf("url"),
        )

        override suspend fun execute(taskId: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val url = arguments.requiredString("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext ToolResult(errorOutput("unsupported_url", "只支持 HTTP/HTTPS"))
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android Agent)")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return@withContext ToolResult(errorOutput("http_error", "HTTP ${it.code}"))
                val contentType = it.header("content-type").orEmpty().lowercase()
                if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
                    return@withContext ToolResult(errorOutput("unsupported_content_type", "search.fetch_page 只处理 HTML"))
                }
                val body = it.body ?: return@withContext ToolResult(errorOutput("empty_body", "页面为空"))
                val bytes = body.bytes()
                if (bytes.size > MAX_RESPONSE_BYTES) {
                    return@withContext ToolResult(errorOutput("too_large", "页面超过 2 MiB"))
                }
                val doc = Jsoup.parse(String(bytes, Charsets.UTF_8), url)
                doc.select("script,style,noscript,svg").remove()
                val text = doc.body()?.text().orEmpty().replace(Regex("""\s+"""), " ").trim()
                val truncated = text.length > MAX_TEXT_CHARS
                ToolResult(buildJsonObject {
                    put("ok", true)
                    put("url", response.request.url.toString())
                    put("title", doc.title())
                    put("text", if (truncated) text.take(MAX_TEXT_CHARS) else text)
                    put("truncated", truncated)
                })
            }
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_TEXT_CHARS = 128 * 1024
    }
}
