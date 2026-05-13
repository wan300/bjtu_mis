package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.network.FileResponse
import cn.edu.bjtu.mis.data.network.MultipartFilePart
import cn.edu.bjtu.mis.data.network.TextResponse
import cn.edu.bjtu.mis.data.parser.buildCourseResourcesData
import cn.edu.bjtu.mis.data.parser.buildCourseReplayData
import cn.edu.bjtu.mis.data.parser.buildHomeworkData
import cn.edu.bjtu.mis.data.parser.parseCalendar
import cn.edu.bjtu.mis.data.parser.parseCalendarTerms
import cn.edu.bjtu.mis.data.parser.parseCourseResourceListing
import cn.edu.bjtu.mis.data.parser.parseCourseResourceTree
import cn.edu.bjtu.mis.data.parser.parseCourseReplayLessons
import cn.edu.bjtu.mis.data.parser.parseCourseReplayPlayback
import cn.edu.bjtu.mis.data.parser.parseCourses
import cn.edu.bjtu.mis.data.parser.parseHomeworkAttachments
import cn.edu.bjtu.mis.data.parser.parseHomeworkList
import cn.edu.bjtu.mis.data.parser.parseVeUserInfo
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CourseReplayData
import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CourseSummary
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkSubmitResponse
import cn.edu.bjtu.mis.model.HomeworkUploadFile
import cn.edu.bjtu.mis.model.ModuleEnvelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class VeProvider(private val client: BjtuHttpClient) {
    private var sessionId: String? = null
    private var hasAjaxSession: Boolean = false
    private var coursePlatformIndexReferer: String = ProviderConstants.VE_COURSE_PLATFORM_BASE_URL
    private var coursePlatformReferer: String = ProviderConstants.VE_COURSE_PLATFORM_BASE_URL
    private var strictFlowReady: Boolean = false
    private var strictFlowStep: String = "init"
    private var lastBootstrapError: String? = null

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

    suspend fun fetchHomework(
        term: String? = null,
        includeAttachments: Boolean = true,
    ): ModuleEnvelope<HomeworkData> {
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
        val attachmentCache = mutableMapOf<Pair<Int, Int>, List<HomeworkAttachment>>()
        for (course in courses) {
            runCatching { openHomeworkContext(course) }
                .onFailure { errors += "toCoursePlatform:${course.courseId}:${it.message}" }
                .onSuccess { teacherId ->
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
                            items += parseHomeworkList(payload, course, subType).map { item ->
                                val homeworkId = item.homeworkId ?: return@map item
                                val attachments = if (includeAttachments) {
                                    attachmentCache.getOrPut(course.courseId to homeworkId) {
                                        runCatching {
                                            fetchHomeworkAttachments(homeworkId, course.courseId, teacherId)
                                        }.onFailure {
                                            errors += "homeWorkAttachment:${course.courseId}:$homeworkId:${it.message}"
                                        }.getOrDefault(emptyList())
                                    }
                                } else {
                                    emptyList()
                                }
                                item.copy(attachments = attachments)
                            }
                        }.onFailure { errors += "homeWork:${course.courseId}:$subType:${it.message}" }
                    }
                }
        }
        val dedupedItems = linkedMapOf<String, cn.edu.bjtu.mis.model.HomeworkItem>()
        items.forEach { item ->
            val key = item.homeworkId?.let { "id:$it" } ?: "course:${item.courseId}:title:${item.title}:due:${item.dueAt.orEmpty()}"
            val previous = dedupedItems[key]
            if (previous == null || (!item.submittedAt.isNullOrBlank() && previous.submittedAt.isNullOrBlank())) {
                dedupedItems[key] = item
            }
        }

        return ModuleEnvelope(
            module = "homework",
            sourceSystem = "ve",
            coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("term", currentTerm)
                put("include_attachments", includeAttachments)
                if (errors.isNotEmpty()) put("partial_error_count", errors.size)
            },
            data = buildHomeworkData(currentTerm, courses, dedupedItems.values.toList()),
        )
    }

    suspend fun submitHomework(
        homeworkId: Int,
        courseId: Int,
        content: String = "",
        files: List<HomeworkUploadFile> = emptyList(),
        term: String? = null,
    ): HomeworkSubmitResponse {
        ensureStrictFlow("homework submit")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second ?: throw IllegalStateException("当前学期缺失，无法定位作业。")

        val courses = parseCourses(
            getJsonObject(
                "/ve/back/coursePlatform/course.shtml",
                mapOf("method" to "getCourseList", "pagesize" to "100", "page" to "1", "xqCode" to currentTerm),
            )
        )
        val course = courses.firstOrNull { it.courseId == courseId }
            ?: throw IllegalStateException("未找到课程 $courseId")

        openHomeworkContext(course)
        val homeworkEntry = listOf(0, 2).firstNotNullOfOrNull { subType ->
            val payload = getJsonObject(
                "/ve/back/coursePlatform/homeWork.shtml",
                mapOf(
                    "method" to "getHomeWorkList",
                    "cId" to courseId.toString(),
                    "subType" to subType.toString(),
                    "page" to "1",
                    "pagesize" to "100",
                ),
            )
            payload.objectList("courseNoteList").firstOrNull { it.text("id") == homeworkId.toString() }
        } ?: throw IllegalStateException("未找到作业 $homeworkId")

        val uploadParams = mapOf(
            "method" to "uploadDiv3",
            "courseId" to courseId.toString(),
            "calendarId" to homeworkEntry.text("calendar_id").orEmpty(),
            "upId" to homeworkId.toString(),
            "contentType" to homeworkEntry.int("content_type", 0).toString(),
            "fz" to homeworkEntry.int("is_fz", 0).toString(),
            "openTime" to homeworkEntry.text("open_date").orEmpty(),
            "endTime" to homeworkEntry.text("end_time").orEmpty(),
            "return_num" to homeworkEntry.int("return_num", 0).toString(),
        )
        val uploadPage = client.getText(
            "${ProviderConstants.VE_BASE_URL}/ve/back/course/courseWorkInfo.shtml",
            params = uploadParams,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to coursePlatformReferer,
            ),
        )
        rememberSession(uploadPage)

        val uploadUrl = extractHomeworkUploadUrl(uploadPage.body, uploadPage.url)
        val uploadedFiles = if (files.isEmpty()) {
            emptyList()
        } else {
            val targetUrl = uploadUrl ?: throw IllegalStateException("作业提交页缺少附件上传地址。")
            files.map { file ->
                uploadHomeworkFile(targetUrl, uploadPage.url, file)
            }
        }

        val submitPayload = buildJsonArray {
            uploadedFiles.forEach { fields ->
                add(buildJsonObject {
                    fields.forEach { (key, value) -> put(key, value) }
                })
            }
        }.toString()
        val submitResponse = client.postForm(
            "${ProviderConstants.VE_BASE_URL}/ve/back/course/courseWorkInfo.shtml",
            params = mapOf("method" to "sendStuHomeWorks"),
            form = mapOf(
                "content" to urlQuote(content),
                "groupName" to urlQuote(extractInputValue(uploadPage.body, "groupName").orEmpty()),
                "groupId" to extractInputValue(uploadPage.body, "groupId").orEmpty(),
                "courseId" to (extractInputValue(uploadPage.body, "courseId") ?: courseId.toString()),
                "contentType" to (extractInputValue(uploadPage.body, "contentType") ?: uploadParams.getValue("contentType")),
                "fz" to (extractInputValue(uploadPage.body, "fz") ?: uploadParams.getValue("fz")),
                "jxrl_id" to extractInputValue(uploadPage.body, "jxrl_id").orEmpty(),
                "fileList" to submitPayload,
                "upId" to (extractInputValue(uploadPage.body, "upId") ?: homeworkId.toString()),
                "return_num" to (extractInputValue(uploadPage.body, "return_num") ?: uploadParams.getValue("return_num")),
                "isTeacher" to "0",
            ),
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to uploadPage.url,
            ) + sessionHeader(),
        )
        rememberSession(submitResponse)
        val payload = parseJsonObjectResponse(submitResponse, "homework submit")
        val flag = (payload.text("flag") ?: payload.text("status")).orEmpty().lowercase()
        if (flag != "success") {
            throw IOException(payload.text("message") ?: payload.text("msg") ?: "VE 作业提交失败")
        }

        return HomeworkSubmitResponse(
            status = "success",
            message = payload.text("message") ?: payload.text("msg") ?: "提交成功",
            homeworkId = homeworkId,
            submittedAt = payload.text("subTime") ?: payload.text("submitted_at"),
            upstream = payload,
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

    suspend fun downloadCourseResource(rpId: String, target: File): FileResponse {
        ensureStrictFlow("download")
        val payload = postJsonObject(
            "/ve/back/resourceSpace.shtml",
            mapOf("method" to "rpinfoDownloadUrl", "rpId" to rpId.trim()),
            referer = coursePlatformReferer,
        )
        val rpUrl = payload.text("rpUrl") ?: payload.text("url") ?: throw IllegalStateException("资源下载地址缺失。")
        val downloadUrl = if (rpUrl.startsWith("http")) rpUrl else "${ProviderConstants.VE_BASE_URL}/ve/${rpUrl.trimStart('/')}"
        return client.downloadToFile(downloadUrl, target, headers = mapOf("Referer" to coursePlatformReferer))
    }

    suspend fun downloadHomeworkAttachment(
        homeworkId: Int,
        attachmentId: String,
        target: File,
    ): FileResponse {
        ensureStrictFlow("homework attachment download")
        warmupAjaxSession()
        return client.downloadToFile(
            "${ProviderConstants.VE_BASE_URL}/ve/back/coursePlatform/dataSynAction.shtml",
            target,
            params = mapOf(
                "method" to "downLoadPic",
                "id" to attachmentId.trim(),
                "noteId" to homeworkId.toString(),
            ),
            headers = mapOf("Referer" to coursePlatformReferer) + sessionHeader(),
        )
    }

    suspend fun previewHomeworkAttachment(homeworkId: Int, attachmentId: String): String {
        ensureStrictFlow("homework attachment preview")
        val payload = getJsonObject(
            "/ve/back/coursePlatform/dataSynAction.shtml",
            mapOf(
                "method" to "queryStuViewUrl",
                "id" to attachmentId.trim(),
                "noteId" to homeworkId.toString(),
                "type" to "4",
            ),
        )
        return payload.text("url") ?: throw IllegalStateException("暂无预览地址")
    }

    suspend fun fetchCourseReplays(
        term: String? = null,
        courseId: String? = null,
    ): ModuleEnvelope<CourseReplayData> {
        ensureStrictFlow("course replay")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second

        if (currentTerm.isNullOrBlank()) {
            return ModuleEnvelope(
                module = "course_replay",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject { put("fallback_reason", "missing_current_term") },
                data = buildCourseReplayData(null, emptyList(), null, null, null, emptyList()),
            )
        }

        val courses = parseCourses(
            getJsonObject(
                "/ve/back/coursePlatform/course.shtml",
                mapOf("method" to "getCourseList", "pagesize" to "100", "page" to "1", "xqCode" to currentTerm),
            )
        )
        val selected = selectCourse(courses, courseId)
        val sourceParams = buildJsonObject {
            put("term", currentTerm)
            put("course_id", courseId?.trim().orEmpty().ifBlank { selected?.courseId?.toString().orEmpty() })
        }
        if (selected == null) {
            return ModuleEnvelope(
                module = "course_replay",
                sourceSystem = "ve",
                coverage = CoverageLevel.Verified,
                sourceParams = sourceParams,
                data = buildCourseReplayData(currentTerm, courses, null, null, null, emptyList()),
            )
        }

        val context = openCourseReplayContext(selected)
        val payload = getJsonObject(
            "/ve/back/rp/common/teachCalendar.shtml",
            mapOf("method" to "toDisplyTeachCourses", "courseId" to selected.courseId.toString()),
        )
        return ModuleEnvelope(
            module = "course_replay",
            sourceSystem = "ve",
            coverage = CoverageLevel.Verified,
            sourceParams = sourceParams,
            data = buildCourseReplayData(
                currentTerm,
                courses,
                selected,
                context.detailUserId ?: context.platformUserId,
                context.listenUserId,
                parseCourseReplayLessons(payload),
            ),
        )
    }

    suspend fun fetchCourseReplayPlayback(
        term: String? = null,
        courseId: String? = null,
        courseSchedId: String,
        userId: String? = null,
        timeTableId: String? = null,
    ): CourseReplayPlaybackInfo {
        ensureStrictFlow("course replay playback")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second
        val courses = parseCourses(
            getJsonObject(
                "/ve/back/coursePlatform/course.shtml",
                mapOf("method" to "getCourseList", "pagesize" to "100", "page" to "1", "xqCode" to currentTerm),
            )
        )
        val selected = selectCourse(courses, courseId)
            ?: throw IllegalStateException("Course not found: ${courseId.orEmpty()}")
        val context = openCourseReplayContext(selected)
        val userIdCandidates = courseReplayUserIdCandidates(
            detailUserId = context.detailUserId,
            contextUserId = context.platformUserId,
            preferredUserId = userId,
            listenUserId = context.listenUserId,
        )
        if (userIdCandidates.isEmpty()) {
            throw IllegalStateException("无法获取课程回放播放身份，请刷新课程回放后重试")
        }
        val (payload, platformUserId) = getCourseReplayDetailPayload(courseSchedId, userIdCandidates)
        return parseCourseReplayPlayback(
            payload = payload,
            courseSchedId = courseSchedId,
            timeTableId = timeTableId,
            courseId = selected.courseId,
            userId = platformUserId,
            listenUserId = context.listenUserId,
            referer = context.referer,
        )
    }

    private suspend fun getCourseReplayDetailPayload(
        courseSchedId: String,
        userIdCandidates: List<String>,
    ): Pair<JsonObject, String> {
        var lastError: Throwable? = null
        userIdCandidates.forEach { candidate ->
            try {
                val payload = getJsonObject(
                    "/ve/back/rp/common/teachCalendar.shtml",
                    mapOf(
                        "method" to "toDisplyCourseSchedDetail",
                        "courseSchedId" to courseSchedId,
                        "userLevel" to "1",
                        "userId" to candidate,
                    ),
                )
                return payload to candidate
            } catch (error: Throwable) {
                lastError = error
                if (!isCourseReplayUserIdRejected(error)) throw error
            }
        }
        if (lastError?.let(::isCourseReplayUserIdRejected) == true) {
            throw IllegalStateException("课程回放播放身份已失效，请刷新课程回放后重试")
        }
        throw lastError ?: IllegalStateException("VE course replay detail request failed")
    }

    suspend fun reportCourseReplayListen(
        userId: String,
        timetableId: String,
        courseId: Int,
        listenTimeSeconds: Long,
    ): Boolean {
        ensureStrictFlow("course replay listen record")
        val payload = getJsonObject(
            "/ve/back/tqa/tqaListenRecord.shtml",
            mapOf(
                "method" to "insertListenRecord",
                "userId" to userId,
                "timetableId" to timetableId,
                "type" to "1",
                "listenTime" to listenTimeSeconds.coerceAtLeast(0).toString(),
                "infoId" to "",
                "cId" to courseId.toString(),
                "listenFrom" to "1",
            ),
        )
        return payload.text("STATUS") in setOf("0", "2")
    }

    private suspend fun ensureStrictFlow(reason: String) {
        if (strictFlowReady && coursePlatformIndexReferer != ProviderConstants.VE_COURSE_PLATFORM_BASE_URL) return
        val ok = bootstrapVeSession()
        if (!ok) {
            val detail = lastBootstrapError?.let { "（$it）" }.orEmpty()
            throw IllegalStateException("VE 会话初始化失败：$reason$detail")
        }
    }

    private suspend fun bootstrapVeSession(): Boolean {
        resetCoursePlatformContext()
        lastBootstrapError = null
        return runCatching {
            strictFlowStep = "mis_module_104_entered"
            val misEntry = getTextWithRetry(
                ProviderConstants.MIS_VE_BRIDGE_URL,
                headers = mapOf("Referer" to ProviderConstants.MIS_HOME_URL),
            )
            rememberSession(misEntry)
            requireExpectedLanding(misEntry.url, "bksy_landing_reached")

            strictFlowStep = "bksycenter_gateway_entered"
            val gateway = getTextWithRetry(
                ProviderConstants.BKSY_VE_BRIDGE_URL,
                headers = mapOf("Referer" to misEntry.url),
            )
            rememberSession(gateway)
            if (gateway.url.contains("Timeout.jsp", ignoreCase = true)) {
                throw IllegalStateException("bksycenter gateway timeout")
            }

            strictFlowStep = "ve_course_platform_index_ready"
            val index = openCoursePlatformIndex(gateway)
            rememberSession(index)
            if (!index.url.contains("123.121.147.7") || !index.url.contains("coursePlatform.shtml")) {
                throw IllegalStateException("unexpected VE index url ${index.url}")
            }
            if (coursePlatformIndexReferer == ProviderConstants.VE_COURSE_PLATFORM_BASE_URL) {
                throw IllegalStateException("course platform index referer not initialized")
            }
            strictFlowReady = true
            true
        }.getOrElse { error ->
            strictFlowReady = false
            lastBootstrapError = "step=$strictFlowStep ${error.message.orEmpty()}".trim()
            false
        }
    }

    private fun resetCoursePlatformContext() {
        sessionId = null
        hasAjaxSession = false
        coursePlatformIndexReferer = ProviderConstants.VE_COURSE_PLATFORM_BASE_URL
        coursePlatformReferer = ProviderConstants.VE_COURSE_PLATFORM_BASE_URL
        strictFlowReady = false
        strictFlowStep = "init"
    }

    private suspend fun getTextWithRetry(
        url: String,
        params: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        attempts: Int = 3,
    ): TextResponse {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return client.getText(url, params, headers)
            } catch (error: Throwable) {
                lastError = error
                if (attempt == attempts - 1) throw error
                delay(400L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("request failed without error")
    }

    private suspend fun openCoursePlatformIndex(gateway: TextResponse): TextResponse {
        return getTextWithRetry(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = mapOf("method" to "toCoursePlatformIndex"),
            headers = mapOf("Referer" to gateway.url),
        )
    }

    private suspend fun warmupAjaxSession() {
        if (sessionId != null && hasAjaxSession) return
        val payload = getJsonObject("/ve/back/coursePlatform/message.shtml", mapOf("method" to "getArticleList"))
        payload.text("sessionId")?.let {
            sessionId = it
            hasAjaxSession = true
        }
    }

    private suspend fun openHomeworkContext(course: CourseSummary): String {
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
        val homeworkPage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course, ProviderConstants.VE_HOMEWORK_COURSE_TO_PAGE, teacherId),
            headers = mapOf("Referer" to coursePlatformReferer),
        )
        rememberCoursePlatformContext(homeworkPage.url, homeworkPage.body)
        return teacherId
    }

    private suspend fun fetchHomeworkAttachments(
        homeworkId: Int,
        courseId: Int,
        teacherId: String,
    ): List<HomeworkAttachment> {
        val payload = getJsonObject(
            "/ve/back/coursePlatform/homeWork.shtml",
            mapOf(
                "method" to "queryStudentCourseNote",
                "id" to homeworkId.toString(),
                "courseId" to courseId.toString(),
                "teacherId" to teacherId,
            ),
        )
        return parseHomeworkAttachments(payload)
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

    private suspend fun openCourseReplayContext(course: CourseSummary): CourseReplayContext {
        val coursePage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course),
            headers = mapOf("Referer" to coursePlatformIndexReferer),
        )
        rememberCoursePlatformContext(coursePage.url, coursePage.body)
        val teacherId = extractInputValue(coursePage.body, "teacherId")
            ?: extractJsStringValue(coursePage.body, "teacherId")
            ?: course.teacherId
            ?: throw IllegalStateException("Course ${course.courseId} is missing teacherId")
        val replayPage = client.getText(
            ProviderConstants.VE_COURSE_PLATFORM_BASE_URL,
            params = buildCoursePageParams(course, ProviderConstants.VE_COURSE_REPLAY_COURSE_TO_PAGE, teacherId),
            headers = mapOf("Referer" to coursePlatformReferer),
        )
        rememberCoursePlatformContext(replayPage.url, replayPage.body)

        val detailUserId = extractJsStringValue(replayPage.body, "uId")
        val listenUserId = extractInputValue(replayPage.body, "userId")
            ?: extractJsStringValue(replayPage.body, "cpersonid")
        val (platformUserId, userInfoLoginId) = runCatching {
            parseVeUserInfo(
                getJsonObject(
                    "/ve/back/coursePlatform/userInfo.shtml",
                    mapOf("method" to "getUserInfo"),
                )
            )
        }.getOrDefault(null to null)

        return CourseReplayContext(
            detailUserId = detailUserId,
            platformUserId = platformUserId,
            listenUserId = listenUserId ?: userInfoLoginId,
            referer = coursePlatformReferer,
        )
    }

    private fun selectCourse(courses: List<CourseSummary>, requestedCourseId: String?): CourseSummary? {
        val requested = requestedCourseId?.trim().orEmpty()
        return if (requested.isNotBlank()) {
            courses.firstOrNull { it.courseId.toString() == requested || it.courseCode == requested }
        } else {
            courses.firstOrNull()
        }
    }

    private data class CourseReplayContext(
        val detailUserId: String?,
        val platformUserId: String?,
        val listenUserId: String?,
        val referer: String,
    )

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
        val isVeApi = path.startsWith("/ve/back/")
        var bootstrapRetried = false
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < 4) {
            try {
                if (isVeApi && (!strictFlowReady || coursePlatformIndexReferer == ProviderConstants.VE_COURSE_PLATFORM_BASE_URL)) {
                    ensureStrictFlow(path)
                }
                if (shouldSendSessionHeader(path, params)) warmupAjaxSession()
                val response = client.getText(
                    ProviderConstants.VE_BASE_URL + path,
                    params = params,
                    headers = jsonHeaders(path, params),
                )
                rememberSession(response)
                val payload = parseJsonObjectResponse(response, path)
                ensurePayloadSuccess(payload, path, params)
                payload.text("sessionId")?.let {
                    sessionId = it
                    hasAjaxSession = true
                }
                return payload
            } catch (error: Throwable) {
                lastError = error
                if (isVeApi && !bootstrapRetried && shouldRebootstrapAfter(error)) {
                    bootstrapRetried = true
                    resetCoursePlatformContext()
                    if (bootstrapVeSession()) {
                        attempt += 1
                        continue
                    }
                }
                if (attempt == 3 || !isRetryableVeRequest(error)) throw error
                delay(600L * (attempt + 1))
                attempt += 1
            }
        }
        throw lastError ?: IOException("VE request failed: $path")
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
        rememberSession(response)
        val payload = parseJsonObjectResponse(response, path)
        ensurePayloadSuccess(payload, path, params)
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
            path == "/ve/back/coursePlatform/homeWork.shtml" && method == "queryStudentCourseNote" -> coursePlatformReferer
            path == "/ve/back/coursePlatform/courseResource.shtml" -> coursePlatformReferer
            path == "/ve/back/coursePlatform/dataSynAction.shtml" -> coursePlatformReferer
            path == "/ve/back/coursePlatform/userInfo.shtml" && method == "getUserInfo" -> coursePlatformReferer
            path == "/ve/back/rp/common/teachCalendar.shtml" && method in setOf("toDisplyTeachCourses", "toDisplyCourseSchedDetail") -> coursePlatformReferer
            path == "/ve/back/tqa/tqaListenRecord.shtml" && method == "insertListenRecord" -> coursePlatformReferer
            else -> coursePlatformIndexReferer
        }
    }

    private fun shouldSendSessionHeader(path: String, params: Map<String, String?>): Boolean {
        val method = params["method"].orEmpty()
        return when {
            path == "/ve/back/coursePlatform/course.shtml" && method in setOf("getCourseList", "getTimeList") -> true
            path == "/ve/back/coursePlatform/homeWork.shtml" && method == "getHomeWorkList" -> true
            path == "/ve/back/coursePlatform/homeWork.shtml" && method == "queryStudentCourseNote" -> true
            path == "/ve/back/coursePlatform/courseResource.shtml" && method == "stuQueryUploadResourceForCourseList" -> true
            path == "/ve/back/coursePlatform/dataSynAction.shtml" && method == "queryStuViewUrl" -> true
            path == "/ve/back/coursePlatform/userInfo.shtml" && method == "getUserInfo" -> true
            path == "/ve/back/rp/common/teachCalendar.shtml" && method in setOf("toDisplyTeachCourses", "toDisplyCourseSchedDetail") -> true
            else -> false
        }
    }

    private fun parseJsonObjectResponse(response: TextResponse, path: String): JsonObject {
        val body = response.body.trimStart()
        if (body.startsWith("<")) {
            throw IOException(
                "VE returned HTML instead of JSON for $path: ${extractHtmlTitle(response.body) ?: response.url}"
            )
        }
        return runCatching {
            cn.edu.bjtu.mis.data.AppJson.parseToJsonElement(response.body).jsonObject
        }.getOrElse { error ->
            throw IOException("VE JSON parse failed for $path: ${error.message}", error)
        }
    }

    private fun extractHtmlTitle(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun shouldRebootstrapAfter(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 401") ||
            message.contains("HTTP 403") ||
            message.contains("HTTP 5") ||
            message.contains("Expected JSON", ignoreCase = true) ||
            message.contains("Json", ignoreCase = true) ||
            message.contains("returned HTML", ignoreCase = true) ||
            message.contains("会话结束")
    }

    private fun isRetryableVeRequest(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return error is IOException || message.contains("HTTP 5")
    }

    private fun ensurePayloadSuccess(payload: JsonObject, path: String, params: Map<String, String?>) {
        val status = payload.text("STATUS")?.lowercase() ?: return
        if (status in setOf("0", "ok", "success", "true")) return
        val method = params["method"].orEmpty()
        if (
            status == "2" &&
            method in setOf("getHomeWorkList", "stuQueryCourseResourceBag", "stuQueryUploadResourceForCourseList", "insertListenRecord")
        ) {
            return
        }
        throw IllegalStateException("VE payload $path STATUS=${payload.text("STATUS")} ERRMSG=${payload.text("ERRMSG") ?: payload.text("message").orEmpty()}")
    }

    private fun rememberSession(response: TextResponse) {
        rememberSession(
            response.url,
            response.body,
            response.headers["Location"],
            response.headers.values("Set-Cookie").joinToString("; "),
        )
    }

    private fun rememberSession(url: String, body: String, location: String?, setCookie: String = "") {
        rememberCoursePlatformContext(url, body)
        listOf(url, body, location.orEmpty(), setCookie).forEach { value ->
            extractSessionId(value)?.let { if (sessionId == null) sessionId = it }
        }
    }

    private fun rememberCoursePlatformContext(url: String, body: String) {
        extractSessionIdFromHtml(body)?.let {
            sessionId = it
            hasAjaxSession = true
        }
        extractSessionIdFromUrl(url)?.let { if (sessionId == null) sessionId = it }
        if (url.contains("coursePlatform.shtml")) {
            if (url.contains("method=toCoursePlatformIndex")) coursePlatformIndexReferer = url
            coursePlatformReferer = url
        }
    }

    private fun extractSessionId(value: String): String? =
        extractSessionIdFromUrl(value)
            ?: extractSessionIdFromCookie(value)
            ?: extractSessionIdFromHtml(value)

    private fun extractSessionIdFromUrl(value: String): String? =
        Regex("""[?&]sessionId=([^&#"'\s]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.get(1)

    private fun extractSessionIdFromCookie(value: String): String? =
        Regex("""(?:^|[;,\s])sessionId=([^;,\s]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.get(1)

    private fun extractSessionIdFromHtml(html: String): String? =
        extractInputValue(html, "sessionId")
            ?: Regex("""(?:var\s+)?sessionId\s*[:=]\s*["']([A-Za-z0-9_-]+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)

    private fun extractInputValue(html: String, fieldName: String): String? =
        listOf(
            Regex("""(?:name|id)=["']${Regex.escape(fieldName)}["'][^>]*value=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""value=["']([^"']*)["'][^>]*(?:name|id)=["']${Regex.escape(fieldName)}["']""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.get(1)
        }?.trim()?.takeIf { it.isNotBlank() }

    private fun requireExpectedLanding(url: String, step: String) {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host in setOf("bksy.bjtu.edu.cn", "bksycenter.bjtu.edu.cn", "123.121.147.7")) return
        throw IllegalStateException("$step expected VE landing, got $url")
    }

    private fun extractJsStringValue(html: String, fieldName: String): String? =
        Regex("""\b(?:var\s+)?$fieldName\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private suspend fun uploadHomeworkFile(
        uploadUrl: String,
        uploadPageUrl: String,
        file: HomeworkUploadFile,
    ): Map<String, String> {
        val parts = filenameParts(file.filename)
        val response = client.postMultipart(
            uploadUrl,
            files = listOf(
                MultipartFilePart(
                    formName = "file",
                    fileName = parts.cleanName,
                    content = file.content,
                    contentType = file.contentType ?: "application/octet-stream",
                )
            ),
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to uploadPageUrl,
            ) + sessionHeader(),
        )
        rememberSession(response)
        val payload = parseJsonObjectResponse(response, "homework upload")
        ensurePayloadSuccess(payload, "homework upload", emptyMap())

        val visitName = payload.text("visitName")
            ?: throw IOException("VE upload response missing visitName")
        val noExt = urlDecode(payload.text("fileNameNoExt") ?: parts.stem).ifBlank { parts.stem }
        val ext = payload.text("fileExtName") ?: parts.extension
        val size = payload.text("fileSize") ?: file.content.size.toString()

        return mapOf(
            "fileNameNoExt" to urlQuote(noExt),
            "fileExtName" to ext,
            "fileSize" to size,
            "visitName" to visitName,
            "pid" to "",
            "ftype" to "insert",
        )
    }

    private fun extractHomeworkUploadUrl(html: String, pageUrl: String): String? {
        val raw = listOf(
            Regex("""url\s*:\s*["']([^"']*rpUpload\.shtml[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']*rpUpload\.shtml[^"']*)["']""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.get(1)
        }?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return if (raw.startsWith("http", ignoreCase = true)) raw else URI(pageUrl).resolve(raw).toString()
    }

    private data class FilenameParts(
        val cleanName: String,
        val stem: String,
        val extension: String,
    )

    private fun filenameParts(filename: String): FilenameParts {
        val cleanName = filename
            .trim()
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .ifBlank { "attachment" }
        val dot = cleanName.lastIndexOf('.')
        val stem = if (dot > 0) cleanName.substring(0, dot) else cleanName
        val extension = if (dot > 0 && dot < cleanName.lastIndex - 1) cleanName.substring(dot + 1) else ""
        return FilenameParts(cleanName, stem.ifBlank { "attachment" }, extension.lowercase())
    }

    private fun urlQuote(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~")

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun JsonObject.objectList(key: String): List<JsonObject> =
        runCatching { this[key]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty() }.getOrDefault(emptyList())

    private fun JsonObject.int(key: String, default: Int): Int =
        text(key)?.toIntOrNull() ?: default

    private fun JsonObject.text(key: String): String? =
        this[key]?.primitiveText()?.takeIf { it.isNotBlank() }

    private fun JsonElement.primitiveText(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()?.trim()

}

internal fun courseReplayUserIdCandidates(
    detailUserId: String?,
    contextUserId: String?,
    preferredUserId: String?,
    listenUserId: String?,
): List<String> =
    listOf(detailUserId, contextUserId, preferredUserId, listenUserId)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .distinct()

private fun isCourseReplayUserIdRejected(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.contains("/ve/back/rp/common/teachCalendar.shtml") &&
        Regex("""STATUS\s*=\s*4""", RegexOption.IGNORE_CASE).containsMatchIn(message)
}
