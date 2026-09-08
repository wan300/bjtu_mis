package cn.edu.bjtu.mis.data.sync

import kotlinx.serialization.Serializable
import java.util.UUID

const val PLUGIN_KEEP_ALIVE_CAPABILITY = "android.session.keepAlive@1"

@Serializable
data class KeepAliveOwner(val publisherSubjectId: String, val pluginId: String)

@Serializable
data class PluginKeepAliveLease(
    val leaseId: String,
    val owner: KeepAliveOwner,
    val version: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val maxExpiresAtMs: Long,
)

@Serializable
data class KeepAliveBudget(val owner: KeepAliveOwner, val atMs: Long, val reservedMs: Long)

@Serializable
data class KeepAliveCommand(
    val owner: KeepAliveOwner,
    val key: String,
    val digest: String,
    val atMs: Long,
    val result: KeepAliveCommandResult,
)

@Serializable
data class KeepAliveCommandResult(
    val receiptId: String,
    val completedAtMs: Long = 0,
    val lease: PluginKeepAliveLease? = null,
    val released: Boolean = false,
)

@Serializable
data class KeepAliveSnapshot(
    val bootId: String = "",
    val elapsedMs: Long = 0,
    val wallMs: Long = 0,
    val leases: List<PluginKeepAliveLease> = emptyList(),
    val budget: List<KeepAliveBudget> = emptyList(),
    val commands: List<KeepAliveCommand> = emptyList(),
)

class KeepAliveRejected(val code: String) : IllegalStateException(code)

/** One transaction persists leases, reservation budget and idempotency receipts together. */
class SessionKeepAliveController(
    private val load: () -> KeepAliveSnapshot,
    private val save: (KeepAliveSnapshot) -> Unit,
    private val wall: () -> Long,
    private val elapsed: () -> Long,
    private val bootId: String,
) {
    private var blocked = false
    private var state = try { load() } catch (_: Exception) {
        blocked = true
        KeepAliveSnapshot()
    }
    private val internal = KeepAliveLeaseRegistry()
    private val validated = mutableSetOf<String>()
    private val ended = mutableListOf<Pair<PluginKeepAliveLease, String>>()

    init { synchronized(this) { if (!blocked) runCatching { expire() } } }

    @Synchronized fun acquireInternal(token: String, reason: String) = internal.acquire(token, reason)
    @Synchronized fun releaseInternal(token: String) = internal.release(token)
    @Synchronized fun internalReasons(): List<String> = internal.snapshot().map { it.reason }.distinct()
    @Synchronized fun isActive(): Boolean = internal.isActive() || activeLeases().isNotEmpty()

    @Synchronized fun validate(leaseId: String) { if (state.leases.any { it.leaseId == leaseId }) validated += leaseId }
    @Synchronized fun activeLeases(): List<PluginKeepAliveLease> = leases().filter { it.leaseId in validated }

    @Synchronized fun leases(): List<PluginKeepAliveLease> {
        if (blocked) return emptyList()
        expire()
        return state.leases.toList()
    }

    @Synchronized fun drainEnded(): List<Pair<PluginKeepAliveLease, String>> =
        ended.toList().also { ended.clear() }

    @Synchronized fun command(
        owner: KeepAliveOwner,
        version: String,
        key: String,
        digest: String,
        method: String,
        durationMs: Long = 0,
        leaseId: String? = null,
        beforeAcquire: () -> Unit = {},
    ): KeepAliveCommandResult {
        if (blocked) throw KeepAliveRejected("capability_unavailable")
        expire()
        require(key.length in 1..160)
        state.commands.firstOrNull { it.owner == owner && it.key == key }?.let {
            if (it.digest != digest) throw KeepAliveRejected("idempotency_conflict")
            return it.result
        }
        val now = wall()
        val old = state.leases.firstOrNull { it.owner == owner && it.leaseId == leaseId }
        val result: KeepAliveCommandResult
        var next = state
        if (method == "release") {
            result = KeepAliveCommandResult(UUID.randomUUID().toString(), completedAtMs = now, released = old != null)
            next = next.copy(leases = next.leases.filterNot { it == old })
        } else {
            if (method !in setOf("acquire", "renew") || durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
                throw KeepAliveRejected("invalid_request")
            }
            if (method == "renew" && old == null) throw KeepAliveRejected("lease_not_found")
            if (old != null && old.version != version) throw KeepAliveRejected("permission_denied")
            if (method == "acquire" && state.leases.count { it.owner == owner } >= 2) {
                throw KeepAliveRejected("quota_exceeded")
            }
            if (method == "acquire" && state.leases.map { it.owner }.distinct().size >= 4 &&
                state.leases.none { it.owner == owner }) throw KeepAliveRejected("quota_exceeded")
            val maximum = old?.maxExpiresAtMs ?: (now + MAX_DURATION_MS)
            val end = now + durationMs
            if (end > maximum) throw KeepAliveRejected("invalid_request")
            val reserved = if (old == null) durationMs else (end - old.expiresAtMs).coerceAtLeast(0)
            val budget = state.budget.filter { it.owner == owner }
            if (budget.count { now - it.atMs < 60_000 } >= 6 ||
                budget.sumOf { it.reservedMs } + reserved > MAX_DURATION_MS) {
                throw KeepAliveRejected("quota_exceeded")
            }
            beforeAcquire()
            val lease = old?.copy(expiresAtMs = maxOf(old.expiresAtMs, end)) ?: PluginKeepAliveLease(
                UUID.randomUUID().toString(), owner, version, now, end, maximum,
            )
            result = KeepAliveCommandResult(UUID.randomUUID().toString(), now, lease)
            next = next.copy(
                leases = next.leases.filterNot { it == old } + lease,
                budget = next.budget + KeepAliveBudget(owner, now, reserved),
            )
        }
        val receipt = KeepAliveCommand(owner, key, digest, now, result)
        val owned = next.commands.filter { it.owner == owner }.takeLast(1023) + receipt
        commit(next.copy(commands = next.commands.filterNot { it.owner == owner } + owned))

        if (method != "release") result.lease?.let { validated += it.leaseId }
        if (method == "release" && old != null) ended += old to "released"
        return result
    }

    @Synchronized fun revoke(owner: KeepAliveOwner, reason: String = "revoked") {
        remove(state.leases.filter { it.owner == owner }, reason)
    }

    @Synchronized fun stopPlugins(reason: String) = remove(state.leases, reason)

    @Synchronized fun stopAll(reason: String) {
        internal.clear()
        stopPlugins(reason)
    }

    private fun expire() {
        val now = wall()
        val tick = elapsed()
        val invalidClock = state.bootId.isNotEmpty() && (
            state.bootId != bootId || tick < state.elapsedMs || now < state.wallMs ||
                kotlin.math.abs((now - state.wallMs) - (tick - state.elapsedMs)) > 60_000
            )
        val expired = state.leases.filter { invalidClock || it.expiresAtMs <= now }
        val next = state.copy(
            bootId = bootId, elapsedMs = tick, wallMs = now,
            leases = state.leases - expired.toSet(),
            budget = state.budget.filter { now - it.atMs < MAX_DURATION_MS },
            commands = state.commands.filter { now - it.atMs < RECEIPT_RETENTION_MS },
        )
        // Persist only semantic changes; frequent status checks must not write to disk.
        if (expired.isNotEmpty() || next.budget != state.budget || next.commands != state.commands ||
            state.bootId != bootId) commit(next) else state = next
        expired.forEach { ended += it to if (invalidClock) "clock_changed" else "expired" }
    }

    private fun remove(leases: List<PluginKeepAliveLease>, reason: String) {
        if (leases.isEmpty()) return
        commit(state.copy(leases = state.leases - leases.toSet()))
        leases.forEach { ended += it to reason }
    }

    private fun commit(next: KeepAliveSnapshot) {
        try {
            save(next)
            state = next
        } catch (error: Exception) {
            blocked = true // No stale persisted lease is allowed to run in this process.
            throw error
        }
    }

    companion object {
        const val MIN_DURATION_MS = 60_000L
        const val MAX_DURATION_MS = 3_600_000L
        const val RECEIPT_RETENTION_MS = 7 * 24 * 3_600_000L
    }
}

