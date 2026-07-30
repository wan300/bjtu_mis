package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ThirdPartyKvStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun isolatesNamespacesAndDoesNotPersistPlaintext() = runBlocking {
        val root = temp.newFolder("kv")
        val store = FileThirdPartyKvStore(root, TestKvCipher())
        val alice = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val bob = ThirdPartyKvNamespace("github-owner:2", "bjtu.demo")

        store.set(alice, "token", JsonPrimitive("alice-secret"))
        store.set(bob, "token", JsonPrimitive("bob-secret"))

        assertEquals("alice-secret", (store.get(alice, "token") as JsonPrimitive).content)
        assertEquals("bob-secret", (store.get(bob, "token") as JsonPrimitive).content)
        val persisted = root.walkTopDown()
            .filter { it.isFile }
            .flatMap { it.readBytes().asSequence() }
            .toList()
            .toByteArray()
            .toString(Charsets.ISO_8859_1)
        assertFalse(persisted.contains("alice-secret"))
        assertFalse(persisted.contains("bob-secret"))
    }

    @Test
    fun enforcesItemQuotaWithoutChangingExistingValue() = runBlocking {
        val cipher = ToggleKvCipher()
        val store = FileThirdPartyKvStore(temp.newFolder("quota"), cipher)
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        store.set(namespace, "key", JsonPrimitive("before"))

        assertThrows(ThirdPartyServiceException::class.java) {
            runBlocking {
                store.set(namespace, "too-large", JsonPrimitive("x".repeat(THIRD_PARTY_KV_ITEM_BYTES)))
            }
        }
        cipher.failWrites = true
        assertThrows(ThirdPartyServiceException::class.java) {
            runBlocking { store.set(namespace, "key", JsonPrimitive("after")) }
        }
        cipher.failWrites = false
        assertEquals("before", (store.get(namespace, "key") as JsonPrimitive).content)
    }

    @Test
    fun shadowCommitAndSnapshotRestoreAreTransactional() = runBlocking {
        val store = FileThirdPartyKvStore(temp.newFolder("migration"), TestKvCipher())
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        store.set(namespace, "schema", JsonPrimitive(1))
        store.beginShadow(namespace)
        store.set(namespace, "schema", JsonPrimitive(2), ThirdPartyKvSpace.Shadow)
        store.set(namespace, "migrated", JsonPrimitive(true), ThirdPartyKvSpace.Shadow)

        assertEquals("1", (store.get(namespace, "schema") as JsonPrimitive).content)
        store.commitShadow(namespace)
        assertEquals("2", (store.get(namespace, "schema") as JsonPrimitive).content)
        assertTrue((store.get(namespace, "migrated") as JsonPrimitive).content.toBoolean())

        assertTrue(store.restoreSnapshot(namespace))
        assertEquals("1", (store.get(namespace, "schema") as JsonPrimitive).content)
        assertNull(store.get(namespace, "migrated"))
    }

    @Test
    fun concurrentWritesPreserveAllKeys() = runBlocking {
        val store = FileThirdPartyKvStore(temp.newFolder("concurrent"), TestKvCipher())
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")

        (0 until 40).map { index ->
            async { store.set(namespace, "key-$index", JsonPrimitive(index)) }
        }.awaitAll()

        assertEquals(40, store.keys(namespace).size)
        assertEquals(40, store.usage(namespace).keyCount)
    }

    @Test
    fun transactionUsesCasAndPublishesOneAtomicWatchEvent() = runBlocking {
        val store = FileThirdPartyKvStore(temp.newFolder("transaction"), TestKvCipher())
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            store.watch(namespace).first()
        }

        val result = store.transact(
            namespace = namespace,
            expectedRevision = 0,
            mutations = listOf(
                ThirdPartyKvMutation.Set("a", JsonPrimitive(1)),
                ThirdPartyKvMutation.Set("b", JsonPrimitive(2)),
            ),
        )

        assertEquals(1, result.revision)
        assertEquals(setOf("a", "b"), result.changedKeys)
        assertEquals(setOf("a", "b"), event.await().changedKeys)
        assertThrows(ThirdPartyKvRevisionConflict::class.java) {
            runBlocking {
                store.transact(
                    namespace,
                    expectedRevision = 0,
                    mutations = listOf(ThirdPartyKvMutation.Remove("a")),
                )
            }
        }
        assertEquals(JsonPrimitive(1), store.get(namespace, "a"))
        assertEquals(JsonPrimitive(2), store.get(namespace, "b"))
    }

    @Test
    fun clearPlusMaximumKeysSupportsAtomicImportAndExport() = runBlocking {
        val store = FileThirdPartyKvStore(temp.newFolder("import-export"), TestKvCipher())
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        store.set(namespace, "old", JsonPrimitive("remove-me"))

        val result = store.transact(
            namespace = namespace,
            expectedRevision = 1,
            mutations = buildList {
                add(ThirdPartyKvMutation.Clear)
                repeat(THIRD_PARTY_KV_MAX_KEYS) { index ->
                    add(ThirdPartyKvMutation.Set("key-$index", JsonPrimitive(index)))
                }
            },
        )
        val exported = store.export(namespace)

        assertEquals(2, result.revision)
        assertEquals(THIRD_PARTY_KV_MAX_KEYS, result.usage.keyCount)
        assertEquals(result.revision, exported.revision)
        assertEquals(THIRD_PARTY_KV_MAX_KEYS, exported.values.size)
        assertNull(exported.values["old"])
    }
}

private open class TestKvCipher : ThirdPartyKvCipher {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
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

private class ToggleKvCipher : ThirdPartyKvCipher {
    private val delegate = TestKvCipher()
    var failWrites: Boolean = false

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        if (failWrites) error("simulated encryption failure")
        return delegate.encrypt(plaintext, associatedData)
    }

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.decrypt(payload, associatedData)
}
