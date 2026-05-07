package cn.edu.bjtu.mis.data.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

class BjtuDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val fallbackHosts: Map<String, List<String>> = DEFAULT_FALLBACK_HOSTS,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val normalizedHost = hostname.lowercase()
        val systemFailure = runCatching { delegate.lookup(hostname) }
            .onSuccess { if (it.isNotEmpty()) return it }
            .exceptionOrNull()

        val fallbackAddresses = fallbackHosts[normalizedHost].orEmpty()
        if (fallbackAddresses.isNotEmpty()) {
            val resolved = fallbackAddresses.map { InetAddress.getByName(it) }
            if (resolved.isNotEmpty()) return resolved
        }

        throw systemFailure as? UnknownHostException
            ?: UnknownHostException("Unable to resolve host \"$hostname\"")
    }

    companion object {
        private val DEFAULT_FALLBACK_HOSTS = mapOf(
            "mis.bjtu.edu.cn" to listOf("202.112.154.99"),
            "cas.bjtu.edu.cn" to listOf("59.64.4.74"),
            "aa.bjtu.edu.cn" to listOf("121.194.57.160"),
            "bksy.bjtu.edu.cn" to listOf("121.194.57.133"),
            "bksycenter.bjtu.edu.cn" to listOf("121.194.57.133"),
        )
    }
}
