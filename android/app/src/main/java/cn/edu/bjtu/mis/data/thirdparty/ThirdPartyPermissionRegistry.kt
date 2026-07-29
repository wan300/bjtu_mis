package cn.edu.bjtu.mis.data.thirdparty

data class ThirdPartyPermission(
    val id: String,
    val title: String,
    val description: String,
    val highRisk: Boolean = false,
)

object ThirdPartyPermissionRegistry {
    val permissions: List<ThirdPartyPermission> = listOf(
        ThirdPartyPermission(
            id = "app.configuration.read",
            title = "读取插件配置",
            description = "允许插件读取你在手机端为它填写的第三方配置。敏感值可能被插件发送到其声明的服务，请仅授权可信插件。",
            highRisk = true,
        ),
        ThirdPartyPermission(
            id = "identity.profile.read",
            title = "读取个人身份信息",
            description = "允许第三方服务读取姓名、学号、学院、专业、邮箱等个人资料。",
        ),
        ThirdPartyPermission(
            id = "academic.timetable.read",
            title = "读取课表",
            description = "允许第三方服务读取当前课表和用户自定义课程。",
        ),
        ThirdPartyPermission(
            id = "academic.user_courses.write",
            title = "管理自定义课程",
            description = "允许第三方服务新增、修改或删除用户手动创建的课程。",
        ),
        ThirdPartyPermission(
            id = "academic.scores.read",
            title = "读取主修成绩",
            description = "允许第三方服务读取当前学期或指定学期的主修成绩。",
        ),
        ThirdPartyPermission(
            id = "academic.history_scores.read",
            title = "读取历史成绩",
            description = "允许第三方服务读取历年成绩。",
        ),
        ThirdPartyPermission(
            id = "academic.exams.read",
            title = "读取考试安排",
            description = "允许第三方服务读取考试时间、地点和考试方式。",
        ),
        ThirdPartyPermission(
            id = "academic.calendar.read",
            title = "读取学年日历",
            description = "允许第三方服务读取校历、周次和学期安排。",
        ),
        ThirdPartyPermission(
            id = "academic.progress.read",
            title = "读取学业进度",
            description = "允许第三方服务读取培养方案完成情况和学分进度。",
        ),
        ThirdPartyPermission(
            id = "academic.homework.read",
            title = "读取作业",
            description = "允许第三方服务读取课程作业、截止时间和提交状态。",
        ),
        ThirdPartyPermission(
            id = "academic.homework.submit",
            title = "提交作业",
            description = "允许第三方服务提交作业内容。每次提交前仍会要求用户确认。",
            highRisk = true,
        ),
        ThirdPartyPermission(
            id = "academic.course_resources.read",
            title = "读取课程资源",
            description = "允许第三方服务读取课程资料列表和课程资源元数据。",
        ),
        ThirdPartyPermission(
            id = "mail.folders.read",
            title = "读取邮箱文件夹",
            description = "允许第三方服务读取邮箱文件夹和未读数量。",
        ),
        ThirdPartyPermission(
            id = "mail.messages.read",
            title = "读取邮件列表",
            description = "允许第三方服务读取邮件摘要列表。",
        ),
        ThirdPartyPermission(
            id = "mail.message_detail.read",
            title = "读取邮件详情",
            description = "允许第三方服务读取单封邮件正文、收发件人和附件信息。",
        ),
        ThirdPartyPermission(
            id = "mail.send",
            title = "发送邮件",
            description = "允许第三方服务发送邮件。每次发送前仍会要求用户确认。",
            highRisk = true,
        ),
    )

    private val byId = permissions.associateBy { it.id }

    fun get(id: String): ThirdPartyPermission? = byId[id]

    fun requireKnown(id: String): ThirdPartyPermission =
        get(id) ?: throw ThirdPartyServiceException("未知第三方服务权限：$id")

    fun allIds(): Set<String> = byId.keys
}
