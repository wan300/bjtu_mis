package cn.edu.bjtu.mis.data.provider

import android.util.Log
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
import cn.edu.bjtu.mis.model.CourseResourceCategory
import cn.edu.bjtu.mis.model.CourseResourceFolder
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CourseSummary
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkSubmitResponse
import cn.edu.bjtu.mis.model.HomeworkUploadFile
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ProgressiveModuleState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val COURSE_RESOURCE_ALL_CATEGORY_KEY = "all"

internal data class CourseResourceCategoryConfig(
    val key: String,
    val label: String,
    val courseToPage: String,
    val docType: String,
)

internal val courseResourceCategoryConfigs: List<CourseResourceCategoryConfig> = listOf(
    CourseResourceCategoryConfig("courseware", "电子课件", "10450", "1"),
    CourseResourceCategoryConfig("lesson_plan", "教案设计", "10451", "5"),
    CourseResourceCategoryConfig("experiment", "实验", "10453", "10"),
)

internal fun courseResourceCategoryModels(): List<CourseResourceCategory> =
    listOf(CourseResourceCategory(COURSE_RESOURCE_ALL_CATEGORY_KEY, "全部")) +
        courseResourceCategoryConfigs.map { CourseResourceCategory(it.key, it.label) }

internal fun normalizeCourseResourceCategoryKey(categoryKey: String?): String {
    val key = categoryKey?.trim().orEmpty()
    return when {
        key.isBlank() || key == COURSE_RESOURCE_ALL_CATEGORY_KEY -> COURSE_RESOURCE_ALL_CATEGORY_KEY
        courseResourceCategoryConfigs.any { it.key == key } -> key
        else -> COURSE_RESOURCE_ALL_CATEGORY_KEY
    }
}

internal fun courseResourceConfigsFor(categoryKey: String?): List<CourseResourceCategoryConfig> {
    val normalized = normalizeCourseResourceCategoryKey(categoryKey)
    return if (normalized == COURSE_RESOURCE_ALL_CATEGORY_KEY) {
        courseResourceCategoryConfigs
    } else {
        courseResourceCategoryConfigs.filter { it.key == normalized }
    }
}

private data class CourseResourceFetchResult(
    val tree: List<CourseResourceFolder>,
    val folders: List<CourseResourceFolder>,
    val resources: List<CourseResourceItem>,
)

internal fun mergeHomeworkItems(items: Iterable<cn.edu.bjtu.mis.model.HomeworkItem>): List<cn.edu.bjtu.mis.model.HomeworkItem> {
    val dedupedItems = linkedMapOf<String, cn.edu.bjtu.mis.model.HomeworkItem>()
    items.forEach { item ->
        val key = item.homeworkId?.let { "id:$it" }
            ?: "course:${item.courseId}:title:${item.title}:due:${item.dueAt.orEmpty()}"
        val previous = dedupedItems[key]
        if (previous == null || (!item.submittedAt.isNullOrBlank() && previous.submittedAt.isNullOrBlank())) {
            dedupedItems[key] = item
        }
    }
    return dedupedItems.values.toList()
}

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
        val dedupedItems = mergeHomeworkItems(items)

        return ModuleEnvelope(
            module = "homework",
            sourceSystem = "ve",
            coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("term", currentTerm)
                put("include_attachments", includeAttachments)
                if (errors.isNotEmpty()) put("partial_error_count", errors.size)
            },
            data = buildHomeworkData(currentTerm, courses, dedupedItems),
        )
    }

    fun fetchHomeworkProgressive(
        term: String? = null,
        includeAttachments: Boolean = true,
    ): Flow<ProgressiveModuleState<HomeworkData>> = flow {
        ensureStrictFlow("homework")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second

        if (currentTerm.isNullOrBlank()) {
            val envelope = ModuleEnvelope(
                module = "homework",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject { put("fallback_reason", "missing_current_term") },
                data = buildHomeworkData(null, emptyList(), emptyList()),
            )
            emit(ProgressiveModuleState(envelope = envelope, loading = false, complete = true))
            return@flow
        }

        val courses = parseCourses(
            getJsonObject(
                "/ve/back/coursePlatform/course.shtml",
                mapOf("method" to "getCourseList", "pagesize" to "100", "page" to "1", "xqCode" to currentTerm),
            )
        )
        val errors = mutableListOf<String>()
        val attachmentCache = mutableMapOf<Pair<Int, Int>, List<HomeworkAttachment>>()
        var items = emptyList<cn.edu.bjtu.mis.model.HomeworkItem>()

        fun currentEnvelope(): ModuleEnvelope<HomeworkData> =
            ModuleEnvelope(
                module = "homework",
                sourceSystem = "ve",
                coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("term", currentTerm)
                    put("include_attachments", includeAttachments)
                    if (errors.isNotEmpty()) put("partial_error_count", errors.size)
                },
                data = buildHomeworkData(currentTerm, courses, items),
            )

        emit(
            ProgressiveModuleState(
                envelope = currentEnvelope(),
                loading = true,
                loadedCount = 0,
                totalCount = null,
                errors = errors.toList(),
            )
        )

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
                            parseHomeworkList(payload, course, subType).forEach { item ->
                                val homeworkId = item.homeworkId
                                val attachments = if (includeAttachments && homeworkId != null) {
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
                                items = mergeHomeworkItems(items + item.copy(attachments = attachments))
                                emit(
                                    ProgressiveModuleState(
                                        envelope = currentEnvelope(),
                                        loading = true,
                                        loadedCount = items.size,
                                        totalCount = null,
                                        errors = errors.toList(),
                                    )
                                )
                            }
                        }.onFailure { errors += "homeWork:${course.courseId}:$subType:${it.message}" }
                    }
                }
        }

        emit(
            ProgressiveModuleState(
                envelope = currentEnvelope(),
                loading = false,
                complete = true,
                loadedCount = items.size,
                totalCount = items.size,
                errors = errors.toList(),
            )
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
        val homeworkLookup = listOf(0, 2).firstNotNullOfOrNull { subType ->
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
            payload.objectList("courseNoteList")
                .firstOrNull { it.text("id") == homeworkId.toString() }
                ?.let { subType to it }
        } ?: throw IllegalStateException("未找到作业 $homeworkId")
        val homeworkSubType = homeworkLookup.first
        val homeworkEntry = homeworkLookup.second

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
        Log.i(TAG, "Opening VE homework submit page: ${homeworkDebugSummary(homeworkId, courseId, homeworkSubType, homeworkEntry)}")
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
        Log.d(
            TAG,
            "VE homework submit page loaded: code=${uploadPage.code}, url=${uploadPage.url}, " +
                "uploadUrlPresent=${uploadUrl != null}, hidden=${homeworkHiddenFieldSummary(uploadPage.body)}"
        )
        val uploadedFiles = if (files.isEmpty()) {
            emptyList()
        } else {
            val targetUrl = uploadUrl ?: throw IllegalStateException(
                "作业提交页缺少附件上传地址。\n诊断信息：${homeworkPageDiagnostic(uploadPage)}"
            )
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
        val submitForm = mapOf(
            "content" to content,
            "groupName" to extractInputValue(uploadPage.body, "groupName").orEmpty(),
            "groupId" to extractInputValue(uploadPage.body, "groupId").orEmpty(),
            "courseId" to (extractInputValue(uploadPage.body, "courseId") ?: courseId.toString()),
            "contentType" to (extractInputValue(uploadPage.body, "contentType") ?: uploadParams.getValue("contentType")),
            "fz" to (extractInputValue(uploadPage.body, "fz") ?: uploadParams.getValue("fz")),
            "jxrl_id" to extractInputValue(uploadPage.body, "jxrl_id").orEmpty(),
            "fileList" to submitPayload,
            "upId" to (extractInputValue(uploadPage.body, "upId") ?: homeworkId.toString()),
            "return_num" to (extractInputValue(uploadPage.body, "return_num") ?: uploadParams.getValue("return_num")),
            "isTeacher" to "0",
        )
        val submitResponse = client.postForm(
            "${ProviderConstants.VE_BASE_URL}/ve/back/course/courseWorkInfo.shtml",
            params = mapOf("method" to "sendStuHomeWorks"),
            form = submitForm,
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to uploadPage.url,
            ) + sessionHeader(),
        )
        rememberSession(submitResponse)
        val payload = parseJsonObjectResponse(submitResponse, "homework submit")
        val flag = (payload.text("flag") ?: payload.text("status")).orEmpty()
        if (!isVeSuccessFlag(flag)) {
            val message = payload.text("message") ?: payload.text("msg") ?: payload.text("error") ?: "VE 作业提交失败"
            val diagnostic = homeworkSubmitDiagnostic(
                homeworkId = homeworkId,
                courseId = courseId,
                subType = homeworkSubType,
                homeworkEntry = homeworkEntry,
                uploadPage = uploadPage,
                submitResponse = submitResponse,
                submitPayload = payload,
                submitForm = submitForm,
            )
            Log.w(TAG, "VE homework submit rejected: $message; $diagnostic")
            throw IOException("$message\n诊断信息：$diagnostic")
        }

        Log.i(TAG, "VE homework submit accepted: flag=$flag, homeworkId=$homeworkId, courseId=$courseId")

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
        categoryKey: String? = null,
    ): ModuleEnvelope<CourseResourcesData> {
        ensureStrictFlow("course resources")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second
        val selectedCategoryKey = normalizeCourseResourceCategoryKey(categoryKey)
        val categoryConfigs = courseResourceConfigsFor(selectedCategoryKey)
        val categories = courseResourceCategoryModels()

        if (currentTerm.isNullOrBlank()) {
            return ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("fallback_reason", "missing_current_term")
                    put("category_key", selectedCategoryKey)
                },
                data = buildCourseResourcesData(
                    null,
                    emptyList(),
                    null,
                    folderId,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    categories = categories,
                    selectedCategoryKey = selectedCategoryKey,
                ),
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
            put("category_key", selectedCategoryKey)
            put("category_keys", categoryConfigs.joinToString(",") { it.key })
        }

        if (selected == null) {
            return ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Verified,
                sourceParams = sourceParams,
                data = buildCourseResourcesData(
                    currentTerm,
                    courses,
                    null,
                    normalizedFolder,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    categories = categories,
                    selectedCategoryKey = selectedCategoryKey,
                ),
            )
        }

        val results = mutableListOf<CourseResourceFetchResult>()
        val errors = mutableListOf<String>()
        categoryConfigs.forEach { category ->
            runCatching {
                fetchCourseResourceCategory(selected, category, normalizedFolder, search)
            }.onSuccess {
                results += it
            }.onFailure { error ->
                errors += courseResourceCategoryError(category, error)
            }
        }
        val merged = mergeCourseResourceFetchResults(results)
        return ModuleEnvelope(
            module = "course_resources",
            sourceSystem = "ve",
            coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                sourceParams.forEach { (key, value) -> put(key, value) }
                if (errors.isNotEmpty()) put("fallback_reason", errors.joinToString("; "))
            },
            data = buildCourseResourcesData(
                currentTerm,
                courses,
                selected,
                normalizedFolder,
                merged.tree,
                merged.folders,
                merged.resources,
                categories = categories,
                selectedCategoryKey = selectedCategoryKey,
            )
        )
    }

    fun fetchCourseResourcesProgressive(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
        categoryKey: String? = null,
    ): Flow<ProgressiveModuleState<CourseResourcesData>> = flow {
        ensureStrictFlow("course resources")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second
        val selectedCategoryKey = normalizeCourseResourceCategoryKey(categoryKey)
        val categoryConfigs = courseResourceConfigsFor(selectedCategoryKey)
        val categories = courseResourceCategoryModels()

        if (currentTerm.isNullOrBlank()) {
            val envelope = ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("fallback_reason", "missing_current_term")
                    put("category_key", selectedCategoryKey)
                },
                data = buildCourseResourcesData(
                    null,
                    emptyList(),
                    null,
                    folderId,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    categories = categories,
                    selectedCategoryKey = selectedCategoryKey,
                ),
            )
            emit(ProgressiveModuleState(envelope = envelope, loading = false, complete = true))
            return@flow
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
            put("category_key", selectedCategoryKey)
            put("category_keys", categoryConfigs.joinToString(",") { it.key })
        }

        fun envelope(
            coverage: CoverageLevel,
            tree: List<CourseResourceFolder> = emptyList(),
            folders: List<CourseResourceFolder> = emptyList(),
            resources: List<CourseResourceItem> = emptyList(),
            errors: List<String> = emptyList(),
        ): ModuleEnvelope<CourseResourcesData> =
            ModuleEnvelope(
                module = "course_resources",
                sourceSystem = "ve",
                coverage = coverage,
                sourceParams = buildJsonObject {
                    sourceParams.forEach { (key, value) -> put(key, value) }
                    if (errors.isNotEmpty()) put("fallback_reason", errors.joinToString("; "))
                },
                data = buildCourseResourcesData(
                    currentTerm,
                    courses,
                    selected,
                    normalizedFolder,
                    tree,
                    folders,
                    resources,
                    categories = categories,
                    selectedCategoryKey = selectedCategoryKey,
                ),
            )

        val courseEnvelope = envelope(CoverageLevel.Provisional)
        emit(
            ProgressiveModuleState(
                envelope = courseEnvelope,
                loading = selected != null,
                complete = selected == null,
                loadedCount = 1,
                totalCount = if (selected == null) 1 else 1 + categoryConfigs.size,
            )
        )
        if (selected == null) return@flow

        val results = mutableListOf<CourseResourceFetchResult>()
        val errors = mutableListOf<String>()
        categoryConfigs.forEachIndexed { index, category ->
            runCatching {
                fetchCourseResourceCategory(selected, category, normalizedFolder, search)
            }.onSuccess {
                results += it
            }.onFailure { error ->
                errors += courseResourceCategoryError(category, error)
            }
            val merged = mergeCourseResourceFetchResults(results)
            emit(
                ProgressiveModuleState(
                    envelope = envelope(
                        coverage = CoverageLevel.Provisional,
                        tree = merged.tree,
                        folders = merged.folders,
                        resources = merged.resources,
                        errors = errors,
                    ),
                    loading = index < categoryConfigs.lastIndex,
                    complete = false,
                    loadedCount = 2 + index,
                    totalCount = 1 + categoryConfigs.size,
                    errors = errors,
                )
            )
        }
        val merged = mergeCourseResourceFetchResults(results)
        emit(
            ProgressiveModuleState(
                envelope = envelope(
                    coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
                    tree = merged.tree,
                    folders = merged.folders,
                    resources = merged.resources,
                    errors = errors,
                ),
                loading = false,
                complete = true,
                loadedCount = 1 + categoryConfigs.size,
                totalCount = 1 + categoryConfigs.size,
                errors = errors,
            )
        )
    }

    private suspend fun fetchCourseResourceCategory(
        course: CourseSummary,
        category: CourseResourceCategoryConfig,
        folderId: String,
        search: String?,
    ): CourseResourceFetchResult {
        val context = openCourseResourcesContext(course, category.courseToPage)
        val baseParams = mapOf(
            "courseId" to context["courseId"],
            "cId" to context["cId"],
            "xkhId" to context["xkhId"],
            "xqCode" to context["xqCode"],
            "docType" to category.docType,
        )
        val treePayload = getJsonObject(
            "/ve/back/coursePlatform/courseResource.shtml",
            mapOf("method" to "stuQueryCourseResourceBag") + baseParams,
        )
        val listingPayload = getJsonObject(
            "/ve/back/coursePlatform/courseResource.shtml",
            mapOf(
                "method" to "stuQueryUploadResourceForCourseList",
                "up_id" to folderId,
                "searchName" to search.orEmpty(),
            ) + baseParams,
        )
        val (folders, resources) = parseCourseResourceListing(
            listingPayload,
            folderId,
            category.key,
            category.label,
        )
        return CourseResourceFetchResult(
            tree = parseCourseResourceTree(treePayload, category.key, category.label),
            folders = folders,
            resources = resources,
        )
    }

    private fun mergeCourseResourceFetchResults(results: List<CourseResourceFetchResult>): CourseResourceFetchResult =
        CourseResourceFetchResult(
            tree = results.flatMap { it.tree },
            folders = results.flatMap { it.folders },
            resources = results.flatMap { it.resources },
        )

    private fun courseResourceCategoryError(category: CourseResourceCategoryConfig, error: Throwable): String =
        "${category.label}: ${error.message ?: "course_resources_detail_failed"}"

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

    suspend fun previewCourseResource(resource: CourseResourceItem): String {
        ensureStrictFlow("course resource preview")
        val previewId = resource.resId?.trim().orEmpty().ifBlank { resource.rpId.trim() }
        val payloadResult = if (previewId.isNotBlank()) {
            runCatching {
                getJsonObject(
                    "/ve/back/coursePlatform/dataSynAction.shtml",
                    mapOf(
                        "method" to "getFilePlayUrl",
                        "id" to previewId,
                        "type" to "2",
                    ),
                )
            }
        } else {
            Result.failure(IllegalStateException("资源缺少预览标识"))
        }
        payloadResult.getOrNull()
            ?.text("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalizeCourseResourcePreviewUrl(it) }

        resource.playUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { return buildCourseResourceOnlinePreviewUrl(it) }

        throw payloadResult.exceptionOrNull() ?: IllegalStateException("暂无预览地址")
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

    fun fetchCourseReplaysProgressive(
        term: String? = null,
        courseId: String? = null,
    ): Flow<ProgressiveModuleState<CourseReplayData>> = flow {
        ensureStrictFlow("course replay")
        val currentTerm = term ?: parseCalendarTerms(
            getJsonObject("/ve/back/rp/common/teachCalendar.shtml", mapOf("method" to "queryCurrentXq"))
        ).second

        if (currentTerm.isNullOrBlank()) {
            val envelope = ModuleEnvelope(
                module = "course_replay",
                sourceSystem = "ve",
                coverage = CoverageLevel.Provisional,
                sourceParams = buildJsonObject { put("fallback_reason", "missing_current_term") },
                data = buildCourseReplayData(null, emptyList(), null, null, null, emptyList()),
            )
            emit(ProgressiveModuleState(envelope = envelope, loading = false, complete = true))
            return@flow
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

        fun envelope(
            coverage: CoverageLevel,
            userId: String? = null,
            listenUserId: String? = null,
            lessons: List<cn.edu.bjtu.mis.model.CourseReplayLesson> = emptyList(),
            error: Throwable? = null,
        ): ModuleEnvelope<CourseReplayData> =
            ModuleEnvelope(
                module = "course_replay",
                sourceSystem = "ve",
                coverage = coverage,
                sourceParams = buildJsonObject {
                    sourceParams.forEach { (key, value) -> put(key, value) }
                    error?.message?.let { put("fallback_reason", it) }
                },
                data = buildCourseReplayData(currentTerm, courses, selected, userId, listenUserId, lessons),
            )

        emit(
            ProgressiveModuleState(
                envelope = envelope(CoverageLevel.Provisional),
                loading = selected != null,
                complete = selected == null,
                loadedCount = 1,
                totalCount = if (selected == null) 1 else 2,
            )
        )
        if (selected == null) return@flow

        runCatching {
            val context = openCourseReplayContext(selected)
            val payload = getJsonObject(
                "/ve/back/rp/common/teachCalendar.shtml",
                mapOf("method" to "toDisplyTeachCourses", "courseId" to selected.courseId.toString()),
            )
            envelope(
                coverage = CoverageLevel.Verified,
                userId = context.detailUserId ?: context.platformUserId,
                listenUserId = context.listenUserId,
                lessons = parseCourseReplayLessons(payload),
            )
        }.onSuccess {
            emit(
                ProgressiveModuleState(
                    envelope = it,
                    loading = false,
                    complete = true,
                    loadedCount = 2,
                    totalCount = 2,
                )
            )
        }.onFailure { error ->
            emit(
                ProgressiveModuleState(
                    envelope = envelope(CoverageLevel.Provisional, error = error),
                    loading = false,
                    complete = true,
                    loadedCount = 1,
                    totalCount = 2,
                    errors = listOf(error.message ?: "course_replay_detail_failed"),
                )
            )
        }
    }

    suspend fun fetchCourseReplayPlayback(
        term: String? = null,
        courseId: String? = null,
        courseSchedId: String,
        userId: String? = null,
        timeTableId: String? = null,
        videoId: String? = null,
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
        val (payload, platformUserId) = getCourseReplayDetailPayload(
            courseSchedId = courseSchedId,
            userIdCandidates = userIdCandidates,
            timeTableId = timeTableId,
            videoId = videoId,
        )
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
        timeTableId: String? = null,
        videoId: String? = null,
    ): Pair<JsonObject, String> {
        var lastError: Throwable? = null
        val hasLessonIdentity = !videoId.isNullOrBlank() || !timeTableId.isNullOrBlank()
        val detailParamVariants = buildList {
            add(
                courseReplayDetailParams(
                    courseSchedId = courseSchedId,
                    userId = "",
                    videoId = videoId,
                    timeTableId = timeTableId,
                )
            )
            if (hasLessonIdentity) {
                add(courseReplayDetailParams(courseSchedId = courseSchedId, userId = ""))
            }
        }

        detailParamVariants.forEach { baseParams ->
            userIdCandidates.forEach { candidate ->
                try {
                    val payload = getJsonObject(
                        "/ve/back/rp/common/teachCalendar.shtml",
                        baseParams + ("userId" to candidate),
                    )
                    return payload to candidate
                } catch (error: Throwable) {
                    lastError = error
                    if (!isCourseReplayUserIdRejected(error)) throw error
                }
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
            if (isBjtuCasLoginUrl(index.url)) {
                throw SessionExpiredException("VE course platform redirected to CAS login.")
            }
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
            if (error is SessionExpiredException) throw error
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

    private suspend fun openCourseResourcesContext(course: CourseSummary, courseToPage: String): Map<String, String> {
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
            params = buildCoursePageParams(course, courseToPage, teacherId),
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
            path == "/ve/back/coursePlatform/dataSynAction.shtml" && method == "getFilePlayUrl" -> true
            path == "/ve/back/coursePlatform/userInfo.shtml" && method == "getUserInfo" -> true
            path == "/ve/back/rp/common/teachCalendar.shtml" && method in setOf("toDisplyTeachCourses", "toDisplyCourseSchedDetail") -> true
            else -> false
        }
    }

    private fun parseJsonObjectResponse(response: TextResponse, path: String): JsonObject {
        val body = response.body.trimStart()
        if (isBjtuCasLoginUrl(response.url)) {
            throw SessionExpiredException("VE request $path redirected to CAS login.")
        }
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
        throw VePayloadStatusException(
            path = path,
            status = payload.text("STATUS").orEmpty(),
            errorMessage = payload.text("ERRMSG") ?: payload.text("message").orEmpty(),
            params = params,
        )
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
        if (isBjtuCasLoginUrl(url)) {
            throw SessionExpiredException("$step redirected to CAS login.")
        }
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

    private fun isVeSuccessFlag(value: String): Boolean =
        value.trim().equals("success", ignoreCase = true)

    private fun homeworkDebugSummary(
        homeworkId: Int,
        courseId: Int,
        subType: Int,
        entry: JsonObject,
    ): String =
        listOf(
            "homeworkId=$homeworkId",
            "courseId=$courseId",
            "subType=$subType",
            "subStatus=${entry.text("subStatus").orEmpty()}",
            "can_submit=${entry.text("can_submit") ?: entry.text("canSubmit").orEmpty()}",
            "end_time=${entry.text("end_time").orEmpty()}",
            "return_num=${entry.text("return_num").orEmpty()}",
            "calendar_id=${entry.text("calendar_id").orEmpty()}",
        ).joinToString(", ")

    private fun homeworkHiddenFieldSummary(html: String): String =
        listOf("groupName", "groupId", "courseId", "contentType", "fz", "jxrl_id", "upId", "return_num")
            .joinToString(", ") { name ->
                val value = extractInputValue(html, name)
                val state = if (value.isNullOrBlank()) "<missing>" else "<present:${value.length}>"
                "$name=$state"
            }

    private fun homeworkPageDiagnostic(response: TextResponse): String =
        "pageCode=${response.code}, pageUrl=${response.url}, title=${extractHtmlTitle(response.body).orEmpty()}, " +
            "hidden=${homeworkHiddenFieldSummary(response.body)}, bodyLength=${response.body.length}"

    private fun homeworkSubmitDiagnostic(
        homeworkId: Int,
        courseId: Int,
        subType: Int,
        homeworkEntry: JsonObject,
        uploadPage: TextResponse,
        submitResponse: TextResponse,
        submitPayload: JsonObject,
        submitForm: Map<String, String>,
    ): String =
        listOf(
            homeworkDebugSummary(homeworkId, courseId, subType, homeworkEntry),
            "uploadPage=${homeworkPageDiagnostic(uploadPage)}",
            "submitCode=${submitResponse.code}",
            "submitUrl=${submitResponse.url}",
            "submitForm=${homeworkSubmitFormSummary(submitForm)}",
            "submitJsonKeys=${submitPayload.keys.sorted().joinToString(",")}",
            "submitBodyLength=${submitResponse.body.length}",
        ).joinToString("; ")

    private fun homeworkSubmitFormSummary(form: Map<String, String>): String =
        listOf("courseId", "contentType", "fz", "jxrl_id", "upId", "return_num", "isTeacher")
            .joinToString(", ") { name -> "$name=${form[name].orEmpty().ifBlank { "<blank>" }}" } +
            ", contentLength=${form["content"].orEmpty().length}, fileListLength=${form["fileList"].orEmpty().length}"

    private fun JsonObject.objectList(key: String): List<JsonObject> =
        runCatching { this[key]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty() }.getOrDefault(emptyList())

    private fun JsonObject.int(key: String, default: Int): Int =
        text(key)?.toIntOrNull() ?: default

    private fun JsonObject.text(key: String): String? =
        this[key]?.primitiveText()?.takeIf { it.isNotBlank() }

    private fun JsonElement.primitiveText(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()?.trim()

    private companion object {
        const val TAG = "VeProvider"
    }

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

internal fun normalizeCourseResourcePreviewUrl(url: String): String {
    val clean = url.trim()
    return when {
        clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
        clean.startsWith("/onlinePreview", ignoreCase = true) -> "${ProviderConstants.VE_KK_PREVIEW_BASE_URL}$clean"
        clean.startsWith("/kk/", ignoreCase = true) -> "http://123.121.147.7:1936$clean"
        clean.startsWith("/rp/", ignoreCase = true) -> buildCourseResourceOnlinePreviewUrl(clean)
        else -> buildCourseResourceOnlinePreviewUrl(clean)
    }
}

internal fun buildCourseResourceOnlinePreviewUrl(playUrl: String): String {
    val fileUrl = courseResourcePreviewFileUrl(playUrl)
    val encoded = URLEncoder.encode(
        Base64.getEncoder().encodeToString(fileUrl.toByteArray(StandardCharsets.UTF_8)),
        StandardCharsets.UTF_8.name(),
    )
    return "${ProviderConstants.VE_KK_PREVIEW_BASE_URL}/onlinePreview?url=$encoded"
}

internal fun courseResourcePreviewFileUrl(playUrl: String): String {
    val clean = playUrl.trim()
    return when {
        clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
        clean.startsWith("/kk/", ignoreCase = true) -> "http://123.121.147.7:1936$clean"
        clean.startsWith("/") -> "${ProviderConstants.VE_KK_PREVIEW_BASE_URL}$clean"
        else -> "${ProviderConstants.VE_KK_PREVIEW_BASE_URL}/$clean"
    }
}

internal fun courseReplayDetailParams(
    courseSchedId: String,
    userId: String,
    videoId: String? = null,
    timeTableId: String? = null,
): Map<String, String?> = buildMap {
    put("method", "toDisplyCourseSchedDetail")
    put("courseSchedId", courseSchedId.trim())
    put("userLevel", "1")
    put("userId", userId.trim())
    videoId?.trim()?.takeIf(String::isNotBlank)?.let { put("videoId", it) }
    timeTableId?.trim()?.takeIf(String::isNotBlank)?.let {
        put("uuid", it)
        put("timeTableId", it)
        put("timetableId", it)
    }
}

private class VePayloadStatusException(
    val path: String,
    val status: String,
    val errorMessage: String,
    val params: Map<String, String?>,
) : IllegalStateException("VE payload $path STATUS=$status ERRMSG=$errorMessage")

private fun isCourseReplayUserIdRejected(error: Throwable): Boolean {
    if (error is VePayloadStatusException) {
        return error.path == "/ve/back/rp/common/teachCalendar.shtml" &&
            error.params["method"] == "toDisplyCourseSchedDetail" &&
            error.status.trim() == "4"
    }
    val message = error.message.orEmpty()
    return message.contains("/ve/back/rp/common/teachCalendar.shtml") &&
        Regex("""STATUS\s*=\s*4""", RegexOption.IGNORE_CASE).containsMatchIn(message)
}
