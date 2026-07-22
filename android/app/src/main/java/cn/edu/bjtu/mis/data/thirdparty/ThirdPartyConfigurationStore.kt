package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ThirdPartyConfigurationStore {
    fun load(serviceId: String): Map<String, String>
    fun save(serviceId: String, values: Map<String, String>)
    fun remove(serviceId: String)
}

class InMemoryThirdPartyConfigurationStore : ThirdPartyConfigurationStore {
    private val values = mutableMapOf<String, Map<String, String>>()
    @Synchronized override fun load(serviceId: String): Map<String, String> = values[serviceId].orEmpty().toMap()
    @Synchronized override fun save(serviceId: String, values: Map<String, String>) { this.values[serviceId] = values.toMap() }
    @Synchronized override fun remove(serviceId: String) { values.remove(serviceId) }
}

class SecureThirdPartyConfigurationStore(
    context: Context,
    private val alias: String = "bjtu_mis_third_party_configuration_key",
    fileName: String = "third_party_configuration.bin",
) : ThirdPartyConfigurationStore {
    private val file = File(context.filesDir, fileName)

    @Synchronized
    override fun load(serviceId: String): Map<String, String> = readVault().values[serviceId].orEmpty().toMap()

    @Synchronized
    override fun save(serviceId: String, values: Map<String, String>) {
        val current = readVault().values.toMutableMap()
        if (values.isEmpty()) current.remove(serviceId) else current[serviceId] = values.toSortedMap()
        writeVault(ConfigurationVault(current))
    }

    @Synchronized
    override fun remove(serviceId: String) {
        if (!file.exists()) return
        val current = try {
            readVault().values.toMutableMap()
        } catch (_: CorruptConfigurationVaultException) {
            resetCorruptVault()
            return
        }
        if (current.remove(serviceId) != null) writeVault(ConfigurationVault(current))
    }

    private fun resetCorruptVault() {
        if (file.exists() && !file.delete()) {
            throw ThirdPartyServiceException("插件配置已损坏且无法重置，请重启应用后重试")
        }
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun readVault(): ConfigurationVault {
        if (!file.isFile) return ConfigurationVault()
        val payload = file.readBytes()
        if (payload.size <= IV_SIZE) throw CorruptConfigurationVaultException()
        val key = getOrCreateKey()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_SIZE_BITS, payload.copyOfRange(0, IV_SIZE)),
            )
            AppJson.decodeFromString<ConfigurationVault>(
                String(cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size)), Charsets.UTF_8),
            )
        } catch (error: AEADBadTagException) {
            throw CorruptConfigurationVaultException(error)
        } catch (error: SerializationException) {
            throw CorruptConfigurationVaultException(error)
        }
    }

    private fun writeVault(vault: ConfigurationVault) {
        file.parentFile?.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(AppJson.encodeToString(vault).toByteArray(Charsets.UTF_8))
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { output -> output.write(payload); output.flush() }
        runCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            temp.delete()
            throw ThirdPartyServiceException("无法原子保存插件配置", it)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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

private class CorruptConfigurationVaultException(cause: Throwable? = null) : Exception(
    "插件配置无法解密；删除任一受影响插件时将重置损坏的配置存储",
    cause,
)

@Serializable
private data class ConfigurationVault(
    val values: Map<String, Map<String, String>> = emptyMap(),
)

fun mergeThirdPartyConfiguration(
    previousDefinitions: List<ThirdPartyConfigurationDefinition>,
    nextDefinitions: List<ThirdPartyConfigurationDefinition>,
    previousValues: Map<String, String>,
): Map<String, String> {
    val previousByKey = previousDefinitions.associateBy { it.key }
    return nextDefinitions.mapNotNull { next ->
        val previous = previousByKey[next.key]
        next.key.takeIf {
            previous != null && previous.type == next.type &&
                (previous.type == "secret") == (next.type == "secret") &&
                previousValues.containsKey(next.key)
        }?.let { it to previousValues.getValue(it) }
    }.toMap()
}
