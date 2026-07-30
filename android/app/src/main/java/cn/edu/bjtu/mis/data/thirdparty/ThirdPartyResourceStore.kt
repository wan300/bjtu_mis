package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

const val THIRD_PARTY_BLOB_PLUGIN_BYTES = 256L * 1024L * 1024L
const val THIRD_PARTY_BLOB_ITEM_BYTES = 64L * 1024L * 1024L
const val THIRD_PARTY_CACHE_PLUGIN_BYTES = 512L * 1024L * 1024L
const val THIRD_PARTY_CACHE_GLOBAL_BYTES = 1024L * 1024L * 1024L
const val THIRD_PARTY_CACHE_ITEM_BYTES = 250L * 1024L * 1024L
const val THIRD_PARTY_RESOURCE_CHUNK_BYTES = 1024 * 1024
const val THIRD_PARTY_RESOURCE_SAFETY_BYTES = 64L * 1024L * 1024L

data class ThirdPartyResourceLimits(
    val blobPluginBytes: Long = THIRD_PARTY_BLOB_PLUGIN_BYTES,
    val blobItemBytes: Long = THIRD_PARTY_BLOB_ITEM_BYTES,
    val cachePluginBytes: Long = THIRD_PARTY_CACHE_PLUGIN_BYTES,
    val cacheGlobalBytes: Long = THIRD_PARTY_CACHE_GLOBAL_BYTES,
    val cacheItemBytes: Long = THIRD_PARTY_CACHE_ITEM_BYTES,
    val safetyBytes: Long = THIRD_PARTY_RESOURCE_SAFETY_BYTES,
)

enum class ThirdPartyResourceKind(val wireName: String) {
    Blob("blob"),
    Cache("cache"),
}

data class ThirdPartyResourceDescriptor(
    val handle: String,
    val kind: ThirdPartyResourceKind,
    val size: Long,
    val mediaType: String,
    val digestSha256: String,
    val pinned: Boolean,
)

data class ThirdPartyResourceContent(
    val descriptor: ThirdPartyResourceDescriptor,
    val start: Long,
    val endInclusive: Long,
    val input: InputStream,
) {
    val contentLength: Long
        get() = endInclusive - start + 1
}

interface ThirdPartyResourceStore {
    suspend fun putBlob(
        namespace: ThirdPartyKvNamespace,
        input: InputStream,
        mediaType: String,
    ): ThirdPartyResourceDescriptor

    suspend fun putCache(
        namespace: ThirdPartyKvNamespace,
        cacheKey: String,
        input: InputStream,
        mediaType: String,
        pinned: Boolean = false,
    ): ThirdPartyResourceDescriptor

    suspend fun describe(
        namespace: ThirdPartyKvNamespace,
        handle: String,
    ): ThirdPartyResourceDescriptor?

    suspend fun open(
        namespace: ThirdPartyKvNamespace,
        handle: String,
        start: Long = 0,
        endInclusive: Long? = null,
    ): ThirdPartyResourceContent

    suspend fun remove(namespace: ThirdPartyKvNamespace, handle: String): Boolean
    suspend fun pin(namespace: ThirdPartyKvNamespace, handle: String, pinned: Boolean)
    suspend fun matchCache(
        namespace: ThirdPartyKvNamespace,
        cacheKey: String,
    ): ThirdPartyResourceDescriptor?
    suspend fun promoteCache(
        namespace: ThirdPartyKvNamespace,
        handle: String,
        cacheKey: String,
        pinned: Boolean? = null,
    ): ThirdPartyResourceDescriptor
    suspend fun removeCache(namespace: ThirdPartyKvNamespace, cacheKey: String): Boolean
    suspend fun pinCache(namespace: ThirdPartyKvNamespace, cacheKey: String, pinned: Boolean)
    suspend fun usage(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
    ): Long
    suspend fun snapshotBlobIndex(namespace: ThirdPartyKvNamespace)
    suspend fun restoreBlobIndex(namespace: ThirdPartyKvNamespace): Boolean
    suspend fun swapBlobIndexWithSnapshot(namespace: ThirdPartyKvNamespace): Boolean
    suspend fun deleteNamespace(namespace: ThirdPartyKvNamespace)
}

class FileThirdPartyResourceStore(
    root: File,
    private val cipher: ThirdPartyKvCipher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val limits: ThirdPartyResourceLimits = ThirdPartyResourceLimits(),
) : ThirdPartyResourceStore {
    constructor(context: android.content.Context) : this(
        root = File(context.filesDir, "third-party-resources"),
        cipher = AndroidKeystoreThirdPartyKvCipher("bjtu_mis_third_party_resource_key"),
    )

    private val root = root.absoluteFile
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val globalResourceLock = Mutex()

    override suspend fun putBlob(
        namespace: ThirdPartyKvNamespace,
        input: InputStream,
        mediaType: String,
    ): ThirdPartyResourceDescriptor = put(
        namespace = namespace,
        kind = ThirdPartyResourceKind.Blob,
        key = null,
        input = input,
        mediaType = mediaType,
        pinned = true,
        itemLimit = limits.blobItemBytes,
        pluginLimit = limits.blobPluginBytes,
    )

    override suspend fun putCache(
        namespace: ThirdPartyKvNamespace,
        cacheKey: String,
        input: InputStream,
        mediaType: String,
        pinned: Boolean,
    ): ThirdPartyResourceDescriptor {
        requireCacheKey(cacheKey)
        return put(
            namespace = namespace,
            kind = ThirdPartyResourceKind.Cache,
            key = cacheKey,
            input = input,
            mediaType = mediaType,
            pinned = pinned,
            itemLimit = limits.cacheItemBytes,
            pluginLimit = limits.cachePluginBytes,
        )
    }

    override suspend fun describe(
        namespace: ThirdPartyKvNamespace,
        handle: String,
    ): ThirdPartyResourceDescriptor? = locked(namespace) {
        findRecord(namespace, handle)?.record?.toDescriptor()
    }

    override suspend fun open(
        namespace: ThirdPartyKvNamespace,
        handle: String,
        start: Long,
        endInclusive: Long?,
    ): ThirdPartyResourceContent = locked(namespace) {
        val located = findRecord(namespace, handle)
            ?: throw PluginRuntimeException("invalid_request", "Unknown resource handle")
        val record = located.record
        if (record.size == 0L) {
            if (start != 0L || endInclusive != null) {
                throw PluginRuntimeException(
                    "invalid_request",
                    "Empty resources do not support byte ranges",
                )
            }
            return@locked ThirdPartyResourceContent(
                descriptor = record.toDescriptor(),
                start = 0,
                endInclusive = -1,
                input = ByteArrayInputStream(ByteArray(0)),
            )
        }
        val end = endInclusive ?: (record.size - 1)
        if (start < 0 || end < start || end >= record.size) {
            throw PluginRuntimeException("invalid_request", "Invalid resource byte range")
        }
        if (located.kind == ThirdPartyResourceKind.Cache) {
            val index = readIndex(namespace, located.kind)
            val updated = record.copy(lastAccessedAt = nowMillis())
            writeIndex(
                namespace,
                located.kind,
                index.copy(entries = index.entries + (handle to updated)),
            )
        }
        ThirdPartyResourceContent(
            descriptor = record.toDescriptor(),
            start = start,
            endInclusive = end,
            input = EncryptedChunkInputStream(
                chunkDirectory = resourceDirectory(namespace, located.kind, handle),
                chunkCount = record.chunkCount,
                size = record.size,
                start = start,
                endInclusive = end,
                decryptChunk = { index, payload ->
                    cipher.decrypt(
                        payload,
                        chunkAssociatedData(namespace, located.kind, handle, index),
                    )
                },
            ),
        )
    }

    override suspend fun remove(
        namespace: ThirdPartyKvNamespace,
        handle: String,
    ): Boolean = locked(namespace) {
        val located = findRecord(namespace, handle) ?: return@locked false
        val index = readIndex(namespace, located.kind)
        val next = index.copy(entries = index.entries - handle)
        writeIndex(namespace, located.kind, next)
        deleteDirectory(resourceDirectory(namespace, located.kind, handle))
        true
    }

    override suspend fun pin(
        namespace: ThirdPartyKvNamespace,
        handle: String,
        pinned: Boolean,
    ) = locked(namespace) {
        val located = findRecord(namespace, handle)
            ?: throw PluginRuntimeException("invalid_request", "Unknown resource handle")
        if (located.kind != ThirdPartyResourceKind.Cache) {
            throw PluginRuntimeException(
                "invalid_request",
                "Only cache resources can change pin state",
            )
        }
        val index = readIndex(namespace, located.kind)
        writeIndex(
            namespace,
            located.kind,
            index.copy(entries = index.entries + (handle to located.record.copy(pinned = pinned))),
        )
    }

    override suspend fun matchCache(
        namespace: ThirdPartyKvNamespace,
        cacheKey: String,
    ): ThirdPartyResourceDescriptor? = locked(namespace) {
        requireCacheKey(cacheKey)
        val index = readIndex(namespace, ThirdPartyResourceKind.Cache)
        val record = index.entries.values.firstOrNull { it.cacheKey == cacheKey }
            ?: return@locked null
        writeIndex(
            namespace,
            ThirdPartyResourceKind.Cache,
            index.copy(
                entries = index.entries + (
                    record.handle to record.copy(lastAccessedAt = nowMillis())
                    ),
            ),
        )
        record.toDescriptor()
    }

    override suspend fun promoteCache(
        namespace: ThirdPartyKvNamespace,
        handle: String,
        cacheKey: String,
        pinned: Boolean?,
    ): ThirdPartyResourceDescriptor = locked(namespace) {
        requireCacheKey(cacheKey)
        val located = findRecord(namespace, handle)
            ?: throw PluginRuntimeException("invalid_request", "Unknown cache handle")
        if (located.kind != ThirdPartyResourceKind.Cache) {
            throw PluginRuntimeException("invalid_request", "Handle is not a cache resource")
        }
        val index = readIndex(namespace, ThirdPartyResourceKind.Cache)
        val replaced = index.entries.values.firstOrNull {
            it.handle != handle && it.cacheKey == cacheKey
        }
        val updated = located.record.copy(
            cacheKey = cacheKey,
            pinned = pinned ?: located.record.pinned,
            lastAccessedAt = nowMillis(),
        )
        writeIndex(
            namespace,
            ThirdPartyResourceKind.Cache,
            index.copy(
                entries = (
                    index.entries -
                        listOfNotNull(replaced?.handle).toSet() +
                        (handle to updated)
                    ).toSortedMap(),
            ),
        )
        replaced?.let {
            deleteDirectory(
                resourceDirectory(namespace, ThirdPartyResourceKind.Cache, it.handle),
            )
        }
        updated.toDescriptor()
    }

    override suspend fun removeCache(
        namespace: ThirdPartyKvNamespace,
        cacheKey: String,
    ): Boolean = locked(namespace) {
        requireCacheKey(cacheKey)
        val index = readIndex(namespace, ThirdPartyResourceKind.Cache)
        val record = index.entries.values.firstOrNull { it.cacheKey == cacheKey }
            ?: return@locked false
        writeIndex(
            namespace,
            ThirdPartyResourceKind.Cache,
            index.copy(entries = index.entries - record.handle),
        )
        deleteDirectory(resourceDirectory(namespace, ThirdPartyResourceKind.Cache, record.handle))
        true
    }

    override suspend fun pinCache(
        namespace: ThirdPartyKvNamespace,
        cacheKey: String,
        pinned: Boolean,
    ) = locked(namespace) {
        requireCacheKey(cacheKey)
        val index = readIndex(namespace, ThirdPartyResourceKind.Cache)
        val record = index.entries.values.firstOrNull { it.cacheKey == cacheKey }
            ?: throw PluginRuntimeException("invalid_request", "Unknown cache key")
        writeIndex(
            namespace,
            ThirdPartyResourceKind.Cache,
            index.copy(
                entries = index.entries + (record.handle to record.copy(pinned = pinned)),
            ),
        )
    }

    override suspend fun usage(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
    ): Long = locked(namespace) {
        readIndex(namespace, kind).entries.values.sumOf { it.size }
    }

    override suspend fun snapshotBlobIndex(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        val source = indexFile(namespace, ThirdPartyResourceKind.Blob)
        val snapshot = blobSnapshotFile(namespace)
        if (!source.isFile) {
            writeIndex(namespace, ThirdPartyResourceKind.Blob, ThirdPartyResourceIndex())
        }
        atomicCopy(indexFile(namespace, ThirdPartyResourceKind.Blob), snapshot)
    }

    override suspend fun restoreBlobIndex(namespace: ThirdPartyKvNamespace): Boolean = locked(namespace) {
        val snapshot = blobSnapshotFile(namespace)
        if (!snapshot.isFile) return@locked false
        atomicCopy(snapshot, indexFile(namespace, ThirdPartyResourceKind.Blob))
        true
    }

    override suspend fun swapBlobIndexWithSnapshot(
        namespace: ThirdPartyKvNamespace,
    ): Boolean = locked(namespace) {
        val current = indexFile(namespace, ThirdPartyResourceKind.Blob)
        val snapshot = blobSnapshotFile(namespace)
        if (!snapshot.isFile) return@locked false
        if (!current.isFile) {
            writeIndex(namespace, ThirdPartyResourceKind.Blob, ThirdPartyResourceIndex())
        }
        val currentBytes = current.readBytes()
        val snapshotBytes = snapshot.readBytes()
        try {
            atomicWrite(current, snapshotBytes)
            atomicWrite(snapshot, currentBytes)
        } catch (error: Exception) {
            runCatching { atomicWrite(current, currentBytes) }.onFailure(error::addSuppressed)
            runCatching { atomicWrite(snapshot, snapshotBytes) }.onFailure(error::addSuppressed)
            throw error
        }
        true
    }

    override suspend fun deleteNamespace(namespace: ThirdPartyKvNamespace) = locked(namespace) {
        deleteDirectory(namespaceDirectory(namespace))
    }

    private suspend fun put(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
        key: String?,
        input: InputStream,
        mediaType: String,
        pinned: Boolean,
        itemLimit: Long,
        pluginLimit: Long,
    ): ThirdPartyResourceDescriptor = locked(namespace) {
        val normalizedMediaType = mediaType.trim().ifBlank { "application/octet-stream" }
        if (normalizedMediaType.length > 160 || '\r' in normalizedMediaType || '\n' in normalizedMediaType) {
            throw ThirdPartyServiceException("Invalid resource media type")
        }
        val stagingHandle = "stage-${UUID.randomUUID()}"
        val staging = resourceDirectory(namespace, kind, stagingHandle)
        staging.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var chunkCount = 0
        try {
            input.use { source ->
                val buffer = ByteArray(THIRD_PARTY_RESOURCE_CHUNK_BYTES)
                while (true) {
                    val read = source.readChunk(buffer)
                    if (read == 0) break
                    total += read
                    if (total > itemLimit) {
                        throw PluginRuntimeException(
                            "resource_too_large",
                            "Resource exceeds its per-item limit",
                        )
                    }
                    ensureSafeRemainingSpace(read.toLong() + RESOURCE_WRITE_OVERHEAD_BYTES)
                    val plaintext = buffer.copyOf(read)
                    digest.update(plaintext)
                    writeEncryptedChunk(
                        namespace,
                        kind,
                        stagingHandle,
                        staging,
                        chunkCount,
                        plaintext,
                    )
                    chunkCount += 1
                }
            }
            val digestHex = digest.digest().toHex()
            val handle = when (kind) {
                ThirdPartyResourceKind.Blob -> "blob-$digestHex"
                ThirdPartyResourceKind.Cache -> "cache-${sha256(requireNotNull(key))}"
            }
            val index = readIndex(namespace, kind)
            index.entries[handle]?.takeIf {
                kind == ThirdPartyResourceKind.Blob && it.digestSha256 == digestHex
            }?.let { existing ->
                deleteDirectory(staging)
                return@locked existing.toDescriptor()
            }

            var nextEntries = index.entries
            var evictedHandles = emptySet<String>()
            if (kind == ThirdPartyResourceKind.Cache) {
                val sameKeyHandles = nextEntries.values
                    .filter { it.cacheKey == key }
                    .mapTo(linkedSetOf()) { it.handle }
                val candidates = nextEntries - (sameKeyHandles + handle)
                nextEntries = selectCacheEntriesToFit(
                    candidates,
                    incomingBytes = total,
                    pluginLimit = pluginLimit,
                )
                evictedHandles = (index.entries.keys - nextEntries.keys) - handle
                val projectedGlobalBytes =
                    storedCacheChunkBytes() -
                        evictedHandles.sumOf { evicted ->
                            directoryBytes(
                                resourceDirectory(
                                    namespace,
                                    ThirdPartyResourceKind.Cache,
                                    evicted,
                                ),
                            )
                        } +
                        directoryBytes(staging)
                if (projectedGlobalBytes > limits.cacheGlobalBytes) {
                    throw PluginRuntimeException(
                        "quota_exceeded",
                        "Global plugin cache quota was exceeded",
                    )
                }
            } else if (nextEntries.values.sumOf { it.size } + total > pluginLimit) {
                throw PluginRuntimeException(
                    "quota_exceeded",
                    "Plugin resource quota was exceeded",
                )
            }
            // Chunks are re-encrypted with the final content-addressed handle as
            // AES-GCM associated data. Account for that temporary second copy
            // before starting so the device safety reserve is never consumed.
            ensureSafeRemainingSpace(directoryBytes(staging))
            val finalDirectory = resourceDirectory(namespace, kind, handle)
            if (finalDirectory.exists()) deleteDirectory(finalDirectory)
            finalDirectory.mkdirs()
            for (chunkIndex in 0 until chunkCount) {
                val stagePayload = chunkFile(staging, chunkIndex).readBytes()
                val plaintext = cipher.decrypt(
                    stagePayload,
                    chunkAssociatedData(namespace, kind, stagingHandle, chunkIndex),
                )
                writeEncryptedChunk(
                    namespace,
                    kind,
                    handle,
                    finalDirectory,
                    chunkIndex,
                    plaintext,
                )
            }
            val now = nowMillis()
            val record = ThirdPartyResourceRecord(
                handle = handle,
                kind = kind.wireName,
                size = total,
                mediaType = normalizedMediaType,
                digestSha256 = digestHex,
                chunkCount = chunkCount,
                cacheKey = key,
                pinned = pinned,
                createdAt = now,
                lastAccessedAt = now,
            )
            writeIndex(
                namespace,
                kind,
                ThirdPartyResourceIndex(entries = (nextEntries + (handle to record)).toSortedMap()),
            )
            evictedHandles.forEach { evicted ->
                deleteDirectory(resourceDirectory(namespace, ThirdPartyResourceKind.Cache, evicted))
            }
            deleteDirectory(staging)
            record.toDescriptor()
        } catch (error: Exception) {
            runCatching { deleteDirectory(staging) }
            throw error
        }
    }

    private fun selectCacheEntriesToFit(
        initial: Map<String, ThirdPartyResourceRecord>,
        incomingBytes: Long,
        pluginLimit: Long,
    ): Map<String, ThirdPartyResourceRecord> {
        val entries = initial.toMutableMap()
        var used = entries.values.sumOf { it.size }
        entries.values
            .filterNot(ThirdPartyResourceRecord::pinned)
            .sortedWith(compareBy(ThirdPartyResourceRecord::lastAccessedAt, ThirdPartyResourceRecord::handle))
            .forEach { record ->
                if (used + incomingBytes <= pluginLimit) return@forEach
                entries.remove(record.handle)
                used -= record.size
            }
        if (used + incomingBytes > pluginLimit) {
            throw PluginRuntimeException(
                "quota_exceeded",
                "Pinned cache entries leave insufficient quota",
            )
        }
        return entries
    }

    private fun findRecord(
        namespace: ThirdPartyKvNamespace,
        handle: String,
    ): LocatedResource? {
        val kind = when {
            handle.startsWith("blob-") -> ThirdPartyResourceKind.Blob
            handle.startsWith("cache-") -> ThirdPartyResourceKind.Cache
            else -> return null
        }
        return readIndex(namespace, kind).entries[handle]?.let { LocatedResource(kind, it) }
    }

    private fun readIndex(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
    ): ThirdPartyResourceIndex {
        val file = indexFile(namespace, kind)
        if (!file.isFile) return ThirdPartyResourceIndex()
        return try {
            val plaintext = cipher.decrypt(file.readBytes(), indexAssociatedData(namespace, kind))
            AppJson.decodeFromString(plaintext.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw ThirdPartyServiceException("Resource index is damaged or cannot be decrypted", error)
        }
    }

    private fun writeIndex(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
        index: ThirdPartyResourceIndex,
    ) {
        val target = indexFile(namespace, kind)
        target.parentFile?.mkdirs()
        val payload = cipher.encrypt(
            AppJson.encodeToString(index).toByteArray(Charsets.UTF_8),
            indexAssociatedData(namespace, kind),
        )
        atomicWrite(target, payload)
    }

    private fun writeEncryptedChunk(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
        handle: String,
        directory: File,
        index: Int,
        plaintext: ByteArray,
    ) {
        val payload = cipher.encrypt(
            plaintext,
            chunkAssociatedData(namespace, kind, handle, index),
        )
        atomicWrite(chunkFile(directory, index), payload)
    }

    private fun atomicWrite(target: File, payload: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            moveReplacing(temp, target)
        } catch (error: Exception) {
            temp.delete()
            throw ThirdPartyServiceException("Unable to atomically write plugin resource", error)
        }
    }

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            moveReplacing(temp, target)
        } catch (error: Exception) {
            temp.delete()
            throw ThirdPartyServiceException("Unable to atomically snapshot plugin resource index", error)
        }
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private fun ensureSafeRemainingSpace(incomingBytes: Long) {
        root.mkdirs()
        if (root.usableSpace - incomingBytes < limits.safetyBytes) {
            throw PluginRuntimeException(
                "quota_exceeded",
                "Device safety reserve prevents this resource write",
            )
        }
    }

    private fun storedCacheChunkBytes(): Long =
        if (!root.isDirectory) 0 else root.walkTopDown()
            .filter {
                it.isFile &&
                    it.parentFile?.name?.startsWith("cache-") == true &&
                    it.parentFile?.parentFile?.name == ThirdPartyResourceKind.Cache.wireName
            }
            .sumOf(File::length)

    private fun directoryBytes(directory: File): Long =
        if (!directory.isDirectory) 0 else directory.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)

    private suspend fun <T> locked(
        namespace: ThirdPartyKvNamespace,
        action: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        globalResourceLock.withLock {
            locks.getOrPut(namespace.identity) { Mutex() }.withLock { action() }
        }
    }

    private fun namespaceDirectory(namespace: ThirdPartyKvNamespace): File =
        File(root, sha256(namespace.identity)).canonicalFile.safeChildOf(root)

    private fun kindDirectory(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
    ): File = File(namespaceDirectory(namespace), kind.wireName).canonicalFile
        .safeChildOf(namespaceDirectory(namespace))

    private fun indexFile(namespace: ThirdPartyKvNamespace, kind: ThirdPartyResourceKind): File =
        File(kindDirectory(namespace, kind), "index.bin")

    private fun blobSnapshotFile(namespace: ThirdPartyKvNamespace): File =
        File(kindDirectory(namespace, ThirdPartyResourceKind.Blob), "index.snapshot.bin")

    private fun resourceDirectory(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
        handle: String,
    ): File {
        require(handle.matches(Regex("^(?:blob|cache|stage)-[a-zA-Z0-9-]{8,80}$"))) {
            "Invalid resource handle"
        }
        return File(kindDirectory(namespace, kind), handle).canonicalFile
            .safeChildOf(kindDirectory(namespace, kind))
    }

    private fun chunkFile(directory: File, index: Int): File =
        File(directory, "%08d.chunk".format(index)).canonicalFile.safeChildOf(directory)

    private fun indexAssociatedData(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
    ): ByteArray =
        "bjtu-mis-resource-index-v1\u0000${namespace.identity}\u0000${kind.wireName}"
            .toByteArray(Charsets.UTF_8)

    private fun chunkAssociatedData(
        namespace: ThirdPartyKvNamespace,
        kind: ThirdPartyResourceKind,
        handle: String,
        index: Int,
    ): ByteArray =
        (
            "bjtu-mis-resource-chunk-v1\u0000${namespace.identity}\u0000${kind.wireName}" +
                "\u0000$handle\u0000$index"
            ).toByteArray(Charsets.UTF_8)

    private fun requireCacheKey(key: String) {
        if (key.isBlank() || key.length > 512 || key.any { it.code < 0x20 }) {
            throw PluginRuntimeException(
                "invalid_request",
                "cache key must be 1-512 printable characters",
            )
        }
    }

    private fun deleteDirectory(directory: File) {
        val target = directory.canonicalFile
        target.safeChildOf(root)
        if (target.exists() && !target.deleteRecursively()) {
            throw ThirdPartyServiceException("Unable to delete plugin resource")
        }
    }

    private fun File.safeChildOf(parent: File): File = canonicalFile.also { candidate ->
        val safeParent = parent.canonicalFile
        if (candidate != safeParent && !candidate.path.startsWith(safeParent.path + File.separator)) {
            throw ThirdPartyServiceException("Plugin resource path escapes its namespace")
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class LocatedResource(
        val kind: ThirdPartyResourceKind,
        val record: ThirdPartyResourceRecord,
    )

    private companion object {
        const val RESOURCE_WRITE_OVERHEAD_BYTES = 4096L
    }
}

@Serializable
private data class ThirdPartyResourceIndex(
    val entries: Map<String, ThirdPartyResourceRecord> = emptyMap(),
)

@Serializable
private data class ThirdPartyResourceRecord(
    val handle: String,
    val kind: String,
    val size: Long,
    val mediaType: String,
    val digestSha256: String,
    val chunkCount: Int,
    val cacheKey: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long,
    val lastAccessedAt: Long,
) {
    fun toDescriptor(): ThirdPartyResourceDescriptor = ThirdPartyResourceDescriptor(
        handle = handle,
        kind = ThirdPartyResourceKind.entries.first { it.wireName == kind },
        size = size,
        mediaType = mediaType,
        digestSha256 = digestSha256,
        pinned = pinned,
    )
}

private class EncryptedChunkInputStream(
    private val chunkDirectory: File,
    private val chunkCount: Int,
    private val size: Long,
    private val start: Long,
    private val endInclusive: Long,
    private val decryptChunk: (Int, ByteArray) -> ByteArray,
) : InputStream() {
    private var position = start
    private var loadedChunkIndex = -1
    private var current = ByteArrayInputStream(ByteArray(0))

    override fun read(): Int {
        if (position > endInclusive) return -1
        ensureChunk()
        val value = current.read()
        if (value >= 0) position += 1
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position > endInclusive) return -1
        if (length == 0) return 0
        ensureChunk()
        val boundedLength = min(length.toLong(), endInclusive - position + 1).toInt()
        val read = current.read(buffer, offset, boundedLength)
        if (read > 0) position += read
        return read
    }

    private fun ensureChunk() {
        val chunkIndex = (position / THIRD_PARTY_RESOURCE_CHUNK_BYTES).toInt()
        if (chunkIndex !in 0 until chunkCount || position >= size) return
        if (chunkIndex == loadedChunkIndex && current.available() > 0) return
        val chunk = File(chunkDirectory, "%08d.chunk".format(chunkIndex))
        val plaintext = decryptChunk(chunkIndex, chunk.readBytes())
        val offset = (position % THIRD_PARTY_RESOURCE_CHUNK_BYTES).toInt()
        current = ByteArrayInputStream(plaintext, offset, plaintext.size - offset)
        loadedChunkIndex = chunkIndex
    }
}

private fun InputStream.readChunk(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        if (read == 0) continue
        total += read
    }
    return total
}
