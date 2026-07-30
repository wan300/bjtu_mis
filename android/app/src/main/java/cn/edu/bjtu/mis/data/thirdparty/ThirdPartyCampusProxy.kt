package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.provider.ProviderConstants
import cn.edu.bjtu.mis.data.provider.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val CampusResponseByteLimit = 5L * 1024L * 1024L
private const val CampusTimeoutSeconds = 15L
private const val CampusRequestCapability = "campus.request@1"

class ThirdPartyCampusProxy(
    private val sessionManager: SessionManager,
) {
    suspend fun request(
        service: ThirdPartyService,
        params: JsonObject,
    ): JsonObject {
        requireCampusRequestCapability(service)
        val campusServiceId = params.string("service_id")?.lowercase(Locale.US)
            ?: throw ThirdPartyCampusProxyException("service_required", "缺少校园服务 ID")
        val unknownParameters = params.keys -
            setOf("service_id", "method", "path", "query", "accept")
        if (unknownParameters.isNotEmpty()) {
            throw ThirdPartyCampusProxyException(
                "parameter_not_allowed",
                "campus.request 包含未注册参数：${unknownParameters.sorted().joinToString()}",
            )
        }
        val method = normalizeCampusMethod(params.string("method"))
        val relativePath = normalizeCampusRelativePath(
            params.string("path") ?: throw ThirdPartyCampusProxyException("path_required", "缺少相对路径"),
        )
        val spec = CampusRegistry.authorize(campusServiceId, relativePath)
            ?: throw ThirdPartyCampusProxyException("path_not_allowed", "校园服务路径未注册")
        if (spec.permission !in service.grantedPermissions) {
            throw ThirdPartyCampusProxyException("permission_denied", "插件未获得权限：${spec.permission}")
        }
        val rawQuery = params["query"]
        if (rawQuery != null && rawQuery !is JsonObject) {
            throw ThirdPartyCampusProxyException("query_invalid", "query 必须是 JSON object")
        }
        val query = rawQuery as? JsonObject ?: JsonObject(emptyMap())
        val accept = params.string("accept") ?: "application/json, text/plain;q=0.9, text/html;q=0.8"
        if (accept.length > 256 || accept.any { it.code !in 0x20..0x7e }) {
            throw ThirdPartyCampusProxyException("accept_invalid", "Accept 请求头无效")
        }
        val initialUrl = buildUrl(spec, relativePath, query)
        return sessionManager.withCampusServiceClient(campusServiceId) { client ->
            withContext(Dispatchers.IO) {
                val http = client.client.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(CampusTimeoutSeconds, TimeUnit.SECONDS)
                    .build()
                var target = initialUrl
                repeat(5) {
                    val request = Request.Builder()
                        .url(target)
                        .header("Accept", accept)
                        .method(method, null)
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (response.code in 300..399) {
                            val location = response.header("Location")
                                ?: throw ThirdPartyCampusProxyException(
                                    "redirect_invalid",
                                    "校园服务返回了无 Location 的重定向",
                                    httpStatus = response.code,
                                )
                            val redirected = target.resolve(location)
                                ?: throw ThirdPartyCampusProxyException("redirect_invalid", "校园服务重定向 URL 无效")
                            if (!isAllowedCampusRedirect(campusServiceId, spec, redirected)) {
                                throw ThirdPartyCampusProxyException(
                                    "redirect_outside_service",
                                    "校园服务重定向越出注册边界",
                                    httpStatus = response.code,
                                )
                            }
                            target = redirected
                            return@use
                        }
                        val responseBody = if (method == "HEAD") {
                            null
                        } else {
                            response.body?.source()?.let(::readBoundedBody)
                        }
                        return@withContext buildJsonObject {
                            put("status", response.code)
                            put("url", target.toString())
                            put("headers", buildJsonObject {
                                SAFE_RESPONSE_HEADERS.forEach { name ->
                                    response.headers.values(name)
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { put(name.lowercase(Locale.US), it.joinToString(",")) }
                                }
                            })
                            responseBody?.let { body ->
                                put(
                                    "body",
                                    runCatching { AppJson.parseToJsonElement(body) }
                                        .getOrElse { JsonPrimitive(body) },
                                )
                            }
                        }
                    }
                }
                throw ThirdPartyCampusProxyException("too_many_redirects", "校园服务重定向次数过多")
            }
        }
    }

    private fun buildUrl(
        spec: CampusPathSpec,
        relativePath: String,
        query: JsonObject,
    ): HttpUrl {
        if (query.size > 32) throw ThirdPartyCampusProxyException("query_invalid", "query 参数过多")
        val resolved = spec.baseUrl.resolve(relativePath)
            ?: throw ThirdPartyCampusProxyException("path_invalid", "无法解析校园服务相对路径")
        val builder = resolved.newBuilder()
        val normalizedQuery = linkedMapOf<String, String>()
        query.forEach { (name, rawValue) ->
            if (name !in spec.queryKeys) {
                throw ThirdPartyCampusProxyException("query_not_allowed", "query 参数未注册：$name")
            }
            val primitive = rawValue as? JsonPrimitive
            val value = primitive?.takeIf { it.isString }?.contentOrNull
                ?: throw ThirdPartyCampusProxyException("query_invalid", "query 参数必须是字符串：$name")
            if (value.length > 512 || value.any { it == '\r' || it == '\n' }) {
                throw ThirdPartyCampusProxyException("query_invalid", "query 参数无效：$name")
            }
            normalizedQuery[name] = value
        }
        spec.requiredQueryValues.forEach { (name, allowedValues) ->
            val value = normalizedQuery[name]
                ?: throw ThirdPartyCampusProxyException(
                    "query_required",
                    "校园服务路径要求 query 参数：$name",
                )
            if (value !in allowedValues) {
                throw ThirdPartyCampusProxyException(
                    "query_value_not_allowed",
                    "query 参数值未注册：$name",
                )
            }
        }
        normalizedQuery.forEach { (name, value) ->
            builder.addQueryParameter(name, value)
        }
        return builder.build()
    }

    private fun readBoundedBody(source: okio.BufferedSource): String {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, 8192)
            if (read == -1L) break
            total += read
            if (total > CampusResponseByteLimit) {
                throw ThirdPartyCampusProxyException("response_too_large", "校园服务响应超过 5 MiB")
            }
        }
        return buffer.readUtf8()
    }

    private companion object {
        val SAFE_RESPONSE_HEADERS = setOf(
            "Content-Type",
            "Content-Language",
            "Cache-Control",
            "ETag",
            "Last-Modified",
        )
    }
}

internal fun requireCampusRequestCapability(service: ThirdPartyService) {
    val declared = service.manifest.requiredCapabilities + service.manifest.optionalCapabilities
    if (CampusRequestCapability !in declared) {
        throw ThirdPartyCampusProxyException(
            "capability_unavailable",
            "插件未声明 $CampusRequestCapability",
        )
    }
    if (CampusRequestCapability !in service.grantedCapabilities) {
        throw ThirdPartyCampusProxyException(
            "permission_denied",
            "插件未获得 capability：$CampusRequestCapability",
        )
    }
}

internal fun normalizeCampusMethod(raw: String?): String {
    val method = (raw ?: "GET").uppercase(Locale.US)
    if (method !in setOf("GET", "HEAD")) {
        throw ThirdPartyCampusProxyException("method_not_allowed", "campus.request 只允许 GET/HEAD")
    }
    return method
}

internal fun normalizeCampusRelativePath(raw: String): String {
    if (!raw.startsWith("/") || raw.contains('\\') || raw.contains('?') || raw.contains('#')) {
        throw ThirdPartyCampusProxyException("path_invalid", "campus.request path 必须是不含 query 的绝对相对路径")
    }
    val uri = runCatching { URI(raw) }.getOrNull()
        ?: throw ThirdPartyCampusProxyException("path_invalid", "campus.request path 无效")
    if (uri.isAbsolute || uri.rawAuthority != null || uri.normalize().path != uri.path) {
        throw ThirdPartyCampusProxyException("path_invalid", "campus.request path 包含越界片段")
    }
    return uri.path
}

internal fun isAllowedCampusRedirect(
    serviceId: String,
    originalSpec: CampusPathSpec,
    redirected: HttpUrl,
): Boolean {
    val redirectedSpec = CampusRegistry.authorize(serviceId, redirected.encodedPath) ?: return false
    if (
        redirected.scheme != originalSpec.baseUrl.scheme ||
        redirected.host != originalSpec.baseUrl.host ||
        redirected.port != originalSpec.baseUrl.port ||
        redirectedSpec.permission != originalSpec.permission
    ) {
        return false
    }
    val queryAllowed = redirected.queryParameterNames.all { name ->
        name in redirectedSpec.queryKeys &&
            redirected.queryParameterValues(name).all { value ->
                value == null ||
                    (value.length <= 512 && value.none { it == '\r' || it == '\n' })
            }
    }
    if (!queryAllowed) return false
    return redirectedSpec.requiredQueryValues.all { (name, allowedValues) ->
        val values = redirected.queryParameterValues(name)
        values.size == 1 && values.single() in allowedValues
    }
}

class ThirdPartyCampusProxyException(
    val code: String,
    override val message: String,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
) : IOException(message)

internal data class CampusPathSpec(
    val baseUrl: HttpUrl,
    val path: Regex,
    val permission: String,
    val queryKeys: Set<String>,
    val requiredQueryValues: Map<String, Set<String>> = emptyMap(),
)

internal object CampusRegistry {
    private val aaQueryKeys = setOf(
        "zxjxjhh", "xnxq01id", "term", "ctype", "xh", "id", "page", "pageNo", "pageSize",
    )
    private val specs = mapOf(
        "mis" to listOf(
            CampusPathSpec(
                ProviderConstants.MIS_HOME_URL.toHttpUrl(),
                Regex("^/home/$"),
                "identity.profile.read",
                emptySet(),
            ),
        ),
        "aa" to listOf(
            CampusPathSpec(
                ProviderConstants.AA_BASE_URL.toHttpUrl(),
                Regex("^/course_selection/courseselect/stuschedule/$"),
                "academic.timetable.read",
                aaQueryKeys,
            ),
            CampusPathSpec(
                ProviderConstants.AA_BASE_URL.toHttpUrl(),
                Regex("^/examine/examplanstudent/stulist/$"),
                "academic.exams.read",
                aaQueryKeys,
            ),
            CampusPathSpec(
                ProviderConstants.AA_BASE_URL.toHttpUrl(),
                Regex("^/score/(scores/stu/view|scorecard/stu)/$"),
                "academic.scores.read",
                aaQueryKeys,
            ),
            CampusPathSpec(
                ProviderConstants.AA_BASE_URL.toHttpUrl(),
                Regex("^/school_census/schoolcensus/stuview/$"),
                "identity.profile.read",
                aaQueryKeys,
            ),
            CampusPathSpec(
                ProviderConstants.AA_BASE_URL.toHttpUrl(),
                Regex("^/school_census/schooltraininfo/[A-Za-z0-9_./-]*$"),
                "academic.progress.read",
                aaQueryKeys,
            ),
        ),
        "ve" to listOf(
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/coursePlatform/coursePlatform\\.shtml$"),
                "academic.timetable.read",
                setOf(
                    "method",
                    "courseId",
                    "dataSource",
                    "cId",
                    "xkhId",
                    "xqCode",
                    "courseToPage",
                    "teacherId",
                ),
                mapOf("method" to setOf("toCoursePlatformIndex", "toCoursePlatform")),
            ),
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/coursePlatform/course\\.shtml$"),
                "academic.timetable.read",
                setOf("method", "xqCode", "monthTime", "page", "pagesize"),
                mapOf("method" to setOf("getCourseList", "getTimeList")),
            ),
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/coursePlatform/homeWork\\.shtml$"),
                "academic.homework.read",
                setOf(
                    "method",
                    "cId",
                    "subType",
                    "page",
                    "pagesize",
                    "id",
                    "courseId",
                    "teacherId",
                ),
                mapOf("method" to setOf("getHomeWorkList", "queryStudentCourseNote")),
            ),
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/coursePlatform/courseResource\\.shtml$"),
                "academic.course_resources.read",
                setOf(
                    "method",
                    "courseId",
                    "cId",
                    "xkhId",
                    "xqCode",
                    "docType",
                    "up_id",
                    "searchName",
                ),
                mapOf(
                    "method" to setOf(
                        "stuQueryCourseResourceBag",
                        "stuQueryUploadResourceForCourseList",
                    )
                ),
            ),
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/coursePlatform/dataSynAction\\.shtml$"),
                "academic.course_resources.read",
                setOf("method", "id", "noteId", "type"),
                mapOf("method" to setOf("queryStuViewUrl", "getFilePlayUrl")),
            ),
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/coursePlatform/userInfo\\.shtml$"),
                "identity.profile.read",
                setOf("method"),
                mapOf("method" to setOf("getUserInfo")),
            ),
            CampusPathSpec(
                ProviderConstants.VE_BASE_URL.toHttpUrl(),
                Regex("^/ve/back/rp/common/teachCalendar\\.shtml$"),
                "academic.timetable.read",
                setOf(
                    "method",
                    "courseId",
                    "courseSchedId",
                    "userLevel",
                    "userId",
                    "videoId",
                    "uuid",
                    "timeTableId",
                    "timetableId",
                ),
                mapOf(
                    "method" to setOf(
                        "queryCurrentXq",
                        "toDisplyTeachCourses",
                        "toDisplyCourseSchedDetail",
                    )
                ),
            ),
        ),
    )

    fun authorize(serviceId: String, path: String): CampusPathSpec? =
        specs[serviceId]?.firstOrNull { it.path.matches(path) }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.let { value ->
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw ThirdPartyCampusProxyException(
                "parameter_invalid",
                "campus.request 参数必须是字符串：$name",
            )
        }
        primitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }
