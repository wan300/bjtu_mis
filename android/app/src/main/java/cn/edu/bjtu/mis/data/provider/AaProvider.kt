package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.parseAcademicProgress
import cn.edu.bjtu.mis.data.parser.parseAcademicProgressDetailPath
import cn.edu.bjtu.mis.data.parser.ParsedCourseSelectionPage
import cn.edu.bjtu.mis.data.parser.parseCourseSelectionCaptcha
import cn.edu.bjtu.mis.data.parser.parseCourseSelectionPage
import cn.edu.bjtu.mis.data.parser.parseEmptyRooms
import cn.edu.bjtu.mis.data.parser.parseExams
import cn.edu.bjtu.mis.data.parser.parseScoreDetail
import cn.edu.bjtu.mis.data.parser.parseScorecardProgress
import cn.edu.bjtu.mis.data.parser.parseScores
import cn.edu.bjtu.mis.data.parser.parseStudentStatusProfile
import cn.edu.bjtu.mis.data.parser.parseTeachingAssessmentForm
import cn.edu.bjtu.mis.data.parser.parseTeachingAssessmentList
import cn.edu.bjtu.mis.data.parser.parseTimetable
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.CourseSelectionAttemptResult
import cn.edu.bjtu.mis.model.CourseSelectionCaptchaChallenge
import cn.edu.bjtu.mis.model.CourseSelectionCourse
import cn.edu.bjtu.mis.model.CourseSelectionData
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TeachingAssessmentData
import cn.edu.bjtu.mis.model.TeachingAssessmentForm
import cn.edu.bjtu.mis.model.TeachingAssessmentSubmitResult
import cn.edu.bjtu.mis.model.TimetableData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

private const val HISTORY_ALL_TERMS = "all"
private const val AA_RETRY_ATTEMPTS = 3
private const val AA_COURSE_SELECTION_PATH = "/course_selection/courseselecttask/selects/"
private const val AA_TEACHING_ASSESSMENT_LIST_PATH = "/teaching_assessment/stu/list/"

internal fun mergeScoreItems(items: Iterable<ScoreItem>): List<ScoreItem> =
    items.distinctBy { item ->
        listOf(
            item.term,
            item.courseName,
            item.credit,
            item.score,
            item.bonusScore,
            item.teacher,
            item.detail,
            item.detailPath,
        ).joinToString("|")
    }

private data class PendingCourseSelectionCaptcha(
    val courseKey: String,
    val courseName: String,
    val inputName: String,
    val fields: Map<String, String>,
)

private object CourseSelectionCaptchaStore {
    val values = mutableMapOf<String, PendingCourseSelectionCaptcha>()
}

class AaProvider(
    private val client: BjtuHttpClient,
    private val aaBaseUrl: String = ProviderConstants.AA_BASE_URL,
) {
    private val courseSelectionUrl: String
        get() = "$aaBaseUrl$AA_COURSE_SELECTION_PATH"
    private val teachingAssessmentListUrl: String
        get() = "$aaBaseUrl$AA_TEACHING_ASSESSMENT_LIST_PATH"

    suspend fun fetchTimetable(term: String? = null, week: String? = null): ModuleEnvelope<TimetableData> {
        val html = getText("/course_selection/courseselect/stuschedule/")
        return ModuleEnvelope(
            module = "timetable",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                term?.let { put("term", it) }
                week?.let { put("week", it) }
            },
            data = parseTimetable(html),
        )
    }

    suspend fun fetchCourseSelection(): ModuleEnvelope<CourseSelectionData> {
        val html = getText(AA_COURSE_SELECTION_PATH)
        val parsed = parseCourseSelectionPage(html, courseSelectionUrl)
        return ModuleEnvelope(
            module = "course_selection",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            data = parsed.data,
        )
    }

    suspend fun fetchTeachingAssessmentList(): ModuleEnvelope<TeachingAssessmentData> {
        val html = getText(AA_TEACHING_ASSESSMENT_LIST_PATH)
        val parsed = parseTeachingAssessmentList(html, teachingAssessmentListUrl)
        return ModuleEnvelope(
            module = "teaching_assessment",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            data = parsed,
        )
    }

    suspend fun fetchTeachingAssessmentForm(courseId: String): TeachingAssessmentForm {
        val cleanedId = courseId.trim()
        require(cleanedId.isNotBlank()) { "评教课程 ID 不能为空。" }
        val path = "/teaching_assessment/stu/$cleanedId/update/"
        val html = getText(path)
        return parseTeachingAssessmentForm(html, "$aaBaseUrl$path")
    }

    suspend fun submitTeachingAssessment(
        form: TeachingAssessmentForm,
        answerValues: Map<String, String>,
        commentValues: Map<String, String>,
    ): TeachingAssessmentSubmitResult {
        if (form.unsupportedMultiCount > 0) {
            return TeachingAssessmentSubmitResult(
                courseId = form.courseId,
                status = "unsupported",
                message = "该评教表包含暂不支持的多选题，请到原系统提交。",
                success = false,
            )
        }
        if (form.questions.isEmpty() && form.comments.isEmpty()) {
            return TeachingAssessmentSubmitResult(
                courseId = form.courseId,
                status = "empty_form",
                message = "未解析到可提交的评教题目。",
                success = false,
            )
        }
        val missing = form.questions.filter { question ->
            answerValues[question.name].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            return TeachingAssessmentSubmitResult(
                courseId = form.courseId,
                status = "missing_answers",
                message = "还有 ${missing.size} 道单选题未选择。",
                success = false,
            )
        }

        val fields = form.fields.toMutableMap()
        form.questions.forEach { question ->
            fields[question.name] = answerValues.getValue(question.name)
        }
        form.comments.forEach { comment ->
            fields[comment.name] = commentValues[comment.name].orEmpty()
        }

        val response = if (form.method.lowercase() == "get") {
            client.getText(form.actionUrl, fields, headers = mapOf("Referer" to form.referer))
        } else {
            client.postForm(
                form.actionUrl,
                form = fields,
                headers = mapOf(
                    "Referer" to form.referer,
                    "Origin" to aaBaseUrl,
                ),
            )
        }
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        val list = parseTeachingAssessmentList(response.body, response.url)
        val refreshed = list.courses.firstOrNull { it.id == form.courseId }
        val success = response.body.contains("评教成功") ||
            refreshed?.canEvaluate == false ||
            refreshed?.status?.contains("已") == true
        return TeachingAssessmentSubmitResult(
            courseId = form.courseId,
            status = if (success) "success" else "submitted",
            message = if (success) "评教成功。" else "已提交评教请求，请刷新列表确认结果。",
            success = success,
            course = refreshed,
        )
    }

    suspend fun attemptCourseSelection(courseKey: String? = null, courseName: String? = null): CourseSelectionAttemptResult {
        val html = getText(AA_COURSE_SELECTION_PATH)
        val parsed = parseCourseSelectionPage(html, courseSelectionUrl)
        val target = findCourseSelectionTarget(parsed.data.availableCourses + parsed.data.selectedCourses, courseKey, courseName)
            ?: return CourseSelectionAttemptResult(status = "not_found", message = "未找到匹配课程。")
        if (target.selected) {
            return CourseSelectionAttemptResult(status = "already_selected", message = "课程已选中。", course = target)
        }
        if (target.remaining != null && target.remaining <= 0) {
            return CourseSelectionAttemptResult(status = "no_remaining", message = "课程余量为 0。", course = target)
        }
        val action = parsed.actions[target.key]
            ?: return CourseSelectionAttemptResult(
                status = "unparseable",
                message = parsed.data.submitError ?: "无法解析选课提交入口。",
                course = target,
            )
        val response = submitCourseSelectionAction(action.actionUrl, action.method, action.fields)
        buildCourseSelectionCaptcha(response.body, response.url, target.key, target.courseName)?.let { captcha ->
            return CourseSelectionAttemptResult(
                status = "captcha_required",
                message = "需要输入验证码后继续提交。",
                course = target,
                captchaChallenge = captcha,
            )
        }
        val refreshed = fetchCourseSelection().data
        val refreshedTarget = findCourseSelectionTarget(
            refreshed.selectedCourses + refreshed.availableCourses,
            target.key,
            target.courseName,
        )
        if (refreshedTarget?.selected == true) {
            return CourseSelectionAttemptResult(status = "success", message = "选课成功。", course = refreshedTarget)
        }
        return CourseSelectionAttemptResult(
            status = "submitted",
            message = "已提交选课请求，请刷新列表确认结果。",
            course = refreshedTarget ?: target,
        )
    }

    suspend fun submitCourseSelectionCaptcha(challengeId: String, captcha: String): CourseSelectionAttemptResult {
        val state = CourseSelectionCaptchaStore.values.remove(challengeId)
            ?: return CourseSelectionAttemptResult(status = "captcha_expired", message = "验证码上下文已失效，请重新尝试选课。")
        val actionUrl = state.fields["__action__"]
            ?: return CourseSelectionAttemptResult(status = "unparseable", message = "无法解析验证码提交入口。")
        val fields = state.fields
            .filterKeys { it != "__action__" }
            .toMutableMap()
            .also { it[state.inputName] = captcha.trim() }
        val response = submitCourseSelectionAction(actionUrl, "post", fields)
        val refreshed = fetchCourseSelection().data
        val refreshedTarget = findCourseSelectionTarget(
            refreshed.selectedCourses + refreshed.availableCourses,
            state.courseKey,
            state.courseName,
        )
        if (refreshedTarget?.selected == true) {
            return CourseSelectionAttemptResult(status = "success", message = "选课成功。", course = refreshedTarget)
        }
        buildCourseSelectionCaptcha(response.body, response.url, state.courseKey, state.courseName)?.let { next ->
            return CourseSelectionAttemptResult(
                status = "captcha_required",
                message = "验证码未通过或需要再次输入。",
                course = refreshedTarget,
                captchaChallenge = next,
            )
        }
        return CourseSelectionAttemptResult(
            status = "submitted",
            message = "验证码已提交，请刷新列表确认结果。",
            course = refreshedTarget,
        )
    }

    suspend fun dropCourseSelection(courseKey: String? = null, courseName: String? = null): CourseSelectionAttemptResult {
        val html = getText(AA_COURSE_SELECTION_PATH)
        val parsed = parseCourseSelectionPage(html, courseSelectionUrl)
        return dropCourseSelectionFromParsed(parsed, courseKey, courseName)
    }

    suspend fun replaceCourseSelection(
        targetCourseKey: String? = null,
        targetCourseName: String? = null,
        dropCourseKey: String? = null,
        dropCourseName: String? = null,
    ): CourseSelectionAttemptResult {
        val html = getText(AA_COURSE_SELECTION_PATH)
        val parsed = parseCourseSelectionPage(html, courseSelectionUrl)
        val target = findCourseSelectionTarget(
            parsed.data.availableCourses + parsed.data.selectedCourses,
            targetCourseKey,
            targetCourseName,
        ) ?: return CourseSelectionAttemptResult(status = "not_found", message = "未找到目标课程。")
        if (target.selected) {
            return CourseSelectionAttemptResult(status = "replace_success", message = "目标课程已在已选列表中。", course = target)
        }
        if (target.remaining != null && target.remaining <= 0) {
            return CourseSelectionAttemptResult(status = "target_no_remaining", message = "目标课程余量为 0。", course = target)
        }

        val dropResult = dropCourseSelectionFromParsed(parsed, dropCourseKey, dropCourseName)
        if (dropResult.status != "drop_success") {
            return CourseSelectionAttemptResult(
                status = "drop_failed",
                message = dropResult.message ?: "退课失败，未继续抢目标课程。",
                course = dropResult.course,
            )
        }

        val selectResult = attemptCourseSelection(target.key, target.courseName)
        if (selectResult.status in setOf("success", "already_selected")) {
            return CourseSelectionAttemptResult(
                status = "replace_success",
                message = "换课成功。",
                course = selectResult.course ?: target,
            )
        }
        if (selectResult.status == "captcha_required") {
            return selectResult
        }

        val rollbackResult = attemptCourseSelection(
            courseKey = dropResult.course?.key ?: dropCourseKey,
            courseName = dropResult.course?.courseName ?: dropCourseName,
        )
        if (rollbackResult.status in setOf("success", "already_selected")) {
            return CourseSelectionAttemptResult(
                status = "rollback_success",
                message = "目标课程选课失败，已尝试把原课程选回。${selectResult.message ?: selectResult.status}",
                course = rollbackResult.course ?: dropResult.course,
            )
        }
        return CourseSelectionAttemptResult(
            status = "rollback_failed",
            message = "目标课程选课失败，且原课程回滚失败。选课结果：${selectResult.message ?: selectResult.status}；回滚结果：${rollbackResult.message ?: rollbackResult.status}",
            course = rollbackResult.course ?: dropResult.course,
        )
    }

    suspend fun fetchExams(term: String? = null): ModuleEnvelope<ExamData> {
        val html = getText("/examine/examplanstudent/stulist/", mapOf("zxjxjhh" to term))
        val parsed = parseExams(html, term)
        return ModuleEnvelope(
            module = "exams",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { parsed.currentTerm?.let { put("term", it) } },
            data = parsed,
        )
    }

    suspend fun fetchScores(term: String? = null, ctype: String? = null): ModuleEnvelope<ScoreData> {
        val requestedTerm = term?.takeIf { it.isNotBlank() }
        val params = mapOf("zxjxjhh" to requestedTerm, "ctype" to ctype)
        val html = runCatching { getText("/score/scores/stu/view/", params) }
            .getOrElse { error ->
                if (!isRetryableAaFailure(error)) throw error
                return ModuleEnvelope(
                    module = "scores",
                    sourceSystem = "aa",
                    coverage = CoverageLevel.Provisional,
                    sourceParams = buildJsonObject {
                        requestedTerm?.let { put("term", it) }
                        ctype?.let { put("ctype", it) }
                        put("fallback_reason", error.message.orEmpty())
                    },
                    data = ScoreData(currentTerm = requestedTerm),
                )
            }
        val retryErrors = mutableListOf<String>()
        var parsed = parseScores(html, requestedTerm)
        if (parsed.items.isEmpty() && requestedTerm == null && parsed.availableTerms.isNotEmpty()) {
            for (option in parsed.availableTerms.take(10)) {
                val candidate = option.value
                if (candidate.isBlank() || candidate == parsed.currentTerm) continue
                val retryParams = mapOf("zxjxjhh" to candidate, "ctype" to ctype)
                runCatching {
                    val retryHtml = getText("/score/scores/stu/view/", retryParams)
                    parseScores(retryHtml, candidate)
                }.onSuccess { retryParsed ->
                    if (retryParsed.items.isNotEmpty()) {
                        parsed = retryParsed
                        return@onSuccess
                    }
                }.onFailure { error ->
                    if (!isRetryableAaFailure(error)) throw error
                    retryErrors += "term=$candidate ${error.message.orEmpty()}".trim()
                }
                if (parsed.items.isNotEmpty()) break
            }
        }
        return ModuleEnvelope(
            module = "scores",
            sourceSystem = "aa",
            coverage = if (retryErrors.isEmpty() || parsed.items.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                parsed.currentTerm?.let { put("term", it) }
                ctype?.let { put("ctype", it) }
                if (retryErrors.isNotEmpty()) put("partial_error_count", retryErrors.size)
            },
            data = parsed,
        )
    }

    suspend fun fetchHistoryScores(term: String? = null): ModuleEnvelope<ScoreData> {
        val requestedTerm = term?.takeIf { it.isNotBlank() && it != HISTORY_ALL_TERMS }
        if (requestedTerm != null) {
            return fetchScores(term = requestedTerm, ctype = "ln").let { envelope ->
                envelope.copy(
                    module = "history_scores",
                    sourceParams = buildJsonObject {
                        put("term", requestedTerm)
                        put("ctype", "ln")
                    },
                )
            }
        }

        val termIndex = runCatching { fetchScoreIndex() }.getOrNull()
        val availableTerms = termIndex?.availableTerms.orEmpty()
        val termValues = availableTerms.map { it.value }.filter { it.isNotBlank() }.distinct()
        val errors = mutableListOf<String>()
        if (termValues.isNotEmpty()) {
            val combinedItems = mergeScoreItems(termValues
                .flatMap { termValue ->
                    runCatching { fetchScores(term = termValue, ctype = "ln") }
                        .getOrElse { error ->
                            if (!isRetryableAaFailure(error)) throw error
                            errors += "term=$termValue ${error.message.orEmpty()}".trim()
                            null
                        }
                        ?.let { envelope ->
                            if (envelope.coverage == CoverageLevel.Provisional && envelope.data.items.isEmpty()) {
                                errors += "term=$termValue unavailable"
                            }
                            envelope.data.items
                        }
                        .orEmpty()
                }
            )
            return ModuleEnvelope(
                module = "history_scores",
                sourceSystem = "aa",
                coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("term", HISTORY_ALL_TERMS)
                    put("ctype", "ln")
                    if (errors.isNotEmpty()) put("partial_error_count", errors.size)
                },
                data = ScoreData(currentTerm = null, availableTerms = availableTerms, items = combinedItems),
            )
        }

        val seed = fetchScores(ctype = "ln")
        return seed.copy(
            module = "history_scores",
            coverage = seed.coverage,
            sourceParams = buildJsonObject {
                put("term", HISTORY_ALL_TERMS)
                put("ctype", "ln")
            },
            data = seed.data.copy(currentTerm = null),
        )
    }

    fun fetchHistoryScoresProgressive(term: String? = null): Flow<ProgressiveModuleState<ScoreData>> = flow {
        val requestedTerm = term?.takeIf { it.isNotBlank() && it != HISTORY_ALL_TERMS }
        if (requestedTerm != null) {
            val envelope = fetchHistoryScores(requestedTerm)
            emit(
                ProgressiveModuleState(
                    envelope = envelope,
                    loading = false,
                    complete = true,
                    loadedCount = envelope.data.items.size,
                    totalCount = envelope.data.items.size,
                )
            )
            return@flow
        }

        val termIndex = runCatching { fetchScoreIndex() }.getOrNull()
        val availableTerms = termIndex?.availableTerms.orEmpty()
        val termValues = availableTerms.map { it.value }.filter { it.isNotBlank() }.distinct()
        val errors = mutableListOf<String>()

        if (termValues.isEmpty()) {
            val seedSource = fetchScores(ctype = "ln")
            val seed = seedSource.copy(
                module = "history_scores",
                sourceParams = buildJsonObject {
                    put("term", HISTORY_ALL_TERMS)
                    put("ctype", "ln")
                },
                data = seedSource.data.copy(currentTerm = null),
            )
            emit(
                ProgressiveModuleState(
                    envelope = seed,
                    loading = false,
                    complete = true,
                    loadedCount = seed.data.items.size,
                    totalCount = seed.data.items.size,
                )
            )
            return@flow
        }

        var items = emptyList<ScoreItem>()

        fun currentEnvelope(): ModuleEnvelope<ScoreData> =
            ModuleEnvelope(
                module = "history_scores",
                sourceSystem = "aa",
                coverage = if (errors.isEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
                sourceParams = buildJsonObject {
                    put("term", HISTORY_ALL_TERMS)
                    put("ctype", "ln")
                    if (errors.isNotEmpty()) put("partial_error_count", errors.size)
                },
                data = ScoreData(currentTerm = null, availableTerms = availableTerms, items = items),
            )

        emit(
            ProgressiveModuleState(
                envelope = currentEnvelope(),
                loading = true,
                loadedCount = 0,
                totalCount = termValues.size,
                errors = errors.toList(),
            )
        )

        termValues.forEachIndexed { index, termValue ->
            runCatching { fetchScores(term = termValue, ctype = "ln") }
                .onSuccess { envelope ->
                    if (envelope.coverage == CoverageLevel.Provisional && envelope.data.items.isEmpty()) {
                        errors += "term=$termValue unavailable"
                    }
                    items = mergeScoreItems(items + envelope.data.items)
                }
                .onFailure { error ->
                    if (!isRetryableAaFailure(error)) throw error
                    errors += "term=$termValue ${error.message.orEmpty()}".trim()
                }

            emit(
                ProgressiveModuleState(
                    envelope = currentEnvelope(),
                    loading = index < termValues.lastIndex,
                    complete = index == termValues.lastIndex,
                    loadedCount = index + 1,
                    totalCount = termValues.size,
                    errors = errors.toList(),
                )
            )
        }
    }

    suspend fun fetchScoreDetail(detailPath: String): ModuleEnvelope<ScoreDetailData> {
        val requestPath = aaRequestPath(detailPath)
        val html = getText(requestPath)
        return ModuleEnvelope(
            module = "score_detail",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { put("detail_path", requestPath) },
            data = parseScoreDetail(html),
        )
    }

    suspend fun fetchStudentProfile(): ModuleEnvelope<StudentProfileData> {
        val html = getText("/school_census/schoolcensus/stuview/")
        val parsed = parseStudentStatusProfile(html).let { profile ->
            if (profile.avatarUrl != null && !profile.avatarUrl.startsWith("http") && !profile.avatarUrl.startsWith("data:")) {
                profile.copy(avatarUrl = "${ProviderConstants.AA_BASE_URL}/${profile.avatarUrl.trimStart('/')}")
            } else {
                profile
            }
        }
        return ModuleEnvelope(
            module = "profile",
            sourceSystem = "aa",
            coverage = if (parsed.fields.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            data = parsed,
        )
    }

    suspend fun fetchAcademicProgress(): ModuleEnvelope<AcademicProgressData> {
        val listHtml = runCatching { getText("/school_census/schooltraininfo/studylist/") }
            .getOrElse { error ->
                if (!isRetryableAaFailure(error)) throw error
                return fetchAcademicProgressFallback(error)
            }
        val detailPath = parseAcademicProgressDetailPath(listHtml)
        val parsed = if (detailPath.isNullOrBlank()) {
            parseAcademicProgress("")
        } else {
            runCatching { parseAcademicProgress(getText(detailPath)) }
                .getOrElse { error ->
                    if (!isRetryableAaFailure(error)) throw error
                    return fetchAcademicProgressFallback(error)
                }
        }
        return ModuleEnvelope(
            module = "academic_progress",
            sourceSystem = "aa",
            coverage = if (parsed.buckets.isNotEmpty() || parsed.courses.isNotEmpty()) CoverageLevel.Verified else CoverageLevel.Provisional,
            sourceParams = buildJsonObject { detailPath?.let { put("detail_path", it) } },
            data = parsed,
        )
    }

    private suspend fun fetchAcademicProgressFallback(error: Throwable): ModuleEnvelope<AcademicProgressData> {
        val scores = runCatching { fetchHistoryScores().data }.getOrNull()
        val scorecardHtml = runCatching { getText("/score/scorecard/stu/") }.getOrDefault("")
        return ModuleEnvelope(
            module = "academic_progress",
            sourceSystem = "aa",
            coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("fallback_reason", error.message.orEmpty())
                if (scores != null) put("fallback_source", "scores")
            },
            data = parseScorecardProgress(scorecardHtml, scores),
        )
    }

    suspend fun fetchEmptyRooms(
        term: String? = null,
        week: String? = null,
        building: String? = null,
        room: String? = null,
    ): ModuleEnvelope<EmptyRoomData> {
        val params = mutableMapOf<String, String?>()
        term?.let { params["zxjxjhh"] = it }
        week?.takeIf { it.isNotBlank() }?.let { params["zc"] = it }
        building?.let { params["jxlh"] = it }
        room?.let { params["jash"] = it }
        if (params.isNotEmpty()) {
            params["has_advance_query"] = ""
        }
        val html = getText("/classroom/timeholdresult/room_view/", params)
        val parsed = parseEmptyRooms(
            html,
            mapOf("term" to term, "week" to params["zc"], "building" to building, "room" to room)
                .filterValues { !it.isNullOrBlank() },
        )
        return ModuleEnvelope(
            module = "empty_rooms",
            sourceSystem = "aa",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                parsed.query.forEach { (key, value) -> value?.let { put(key, it) } }
            },
            data = parsed,
        )
    }

    private suspend fun fetchScoreIndex(): ScoreData =
        parseScores(getText("/score/scores/stu/view/"), null)

    private suspend fun dropCourseSelectionFromParsed(
        parsed: ParsedCourseSelectionPage,
        courseKey: String?,
        courseName: String?,
    ): CourseSelectionAttemptResult {
        val target = findCourseSelectionTarget(parsed.data.selectedCourses, courseKey, courseName)
            ?: return CourseSelectionAttemptResult(status = "not_selected", message = "未找到要退的已选课程。")
        val action = parsed.dropActions[target.key]
            ?: return CourseSelectionAttemptResult(status = "unparseable", message = "无法解析退课入口。", course = target)
        val response = submitCourseSelectionAction(action.actionUrl, action.method, action.fields)
        val refreshed = fetchCourseSelection().data
        val refreshedTarget = findCourseSelectionTarget(
            refreshed.selectedCourses + refreshed.availableCourses,
            target.key,
            target.courseName,
        )
        if (refreshedTarget?.selected != true) {
            return CourseSelectionAttemptResult(status = "drop_success", message = "退课成功。", course = target)
        }
        return CourseSelectionAttemptResult(
            status = "drop_failed",
            message = response.body.takeIf { it.isNotBlank() }?.take(200) ?: "已提交退课请求，但课程仍在已选列表中。",
            course = refreshedTarget,
        )
    }

    private fun findCourseSelectionTarget(
        courses: List<CourseSelectionCourse>,
        courseKey: String?,
        courseName: String?,
    ): CourseSelectionCourse? {
        val key = courseKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            courses.firstOrNull { it.key == key }?.let { return it }
        }
        val name = courseName?.trim().orEmpty()
        if (name.isBlank()) return null
        val normalized = name.normalizedCourseSelectionText()
        return courses.firstOrNull { course ->
            val candidate = course.courseName.normalizedCourseSelectionText()
            candidate == normalized || candidate.contains(normalized)
        }
    }

    private suspend fun submitCourseSelectionAction(
        actionUrl: String,
        method: String,
        fields: Map<String, String>,
    ): cn.edu.bjtu.mis.data.network.TextResponse {
        val response = if (method.lowercase() == "get") {
            client.getText(actionUrl, fields, headers = mapOf("Referer" to courseSelectionUrl))
        } else {
            client.postForm(
                actionUrl,
                form = fields,
                headers = mapOf(
                    "Referer" to courseSelectionUrl,
                    "Origin" to aaBaseUrl,
                ),
            )
        }
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        return response
    }

    private suspend fun buildCourseSelectionCaptcha(
        html: String,
        pageUrl: String,
        courseKey: String,
        courseName: String,
    ): CourseSelectionCaptchaChallenge? {
        val form = parseCourseSelectionCaptcha(html, pageUrl)
        val imageUrl = form.imageUrl ?: return null
        val inputName = form.inputName ?: return null
        val imageDataUrl = if (imageUrl.startsWith("data:")) {
            imageUrl
        } else {
            val image = client.getBytes(imageUrl, headers = mapOf("Referer" to pageUrl))
            val mimeType = image.headers["Content-Type"]?.substringBefore(";")?.trim().orEmpty()
                .ifBlank { "image/png" }
            "data:$mimeType;base64," + Base64.getEncoder().encodeToString(image.body)
        }
        val challengeId = UUID.randomUUID().toString()
        CourseSelectionCaptchaStore.values[challengeId] = PendingCourseSelectionCaptcha(
            courseKey = courseKey,
            courseName = courseName,
            inputName = inputName,
            fields = form.fields,
        )
        return CourseSelectionCaptchaChallenge(
            challengeId = challengeId,
            imageDataUrl = imageDataUrl,
            prompt = form.prompt,
            fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString(),
        )
    }

    private suspend fun getText(path: String, params: Map<String, String?> = emptyMap()): String {
        var lastError: IOException? = null
        repeat(AA_RETRY_ATTEMPTS) { attempt ->
            try {
                return getTextOnce(path, params)
            } catch (error: IOException) {
                lastError = error
                if (!isRetryableAaFailure(error) || attempt == AA_RETRY_ATTEMPTS - 1) throw error
                delay(400L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("AA request failed")
    }

    private suspend fun getTextOnce(path: String, params: Map<String, String?> = emptyMap()): String {
        val url = if (path.startsWith("http")) path else aaBaseUrl + path
        val response = client.getText(url, params)
        val head = response.body.take(4096)
        if (response.url.contains("/client/login/") || (head.contains("用户登录") && head.contains("教学支撑平台"))) {
            throw SessionExpiredException("教学支撑平台未登录，请重新登录。")
        }
        return response.body
    }

    private fun isRetryableAaFailure(error: Throwable): Boolean {
        if (error !is IOException) return false
        val code = Regex("""HTTP\s+(\d{3})""")
            .find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return code == null || code >= 500
    }

    private fun aaRequestPath(pathOrUrl: String): String {
        val resolved = URI(ProviderConstants.AA_BASE_URL + "/").resolve(pathOrUrl.trim())
        if (resolved.scheme !in setOf("http", "https") || resolved.host != "aa.bjtu.edu.cn") {
            throw IllegalArgumentException("成绩详情链接不是 AA 教学支撑平台地址。")
        }
        return buildString {
            append(resolved.rawPath.ifBlank { "/" })
            if (!resolved.rawQuery.isNullOrBlank()) {
                append("?")
                append(resolved.rawQuery)
            }
        }
    }
}

private fun String.normalizedCourseSelectionText(): String =
    trim().lowercase().replace(Regex("""\s+"""), "")
