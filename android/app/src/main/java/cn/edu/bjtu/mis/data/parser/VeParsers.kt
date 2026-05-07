package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.CourseResourceFolder
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CourseSummary
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ProfileField
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TermOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun parseCalendarTerms(payload: JsonObject): Pair<List<TermOption>, String?> {
    val result = payload.list("result")
    val options = result.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val value = obj.text("xqCode")
        if (value.isNullOrBlank()) return@mapNotNull null
        val selected = obj.int("currentFlag") == 2 || obj.bool("selected") == true
        TermOption(
            value = value,
            label = obj.text("xqName") ?: obj.text("CNAME") ?: value,
            selected = selected,
        )
    }
    return options to (options.firstOrNull { it.selected }?.value ?: options.firstOrNull()?.value)
}

fun parseCalendar(
    payload: JsonObject,
    month: String,
    currentTerm: String?,
    availableTerms: List<TermOption>,
): CalendarData {
    val week = payload.text("weekCode")
    val items = payload.list("maps").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val date = obj.text("dayTime") ?: return@mapNotNull null
        CalendarItem(date = date, week = week)
    }
    return CalendarData(
        month = month,
        currentWeek = week,
        currentTerm = currentTerm,
        availableTerms = availableTerms,
        items = items,
    )
}

fun parseCourses(payload: JsonObject): List<CourseSummary> =
    payload.list("courseList").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.int("id") ?: return@mapNotNull null
        val xqCode = obj.text("xq_code")
        CourseSummary(
            courseId = id,
            courseName = obj.text("name").orEmpty(),
            courseCode = obj.text("course_num"),
            teacherName = obj.text("teacher_name"),
            teacherId = obj.text("teacher_id"),
            term = xqCode,
            xqCode = xqCode,
            xkhId = obj.text("fz_id") ?: obj.text("xkhId"),
        )
    }

fun parseHomeworkList(payload: JsonObject, course: CourseSummary, subType: Int): List<HomeworkItem> =
    payload.list("courseNoteList").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val homeworkId = obj.int("id")
        val title = obj.text("title") ?: homeworkId?.let { "作业#$it" }.orEmpty()
        if (title.isBlank()) return@mapNotNull null
        HomeworkItem(
            homeworkId = homeworkId,
            course = obj.text("course_name") ?: course.courseName,
            courseId = course.courseId,
            courseCode = course.courseCode,
            title = title,
            contentExcerpt = stripHtmlExcerpt(obj.text("content")),
            openedAt = obj.text("open_date"),
            dueAt = obj.text("end_time"),
            status = if (subType == 2) "done" else "open",
            subType = subType,
            submissionStatus = obj.text("subStatus"),
        )
    }

fun buildHomeworkData(
    currentTerm: String?,
    courses: List<CourseSummary>,
    items: List<HomeworkItem>,
): HomeworkData = HomeworkData(currentTerm = currentTerm, courses = courses, items = items)

fun parseStudentProfile(payload: JsonObject): StudentProfileData {
    val source = listOf("userInfo", "user", "student", "data", "result", "map")
        .firstNotNullOfOrNull { payload[it] as? JsonObject }
        ?: payload
    val profile = StudentProfileData(
        name = source.firstText("name", "userName", "xm", "realName", "trueName"),
        studentId = source.firstText("studentId", "studentNo", "stuNo", "xh", "student_no"),
        account = source.firstText("loginName", "userId", "account", "userNo", "login_name"),
        gender = source.firstText("sex", "gender", "xb"),
        college = source.firstText("college", "collegeName", "deptName", "xy", "academyName"),
        major = source.firstText("major", "majorName", "zymc", "specialtyName"),
        className = source.firstText("className", "bjmc", "class_name"),
        grade = source.firstText("grade", "nj", "rxnf"),
        educationLevel = source.firstText("educationLevel", "pycc", "levelName"),
        phone = source.firstText("phone", "mobile", "telephone"),
        email = source.firstText("email", "mail"),
        avatarUrl = source.firstText("avatar", "avatarUrl", "photo", "photoUrl", "headPic", "userPic"),
    )
    val fields = buildList {
        fun add(label: String, value: String?) {
            if (!value.isNullOrBlank()) add(ProfileField(label, value))
        }
        add("姓名", profile.name)
        add("学号", profile.studentId)
        add("账号", profile.account)
        add("性别", profile.gender)
        add("学院", profile.college)
        add("专业", profile.major)
        add("班级", profile.className)
        add("年级", profile.grade)
        add("培养层次", profile.educationLevel)
        add("电话", profile.phone)
        add("邮箱", profile.email)
    }.distinctBy { it.label }
    return profile.copy(fields = fields)
}

fun parseCourseResourceTree(payload: JsonObject): List<CourseResourceFolder> =
    payload.list("nodes").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.text("id") ?: return@mapNotNull null
        val name = obj.text("name") ?: obj.text("bag_name") ?: return@mapNotNull null
        CourseResourceFolder(
            folderId = id,
            name = name,
            parentId = obj.firstText("pId", "pid", "parent_id"),
        )
    }

fun parseCourseResourceListing(
    payload: JsonObject,
    folderId: String,
): Pair<List<CourseResourceFolder>, List<CourseResourceItem>> {
    val folders = payload.list("bagList").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.text("id") ?: return@mapNotNull null
        val name = obj.text("bag_name") ?: obj.text("name") ?: return@mapNotNull null
        CourseResourceFolder(
            folderId = id,
            name = name,
            parentId = obj.firstText("pId", "pid", "up_id") ?: folderId,
        )
    }
    val resources = payload.list("resList").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val rpId = obj.firstText("rpId", "rp_id", "id") ?: return@mapNotNull null
        val name = obj.firstText("rpName", "rp_name", "name") ?: return@mapNotNull null
        val resId = obj.firstText("resId", "res_id")
        CourseResourceItem(
            resourceId = resId ?: rpId,
            rpId = rpId,
            resId = resId,
            name = name,
            extension = obj.firstText("RP_PRIX", "rp_prix", "extName", "ext_name")?.lowercase(),
            size = obj.firstText("rpSize", "rp_size"),
            uploadedAt = obj.firstText("inputTime", "input_time", "created_at"),
            teacherName = obj.firstText("teacherName", "teacher_name"),
            downloadCount = obj.firstInt("downloadNum", "download_num"),
            clickCount = obj.firstInt("clicks", "click_count"),
            canDownload = obj.text("stu_download") == "2",
            folderId = folderId,
        )
    }
    return folders to resources
}

fun buildCourseResourcesData(
    currentTerm: String?,
    courses: List<CourseSummary>,
    selectedCourse: CourseSummary?,
    folderId: String,
    tree: List<CourseResourceFolder>,
    folders: List<CourseResourceFolder>,
    resources: List<CourseResourceItem>,
): CourseResourcesData = CourseResourcesData(
    currentTerm = currentTerm,
    courses = courses,
    selectedCourseId = selectedCourse?.courseId,
    folderId = folderId,
    tree = tree,
    folders = folders,
    resources = resources,
)

fun JsonObject.text(key: String): String? =
    this[key]?.primitiveText()?.takeIf { it.isNotBlank() }

fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitiveOrNull()?.intOrNull

fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitiveOrNull()?.booleanOrNull

fun JsonObject.list(key: String): List<JsonElement> =
    when (val value = this[key]) {
        is JsonArray -> value
        else -> emptyList()
    }

fun JsonObject.firstText(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> text(key) }

fun JsonObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> int(key) }

private fun JsonElement.primitiveText(): String? =
    when (this) {
        JsonNull -> null
        else -> jsonPrimitiveOrNull()?.contentOrNull
            ?: jsonPrimitiveOrNull()?.intOrNull?.toString()
            ?: jsonPrimitiveOrNull()?.doubleOrNull?.toString()
    }?.trim()

private fun JsonElement.jsonPrimitiveOrNull() =
    runCatching { jsonPrimitive }.getOrNull()
