package cn.edu.bjtu.mis.data.perf

import android.util.Log

object PerfTrace {
    private const val TAG = "BjtuPerf"

    fun mark(name: String, detail: String = "") {
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val message = "$name$suffix"
        runCatching { Log.d(TAG, message) }
            .onFailure { println("$TAG: $message") }
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        val startedAt = nowMillis()
        return try {
            block()
        } finally {
            mark(name, "${nowMillis() - startedAt}ms")
        }
    }

    suspend inline fun <T> measureSuspend(name: String, crossinline block: suspend () -> T): T {
        val startedAt = nowMillis()
        return try {
            block()
        } finally {
            mark(name, "${nowMillis() - startedAt}ms")
        }
    }

    fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}
