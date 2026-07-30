package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginCommandReceiptStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sameKeyAndDigestReturnsEncryptedReceiptWithoutRepeatingAction() = runBlocking {
        val root = temp.newFolder("receipts")
        val store = FilePluginCommandReceiptStore(root, ResourceTestReceiptCipher)
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        val params = buildJsonObject {
            put("idempotencyKey", "request-1")
            put("subject", "secret subject")
        }
        val digest = pluginCommandRequestDigest("mail.send@1", "send", params)
        var calls = 0

        val first = store.executeOnce(namespace, "request-1", digest) {
            calls += 1
            JsonPrimitive("receipt-$calls")
        }
        val second = store.executeOnce(namespace, "request-1", digest) {
            calls += 1
            JsonPrimitive("should-not-run")
        }

        assertEquals(JsonPrimitive("receipt-1"), first)
        assertEquals(first, second)
        assertEquals(1, calls)
        val persisted = root.walkTopDown()
            .filter { it.isFile }
            .joinToString { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(persisted.contains("secret subject"))
        assertFalse(persisted.contains("receipt-1"))
    }

    @Test
    fun reusedKeyWithDifferentDigestFailsBeforeAction() = runBlocking {
        val store = FilePluginCommandReceiptStore(
            temp.newFolder("conflict"),
            ResourceTestReceiptCipher,
        )
        val namespace = ThirdPartyKvNamespace("github-owner:1", "bjtu.demo")
        store.executeOnce(namespace, "request-1", "a".repeat(64)) { JsonPrimitive("ok") }
        var repeated = false

        assertThrows(PluginIdempotencyConflict::class.java) {
            runBlocking {
                store.executeOnce(namespace, "request-1", "b".repeat(64)) {
                    repeated = true
                    JsonPrimitive("bad")
                }
            }
        }
        assertFalse(repeated)
    }
}

private object ResourceTestReceiptCipher : ThirdPartyKvCipher {
    private val delegate = object : ThirdPartyKvCipher {
        private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 23 }, "AES")

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val iv = ByteArray(12) { index -> (index + plaintext.size).toByte() }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                key,
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(associatedData)
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                key,
                javax.crypto.spec.GCMParameterSpec(128, payload.copyOfRange(0, 12)),
            )
            cipher.updateAAD(associatedData)
            return cipher.doFinal(payload.copyOfRange(12, payload.size))
        }
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.encrypt(plaintext, associatedData)

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray =
        delegate.decrypt(payload, associatedData)
}
