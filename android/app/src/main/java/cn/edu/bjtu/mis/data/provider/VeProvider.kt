package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.network.BytesResponse
import cn.edu.bjtu.mis.data.parser.buildCourseResourcesData
import cn.edu.bjtu.mis.data.parser.buildHomeworkData
import cn.edu.bjtu.mis.data.parser.parseCalendar
import cn.edu.bjtu.mis.data.parser.parseCalendarTerms
import cn.edu.bjtu.mis.data.parser.parseCourseResourceListing
import cn.edu.bjtu.mis.data.parser.parseCourseResourceTree
import cn.edu.bjtu.mis.data.parser.parseCourses
import cn.edu.bjtu.mis.data.parser.parseHomeworkList
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CourseSummary
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate

class VeProvider(private val client: BjtuHttpClient) {
    private var sessionId: String? = null
    private var hasAjaxSession: Boolean = false
    private var coursePlatformIndexReferer: String = ProviderConstants.VE_COURSE_PLATFORM_BASE_URL
    private var coursePlatformReferer: String = ProviderConstants.VE_COURSE_PLATFORM_BASE_URL
    private var strictFlowReady: Boolean = false

    suspend fun fetchCalendar(month: String? = null): ModuleEnvelope<CalendarData> {
        ensureStrictFlow("calendar")
        val termsPayload = getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        val (terms, currentTerm) = parseCalendarTerms(termsPayload)
        val targetMonth = month ?: LocalDate.now().toString().substring(0, 7)
        val calendarPayload = getJsonObject(
            "/ve/back/coursePlatform/course.shtml",
            mapOf("method" to "getTimeList", "monthTime" to targetMonth),
        )
        return ModuleEnvelope(
            module = "calendar",
            sourceSystem = "ve",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { put("month", targetMonth) },
            data = parseCalendar(calendarPayload, targetMonth, currentTerm, terms),
        )
    }

    suspend fun fetchHomework(term: String? = null): ModuleEnvelope<HomeworkData> {
        ensureStrictFlow("homework")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second

        if (currentTerm.isNullOrBlank()) {
            return ModuleEnvelope(
                module = "homework",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject { put("fallback_reason", "missing_current_term") },
                data = buildHomeworkData(null, emptyList(), emptyList()),
            )
        }

        val courses = parseCourses(
            getJsonObject(
                "/ve/back/coursePlatform/course.shtml",
                mapOf("method" to "getCourseList", "pagesize" to "100", "page" to "1", "xqCode" to currentTerm),
            )
        )
        val items = mutableListOf<cn.edu.bjtu.mis.model.HomeworkItem>()
        val errors = mutableListOf<String>()
        for (course in courses) {
            runCatching { openHomeworkContext(course) }
                .onFailure { errors += "toCoursePlatform:${course.courseId}:${it.message}" }
                .onSuccess {
                    for (subType in listOf(0, 2)) {
                        runCatching {
                            val payload = getJsonObject(
                                "/ve/back/coursePlatform/homeWork.shtml",
                                mapOf(
                                    "method" to "getHomeWorkList",
                                    "cId" to course.courseId.toString(),
                                    "subType" to subType.toString(),
                                    "page" to "1",
                                    "pagesize" to "10",
                                ),
                            )
                            items += parseHomeworkList(payload, course, subType)
                        }.onFailure { errors += "homeWork:${course.courseId}:$subType:${it.message}" }
                    }
                }
        }

        return ModuleEnvelope(
            module = "homework",
            sourceSystem = "ve",
            coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("term", currentTerm)
                if (errors.isNotEmpty()) put("partial_error_count", errors.size)
            },
            data = buildHomeworkData(currentTerm, courses, items),
        )
    }

    suspend fun fetchCourseResources(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
    ): ModuleEnvelope<CourseResourcesData> {
        ensureStrictFlow("course resources")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second

        if (currentTerm.isNullOrBlank()) {
            return ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject { put("fallback_reason", "missing_current_term") },
                data = buildCourseResourcesData(null, emptyList(), null, folderId, emptyList(), emptyList(), emptyList()),
            )
        }

        val courses = parseCourses(
            getJsonObject(
                "/ve/back/coursePlatform/course.shtml",
                mapOf("method" to "getCourseList", "pagesize" to "100", "page" to "1", "xqCode" to currentTerm),
            )
        )
        val requestedCourseId = courseId?.trim().orEmpty()
        val selected = if (requestedCourseId.isNotBlank()) {
            courses.firstOrNull { it.courseId.toString() == requestedCourseId || it.courseCode == requestedCourseId }
        } else {
            courses.firstOrNull()
        }
        val normalizedFolder = folderId.ifBlank { "0" }
        val sourceParams = buildJsonObject {
            put("term", currentTerm)
            put("course_id", requestedCourseId.ifBlank { selected?.courseId?.toString().orEmpty() })
            put("folder_id", normalizedFolder)
            put("search", search.orEmpty())
        }

        if (selected == null) {
            return ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Verified,
                sourceParams = sourceParams,
                data = buildCourseResourcesData(currentTerm, courses, null, normalizedFolder, emptyList(), emptyList(), emptyList()),
            )
        }

        return runCatching {
            val context = openCourseResourcesContext(selected)
            val baseParams = mapOf(
                "courseId" to context["courseId"],
                "cId" to context["cId"],
                "xkhId" to context["xkhId"],
                "xqCode" to context["xqCode"],
                "docType" to ProviderConstants.VE_COURSE_RESOURCES_DOC_TYPE,
            )
            val treePayload = getJsonObject(
                "/ve/back/coursePlatform/courseResource.shtml",
                mapOf("method" to "stuQueryCourseResourceBag") + baseParams,
            )
            val listingPayload = getJsonObject(
                "/ve/back/coursePlatform/courseResource.shtml",
                mapOf(
                    "method" to "stuQueryUploadResourceForCourseList",
                    "up_id" to normalizedFolder,
                    "searchName" to search.orEmpty(),
                ) + baseParams,
            )
            val (folders, resources) = parseCourseResourceListing(listingPayload, normalizedFolder)
            ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Verified,
                sourceParams = sourceParams,
                data = buildCourseResourcesData(
                    currentTerm,
                    courses,
                    selected,
                    normalizedFolder,
                    parseCourseResourceTree(treePayload),
                    folders,
                    resources,
                ),
            )
        }.getOrElse { error ->
            ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    sourceParams.forEach { (key, value) -> put(key, value) }
                    put("fallback_reason", error.message.orEmpty())
                },
                data = buildCourseResourcesData(currentTerm, courses, selected, normalizedFolder, emptyList(), emptyList(), emptyList()),
            )
        }
    }

    suspend fun downloadCourseResource(rpId: String): BytesResponse {
        ensureStrictFlow("download")
        val payload = postJsonObject(
            "/ve/back/resourceSpace.shtml",
            mapOf("method" to "rpinfoDownloadUrl", "rpId" to rpId.trim()),
            referer = coursePlatformReferer,
        )
        val rpUrl = payload.text("rpUrl") ?: payload.text("url") ?: throw IllegalStateException("资源下载地址缺失。")
        val downloadUrl = if (rpUrl.startsWith("http")) rpUrl else "${ProviderConstants.VE_BASE_URL}/ve/${rpUrl.trimStart('/')}"
        return client.getBytes(downloadUrl, headers = mapOf("Referer" to coursePlatformReferer))
    }

    private suspend fun ensureStrictFlow(reason: String) {
        if (strictFlowReady && coursePlatformIndexReferer != ProviderConstants.VE_COURSE_PLATFORM_BASE_URL) return
        val ok = bootstrapVeSession()
        if (!ok) throw IllegalStateException("VE 会话初始化失败：$reason")
    }

    private suspend fun bootstrapVeSession(): Boolean = runCatching {
        val misEntry = client.getText(
            ProviderConstants.MIS_VE_BRIDGE_URL,
            headers = mapOf("Referer" to ProviderConstants.MIS_HOME_URL),
        )
        rememberSession(misEntry.url, misEntry.body, misEntry.headers["Location"])

        val gateway = client.getText(
            ProviderConstants.BKSY_VE_BRIDGE_URL,
            headers = mapOf("Referer" to misEntry.url),
        )
        rememberSession(gateway.url, gateway.body, gateway.headers["Location"])

        val index = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = mapOf("method" to "toCoursePlatformIndex"),
            headers = mapOf("Referer" to gateway.url),
        )
        rememberSession(index.url, index.body, index.headers["Location"])
        if (index.url.contains("coursePlatform.shtml")) {
            coursePlatformIndexReferer = index.url
            coursePlatformReferer = index.url
        }
        strictFlowReady = true
        true
    }.getOrDefault(false)

    private suspend fun warmupAjaxSession() {
        if (sessionId != null && hasAjaxSession) return
        val payload = getJsonObject("/ve/back/coursePlatform/message.shtml", mapOf("method" to "getArticleList"))
        payload.text("sessionId")?.let {
            sessionId = it
            hasAjaxSession = true
        }
    }

    private suspend fun openHomeworkContext(course: CourseSummary) {
        val coursePage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course),
            headers = mapOf("Referer" to coursePlatformIndexReferer),
        )
        rememberCoursePlatformContext(coursePage.url, coursePage.body)
        val teacherId = extractInputValue(coursePage.body, "teacherId") ?: course.teacherId
            ?: throw IllegalStateException("课程缺少 teacherId：${course.courseId}")
        val homeworkPage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course, ProviderConstants.VE_HOMEWORK_COURSE_TO_PAGE, teacherId),
            headers = mapOf("Referer" to coursePlatformReferer),
        )
        rememberCoursePlatformContext(homeworkPage.url, homeworkPage.body)
    }

    private suspend fun openCourseResourcesContext(course: CourseSummary): Map<String, String> {
        val coursePage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course),
            headers = mapOf("Referer" to coursePlatformIndexReferer),
        )
        rememberCoursePlatformContext(coursePage.url, coursePage.body)
        val teacherId = extractInputValue(coursePage.body, "teacherId")
            ?: extractJsStringValue(coursePage.body, "teacherId")
            ?: course.teacherId
            ?: throw IllegalStateException("课程缺少 teacherId：${course.courseId}")
        val resourcesPage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course, ProviderConstants.VE_COURSE_RESOURCES_COURSE_TO_PAGE, teacherId),
            headers = mapOf("Referer" to coursePlatformReferer),
        )
        rememberCoursePlatformContext(resourcesPage.url, resourcesPage.body)

        fun pick(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
        return mapOf(
            "courseId" to pick(extractJsStringValue(resourcesPage.body, "courseNum"), extractInputValue(resourcesPage.body, "courseId"), course.courseCode),
            "cId" to pick(extractInputValue(resourcesPage.body, "courseId"), extractJsStringValue(resourcesPage.body, "courseNum"), course.courseCode),
            "xkhId" to pick(extractInputValue(resourcesPage.body, "xkhId"), extractJsStringValue(resourcesPage.body, "xkhId"), course.xkhId),
            "xqCode" to pick(extractInputValue(resourcesPage.body, "xqCode"), extractJsStringValue(resourcesPage.body, "xqCode"), course.xqCode),
            "teacherId" to pick(extractInputValue(resourcesPage.body, "teacherId"), extractJsStringValue(resourcesPage.body, "teacherId"), teacherId),
        )
    }

    private fun buildCoursePageParams(
        course: CourseSummary,
        courseToPage: String? = null,
        teacherId: String? = null,
    ): Map<String, String?> = buildMap {
        put("method", "toCoursePlatform")
        put("courseId", course.courseCode)
        put("dataSource", "1")
        put("cId", course.courseId.toString())
        put("xkhId", course.xkhId)
        put("xqCode", course.xqCode)
        courseToPage?.let { put("courseToPage", it) }
        teacherId?.let { put("teacherId", it) }
    }

    private suspend fun getJsonObject(path: String, params: Map<String, String?>): JsonObject {
        if (path.startsWith("/ve/back/coursePlatform/") && !strictFlowReady) ensureStrictFlow(path)
        if (shouldSendSessionHeader(path, params)) warmupAjaxSession()
        val response = client.getText(
            ProviderConstants.VE_BASE_URL + path,
            params = params,
            headers = jsonHeaders(path, params),
        )
        rememberSession(response.url, response.body, response.headers["Location"])
        val payload = cn.edu.bjtu.mis.data.AppJson.parseToJsonElement(response.body).jsonObject
        ensurePayloadSuccess(payload)
        payload.text("sessionId")?.let {
            sessionId = it
            hasAjaxSession = true
        }
        return payload
    }

    private suspend fun postJsonObject(
        path: String,
        params: Map<String, String?>,
        referer: String,
    ): JsonObject {
        if (!strictFlowReady) ensureStrictFlow(path)
        warmupAjaxSession()
        val response = client.postJson(
            ProviderConstants.VE_BASE_URL + path,
            json = "{}",
            params = params,
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to referer,
            ) + sessionHeader(),
        )
        val payload = cn.edu.bjtu.mis.data.AppJson.parseToJsonElement(response.body).jsonObject
        ensurePayloadSuccess(payload)
        return payload
    }

    private fun jsonHeaders(path: String, params: Map<String, String?>): Map<String, String> =
        mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to selectReferer(path, params),
        ) + if (shouldSendSessionHeader(path, params)) sessionHeader() else emptyMap()

    private fun sessionHeader(): Map<String, String> =
        sessionId?.takeIf { hasAjaxSession }?.let { mapOf("sessionId" to it) } ?: emptyMap()

    private fun selectReferer(path: String, params: Map<String, String?>): String {
        val method = params["method"].orEmpty()
        return when {
            path == "/ve/back/coursePlatform/homeWork.shtml" && method == "getHomeWorkList" -> coursePlatformReferer
            path == "/ve/back/coursePlatform/courseResource.shtml" -> coursePlatformReferer
            else -> coursePlatformIndexReferer
        }
    }

    private fun shouldSendSessionHeader(path: String, params: Map<String, String?>): Boolean {
        val method = params["method"].orEmpty()
        return when {
            path == "/ve/back/coursePlatform/course.shtml" && method in setOf("getCourseList", "getTimeList") -> true
            path == "/ve/back/coursePlatform/homeWork.shtml" && method == "getHomeWorkList" -> true
            path == "/ve/back/coursePlatform/courseResource.shtml" && method == "stuQueryUploadResourceForCourseList" -> true
            path == "/ve/back/coursePlatform/userInfo.shtml" && method == "getUserInfo" -> true
            else -> false
        }
    }

    private fun ensurePayloadSuccess(payload: JsonObject) {
        val status = payload.text("STATUS")?.lowercase() ?: return
        if (status in setOf("0", "ok", "success", "true", "2")) return
        throw IllegalStateException("VE payload STATUS=${payload.text("STATUS")} ERRMSG=${payload.text("ERRMSG") ?: payload.text("message").orEmpty()}")
    }

    private fun rememberSession(url: String, body: String, location: String?) {
        rememberCoursePlatformContext(url, body)
        listOf(url, body, location.orEmpty()).forEach { value ->
            extractSessionId(value)?.let { if (sessionId == null) sessionId = it }
        }
    }

    private fun rememberCoursePlatformContext(url: String, body: String) {
        extractSessionId(body)?.let {
            sessionId = it
            hasAjaxSession = true
        }
        extractSessionId(url)?.let { if (sessionId == null) sessionId = it }
        if (url.contains("coursePlatform.shtml")) {
            if (url.contains("method=toCoursePlatformIndex")) coursePlatformIndexReferer = url
            coursePlatformReferer = url
        }
    }

    private fun extractSessionId(value: String): String? =
        Regex("""(?:sessionId=|name=["']sessionId["'][^>]*value=["'])([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.get(1)

    private fun extractInputValue(html: String, fieldName: String): String? =
        Regex("""(?:name|id)=["']$fieldName["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)

    private fun extractJsStringValue(html: String, fieldName: String): String? =
        Regex("""\b(?:var\s+)?$fieldName\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.text(key: String): String? =
        this[key]?.primitiveText()?.takeIf { it.isNotBlank() }

    private fun JsonElement.primitiveText(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()?.trim()

}
