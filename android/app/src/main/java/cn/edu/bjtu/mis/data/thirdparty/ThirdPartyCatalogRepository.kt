package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ThirdPartyCatalogRepository(
    private val client: BjtuHttpClient,
    baseUrl: String,
    cacheRoot: File,
) {
    private val apiBaseUrl = baseUrl.trimEnd('/')
    private val snapshotFile = File(cacheRoot, "catalog-snapshot.json")

    suspend fun listPlugins(
        query: String = "",
        category: String = "",
        cursor: String? = null,
    ): CatalogPluginPage {
        val normalizedQuery = query.trim()
        val normalizedCategory = category.trim()
        val isBaseSnapshotRequest = cursor == null && normalizedQuery.isBlank() && normalizedCategory.isBlank()
        val url = "$apiBaseUrl/api/v1/plugins".toHttpUrl().newBuilder().apply {
            normalizedQuery.takeIf(String::isNotBlank)?.let { addQueryParameter("query", it) }
            normalizedCategory.takeIf(String::isNotBlank)?.let { addQueryParameter("category", it) }
            cursor?.takeIf(String::isNotBlank)?.let { addQueryParameter("cursor", it) }
        }.build().toString()
        return runCatching {
            val response = client.getText(url, timeoutMillis = 15_000)
            val page = AppJson.decodeFromString<CatalogPluginPage>(response.body)
            if (isBaseSnapshotRequest) writeSnapshot(page)
            page
        }.getOrElse { error ->
            if (cursor == null) readSnapshot()?.filter(normalizedQuery, normalizedCategory)?.copy(fromCache = true)
                ?: throw IOException(error.message ?: "插件目录暂时不可用", error)
            else throw IOException(error.message ?: "无法加载更多插件", error)
        }
    }

    suspend fun resolveUpdates(installed: List<ThirdPartyService>): List<CatalogPlugin> {
        if (installed.isEmpty()) return emptyList()
        val request = CatalogUpdateRequest(
            installed = installed.take(100).map { CatalogInstalledVersion(it.serviceId, it.commitSha) },
        )
        val response = client.postJson(
            url = "$apiBaseUrl/api/v1/plugins/resolve-updates",
            json = AppJson.encodeToString(request),
            timeoutMillis = 15_000,
        )
        return AppJson.decodeFromString<CatalogUpdateResponse>(response.body).items
    }

    private suspend fun writeSnapshot(page: CatalogPluginPage) = withContext(Dispatchers.IO) {
        snapshotFile.parentFile?.mkdirs()
        val temp = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
        temp.writeText(AppJson.encodeToString(page.copy(fromCache = false)), Charsets.UTF_8)
        runCatching {
            Files.move(temp.toPath(), snapshotFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temp.toPath(), snapshotFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { error ->
            temp.delete()
            throw IOException("无法原子替换插件目录缓存", error)
        }
    }

    private suspend fun readSnapshot(): CatalogPluginPage? = withContext(Dispatchers.IO) {
        if (!snapshotFile.isFile) return@withContext null
        runCatching { AppJson.decodeFromString<CatalogPluginPage>(snapshotFile.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun CatalogPluginPage.filter(query: String, category: String): CatalogPluginPage {
        val normalizedQuery = query.lowercase()
        return copy(
            items = items.filter { plugin ->
                val matchesCategory = category.isBlank() || plugin.category == category
                val matchesQuery = normalizedQuery.isBlank() || listOf(
                    plugin.id,
                    plugin.name,
                    plugin.description,
                    plugin.author,
                    plugin.tags.joinToString(" "),
                ).any { normalizedQuery in it.lowercase() }
                matchesCategory && matchesQuery
            },
            nextCursor = null,
        )
    }
}
