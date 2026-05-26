package cn.edu.bjtu.mis.data.update

import android.content.Context
import android.content.pm.PackageManager
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val StableTagPattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""", RegexOption.IGNORE_CASE)

        fun parse(value: String?): SemanticVersion? {
            val match = StableTagPattern.matchEntire(value?.trim().orEmpty()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemanticVersion(major, minor, patch)
        }
    }
}

@Serializable
data class GithubReleaseDto(
    val tagName: String = "",
    val htmlUrl: String = "",
    val body: String? = null,
    val publishedAt: String? = null,
)

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseNotes: String? = null,
)

class AppUpdateChecker(
    private val client: BjtuHttpClient,
    private val currentVersionProvider: () -> String?,
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    private val normalizedApiBaseUrl = apiBaseUrl.trimEnd('/')

    suspend fun checkForUpdate(): AppUpdateInfo? {
        val currentVersionName = try {
            currentVersionProvider()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        }
        val currentVersion = SemanticVersion.parse(currentVersionName) ?: return null
        val release = try {
            fetchLatestRelease()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        }
        val latestVersion = SemanticVersion.parse(release.tagName) ?: return null
        val releaseUrl = release.htmlUrl.takeIf { it.isNotBlank() } ?: return null

        if (latestVersion <= currentVersion) return null

        return AppUpdateInfo(
            currentVersion = currentVersion.toString(),
            latestVersion = latestVersion.toString(),
            releaseUrl = releaseUrl,
            releaseNotes = release.body?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun fetchLatestRelease(): GithubReleaseDto {
        val response = client.getText(
            url = "$normalizedApiBaseUrl/repos/$owner/$repo/releases/latest",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
        )
        return AppJson.decodeFromString(response.body)
    }

    companion object {
        private const val DEFAULT_OWNER = "wan300"
        private const val DEFAULT_REPO = "bjtu_web"
        private const val DEFAULT_API_BASE_URL = "https://api.github.com"
    }
}

fun Context.installedVersionName(): String? =
    try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
