package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ThirdPartyResourceStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun bridgeBinaryPayloadStreamsFromAndDeletesPrivateStagingFile() {
        val staging = temp.newFile("bridge.part")
        val bytes = ByteArray(8192) { index -> (index % 251).toByte() }
        staging.writeBytes(bytes)
        val payload = PluginBinaryPayload(bytes.size.toLong(), staging)

        assertArrayEquals(bytes, payload.openInputStream().use { it.readBytes() })
        payload.close()
        assertFalse(staging.exists())
    }

    @Test
    fun blobIsContentAddressedEncryptedIsolatedAndRangeReadable() = runBlocking {
        val root = temp.newFolder("resources")
        val store = FileThirdPartyResourceStore(
            root,
            ResourceTestCipher(),
            limits = ThirdPartyResourceLimits(safetyBytes = 0),
        )
        val alice = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val bob = ThirdPartyKvNamespace("github-owner:2", "bjtu.demo")
        val bytes = ByteArray(THIRD_PARTY_RESOURCE_CHUNK_BYTES + 37) { index ->
            (index % 251).toByte()
        }

        val first = store.putBlob(alice, ByteArrayInputStream(bytes), "application/octet-stream")
        val duplicate = store.putBlob(alice, ByteArrayInputStream(bytes), "application/octet-stream")

        assertEquals(first.handle, duplicate.handle)
        assertEquals(bytes.size.toLong(), store.usage(alice, ThirdPartyResourceKind.Blob))
        assertNull(store.describe(bob, first.handle))
        val rangeStart = THIRD_PARTY_RESOURCE_CHUNK_BYTES - 11L
        val rangeEnd = THIRD_PARTY_RESOURCE_CHUNK_BYTES + 19L
        val range = store.open(alice, first.handle, rangeStart, rangeEnd)
        assertArrayEquals(
            bytes.copyOfRange(rangeStart.toInt(), rangeEnd.toInt() + 1),
            range.input.use { it.readBytes() },
        )
        val plaintextPrefix = bytes.copyOfRange(0, 64)
        assertFalse(
            root.walkTopDown().filter { it.isFile }.any { file ->
                val payload = file.readBytes()
                payload.size >= plaintextPrefix.size &&
                    payload.copyOfRange(0, plaintextPrefix.size).contentEquals(plaintextPrefix)
            },
        )
    }

    @Test
    fun zeroByteResourcesRoundTripWithoutSpecialCaseFailures() = runBlocking {
        val store = FileThirdPartyResourceStore(
            temp.newFolder("empty-resources"),
            ResourceTestCipher(),
            limits = ThirdPartyResourceLimits(safetyBytes = 0),
        )
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")

        val blob = store.putBlob(
            namespace,
            ByteArrayInputStream(ByteArray(0)),
            "application/octet-stream",
        )
        assertEquals(0L, blob.size)
        val content = store.open(namespace, blob.handle)
        assertEquals(0L, content.contentLength)
        assertArrayEquals(ByteArray(0), content.input.use { it.readBytes() })
        val error = assertThrows(PluginRuntimeException::class.java) {
            runBlocking { store.open(namespace, blob.handle, 0, 0) }
        }
        assertEquals("invalid_request", error.code)
    }

    @Test
    fun blobIndexSnapshotRestoresVisibleHandlesAtomically() = runBlocking {
        val store = FileThirdPartyResourceStore(
            temp.newFolder("snapshot"),
            ResourceTestCipher(),
            limits = ThirdPartyResourceLimits(safetyBytes = 0),
        )
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val first = store.putBlob(namespace, ByteArrayInputStream("first".toByteArray()), "text/plain")
        store.snapshotBlobIndex(namespace)
        val second = store.putBlob(namespace, ByteArrayInputStream("second".toByteArray()), "text/plain")

        assertTrue(store.swapBlobIndexWithSnapshot(namespace))
        assertEquals(first, store.describe(namespace, first.handle))
        assertNull(store.describe(namespace, second.handle))
        assertTrue(store.swapBlobIndexWithSnapshot(namespace))
        assertEquals(second, store.describe(namespace, second.handle))
    }

    @Test
    fun cacheUsesLruWhilePinnedEntriesAreNeverAutoEvicted() = runBlocking {
        var clock = 1L
        val store = FileThirdPartyResourceStore(
            root = temp.newFolder("cache"),
            cipher = ResourceTestCipher(),
            nowMillis = { clock++ },
            limits = ThirdPartyResourceLimits(
                cachePluginBytes = 12,
                cacheGlobalBytes = 1_000_000,
                cacheItemBytes = 12,
                safetyBytes = 0,
            ),
        )
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        store.putCache(namespace, "a", ByteArrayInputStream(ByteArray(6) { 1 }), "x/test")
        store.putCache(namespace, "b", ByteArrayInputStream(ByteArray(6) { 2 }), "x/test")
        store.matchCache(namespace, "a")
        store.putCache(namespace, "c", ByteArrayInputStream(ByteArray(6) { 3 }), "x/test")

        assertTrue(store.matchCache(namespace, "a") != null)
        assertNull(store.matchCache(namespace, "b"))
        assertTrue(store.matchCache(namespace, "c") != null)

        store.pinCache(namespace, "a", true)
        val quota = assertThrows(PluginRuntimeException::class.java) {
            runBlocking {
                store.putCache(
                    namespace,
                    "too-large-with-pin",
                    ByteArrayInputStream(ByteArray(8) { 4 }),
                    "x/test",
                )
            }
        }
        assertEquals("quota_exceeded", quota.code)
        assertTrue(store.matchCache(namespace, "a")?.pinned == true)
    }

    @Test
    fun downloadedCacheHandleCanBePromotedAndDeletedByHandle() = runBlocking {
        val store = FileThirdPartyResourceStore(
            temp.newFolder("promoted-cache"),
            ResourceTestCipher(),
            limits = ThirdPartyResourceLimits(safetyBytes = 0),
        )
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val downloaded = store.putCache(
            namespace,
            "network:request-1",
            ByteArrayInputStream("payload".toByteArray()),
            "text/plain",
        )

        val promoted = store.promoteCache(
            namespace,
            downloaded.handle,
            "avatars/current",
            pinned = true,
        )

        assertEquals(downloaded.handle, promoted.handle)
        assertTrue(promoted.pinned)
        assertNull(store.matchCache(namespace, "network:request-1"))
        assertEquals(promoted.handle, store.matchCache(namespace, "avatars/current")?.handle)
        assertTrue(store.remove(namespace, promoted.handle))
        assertNull(store.matchCache(namespace, "avatars/current"))
    }

    @Test
    fun globalCacheQuotaIsAtomicAcrossPublisherNamespaces() = runBlocking {
        val store = FileThirdPartyResourceStore(
            root = temp.newFolder("global-cache"),
            cipher = PassThroughResourceCipher,
            limits = ThirdPartyResourceLimits(
                cachePluginBytes = 100,
                cacheGlobalBytes = 10,
                cacheItemBytes = 100,
                safetyBytes = 0,
            ),
        )
        val alice = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val bob = ThirdPartyKvNamespace("github-owner:2", "bjtu.demo")
        store.putCache(alice, "kept", ByteArrayInputStream(ByteArray(6) { 1 }), "x/test")

        val quota = assertThrows(PluginRuntimeException::class.java) {
            runBlocking {
                store.putCache(bob, "rejected", ByteArrayInputStream(ByteArray(6) { 2 }), "x/test")
            }
        }
        assertEquals("quota_exceeded", quota.code)
        assertTrue(store.matchCache(alice, "kept") != null)
        assertNull(store.matchCache(bob, "rejected"))
        assertEquals(6, store.usage(alice, ThirdPartyResourceKind.Cache))
        assertEquals(0, store.usage(bob, ThirdPartyResourceKind.Cache))
    }
}

private class ResourceTestCipher : ThirdPartyKvCipher {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 17).toByte() }, "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.copyOfRange(12, payload.size))
    }
}

private object PassThroughResourceCipher : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        plaintext.copyOf()

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        payload.copyOf()
}
