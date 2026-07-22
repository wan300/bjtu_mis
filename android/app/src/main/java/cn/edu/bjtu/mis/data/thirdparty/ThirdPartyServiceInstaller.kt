package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

private const val MaxDownloadBytes = 25L * 1024L * 1024L
private const val MaxExtractedBytes = 50L * 1024L * 1024L
private const val MaxExtractedEntries = 1000
private const val StagingMaxAgeMillis = 24L * 60L * 60L * 1000L

data class PreparedThirdPartyServicePackage(
    val token: String,
    val manifest: ThirdPartyServiceManifest,
    val source: GitHubRepositoryRef,
    val defaultBranch: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val packageBytes: Long,
    val packageFileCount: Int,
    val stagingDir: File,
    val createdAtMillis: Long,
    val archiveSha256: String? = null,
    val platformVerified: Boolean = false,
)

data class InstalledThirdPartyServicePackage(
    val manifest: ThirdPartyServiceManifest,
    val source: GitHubRepositoryRef,
    val defaultBranch: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val packageBytes: Long,
    val packageFileCount: Int,
    val installDir: File,
)

data class StagedThirdPartyServiceDeletion(
    val serviceId: String,
    val originalDir: File,
    val stagedDir: File,
)

class ThirdPartyServiceInstaller(
    private val client: BjtuHttpClient,
    servicesRoot: File,
    apiBaseUrl: String = "https://api.github.com",
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val normalizedApiBaseUrl = apiBaseUrl.trimEnd('/')
    private val root = servicesRoot.canonicalFile
    private val stagingRoot = File(root, "staging").canonicalFile
    private val installedRoot = File(root, "installed").canonicalFile
    private val deletionRoot = File(root, "deletion").canonicalFile
    private val preparedPackages = ConcurrentHashMap<String, PreparedThirdPartyServicePackage>()

    suspend fun prepareFromGitHub(sourceUrl: String): PreparedThirdPartyServicePackage {
        cleanupStalePreparedImports()
        val source = parseGitHubRepositoryUrl(sourceUrl)
        val repo = fetchRepository(source)
        val defaultBranch = repo.defaultBranch.trim().takeIf { it.isNotBlank() }
            ?: throw ThirdPartyServiceException("GitHub 仓库缺少默认分支")
        val commitSha = fetchCommitSha(source, defaultBranch)
        val tempDir = File(root, "tmp/${UUID.randomUUID()}").canonicalFile.safeChildOf(root)
        val zipFile = File(tempDir, "source.zip")
        return try {
            tempDir.mkdirs()
            downloadZip("$normalizedApiBaseUrl/repos/${source.owner}/${source.repo}/zipball/$commitSha", zipFile)
            preparePackageFromZip(source, defaultBranch, commitSha, zipFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun installFromGitHub(sourceUrl: String): InstalledThirdPartyServicePackage {
        val prepared = prepareFromGitHub(sourceUrl)
        return commitPreparedImport(prepared.token)
    }

    suspend fun prepareFromCatalog(plugin: CatalogPlugin): PreparedThirdPartyServicePackage {
        cleanupStalePreparedImports()
        val source = parseGitHubRepositoryUrl(plugin.repositoryUrl)
        val tempDir = File(root, "tmp/${UUID.randomUUID()}").canonicalFile.safeChildOf(root)
        val zipFile = File(tempDir, "artifact.zip")
        return try {
            tempDir.mkdirs()
            downloadZip(plugin.artifactUrl, zipFile, emptyMap(), "插件快照")
            val archiveSha256 = fileSha256(zipFile)
            if (!archiveSha256.equals(plugin.archiveSha256, ignoreCase = true)) {
                throw ThirdPartyServiceException("插件快照 SHA-256 校验失败，已阻止安装")
            }
            val prepared = preparePackageFromZip(source, "platform-snapshot", plugin.commitSha, zipFile)
            if (prepared.manifest.id != plugin.id || prepared.manifest.version != plugin.version) {
                discardPreparedImport(prepared.token)
                throw ThirdPartyServiceException("插件快照 manifest 与目录元数据不一致")
            }
            if (!prepared.packageDigestSha256.equals(plugin.packageDigestSha256, ignoreCase = true)) {
                discardPreparedImport(prepared.token)
                throw ThirdPartyServiceException("插件 dist digest 校验失败，已阻止安装")
            }
            prepared.copy(archiveSha256 = archiveSha256, platformVerified = true).also {
                preparedPackages[it.token] = it
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun preparePackageFromZip(
        source: GitHubRepositoryRef,
        defaultBranch: String,
        commitSha: String,
        zipFile: File,
    ): PreparedThirdPartyServicePackage = withContext(Dispatchers.IO) {
        val tempDir = File(root, "extract/${UUID.randomUUID()}").canonicalFile.safeChildOf(root)
        val extractedDir = File(tempDir, "package")
        try {
            extractZip(zipFile, extractedDir)
            val packageRoot = locatePackageRoot(extractedDir)
            preparePackageFromRoot(source, defaultBranch, commitSha, packageRoot)
        } catch (error: ThirdPartyServiceException) {
            throw error
        } catch (error: Exception) {
            throw ThirdPartyServiceException(error.message ?: "第三方服务预检失败", error)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun preparePackageFromDirectory(
        source: GitHubRepositoryRef,
        defaultBranch: String,
        commitSha: String,
        packageRoot: File,
    ): PreparedThirdPartyServicePackage = withContext(Dispatchers.IO) {
        try {
            preparePackageFromRoot(
                source = source,
                defaultBranch = defaultBranch,
                commitSha = commitSha,
                packageRoot = packageRoot.canonicalFile,
            )
        } catch (error: ThirdPartyServiceException) {
            throw error
        } catch (error: Exception) {
            throw ThirdPartyServiceException(error.message ?: "第三方服务预检失败", error)
        }
    }

    suspend fun installPackageFromZip(
        source: GitHubRepositoryRef,
        defaultBranch: String,
        commitSha: String,
        zipFile: File,
    ): InstalledThirdPartyServicePackage {
        val prepared = preparePackageFromZip(source, defaultBranch, commitSha, zipFile)
        return commitPreparedImport(prepared.token)
    }

    suspend fun installPackageFromDirectory(
        source: GitHubRepositoryRef,
        defaultBranch: String,
        commitSha: String,
        packageRoot: File,
    ): InstalledThirdPartyServicePackage {
        val prepared = preparePackageFromDirectory(source, defaultBranch, commitSha, packageRoot)
        return commitPreparedImport(prepared.token)
    }

    suspend fun commitPreparedImport(token: String): InstalledThirdPartyServicePackage = withContext(Dispatchers.IO) {
        val prepared = preparedPackages.remove(token)
            ?: throw ThirdPartyServiceException("第三方服务预检包已失效，请重新导入")
        try {
            val serviceRoot = File(installedRoot, prepared.manifest.id).canonicalFile.safeChildOf(installedRoot)
            val finalDir = File(serviceRoot, prepared.commitSha).canonicalFile.safeChildOf(serviceRoot)
            serviceRoot.mkdirs()
            if (finalDir.exists() && !finalDir.deleteRecursively()) {
                throw IOException("无法替换已安装目录：${finalDir.absolutePath}")
            }
            if (!prepared.stagingDir.renameTo(finalDir)) {
                prepared.stagingDir.copyRecursively(finalDir, overwrite = true)
                prepared.stagingDir.deleteRecursively()
            }
            InstalledThirdPartyServicePackage(
                manifest = prepared.manifest,
                source = prepared.source,
                defaultBranch = prepared.defaultBranch,
                commitSha = prepared.commitSha,
                packageDigestSha256 = prepared.packageDigestSha256,
                packageBytes = prepared.packageBytes,
                packageFileCount = prepared.packageFileCount,
                installDir = finalDir,
            )
        } catch (error: ThirdPartyServiceException) {
            preparedPackages[token] = prepared
            throw error
        } catch (error: Exception) {
            preparedPackages[token] = prepared
            throw ThirdPartyServiceException(error.message ?: "第三方服务安装失败", error)
        }
    }

    fun preparedPackage(token: String): PreparedThirdPartyServicePackage? = preparedPackages[token]

    fun pruneInstalledVersions(serviceId: String, keepCommitSha: String) {
        val serviceRoot = File(installedRoot, serviceId).canonicalFile.safeChildOf(installedRoot)
        serviceRoot.listFiles().orEmpty().forEach { child ->
            if (child.name != keepCommitSha) child.safeDeleteWithin(serviceRoot)
        }
    }

    fun discardPreparedImport(token: String) {
        val prepared = preparedPackages.remove(token)
        prepared?.stagingDir?.safeDeleteWithin(stagingRoot)
    }

    fun cleanupStalePreparedImports(maxAgeMillis: Long = StagingMaxAgeMillis) {
        val cutoff = nowMillis() - maxAgeMillis
        preparedPackages.values
            .filter { it.createdAtMillis < cutoff }
            .forEach { discardPreparedImport(it.token) }
        stagingRoot.mkdirs()
        stagingRoot.listFiles().orEmpty().forEach { child ->
            if (child.lastModified() < cutoff) child.safeDeleteWithin(stagingRoot)
        }
    }

    fun deleteInstalledService(serviceId: String) {
        requireValidServiceId(serviceId)
        File(installedRoot, serviceId).safeDeleteWithin(installedRoot)
    }

    fun stageInstalledServiceDeletion(serviceId: String): StagedThirdPartyServiceDeletion? {
        requireValidServiceId(serviceId)
        val originalDir = File(installedRoot, serviceId).canonicalFile.safeChildOf(installedRoot)
        if (!originalDir.exists()) return null
        deletionRoot.mkdirs()
        val stagedDir = File(deletionRoot, "$serviceId-${UUID.randomUUID()}").canonicalFile.safeChildOf(deletionRoot)
        if (!originalDir.renameTo(stagedDir)) {
            throw ThirdPartyServiceException("无法暂存待删除的第三方服务目录：${originalDir.absolutePath}")
        }
        return StagedThirdPartyServiceDeletion(serviceId, originalDir, stagedDir)
    }

    fun restoreStagedServiceDeletion(deletion: StagedThirdPartyServiceDeletion) {
        if (!deletion.stagedDir.exists()) return
        deletion.originalDir.parentFile?.mkdirs()
        if (deletion.originalDir.exists() || !deletion.stagedDir.renameTo(deletion.originalDir)) {
            throw ThirdPartyServiceException("无法恢复第三方服务目录：${deletion.serviceId}")
        }
    }

    fun commitStagedServiceDeletion(deletion: StagedThirdPartyServiceDeletion) {
        deletion.stagedDir.safeDeleteWithin(deletionRoot)
    }

    private fun requireValidServiceId(serviceId: String) {
        if (!serviceId.matches(Regex("^[a-z][a-z0-9_\\-.]{2,63}$"))) {
            throw ThirdPartyServiceException("第三方服务 id 格式无效：$serviceId")
        }
    }

    private suspend fun fetchRepository(source: GitHubRepositoryRef): GitHubRepoDto {
        val response = client.getText(
            "$normalizedApiBaseUrl/repos/${source.owner}/${source.repo}",
            headers = githubHeaders(),
        )
        return AppJson.decodeFromString(response.body)
    }

    private suspend fun fetchCommitSha(source: GitHubRepositoryRef, defaultBranch: String): String {
        val response = client.getText(
            "$normalizedApiBaseUrl/repos/${source.owner}/${source.repo}/git/ref/heads/$defaultBranch",
            headers = githubHeaders(),
        )
        return AppJson.decodeFromString<GitHubRefDto>(response.body).objectInfo.sha
            .trim()
            .takeIf { it.matches(Regex("^[a-fA-F0-9]{7,40}$")) }
            ?: throw ThirdPartyServiceException("无法识别 GitHub 默认分支 commit")
    }

    private suspend fun downloadZip(
        url: String,
        target: File,
        headers: Map<String, String> = githubHeaders(),
        sourceLabel: String = "GitHub",
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .get()
            .build()
        client.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ThirdPartyServiceException("$sourceLabel 下载失败：HTTP ${response.code}")
            val body = response.body ?: throw ThirdPartyServiceException("$sourceLabel 下载内容为空")
            target.parentFile?.mkdirs()
            var total = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MaxDownloadBytes) throw ThirdPartyServiceException("第三方服务包超过 25 MiB 限制")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        val root = targetDir.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        root.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MaxExtractedEntries) {
                    throw ThirdPartyServiceException("第三方服务包条目数量超过 $MaxExtractedEntries")
                }
                val entryName = entry.name.replace('\\', '/')
                if (entryName.startsWith("/") || entryName.split('/').any { it == ".." }) {
                    throw ThirdPartyServiceException("第三方服务包包含越界路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    File(root, entryName).safeChildOf(root).mkdirs()
                    continue
                }
                val target = File(root, entryName).safeChildOf(root)
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        if (totalBytes > MaxExtractedBytes) {
                            throw ThirdPartyServiceException("第三方服务解压后超过 50 MiB 限制")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun locatePackageRoot(extractedDir: File): File {
        val children = extractedDir.listFiles().orEmpty().filter { it.name != "__MACOSX" }
        val singleDir = children.singleOrNull()?.takeIf { it.isDirectory }
        return when {
            File(extractedDir, "bjtu-service.json").isFile -> extractedDir
            singleDir != null && File(singleDir, "bjtu-service.json").isFile -> singleDir
            else -> throw ThirdPartyServiceException("第三方服务包根目录缺少 bjtu-service.json")
        }.canonicalFile
    }

    private fun preparePackageFromRoot(
        source: GitHubRepositoryRef,
        defaultBranch: String,
        commitSha: String,
        packageRoot: File,
    ): PreparedThirdPartyServicePackage {
        val manifestFile = File(packageRoot, "bjtu-service.json")
        if (!manifestFile.isFile) throw ThirdPartyServiceException("第三方服务缺少 bjtu-service.json")
        val manifest = ThirdPartyManifestValidator.validate(
            AppJson.decodeFromString<ThirdPartyServiceManifest>(manifestFile.readText(Charsets.UTF_8)),
            packageRoot,
        )
        val token = UUID.randomUUID().toString()
        val stagingDir = File(stagingRoot, token).canonicalFile.safeChildOf(stagingRoot)
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            throw IOException("无法清理暂存目录：${stagingDir.absolutePath}")
        }
        stagingDir.parentFile?.mkdirs()
        File(packageRoot, "dist").copyRecursively(stagingDir, overwrite = true)
        val digest = ThirdPartyPackageDigests.computeDistDigest(stagingDir)
        val prepared = PreparedThirdPartyServicePackage(
            token = token,
            manifest = manifest,
            source = source,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = digest.sha256,
            packageBytes = digest.totalBytes,
            packageFileCount = digest.fileCount,
            stagingDir = stagingDir,
            createdAtMillis = nowMillis(),
        )
        preparedPackages[token] = prepared
        return prepared
    }

    companion object {
        fun parseGitHubRepositoryUrl(value: String): GitHubRepositoryRef {
            val raw = value.trim()
            val uri = runCatching { URI(raw) }.getOrElse {
                throw ThirdPartyServiceException("请输入 GitHub 公开仓库链接")
            }
            if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() != "github.com") {
                throw ThirdPartyServiceException("仅支持 https://github.com/{owner}/{repo} 链接")
            }
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
                throw ThirdPartyServiceException("GitHub 仓库链接不能包含查询参数、片段或用户名")
            }
            val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
            if (parts.size != 2) throw ThirdPartyServiceException("仅支持仓库根链接，不支持分支、文件或子目录链接")
            val owner = parts[0]
            val repo = parts[1]
            if (!owner.matches(Regex("^[A-Za-z0-9-]{1,39}$")) || !repo.matches(Regex("^[A-Za-z0-9_.-]{1,100}$")) || repo.endsWith(".git")) {
                throw ThirdPartyServiceException("GitHub 仓库 owner 或 repo 格式无效")
            }
            return GitHubRepositoryRef(owner = owner, repo = repo, canonicalUrl = "https://github.com/$owner/$repo")
        }
    }

    private fun File.safeChildOf(root: File): File =
        canonicalFile.also { file ->
            if (file != root && !file.path.startsWith(root.path + File.separator)) {
                throw ThirdPartyServiceException("第三方服务包包含越界路径：$path")
            }
        }

    private fun File.safeDeleteWithin(root: File) {
        val target = canonicalFile
        target.safeChildOf(root)
        if (target.exists() && !target.deleteRecursively()) {
            throw ThirdPartyServiceException("无法删除第三方服务目录：${target.absolutePath}")
        }
    }

    private fun githubHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )
}

@Serializable
private data class GitHubRepoDto(
    val defaultBranch: String = "",
)

@Serializable
private data class GitHubRefDto(
    @kotlinx.serialization.SerialName("object")
    val objectInfo: GitHubRefObjectDto = GitHubRefObjectDto(),
)

@Serializable
private data class GitHubRefObjectDto(
    val sha: String = "",
)
