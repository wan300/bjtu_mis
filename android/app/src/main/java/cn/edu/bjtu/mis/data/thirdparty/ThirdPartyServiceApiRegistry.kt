package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.repository.MailRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.UserCourseDraft
import cn.edu.bjtu.mis.model.UserCourseDurationType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface ThirdPartySensitiveActionConfirmer {
    suspend fun confirm(title: String, message: String): Boolean
}

class ThirdPartyServiceApiRegistry(
    private val moduleRepository: ModuleRepository?,
    private val mailRepository: MailRepository?,
    private val credentialStore: CredentialStore? = null,
) {
    suspend fun invoke(
        service: ThirdPartyService,
        method: String,
        params: JsonObject = buildJsonObject { },
        confirmer: ThirdPartySensitiveActionConfirmer,
        currentPageUrl: String = "",
    ): JsonObject {
        val normalizedMethod = method.trim()
        val spec = methodSpecs[normalizedMethod]
            ?: return errorResponse("unknown_method", "未知第三方服务接口：$normalizedMethod")
        if (service.needsReview || !service.enabled) {
            return errorResponse("service_not_enabled", "第三方服务尚未完成授权")
        }
        if (spec.permission !in service.grantedPermissions) {
            return errorResponse("permission_denied", "第三方服务未获得权限：${spec.permission}")
        }
        if (spec.highRisk) {
            val confirmed = confirmer.confirm(spec.confirmTitle, spec.confirmMessage(service, currentPageUrl, params))
            if (!confirmed) return errorResponse("user_denied", "用户未确认该操作")
        }
        return runCatching {
            successResponse(execute(normalizedMethod, params))
        }.getOrElse { error ->
            errorResponse("api_failed", error.message ?: "第三方服务接口调用失败")
        }
    }

    private suspend fun execute(method: String, params: JsonObject): JsonElement = when (method) {
        "identity.get_profile" -> json(moduleRepository().profile(strategy = readStrategy(params)))
        "identity.get_credentials" -> credentialsJson()
        "academic.get_timetable" -> json(moduleRepository().timetable(strategy = readStrategy(params)))
        "academic.save_user_course" -> buildJsonObject {
            put("id", moduleRepository().saveUserCourse(params.toUserCourseDraft()))
        }
        "academic.delete_user_course" -> {
            moduleRepository().deleteUserCourse(params.requiredLong("id"))
            buildJsonObject { put("deleted", true) }
        }
        "academic.get_scores" -> json(
            moduleRepository().scores(
                term = params.string("term"),
                ctype = params.string("ctype"),
                strategy = readStrategy(params),
            )
        )
        "academic.get_history_scores" -> json(
            moduleRepository().historyScores(term = params.string("term"), strategy = readStrategy(params))
        )
        "academic.get_exams" -> json(moduleRepository().exams(term = params.string("term"), strategy = readStrategy(params)))
        "academic.get_calendar" -> json(moduleRepository().calendar(month = params.string("month"), strategy = readStrategy(params)))
        "academic.get_academic_progress" -> json(moduleRepository().academicProgress(strategy = readStrategy(params)))
        "academic.get_homework" -> json(
            moduleRepository().homework(status = params.string("status") ?: "all", strategy = readStrategy(params))
        )
        "academic.submit_homework" -> json(
            moduleRepository().submitHomework(
                homeworkId = params.requiredInt("homework_id"),
                courseId = params.requiredInt("course_id"),
                content = params.string("content").orEmpty(),
                files = emptyList(),
            )
        )
        "academic.get_course_resources" -> json(
            moduleRepository().courseResources(
                term = params.string("term"),
                courseId = params.string("course_id"),
                folderId = params.string("folder_id") ?: "0",
                search = params.string("search"),
                categoryKey = params.string("category_key"),
                strategy = readStrategy(params),
            )
        )
        "mail.list_folders" -> json(mailRepository().folders(strategy = readStrategy(params)))
        "mail.list_messages" -> json(
            mailRepository().messages(
                folderId = params.string("folder_id") ?: "1",
                start = params.int("start") ?: 0,
                limit = (params.int("limit") ?: 20).coerceIn(1, 100),
                strategy = readStrategy(params),
            )
        )
        "mail.get_message" -> json(
            mailRepository().detail(
                messageId = params.requiredString("message_id"),
                mboxa = params.string("mboxa").orEmpty(),
            )
        )
        "mail.send" -> json(mailRepository().send(params.toMailComposeRequest()))
        else -> error("Unknown method")
    }

    private fun moduleRepository(): ModuleRepository =
        moduleRepository ?: error("ModuleRepository is not available")

    private fun mailRepository(): MailRepository =
        mailRepository ?: error("MailRepository is not available")

    private fun credentialStore(): CredentialStore =
        credentialStore ?: error("CredentialStore is not available")

    private fun readStrategy(params: JsonObject): ModuleLoadStrategy =
        if (params.boolean("force_refresh") == true) {
            ModuleLoadStrategy.NetworkFirst
        } else {
            ModuleLoadStrategy.CacheFirst
        }

    private fun credentialsJson(): JsonElement {
        val credentials = credentialStore().load()
            ?: error("未找到已保存的 BJTU 登录凭据，请先在主应用登录。")
        val loginName = credentials.loginName.trim()
        return buildJsonObject {
            put("login_name", loginName)
            put("loginName", loginName)
            put("student_id", loginName)
            put("studentId", loginName)
            put("account", loginName)
            put("password", credentials.password)
        }
    }

    private inline fun <reified T> json(value: T): JsonElement =
        AppJson.parseToJsonElement(AppJson.encodeToString(value))

    companion object {
        val methodSpecs: Map<String, ThirdPartyApiMethodSpec> = listOf(
            ThirdPartyApiMethodSpec("identity.get_profile", "identity.profile.read"),
            ThirdPartyApiMethodSpec(
                method = "identity.get_credentials",
                permission = "identity.credentials.read",
                highRisk = true,
                confirmTitle = "确认读取登录凭据",
                confirmMessage = { service, pageUrl, _ ->
                    "${service.manifest.name} 请求从 ${pageUrl.ifBlank { "当前页面" }} 读取你首次登录 BJTU MIS 时保存的学号和密码。"
                },
            ),
            ThirdPartyApiMethodSpec("academic.get_timetable", "academic.timetable.read"),
            ThirdPartyApiMethodSpec("academic.save_user_course", "academic.user_courses.write"),
            ThirdPartyApiMethodSpec("academic.delete_user_course", "academic.user_courses.write"),
            ThirdPartyApiMethodSpec("academic.get_scores", "academic.scores.read"),
            ThirdPartyApiMethodSpec("academic.get_history_scores", "academic.history_scores.read"),
            ThirdPartyApiMethodSpec("academic.get_exams", "academic.exams.read"),
            ThirdPartyApiMethodSpec("academic.get_calendar", "academic.calendar.read"),
            ThirdPartyApiMethodSpec("academic.get_academic_progress", "academic.progress.read"),
            ThirdPartyApiMethodSpec("academic.get_homework", "academic.homework.read"),
            ThirdPartyApiMethodSpec(
                method = "academic.submit_homework",
                permission = "academic.homework.submit",
                highRisk = true,
                confirmTitle = "确认提交作业",
                confirmMessage = { service, pageUrl, params ->
                    "${service.manifest.name} 请求从 ${pageUrl.ifBlank { "当前页面" }} 提交作业 ${params.string("homework_id").orEmpty()}。"
                },
            ),
            ThirdPartyApiMethodSpec("academic.get_course_resources", "academic.course_resources.read"),
            ThirdPartyApiMethodSpec("mail.list_folders", "mail.folders.read"),
            ThirdPartyApiMethodSpec("mail.list_messages", "mail.messages.read"),
            ThirdPartyApiMethodSpec("mail.get_message", "mail.message_detail.read"),
            ThirdPartyApiMethodSpec(
                method = "mail.send",
                permission = "mail.send",
                highRisk = true,
                confirmTitle = "确认发送邮件",
                confirmMessage = { service, pageUrl, params ->
                    "${service.manifest.name} 请求从 ${pageUrl.ifBlank { "当前页面" }} 发送邮件：${params.string("subject").orEmpty().ifBlank { "无主题" }}"
                },
            ),
        ).associateBy { it.method }
    }
}

data class ThirdPartyApiMethodSpec(
    val method: String,
    val permission: String,
    val highRisk: Boolean = false,
    val confirmTitle: String = "",
    val confirmMessage: (ThirdPartyService, String, JsonObject) -> String = { _, _, _ -> "" },
)

private fun successResponse(data: JsonElement): JsonObject = buildJsonObject {
    put("ok", true)
    put("data", data)
}

private fun errorResponse(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
}

private fun JsonObject.toUserCourseDraft(): UserCourseDraft =
    UserCourseDraft(
        id = long("id"),
        courseName = requiredString("course_name"),
        weekday = requiredString("weekday"),
        weekdayIndex = requiredInt("weekday_index"),
        period = requiredString("period"),
        periodNumber = requiredInt("period_number"),
        timeRange = string("time_range"),
        startWeek = requiredInt("start_week"),
        endWeek = requiredInt("end_week"),
        weeksText = string("weeks_text"),
        durationType = runCatching {
            UserCourseDurationType.valueOf(string("duration_type") ?: UserCourseDurationType.Temporary.name)
        }.getOrDefault(UserCourseDurationType.Temporary),
        teacher = string("teacher"),
        locationText = string("location_text"),
        remark = string("remark"),
        colorIndex = int("color_index") ?: 0,
    )

private fun JsonObject.toMailComposeRequest(): MailComposeRequest =
    MailComposeRequest(
        composeId = string("compose_id"),
        account = string("account"),
        to = stringList("to"),
        cc = stringList("cc"),
        bcc = stringList("bcc"),
        subject = string("subject").orEmpty(),
        content = string("content") ?: string("body"),
        htmlContent = string("html_content"),
        isHtml = boolean("is_html") ?: false,
        saveSentCopy = boolean("save_sent_copy") ?: true,
        requestReadReceipt = boolean("request_read_receipt") ?: false,
        scheduleDate = string("schedule_date"),
        showOneRcpt = boolean("show_one_rcpt") ?: false,
        forbidDownload = boolean("forbid_download") ?: false,
        mboxa = string("mboxa").orEmpty(),
    )

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: throw IllegalArgumentException("缺少参数 $name")

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.requiredInt(name: String): Int =
    int(name) ?: throw IllegalArgumentException("缺少整数参数 $name")

private fun JsonObject.long(name: String): Long? =
    string(name)?.toLongOrNull() ?: this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.requiredLong(name: String): Long =
    long(name) ?: throw IllegalArgumentException("缺少整数参数 $name")

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.stringList(name: String): List<String> =
    this[name]?.jsonArray
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty()
