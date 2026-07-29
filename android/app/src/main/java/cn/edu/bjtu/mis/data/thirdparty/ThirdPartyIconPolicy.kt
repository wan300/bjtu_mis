package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import java.net.URI

const val MAX_THIRD_PARTY_ICON_BYTES = 1024L * 1024L

private val SupportedThirdPartyIconExtensions =
    setOf("svg", "png", "webp", "jpg", "jpeg")

sealed interface ThirdPartyIconSource {
    class LocalFile internal constructor(val file: File) : ThirdPartyIconSource {
        override fun equals(other: Any?): Boolean =
            other is LocalFile && file == other.file

        override fun hashCode(): Int = file.hashCode()
    }

    class RemoteUrl internal constructor(val url: String) : ThirdPartyIconSource {
        override fun equals(other: Any?): Boolean =
            other is RemoteUrl && url == other.url

        override fun hashCode(): Int = url.hashCode()
    }
}

internal fun isSupportedThirdPartyIconPath(path: String): Boolean =
    path.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase() in SupportedThirdPartyIconExtensions

internal fun resolveLocalThirdPartyIconSource(
    rootDirectory: File,
    iconPath: String,
): ThirdPartyIconSource.LocalFile? {
    val normalizedPath = runCatching {
        ThirdPartyManifestValidator.validateAssetPath(iconPath, "icon")
    }.getOrNull() ?: return null
    if (!isSupportedThirdPartyIconPath(normalizedPath)) return null

    val root = runCatching { rootDirectory.canonicalFile }.getOrNull() ?: return null
    val icon = runCatching { File(root, normalizedPath).canonicalFile }.getOrNull() ?: return null
    val insideRoot = icon.toPath().startsWith(root.toPath()) && icon != root
    return icon.takeIf {
        insideRoot &&
            it.isFile &&
            it.length() in 1..MAX_THIRD_PARTY_ICON_BYTES
    }?.let { ThirdPartyIconSource.LocalFile(it) }
}

internal fun resolveRemoteThirdPartyIconSource(
    iconUrl: String,
): ThirdPartyIconSource.RemoteUrl? {
    val normalized = iconUrl.trim()
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    return normalized.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
    }?.let { ThirdPartyIconSource.RemoteUrl(it) }
}
