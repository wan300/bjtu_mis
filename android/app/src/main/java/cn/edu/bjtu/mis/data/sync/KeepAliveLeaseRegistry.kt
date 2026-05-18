package cn.edu.bjtu.mis.data.sync

internal data class KeepAliveLease(
    val token: String,
    val reason: String,
)

internal class KeepAliveLeaseRegistry {
    private val leases = linkedMapOf<String, KeepAliveLease>()

    @Synchronized
    fun acquire(token: String, reason: String) {
        leases[token] = KeepAliveLease(token = token, reason = reason)
    }

    @Synchronized
    fun release(token: String): Boolean =
        leases.remove(token) != null

    @Synchronized
    fun clear() {
        leases.clear()
    }

    @Synchronized
    fun isActive(): Boolean = leases.isNotEmpty()

    @Synchronized
    fun contains(token: String): Boolean = leases.containsKey(token)

    @Synchronized
    fun activeCount(): Int = leases.size

    @Synchronized
    fun snapshot(): List<KeepAliveLease> = leases.values.toList()
}
