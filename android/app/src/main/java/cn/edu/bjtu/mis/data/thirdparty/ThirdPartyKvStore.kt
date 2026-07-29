package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val THIRD_PARTY_KV_TOTAL_BYTES = 10 * 1024 * 1024
const val THIRD_PARTY_KV_ITEM_BYTES = 256 * 1024
const val THIRD_PARTY_KV_MAX_KEYS = 1024

data class ThirdPartyKvNamespace(
    val publisherSubjectId: String,
    val pluginId: String,
) {
    init {
        require(publisherSubjectId.isNotBlank()) { "publisherSubjectId must not be blank" }
        require(pluginId.matches(Regex("^[a-z][a-z0-9_.-]{2,63}$"))) { "Invalid pluginId" }
    }

    internal val identity: String
        get() = "$publisherSubjectId\u0000$pluginId"
}

enum class ThirdPartyKvSpace(internal val fileName: String) {
    Current("current.bin"),
    Shadow("shadow.bin"),
}

data class ThirdPartyKvUsage(
    val bytesUsed: Int,
    val byteLimit: Int = THIRD_PARTY_KV_TOTAL_BYTES,
    val keyCount: Int,
    val keyLimit: Int = THIRD_PARTY_KV_MAX_KEYS,
)

interface ThirdPartyKvStore {
    suspend fun get(namespace: ThirdPartyKvNamespace, key: String, space: ThirdPartyKvSpace = ThirdPartyKvSpace.Current): JsonElement?
    suspend fun set(namespace: ThirdPartyKvNamespace, key: String, value: JsonElement, space: ThirdPartyKvSpace = ThirdPartyKvSpace.Current): ThirdPartyKvUsage
    suspend fun remove(namespace: ThirdPartyKvNamespace, key: String, space: ThirdPartyKvSpace = ThirdPartyKvSpace.Current): Boolean
    suspend fun keys(namespace: ThirdPartyKvNamespace, space: ThirdPartyKvSpace = ThirdPartyKvSpace.Current): List<String>
    suspend fun usage(namespace: ThirdPartyKvNamespace, space: ThirdPartyKvSpace = ThirdPartyKvSpace.Current): ThirdPartyKvUsage
    suspend fun clear(namespace: ThirdPartyKvNamespace, space: ThirdPartyKvSpace = ThirdPartyKvSpace.Current)
    suspend fun snapshot(namespace: ThirdPartyKvNamespace)
    suspend fun restoreSnapshot(namespace: ThirdPartyKvNamespace): Boolean
    suspend fun beginShadow(namespace: ThirdPartyKvNamespace)
    suspend fun commitShadow(namespace: ThirdPartyKvNamespace)
    suspend fun discardShadow(namespace: ThirdPartyKvNamespace)
    suspend fun deleteNamespace(namespace: ThirdPartyKvNamespace)
}

interface ThirdPartyKvCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray
}

class AndroidKeystoreThirdPartyKvCipher(
    private val alias: String = "bjtu_mis_third_party_kv_key",
) : ThirdPartyKvCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray {
        require(payload.size > IV_SIZE) { "Encrypted KV payload is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_SIZE_BITS, payload.copyOfRange(0, IV_SIZE)),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE_BITS = 128
    }
}

class FileThirdPartyKvStore(
    root: File,
    private val cipher: ThirdPartyKvCipher,
) : ThirdPartyKvStore {
    constructor(context: Context) : this(
        root = File(context.filesDir, "third-party-kv"),
        cipher = AndroidKeystoreThirdPartyKvCipher(),
    )

    private val root = root.absoluteFile
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun get(
        namespace: ThirdPartyKvNamespace,
        key: String,
        space: ThirdPartyKvSpace,
    ): JsonElement? = locked(namespace) {
        validateKey(key)
        read(namespace, space).values[key]
    }

    override suspend fun set(
        namespace: ThirdPartyKvNamespace,
        key: String,
        value: JsonElement,
        space: ThirdPartyKvSpace,
    ): ThirdPartyKvUsage = locked(namespace) {
        validateKey(key)
        val itemBytes = AppJson.encodeToString(value).toByteArray(Charsets.UTF_8).size
        if (itemBytes > THIRD_PARTY_KV_ITEM_BYTES) {
            throw ThirdPartyServiceException("app.storage 单项超过 256 KiB 配额")
        }
        val current = read(namespace, space).values.toMutableMap()
        if (key !in current && current.size >= THIRD_PARTY_KV_MAX_KEYS) {
            throw ThirdPartyServiceException("app.storage key 数量超过 1024")
        }
        current[key] = value
        val document = ThirdPartyKvDocument(current.toSortedMap())
        val usage = usageOf(document)
        if (usage.bytesUsed > THIRD_PARTY_KV_TOTAL_BYTES) {
            throw ThirdPartyServiceException("app.storage 总量超过 10 MiB 配额")
        }
        write(namespace, space.fileName, document)
        usage
    }

    override suspend fun remove(
        namespace: ThirdPartyKvNamespace,
        key: String,
        space: ThirdPartyKvSpace,
    ): Boolean = locked(namespace) {
        validateKey(key)
        val current = read(namespace, space).values.toMutableMap()
        val removed = current.remove(key) != null
        if (removed) write(namespace, space.fileName, ThirdPartyKvDocument(current.toSortedMap()))
        removed
    }

    override suspend fun keys(
        namespace: ThirdPartyKvNamespace,
        space: ThirdPartyKvSpace,
    ): List<String> = locked(namespace) {
        read(namespace, space).values.keys.sorted()
    }

    override suspend fun usage(
        namespace: ThirdPartyKvNamespace,
        space: ThirdPartyKvSpace,
    ): ThirdPartyKvUsage = locked(namespace) {
        usageOf(read(namespace, space))
    }

    override suspend fun clear(
        namespace: ThirdPartyKvNamespace,
        space: ThirdPartyKvSpace,
    ) = locked(namespace) {
        write(namespace, space.fileName, ThirdPartyKvDocument())
    }

    override suspend fun snapshot(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        write(namespace, SNAPSHOT_FILE, read(namespace, ThirdPartyKvSpace.Current))
    }

    override suspend fun restoreSnapshot(namespace: ThirdPartyKvNamespace): Boolean = locked(namespace) {
        val file = namespaceFile(namespace, SNAPSHOT_FILE)
        if (!file.isFile) return@locked false
        write(namespace, ThirdPartyKvSpace.Current.fileName, readFile(namespace, SNAPSHOT_FILE))
        true
    }

    override suspend fun beginShadow(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        write(namespace, ThirdPartyKvSpace.Shadow.fileName, read(namespace, ThirdPartyKvSpace.Current))
    }

    override suspend fun commitShadow(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        val shadow = namespaceFile(namespace, ThirdPartyKvSpace.Shadow.fileName)
        if (!shadow.isFile) throw ThirdPartyServiceException("迁移影子 KV 不存在")
        val migrated = readFile(namespace, ThirdPartyKvSpace.Shadow.fileName)
        write(namespace, SNAPSHOT_FILE, read(namespace, ThirdPartyKvSpace.Current))
        write(namespace, ThirdPartyKvSpace.Current.fileName, migrated)
        if (!shadow.delete()) throw ThirdPartyServiceException("无法清理已提交的迁移影子 KV")
    }

    override suspend fun discardShadow(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        namespaceFile(namespace, ThirdPartyKvSpace.Shadow.fileName).delete()
        Unit
    }

    override suspend fun deleteNamespace(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        val directory = namespaceDirectory(namespace)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw ThirdPartyServiceException("无法删除插件 app.storage 数据")
        }
    }

    private suspend fun <T> locked(
        namespace: ThirdPartyKvNamespace,
        action: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        locks.getOrPut(namespace.identity) { Mutex() }.withLock { action() }
    }

    private fun read(namespace: ThirdPartyKvNamespace, space: ThirdPartyKvSpace): ThirdPartyKvDocument {
        val file = namespaceFile(namespace, space.fileName)
        return if (file.isFile) readFile(namespace, space.fileName) else ThirdPartyKvDocument()
    }

    private fun readFile(namespace: ThirdPartyKvNamespace, fileName: String): ThirdPartyKvDocument {
        val file = namespaceFile(namespace, fileName)
        return try {
            val plaintext = cipher.decrypt(file.readBytes(), associatedData(namespace, fileName))
            AppJson.decodeFromString(plaintext.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw ThirdPartyServiceException("插件 app.storage 数据损坏或无法解密", error)
        }
    }

    private fun write(
        namespace: ThirdPartyKvNamespace,
        fileName: String,
        document: ThirdPartyKvDocument,
    ) {
        val directory = namespaceDirectory(namespace)
        directory.mkdirs()
        val target = namespaceFile(namespace, fileName)
        val temp = File(directory, ".$fileName.tmp")
        try {
            val plaintext = AppJson.encodeToString(document).toByteArray(Charsets.UTF_8)
            val payload = cipher.encrypt(plaintext, associatedData(namespace, fileName))
            FileOutputStream(temp).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } catch (error: Exception) {
            temp.delete()
            throw ThirdPartyServiceException("无法原子保存插件 app.storage", error)
        }
    }

    private fun usageOf(document: ThirdPartyKvDocument): ThirdPartyKvUsage =
        ThirdPartyKvUsage(
            bytesUsed = AppJson.encodeToString(document).toByteArray(Charsets.UTF_8).size,
            keyCount = document.values.size,
        )

    private fun validateKey(key: String) {
        if (key.isBlank() || key.length > 256 || key.any { it.code < 0x20 }) {
            throw ThirdPartyServiceException("app.storage key 必须为 1-256 个可打印字符")
        }
    }

    private fun namespaceDirectory(namespace: ThirdPartyKvNamespace): File =
        File(root, sha256(namespace.identity)).absoluteFile.also { directory ->
            val rootPath = root.canonicalFile
            val candidate = directory.canonicalFile
            if (!candidate.path.startsWith(rootPath.path + File.separator)) {
                throw ThirdPartyServiceException("插件 app.storage namespace 越界")
            }
        }

    private fun namespaceFile(namespace: ThirdPartyKvNamespace, fileName: String): File =
        File(namespaceDirectory(namespace), fileName)

    private fun associatedData(namespace: ThirdPartyKvNamespace, fileName: String): ByteArray =
        "bjtu-mis-third-party-kv-v1\u0000${namespace.identity}\u0000$fileName".toByteArray(Charsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SNAPSHOT_FILE = "snapshot.bin"
    }
}

@Serializable
private data class ThirdPartyKvDocument(
    val values: Map<String, JsonElement> = emptyMap(),
)
