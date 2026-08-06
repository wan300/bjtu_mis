package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class PluginBinaryStagingTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun arrayBufferUploadStreamsMultipleChunksAndVerifiesDigest() {
        val directory = temp.newFolder("arraybuffer")
        val bytes = ByteArray(PluginBinaryTransport.ArrayBuffer.chunkBytes + 7) { index ->
            (index % 251).toByte()
        }
        val manager = manager(directory)
        val declaration = declaration(PluginBinaryTransport.ArrayBuffer, bytes)

        assertTrue(
            manager.begin("request-1", "owner", declaration, bytes.size.toLong()) is
                PluginBinaryStagingResult.Pending,
        )
        val first = manager.receiveArrayBuffer(
            "request-1",
            0,
            false,
            bytes.copyOfRange(0, PluginBinaryTransport.ArrayBuffer.chunkBytes),
        )
        assertTrue(first is PluginBinaryStagingResult.Pending)
        val complete = manager.receiveArrayBuffer(
            "request-1",
            1,
            true,
            bytes.copyOfRange(PluginBinaryTransport.ArrayBuffer.chunkBytes, bytes.size),
        ) as PluginBinaryStagingResult.Complete

        assertArrayEquals(bytes, complete.payload.openInputStream().use { it.readBytes() })
        complete.payload.close()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun compatibilityUploadUses48KiBChunksAndAcknowledgesEachAcceptedChunk() {
        val directory = temp.newFolder("base64url")
        val bytes = ByteArray(PluginBinaryTransport.Base64UrlChunksV1.chunkBytes + 1) { index ->
            index.toByte()
        }
        val manager = manager(directory)
        manager.begin(
            "request-2",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, bytes),
            bytes.size.toLong(),
        )

        val first = manager.receiveBase64Url(
            "request-2",
            0,
            false,
            encode(bytes.copyOfRange(0, PluginBinaryTransport.Base64UrlChunksV1.chunkBytes)),
        )
        assertTrue(first is PluginBinaryStagingResult.Pending)
        assertTrue(first.acknowledgeChunk)
        assertEquals(0, first.acceptedChunkIndex)
        val complete = manager.receiveBase64Url(
            "request-2",
            1,
            true,
            encode(bytes.copyOfRange(PluginBinaryTransport.Base64UrlChunksV1.chunkBytes, bytes.size)),
        ) as PluginBinaryStagingResult.Complete
        assertTrue(complete.acknowledgeChunk)
        assertEquals(1, complete.acceptedChunkIndex)
        assertArrayEquals(bytes, complete.payload.openInputStream().use { it.readBytes() })
        complete.payload.close()
    }

    @Test
    fun emptyPayloadRequiresTheEmptyDigestAndCreatesNoPendingRequest() {
        val directory = temp.newFolder("empty")
        val manager = manager(directory)
        val complete = manager.begin(
            "empty",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, ByteArray(0)),
            1,
        ) as PluginBinaryStagingResult.Complete

        assertEquals(0L, complete.payload.size)
        assertEquals(0, manager.pendingCount())
        complete.payload.close()
    }

    @Test
    fun rejectsMalformedBase64OrderingDuplicatesSizesCountsAndDigest() {
        assertRejectedAndCleaned("padding") { manager, bytes ->
            manager.receiveBase64Url("request", 0, true, encode(bytes) + "=")
        }
        assertRejectedAndCleaned("alphabet") { manager, bytes ->
            manager.receiveBase64Url("request", 0, true, encode(bytes).dropLast(1) + "+")
        }
        assertRejectedAndCleaned("out-of-order") { manager, bytes ->
            manager.receiveBase64Url("request", 1, true, encode(bytes))
        }
        assertRejectedAndCleaned("wrong-size") { manager, bytes ->
            manager.receiveBase64Url("request", 0, true, encode(bytes + byteArrayOf(4)))
        }

        val duplicateDirectory = temp.newFolder("duplicate")
        val duplicateManager = manager(duplicateDirectory)
        val twoChunks = ByteArray(PluginBinaryTransport.Base64UrlChunksV1.chunkBytes + 1)
        duplicateManager.begin(
            "request",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, twoChunks),
            twoChunks.size.toLong(),
        )
        duplicateManager.receiveBase64Url(
            "request",
            0,
            false,
            encode(twoChunks.copyOfRange(0, PluginBinaryTransport.Base64UrlChunksV1.chunkBytes)),
        )
        assertThrows(PluginBinaryStagingException::class.java) {
            duplicateManager.receiveBase64Url(
                "request",
                0,
                false,
                encode(twoChunks.copyOfRange(0, PluginBinaryTransport.Base64UrlChunksV1.chunkBytes)),
            )
        }
        assertTrue(duplicateDirectory.listFiles().orEmpty().isEmpty())

        val countDirectory = temp.newFolder("count")
        assertThrows(PluginBinaryStagingException::class.java) {
            manager(countDirectory).begin(
                "request",
                "owner",
                declaration(PluginBinaryTransport.Base64UrlChunksV1, byteArrayOf(1)).copy(chunks = 2),
                10,
            )
        }

        val digestDirectory = temp.newFolder("digest")
        val digestManager = manager(digestDirectory)
        val digestBytes = byteArrayOf(1, 2, 3)
        digestManager.begin(
            "request",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, digestBytes).copy(
                sha256 = "0".repeat(64),
            ),
            10,
        )
        assertThrows(PluginBinaryStagingException::class.java) {
            digestManager.receiveBase64Url("request", 0, true, encode(digestBytes))
        }
        assertTrue(digestDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsNonCanonicalTrailingBitsAndTransportMismatch() {
        val trailingBitsDirectory = temp.newFolder("trailing-bits")
        val trailingBitsManager = manager(trailingBitsDirectory)
        val oneByte = byteArrayOf(0)
        trailingBitsManager.begin(
            "request",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, oneByte),
            10,
        )
        assertThrows(PluginBinaryStagingException::class.java) {
            // AA is canonical for a zero byte; AB decodes to the same byte only
            // if a decoder ignores the non-zero unused trailing bits.
            trailingBitsManager.receiveBase64Url("request", 0, true, "AB")
        }
        assertTrue(trailingBitsDirectory.listFiles().orEmpty().isEmpty())

        val mismatchDirectory = temp.newFolder("transport-mismatch")
        val mismatchManager = manager(mismatchDirectory)
        mismatchManager.begin(
            "request",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, oneByte),
            10,
        )
        assertThrows(PluginBinaryStagingException::class.java) {
            mismatchManager.receiveArrayBuffer("request", 0, true, oneByte)
        }
        assertTrue(mismatchDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsFifthConcurrentRequestAndUnsafeDiskReservation() {
        val directory = temp.newFolder("concurrency")
        val manager = manager(directory)
        repeat(4) { index ->
            manager.begin(
                "request-$index",
                "owner-$index",
                declaration(PluginBinaryTransport.ArrayBuffer, byteArrayOf(index.toByte())),
                10,
            )
        }
        val fifth = assertThrows(PluginBinaryStagingException::class.java) {
            manager.begin(
                "request-5",
                "owner-5",
                declaration(PluginBinaryTransport.ArrayBuffer, byteArrayOf(5)),
                10,
            )
        }
        assertEquals("quota_exceeded", fifth.code)
        manager.close()
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        val diskDirectory = temp.newFolder("disk")
        val diskError = assertThrows(PluginBinaryStagingException::class.java) {
            PluginBinaryStagingManager<String>(
                directory = diskDirectory,
                safetyBytes = 64,
                usableSpace = { 64 },
            ).begin(
                "request",
                "owner",
                declaration(PluginBinaryTransport.ArrayBuffer, byteArrayOf(1)),
                10,
            )
        }
        assertEquals("quota_exceeded", diskError.code)
    }

    @Test
    fun cancellationAndProcessStartupCleanupDeleteStagingFiles() {
        val directory = temp.newFolder("cleanup")
        val manager = manager(directory)
        manager.begin(
            "cancelled",
            "owner",
            declaration(PluginBinaryTransport.ArrayBuffer, byteArrayOf(1, 2, 3)),
            10,
        )
        assertTrue(directory.listFiles().orEmpty().isNotEmpty())
        assertTrue(manager.cancel("cancelled"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        val orphanDirectory = File(directory, "plugin").apply { mkdirs() }
        File(orphanDirectory, "bridge-orphan.part").writeBytes(byteArrayOf(1))
        File(orphanDirectory, "keep.txt").writeText("keep")
        PluginBinaryStagingManager.cleanupOrphans(directory)
        assertFalse(File(orphanDirectory, "bridge-orphan.part").exists())
        assertTrue(File(orphanDirectory, "keep.txt").exists())
    }

    private fun assertRejectedAndCleaned(
        name: String,
        action: (PluginBinaryStagingManager<String>, ByteArray) -> Unit,
    ) {
        val directory = temp.newFolder(name)
        val bytes = byteArrayOf(1, 2, 3)
        val manager = manager(directory)
        manager.begin(
            "request",
            "owner",
            declaration(PluginBinaryTransport.Base64UrlChunksV1, bytes),
            10,
        )
        assertThrows(PluginBinaryStagingException::class.java) { action(manager, bytes) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun manager(directory: File) = PluginBinaryStagingManager<String>(
        directory = directory,
        safetyBytes = 0,
        usableSpace = { Long.MAX_VALUE },
    )

    private fun declaration(
        transport: PluginBinaryTransport,
        bytes: ByteArray,
    ) = PluginBinaryDeclaration(
        transport = transport,
        size = bytes.size.toLong(),
        chunks = if (bytes.isEmpty()) 0 else
            (bytes.size + transport.chunkBytes - 1) / transport.chunkBytes,
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
    )

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
