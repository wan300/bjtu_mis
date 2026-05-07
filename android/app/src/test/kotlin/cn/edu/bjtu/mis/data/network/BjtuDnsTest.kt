package cn.edu.bjtu.mis.data.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class BjtuDnsTest {
    @Test
    fun usesDelegateResultWhenSystemDnsSucceeds() {
        val expected = listOf(InetAddress.getByName("127.0.0.1"))
        val dns = BjtuDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = expected
            },
        )

        assertSame(expected, dns.lookup("mis.bjtu.edu.cn"))
    }

    @Test
    fun usesFallbackAddressWhenSystemDnsFails() {
        val dns = BjtuDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    throw UnknownHostException(hostname)
                }
            },
            fallbackHosts = mapOf("mis.bjtu.edu.cn" to listOf("202.112.154.99")),
        )

        assertEquals("202.112.154.99", dns.lookup("mis.bjtu.edu.cn").single().hostAddress)
    }

    @Test(expected = UnknownHostException::class)
    fun rethrowsWhenNoFallbackExists() {
        val dns = BjtuDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    throw UnknownHostException(hostname)
                }
            },
            fallbackHosts = emptyMap(),
        )

        dns.lookup("example.invalid")
    }
}
