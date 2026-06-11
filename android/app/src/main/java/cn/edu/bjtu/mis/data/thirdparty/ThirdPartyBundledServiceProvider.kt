package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

private const val JijiangServiceId = "com.jijiang.campus-service"
private const val JijiangAssetPath = "third-party-services/$JijiangServiceId"

data class BundledThirdPartyServiceSpec(
    val serviceId: String,
    val assetPath: String,
    val source: GitHubRepositoryRef,
    val defaultBranch: String,
    val defaultGrantedPermissions: Set<String>,
)

data class InstalledBundledThirdPartyService(
    val packageInfo: InstalledThirdPartyServicePackage,
    val defaultGrantedPermissions: Set<String>,
)

interface ThirdPartyBundledServiceProvider {
    suspend fun installMissingOrUpdated(
        existingServices: Map<String, ThirdPartyServiceEntity>,
    ): List<InstalledBundledThirdPartyService>
}

class AssetThirdPartyBundledServiceProvider(
    context: Context,
    private val installer: ThirdPartyServiceInstaller,
    servicesRoot: File,
    private val specs: List<BundledThirdPartyServiceSpec> = DefaultBundledThirdPartyServices,
) : ThirdPartyBundledServiceProvider {
    private val assets = context.applicationContext.assets
    private val tempRoot = File(servicesRoot, "bundled-tmp").canonicalFile

    override suspend fun installMissingOrUpdated(
        existingServices: Map<String, ThirdPartyServiceEntity>,
    ): List<InstalledBundledThirdPartyService> = withContext(Dispatchers.IO) {
        specs.mapNotNull { spec ->
            val tempDir = File(tempRoot, "${spec.serviceId}-${UUID.randomUUID()}").canonicalFile.safeChildOf(tempRoot)
            try {
                tempDir.mkdirs()
                copyAssetTree(spec.assetPath, tempDir, tempDir)
                val manifest = readManifest(tempDir)
                if (manifest.id != spec.serviceId) {
                    throw ThirdPartyServiceException("内置第三方服务 id 不匹配：${spec.assetPath}")
                }
                val digest = ThirdPartyPackageDigests.computeDistDigest(File(tempDir, "dist"))
                val existing = existingServices[manifest.id]
                val manifestJson = AppJson.encodeToString(manifest)
                if (
                    existing != null &&
                    existing.packageDigestSha256 == digest.sha256 &&
                    existing.manifestJson == manifestJson &&
                    File(existing.installDir, manifest.entrypoint).isFile
                ) {
                    return@mapNotNull null
                }
                val installed = installer.installPackageFromDirectory(
                    source = spec.source,
                    defaultBranch = spec.defaultBranch,
                    commitSha = digest.sha256.take(40),
                    packageRoot = tempDir,
                )
                InstalledBundledThirdPartyService(
                    packageInfo = installed,
                    defaultGrantedPermissions = spec.defaultGrantedPermissions,
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun readManifest(packageRoot: File): ThirdPartyServiceManifest {
        val manifestFile = File(packageRoot, "bjtu-service.json")
        if (!manifestFile.isFile) throw ThirdPartyServiceException("内置第三方服务缺少 bjtu-service.json")
        return ThirdPartyManifestValidator.validate(
            AppJson.decodeFromString<ThirdPartyServiceManifest>(manifestFile.readText(Charsets.UTF_8)),
            packageRoot,
        )
    }

    private fun copyAssetTree(assetPath: String, target: File, root: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            if (child.contains('/') || child.contains('\\') || child == "." || child == "..") {
                throw ThirdPartyServiceException("内置第三方服务包含非法资源路径：$assetPath/$child")
            }
            copyAssetTree(
                assetPath = "$assetPath/$child",
                target = File(target, child).canonicalFile.safeChildOf(root),
                root = root,
            )
        }
    }

    private fun File.safeChildOf(root: File): File =
        canonicalFile.also { file ->
            val safeRoot = root.canonicalFile
            if (file != safeRoot && !file.path.startsWith(safeRoot.path + File.separator)) {
                throw ThirdPartyServiceException("内置第三方服务包含越界路径：$path")
            }
        }
}

private val DefaultBundledThirdPartyServices = listOf(
    BundledThirdPartyServiceSpec(
        serviceId = JijiangServiceId,
        assetPath = JijiangAssetPath,
        source = GitHubRepositoryRef(
            owner = "bundled",
            repo = "jijiang",
            canonicalUrl = "asset://$JijiangAssetPath",
        ),
        defaultBranch = "bundled",
        defaultGrantedPermissions = emptySet(),
    )
)
