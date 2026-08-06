package cn.edu.bjtu.mis.data.thirdparty

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

private const val DEFAULT_MAX_PENDING_BINARY_REQUESTS = 4
private const val DECODE_BUFFER_BYTES = 8 * 1024

data class PluginBinaryDeclaration(
    val transport: PluginBinaryTransport,
    val size: Long,
    val chunks: Int,
    val sha256: String,
)

internal class PluginBinaryStagingException(
    val code: String,
    override val message: String,
) : IOException(message)

internal sealed interface PluginBinaryStagingResult<out T> {
    val owner: T
    val acknowledgeChunk: Boolean
    val acceptedChunkIndex: Int?

    data class Pending<T>(
        override val owner: T,
        override val acknowledgeChunk: Boolean = false,
        override val acceptedChunkIndex: Int? = null,
    ) : PluginBinaryStagingResult<T>

    data class Complete<T>(
        override val owner: T,
        val payload: PluginBinaryPayload,
        override val acknowledgeChunk: Boolean = false,
        override val acceptedChunkIndex: Int? = null,
    ) : PluginBinaryStagingResult<T>
}

/**
 * Owns all in-flight bridge uploads for one plugin WebView. Each chunk is
 * validated and written immediately; the manager never retains a complete
 * Base64 value or complete binary payload in memory.
 */
internal class PluginBinaryStagingManager<T>(
    private val directory: File,
    private val maxPendingRequests: Int = DEFAULT_MAX_PENDING_BINARY_REQUESTS,
    private val safetyBytes: Long = THIRD_PARTY_RESOURCE_SAFETY_BYTES,
    private val usableSpace: () -> Long = { directory.usableSpace },
) {
    private val pending = linkedMapOf<String, PendingUpload<T>>()

    @Synchronized
    fun begin(
        requestId: String,
        owner: T,
        declaration: PluginBinaryDeclaration,
        itemLimitBytes: Long,
    ): PluginBinaryStagingResult<T> {
        validateDeclaration(declaration, itemLimitBytes)
        if (requestId in pending) {
            throw PluginBinaryStagingException("invalid_request", "Duplicate binary request ID")
        }
        if (pending.size >= maxPendingRequests) {
            throw PluginBinaryStagingException(
                "quota_exceeded",
                "Too many pending binary requests",
            )
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw PluginBinaryStagingException(
                "capability_unavailable",
                "Unable to create binary staging directory",
            )
        }
        val reservedBytes = pending.values.sumOf { upload ->
            upload.staging.remainingBytes
        }
        val requiredBytes = safeAdd(safeAdd(reservedBytes, declaration.size), safetyBytes)
        if (usableSpace() < requiredBytes) {
            throw PluginBinaryStagingException(
                "quota_exceeded",
                "Binary staging would consume the device safety reserve",
            )
        }
        val upload = PendingUpload(
            owner = owner,
            staging = PluginBinaryStagingFile.create(directory, declaration),
        )
        if (declaration.size == 0L) {
            return PluginBinaryStagingResult.Complete(owner, upload.staging.finish())
        }
        pending[requestId] = upload
        return PluginBinaryStagingResult.Pending(owner)
    }

    @Synchronized
    fun receiveArrayBuffer(
        requestId: String,
        index: Int,
        last: Boolean,
        bytes: ByteArray,
    ): PluginBinaryStagingResult<T> = receive(requestId, index) { upload ->
        if (upload.staging.transport != PluginBinaryTransport.ArrayBuffer) {
            throw PluginBinaryStagingException(
                "invalid_request",
                "Binary chunk transport does not match the negotiated mode",
            )
        }
        upload.staging.writeArrayBuffer(index, last, bytes)
        false
    }

    @Synchronized
    fun receiveBase64Url(
        requestId: String,
        index: Int,
        last: Boolean,
        payload: String,
    ): PluginBinaryStagingResult<T> = receive(requestId, index) { upload ->
        if (upload.staging.transport != PluginBinaryTransport.Base64UrlChunksV1) {
            throw PluginBinaryStagingException(
                "invalid_request",
                "Binary chunk transport does not match the negotiated mode",
            )
        }
        upload.staging.writeBase64Url(index, last, payload)
        true
    }

    @Synchronized
    fun transportFor(requestId: String): PluginBinaryTransport? =
        pending[requestId]?.staging?.transport

    @Synchronized
    fun cancel(requestId: String): Boolean = pending.remove(requestId)?.let { upload ->
        upload.staging.discard()
        true
    } ?: false

    @Synchronized
    fun close() {
        pending.values.forEach { upload -> upload.staging.discard() }
        pending.clear()
    }

    @Synchronized
    internal fun pendingCount(): Int = pending.size

    private inline fun receive(
        requestId: String,
        acceptedChunkIndex: Int,
        write: (PendingUpload<T>) -> Boolean,
    ): PluginBinaryStagingResult<T> {
        val upload = pending[requestId]
            ?: throw PluginBinaryStagingException("invalid_request", "Unknown binary request ID")
        return try {
            val acknowledge = write(upload)
            if (upload.staging.isComplete) {
                pending.remove(requestId)
                PluginBinaryStagingResult.Complete(
                    upload.owner,
                    upload.staging.finish(),
                    acknowledge,
                    acceptedChunkIndex,
                )
            } else {
                PluginBinaryStagingResult.Pending(
                    upload.owner,
                    acknowledge,
                    acceptedChunkIndex,
                )
            }
        } catch (error: Exception) {
            pending.remove(requestId)
            upload.staging.discard()
            throw error
        }
    }

    private fun validateDeclaration(
        declaration: PluginBinaryDeclaration,
        itemLimitBytes: Long,
    ) {
        if (declaration.size < 0L || declaration.size > itemLimitBytes) {
            throw PluginBinaryStagingException("invalid_request", "Invalid binary size")
        }
        val expectedChunks = if (declaration.size == 0L) {
            0L
        } else {
            (declaration.size + declaration.transport.chunkBytes - 1L) /
                declaration.transport.chunkBytes
        }
        if (expectedChunks > Int.MAX_VALUE || declaration.chunks != expectedChunks.toInt()) {
            throw PluginBinaryStagingException("invalid_request", "Invalid binary chunk count")
        }
        if (!declaration.sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            throw PluginBinaryStagingException("invalid_request", "Invalid binary SHA-256")
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private data class PendingUpload<T>(
        val owner: T,
        val staging: PluginBinaryStagingFile,
    )

    companion object {
        fun cleanupOrphans(root: File) {
            if (!root.isDirectory) return
            root.walkBottomUp().forEach { file ->
                if (file.isFile && file.name.endsWith(".part")) {
                    runCatching { file.delete() }
                } else if (file.isDirectory && file != root && file.list().isNullOrEmpty()) {
                    runCatching { file.delete() }
                }
            }
        }
    }
}

private class PluginBinaryStagingFile private constructor(
    val transport: PluginBinaryTransport,
    private val expectedSize: Long,
    private val expectedChunks: Int,
    private val expectedSha256: String,
    private val file: File,
    private val output: BufferedOutputStream,
    private val digest: MessageDigest,
) {
    private var nextChunk = 0
    private var writtenBytes = 0L
    private var closed = false

    val remainingBytes: Long
        get() = expectedSize - writtenBytes

    val isComplete: Boolean
        get() = nextChunk == expectedChunks && writtenBytes == expectedSize

    fun writeArrayBuffer(index: Int, last: Boolean, bytes: ByteArray) {
        validateChunk(index, last, bytes.size)
        write(bytes, 0, bytes.size)
        nextChunk += 1
    }

    fun writeBase64Url(index: Int, last: Boolean, payload: String) {
        val expectedBytes = expectedChunkBytes(index, last)
        validateBase64Url(payload, expectedBytes)
        var decodedBytes = 0
        try {
            Base64.getUrlDecoder().wrap(
                ByteArrayInputStream(payload.toByteArray(StandardCharsets.US_ASCII)),
            ).use { input ->
                val buffer = ByteArray(DECODE_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    decodedBytes += read
                    if (decodedBytes > expectedBytes) {
                        throw PluginBinaryStagingException(
                            "invalid_request",
                            "Decoded binary chunk exceeds its declared size",
                        )
                    }
                    write(buffer, 0, read)
                }
            }
        } catch (error: PluginBinaryStagingException) {
            throw error
        } catch (_: Exception) {
            throw PluginBinaryStagingException("invalid_request", "Invalid Base64URL chunk")
        }
        if (decodedBytes != expectedBytes) {
            throw PluginBinaryStagingException(
                "invalid_request",
                "Decoded binary chunk size does not match its declaration",
            )
        }
        nextChunk += 1
    }

    fun finish(): PluginBinaryPayload {
        if (!isComplete) {
            throw PluginBinaryStagingException("invalid_request", "Incomplete binary payload")
        }
        try {
            output.flush()
            output.close()
            closed = true
        } catch (_: Exception) {
            discard()
            throw PluginBinaryStagingException(
                "capability_unavailable",
                "Unable to finalize binary payload",
            )
        }
        val actualSha256 = digest.digest().toHex()
        if (actualSha256 != expectedSha256) {
            file.delete()
            throw PluginBinaryStagingException("invalid_request", "Binary SHA-256 mismatch")
        }
        return PluginBinaryPayload(writtenBytes, file)
    }

    fun discard() {
        if (!closed) runCatching { output.close() }
        closed = true
        file.delete()
    }

    private fun validateChunk(index: Int, last: Boolean, actualBytes: Int) {
        val expectedBytes = expectedChunkBytes(index, last)
        if (actualBytes != expectedBytes) {
            throw PluginBinaryStagingException(
                "invalid_request",
                "Binary chunk size does not match its declared position",
            )
        }
    }

    private fun expectedChunkBytes(index: Int, last: Boolean): Int {
        if (index != nextChunk || index !in 0 until expectedChunks) {
            throw PluginBinaryStagingException(
                "invalid_request",
                "Binary chunk is duplicated or out of order",
            )
        }
        val expectedLast = index == expectedChunks - 1
        if (last != expectedLast) {
            throw PluginBinaryStagingException("invalid_request", "Invalid final chunk marker")
        }
        val offset = index.toLong() * transport.chunkBytes
        return minOf(transport.chunkBytes.toLong(), expectedSize - offset).toInt()
    }

    private fun validateBase64Url(payload: String, expectedBytes: Int) {
        val expectedLength = ((expectedBytes + 2L) / 3L * 4L - when (expectedBytes % 3) {
            1 -> 2
            2 -> 1
            else -> 0
        }).toInt()
        if (
            payload.length != expectedLength ||
            payload.any { character -> !character.isBase64UrlCharacter() }
        ) {
            throw PluginBinaryStagingException(
                "invalid_request",
                "Binary chunk is not canonical unpadded Base64URL",
            )
        }
        if (payload.isNotEmpty()) {
            val trailingValue = payload.last().base64UrlValue()
            val canonicalTrailingBits = when (expectedBytes % 3) {
                1 -> trailingValue and 0x0f == 0
                2 -> trailingValue and 0x03 == 0
                else -> true
            }
            if (!canonicalTrailingBits) {
                throw PluginBinaryStagingException(
                    "invalid_request",
                    "Binary chunk uses non-canonical Base64URL trailing bits",
                )
            }
        }
    }

    private fun write(bytes: ByteArray, offset: Int, count: Int) {
        try {
            output.write(bytes, offset, count)
            digest.update(bytes, offset, count)
            writtenBytes += count
        } catch (_: Exception) {
            throw PluginBinaryStagingException(
                "capability_unavailable",
                "Unable to write binary staging file",
            )
        }
    }

    companion object {
        fun create(
            directory: File,
            declaration: PluginBinaryDeclaration,
        ): PluginBinaryStagingFile {
            val file = try {
                File.createTempFile("bridge-", ".part", directory)
            } catch (_: Exception) {
                throw PluginBinaryStagingException(
                    "capability_unavailable",
                    "Unable to create binary staging file",
                )
            }
            return try {
                PluginBinaryStagingFile(
                    transport = declaration.transport,
                    expectedSize = declaration.size,
                    expectedChunks = declaration.chunks,
                    expectedSha256 = declaration.sha256,
                    file = file,
                    output = BufferedOutputStream(
                        FileOutputStream(file),
                        minOf(declaration.transport.chunkBytes, 64 * 1024),
                    ),
                    digest = MessageDigest.getInstance("SHA-256"),
                )
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }
}

private fun Char.isBase64UrlCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'

private fun Char.base64UrlValue(): Int = when (this) {
    in 'A'..'Z' -> code - 'A'.code
    in 'a'..'z' -> code - 'a'.code + 26
    in '0'..'9' -> code - '0'.code + 52
    '-' -> 62
    '_' -> 63
    else -> -1
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
