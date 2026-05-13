package cn.edu.bjtu.mis.data.network

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.perf.PerfTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class TextResponse(
    val url: String,
    val code: Int,
    val body: String,
    val headers: Headers,
)

data class BytesResponse(
    val url: String,
    val code: Int,
    val body: ByteArray,
    val headers: Headers,
)

data class FileResponse(
    val url: String,
    val code: Int,
    val file: File,
    val headers: Headers,
    val bytesWritten: Long,
)

data class MultipartFilePart(
    val formName: String,
    val fileName: String,
    val content: ByteArray,
    val contentType: String? = null,
)

class BjtuHttpClient(
    val cookieJar: AppCookieJar,
    userAgent: String = DEFAULT_USER_AGENT,
    dns: BjtuDns = BjtuDns(),
) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(dns)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun getText(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): TextResponse = executeText(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
    )

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): TextResponse {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        return executeText(
            Request.Builder()
                .url(buildUrl(url, params))
                .headers(headers.toHeaders())
                .post(body)
                .build()
        )
    }

    suspend fun postMultipart(
        url: String,
        files: List<MultipartFilePart>,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): TextResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                files.forEach { file ->
                    addFormDataPart(
                        file.formName,
                        file.fileName,
                        file.content.toRequestBody(file.contentType?.toMediaTypeOrNull()),
                    )
                }
            }
            .build()
        return executeText(
            Request.Builder()
                .url(buildUrl(url, params))
                .headers(headers.toHeaders())
                .post(body)
                .build()
        )
    }

    suspend fun postJson(
        url: String,
        json: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): TextResponse = executeText(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .post(json.toRequestBody(contentType.toMediaType()))
            .build()
    )

    suspend fun getJson(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = AppJson.parseToJsonElement(getText(url, params, headers).body)

    suspend fun getBytes(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): BytesResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
        val response = executeCall(request)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for ${it.request.url}")
            BytesResponse(
                url = it.request.url.toString(),
                code = it.code,
                body = it.body?.bytes() ?: ByteArray(0),
                headers = it.headers,
            )
        }
    }

    suspend fun downloadToFile(
        url: String,
        target: File,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): FileResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
        val response = executeCall(request)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for ${it.request.url}")
            val body = it.body ?: throw IOException("Empty response body for ${it.request.url}")
            target.parentFile?.mkdirs()

            val partial = File(target.absolutePath + ".part")
            if (partial.exists() && !partial.delete()) {
                throw IOException("Cannot replace partial download ${partial.absolutePath}")
            }

            var completed = false
            var replacementStarted = false
            val bytesWritten = try {
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }.also {
                    if (target.exists() && !target.delete()) {
                        throw IOException("Cannot replace existing file ${target.absolutePath}")
                    }
                    replacementStarted = true
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        if (!partial.delete()) partial.deleteOnExit()
                    }
                    completed = true
                }
            } finally {
                if (!completed) {
                    partial.delete()
                    if (replacementStarted) target.delete()
                }
            }

            FileResponse(
                url = it.request.url.toString(),
                code = it.code,
                file = target,
                headers = it.headers,
                bytesWritten = bytesWritten,
            )
        }
    }

    private suspend fun executeText(request: Request): TextResponse = withContext(Dispatchers.IO) {
        val response = executeCall(request)
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for ${it.request.url}")
            TextResponse(
                url = it.request.url.toString(),
                code = it.code,
                body = it.body?.string().orEmpty(),
                headers = it.headers,
            )
        }
    }

    private fun executeCall(request: Request) =
        try {
            val startedAt = PerfTrace.nowMillis()
            client.newCall(request).execute().also { response ->
                PerfTrace.mark(
                    "http",
                    "${request.method} ${request.url.host}${request.url.encodedPath} ${response.code} ${PerfTrace.nowMillis() - startedAt}ms",
                )
            }
        } catch (error: UnknownHostException) {
            throw IOException(
                "无法解析域名 ${request.url.host}。请检查模拟器网络、系统 Private DNS，或连接可访问北交大 MIS 的网络后重试。",
                error,
            )
        } catch (error: SocketTimeoutException) {
            throw IOException("连接 ${request.url.host} 超时，请检查网络后重试。", error)
        }

    private fun buildUrl(url: String, params: Map<String, String?>): String {
        if (params.isEmpty()) return url
        val builder = url.toHttpUrl().newBuilder()
        params.forEach { (key, value) ->
            if (!value.isNullOrBlank()) builder.setQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    private fun Map<String, String>.toHeaders(): Headers =
        Headers.Builder().also { builder ->
            forEach { (key, value) -> builder.add(key, value) }
        }.build()

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
    }
}
