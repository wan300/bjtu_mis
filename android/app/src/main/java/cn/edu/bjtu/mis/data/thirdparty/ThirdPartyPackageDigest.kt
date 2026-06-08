package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import java.security.MessageDigest

data class ThirdPartyPackageDigest(
    val sha256: String,
    val fileCount: Int,
    val totalBytes: Long,
)

object ThirdPartyPackageDigests {
    fun computeDistDigest(distRoot: File): ThirdPartyPackageDigest {
        val root = distRoot.canonicalFile
        if (!root.isDirectory) throw ThirdPartyServiceException("第三方服务缺少 dist/ 目录")
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        files.forEach { file ->
            val relativePath = file.relativeTo(root).invariantSeparatorsPath
            val length = file.length()
            totalBytes += length
            digest.update("file\u0000$relativePath\u0000$length\u0000".toByteArray(Charsets.UTF_8))
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }
        return ThirdPartyPackageDigest(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            fileCount = files.size,
            totalBytes = totalBytes,
        )
    }
}
