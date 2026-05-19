package cn.edu.bjtu.mis.data.network

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AppCookieJar : CookieJar {
    private val lock = ReentrantLock()
    private val cookies = linkedMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        lock.withLock {
            val now = System.currentTimeMillis()
            this.cookies.entries.removeIf { it.value.expiresAt < now }
            for (cookie in cookies) {
                this.cookies[cookie.storageKey()] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = lock.withLock {
        val now = System.currentTimeMillis()
        cookies.entries.removeIf { it.value.expiresAt < now }
        cookies.values.filter { it.matches(url) }
    }

    fun clear() {
        lock.withLock { cookies.clear() }
    }

    fun clearForDomain(domainSuffix: String) {
        val normalized = domainSuffix.trim().trimStart('.').lowercase()
        if (normalized.isBlank()) return
        lock.withLock {
            cookies.entries.removeIf { entry ->
                val domain = entry.value.domain.trimStart('.').lowercase()
                domain == normalized || domain.endsWith(".$normalized")
            }
        }
    }

    fun snapshot(): List<Cookie> = lock.withLock { cookies.values.toList() }

    fun replaceAll(next: List<Cookie>) {
        lock.withLock {
            cookies.clear()
            next.forEach { cookies[it.storageKey()] = it }
        }
    }

    fun encodeSnapshot(): String = AppJson.encodeToString(snapshot().map { it.toDto() })

    fun restoreFromJson(payload: String?) {
        if (payload.isNullOrBlank()) {
            clear()
            return
        }
        val restored = runCatching {
            AppJson.decodeFromString<List<CookieDto>>(payload).mapNotNull { it.toCookie() }
        }.getOrDefault(emptyList())
        replaceAll(restored)
    }

    private fun Cookie.storageKey(): String = "${name}|${domain}|${path}"
}

@Serializable
data class CookieDto(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

fun Cookie.toDto(): CookieDto = CookieDto(
    name = name,
    value = value,
    domain = domain,
    path = path,
    expiresAt = expiresAt,
    secure = secure,
    httpOnly = httpOnly,
    hostOnly = hostOnly,
)

fun CookieDto.toCookie(): Cookie? {
    val builder = Cookie.Builder()
        .name(name)
        .value(value)
        .path(path.ifBlank { "/" })
        .expiresAt(expiresAt)
    if (hostOnly) {
        builder.hostOnlyDomain(domain)
    } else {
        builder.domain(domain)
    }
    if (secure) builder.secure()
    if (httpOnly) builder.httpOnly()
    return runCatching { builder.build() }.getOrNull()
}
