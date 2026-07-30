package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val PLUGIN_COMMAND_RECEIPT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
const val PLUGIN_COMMAND_RECEIPT_LIMIT = 1024

class PluginIdempotencyConflict :
    ThirdPartyServiceException("The idempotency key was already used for a different request")

interface PluginCommandReceiptStore {
    suspend fun executeOnce(
        namespace: ThirdPartyKvNamespace,
        idempotencyKey: String,
        requestDigest: String,
        action: suspend () -> JsonElement,
    ): JsonElement

    suspend fun deleteNamespace(namespace: ThirdPartyKvNamespace)
}

class FilePluginCommandReceiptStore(
    root: File,
    private val cipher: ThirdPartyKvCipher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PluginCommandReceiptStore {
    constructor(context: android.content.Context) : this(
        root = File(context.filesDir, "third-party-command-receipts"),
        cipher = AndroidKeystoreThirdPartyKvCipher("bjtu_mis_third_party_command_receipt_key"),
    )

    private val root = root.absoluteFile
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun executeOnce(
        namespace: ThirdPartyKvNamespace,
        idempotencyKey: String,
        requestDigest: String,
        action: suspend () -> JsonElement,
    ): JsonElement = withContext(Dispatchers.IO) {
        validateKey(idempotencyKey)
        require(requestDigest.matches(Regex("^[a-f0-9]{64}$"))) { "Invalid request digest" }
        locks.getOrPut(namespace.identity) { Mutex() }.withLock {
            val now = nowMillis()
            val current = read(namespace).records
                .filterValues { now - it.createdAt <= PLUGIN_COMMAND_RECEIPT_RETENTION_MS }
                .toMutableMap()
            current[idempotencyKey]?.let { existing ->
                if (existing.requestDigest != requestDigest) throw PluginIdempotencyConflict()
                return@withLock existing.receipt
            }
            val receipt = action()
            current[idempotencyKey] = PluginCommandReceipt(
                requestDigest = requestDigest,
                receipt = receipt,
                createdAt = now,
            )
            val bounded = current.entries
                .sortedWith(compareByDescending<Map.Entry<String, PluginCommandReceipt>> {
                    it.value.createdAt
                }.thenBy { it.key })
                .take(PLUGIN_COMMAND_RECEIPT_LIMIT)
                .associateTo(sortedMapOf()) { it.key to it.value }
            write(namespace, PluginCommandReceiptDocument(bounded))
            receipt
        }
    }

    override suspend fun deleteNamespace(namespace: ThirdPartyKvNamespace): Unit =
        withContext(Dispatchers.IO) {
            locks.getOrPut(namespace.identity) { Mutex() }.withLock {
                val directory = requireNotNull(file(namespace).parentFile)
                if (directory.exists() && !directory.deleteRecursively()) {
                    throw ThirdPartyServiceException("Unable to delete command receipts")
                }
                locks.remove(namespace.identity)
                Unit
            }
        }

    private fun read(namespace: ThirdPartyKvNamespace): PluginCommandReceiptDocument {
        val file = file(namespace)
        if (!file.isFile) return PluginCommandReceiptDocument()
        return try {
            val plaintext = cipher.decrypt(file.readBytes(), associatedData(namespace))
            AppJson.decodeFromString(plaintext.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw ThirdPartyServiceException("Command receipt store is damaged", error)
        }
    }

    private fun write(
        namespace: ThirdPartyKvNamespace,
        document: PluginCommandReceiptDocument,
    ) {
        val target = file(namespace)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".receipts-${UUID.randomUUID()}.tmp")
        try {
            val payload = cipher.encrypt(
                AppJson.encodeToString(document).toByteArray(Charsets.UTF_8),
                associatedData(namespace),
            )
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
            throw ThirdPartyServiceException("Unable to atomically save command receipt", error)
        }
    }

    private fun file(namespace: ThirdPartyKvNamespace): File {
        root.mkdirs()
        val directory = File(root, sha256(namespace.identity)).canonicalFile
        if (!directory.path.startsWith(root.canonicalFile.path + File.separator)) {
            throw ThirdPartyServiceException("Command receipt namespace escapes storage root")
        }
        return File(directory, "receipts.bin")
    }

    private fun associatedData(namespace: ThirdPartyKvNamespace): ByteArray =
        "bjtu-mis-command-receipts-v1\u0000${namespace.identity}".toByteArray(Charsets.UTF_8)

    private fun validateKey(value: String) {
        if (value.isBlank() || value.length > 128 || value.any { it.code < 0x20 }) {
            throw ThirdPartyServiceException("idempotencyKey must be 1-128 printable characters")
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

fun pluginCommandRequestDigest(
    capability: String,
    method: String,
    params: JsonObject,
): String {
    val canonical = canonicalJson(
        JsonObject(
            sortedMapOf(
                "capability" to kotlinx.serialization.json.JsonPrimitive(capability),
                "method" to kotlinx.serialization.json.JsonPrimitive(method),
                "params" to params,
            ),
        ),
    )
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, child) ->
            "${AppJson.encodeToString(key)}:${canonicalJson(child)}"
        }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
    else -> value.toString()
}

@Serializable
private data class PluginCommandReceiptDocument(
    val records: Map<String, PluginCommandReceipt> = emptyMap(),
)

@Serializable
private data class PluginCommandReceipt(
    val requestDigest: String,
    val receipt: JsonElement,
    val createdAt: Long,
)
