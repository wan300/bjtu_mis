package cn.edu.bjtu.mis.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SingleFlight {
    private val mutex = Mutex()
    private val active = mutableMapOf<String, CompletableDeferred<Result<Any?>>>()

    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val normalizedKey = key.trim().ifBlank { "default" }
        val existing = mutex.withLock { active[normalizedKey] }
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing.await().getOrThrow() as T
        }

        val deferred = CompletableDeferred<Result<Any?>>()
        val creator = mutex.withLock {
            active[normalizedKey]?.let { it to false } ?: (deferred.also { active[normalizedKey] = it } to true)
        }
        if (!creator.second) {
            @Suppress("UNCHECKED_CAST")
            return creator.first.await().getOrThrow() as T
        }

        return try {
            val value = block()
            deferred.complete(Result.success(value))
            value
        } catch (error: Throwable) {
            deferred.complete(Result.failure(error))
            throw error
        } finally {
            mutex.withLock {
                if (active[normalizedKey] === deferred) {
                    active.remove(normalizedKey)
                }
            }
        }
    }
}
