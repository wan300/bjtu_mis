package cn.edu.bjtu.mis.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class CoverageLevel {
    @SerialName("verified")
    Verified,

    @SerialName("provisional")
    Provisional
}

@Serializable
enum class SessionState {
    @SerialName("ready")
    Ready,

    @SerialName("waiting_for_login")
    WaitingForLogin,

    @SerialName("expired")
    Expired
}

@Serializable
data class SessionStatus(
    val state: SessionState,
    val detail: String? = null,
)

@Serializable
data class SessionCaptcha(
    val imageDataUrl: String,
    val fetchedAt: String,
)

@Serializable
data class TermOption(
    val value: String,
    val label: String,
    val selected: Boolean = false,
)

@Serializable
data class CourseEntry(
    val weekday: String,
    val period: String,
    val timeRange: String? = null,
    val courseCode: String,
    val section: String? = null,
    val courseName: String,
    val teacher: String? = null,
    val weeks: String? = null,
    val campus: String? = null,
    val building: String? = null,
    val room: String? = null,
    val locationText: String? = null,
)

@Serializable
data class TimetableData(
    val days: List<String> = emptyList(),
    val periods: List<String> = emptyList(),
    val entries: List<CourseEntry> = emptyList(),
    val currentTerm: String? = null,
    val availableTerms: List<TermOption> = emptyList(),
)

@Serializable
data class ExamItem(
    val term: String? = null,
    val courseName: String,
    val schedule: String? = null,
    val examMode: String? = null,
    val remark: String? = null,
    val registration: String? = null,
    val status: String? = null,
)

@Serializable
data class ExamData(
    val currentTerm: String? = null,
    val availableTerms: List<TermOption> = emptyList(),
    val items: List<ExamItem> = emptyList(),
)

@Serializable
data class ScoreItem(
    val term: String? = null,
    val courseName: String,
    val credit: String? = null,
    val score: String? = null,
    val bonusScore: String? = null,
    val teacher: String? = null,
    val detail: String? = null,
)

@Serializable
data class ScoreData(
    val currentTerm: String? = null,
    val availableTerms: List<TermOption> = emptyList(),
    val items: List<ScoreItem> = emptyList(),
)

@Serializable
data class CalendarItem(
    val date: String,
    val week: String? = null,
    val note: String? = null,
)

@Serializable
data class CalendarData(
    val month: String,
    val currentWeek: String? = null,
    val currentTerm: String? = null,
    val availableTerms: List<TermOption> = emptyList(),
    val items: List<CalendarItem> = emptyList(),
)

@Serializable
data class CourseSummary(
    val courseId: Int,
    val courseName: String,
    val courseCode: String? = null,
    val teacherName: String? = null,
    val teacherId: String? = null,
    val term: String? = null,
    val xqCode: String? = null,
    val xkhId: String? = null,
)

@Serializable
data class HomeworkItem(
    val homeworkId: Int? = null,
    val course: String,
    val courseId: Int,
    val courseCode: String? = null,
    val title: String,
    val contentExcerpt: String? = null,
    val openedAt: String? = null,
    val dueAt: String? = null,
    val status: String,
    val subType: Int,
    val submissionStatus: String? = null,
)

@Serializable
data class HomeworkData(
    val currentTerm: String? = null,
    val courses: List<CourseSummary> = emptyList(),
    val items: List<HomeworkItem> = emptyList(),
)

@Serializable
data class CourseResourceFolder(
    val folderId: String,
    val name: String,
    val parentId: String? = null,
)

@Serializable
data class CourseResourceItem(
    val resourceId: String,
    val rpId: String,
    val resId: String? = null,
    val name: String,
    val extension: String? = null,
    val size: String? = null,
    val uploadedAt: String? = null,
    val teacherName: String? = null,
    val downloadCount: Int? = null,
    val clickCount: Int? = null,
    val canDownload: Boolean = false,
    val folderId: String = "0",
)

@Serializable
data class CourseResourcesData(
    val currentTerm: String? = null,
    val courses: List<CourseSummary> = emptyList(),
    val selectedCourseId: Int? = null,
    val folderId: String = "0",
    val tree: List<CourseResourceFolder> = emptyList(),
    val folders: List<CourseResourceFolder> = emptyList(),
    val resources: List<CourseResourceItem> = emptyList(),
)

@Serializable
data class ProfileField(
    val label: String,
    val value: String,
)

@Serializable
data class ProfileSection(
    val title: String,
    val fields: List<ProfileField> = emptyList(),
)

@Serializable
data class StudentProfileData(
    val name: String? = null,
    val studentId: String? = null,
    val account: String? = null,
    val gender: String? = null,
    val birthday: String? = null,
    val namePinyin: String? = null,
    val englishName: String? = null,
    val ethnicity: String? = null,
    val politicalStatus: String? = null,
    val nationality: String? = null,
    val isInternationalStudent: String? = null,
    val college: String? = null,
    val major: String? = null,
    val className: String? = null,
    val grade: String? = null,
    val educationLevel: String? = null,
    val hasStudentStatus: String? = null,
    val studentStatus: String? = null,
    val studentCategory: String? = null,
    val changeStatus: String? = null,
    val cultivationMethod: String? = null,
    val isAuditor: String? = null,
    val studyLanguage: String? = null,
    val campus: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val fields: List<ProfileField> = emptyList(),
    val sections: List<ProfileSection> = emptyList(),
)

@Serializable
data class CreditSummary(
    val courseCount: Int = 0,
    val passedCourseCount: Int = 0,
    val failedCourseCount: Int = 0,
    val attemptedCredits: Double = 0.0,
    val passedCredits: Double = 0.0,
    val failedCredits: Double = 0.0,
    val targetCredits: Double? = null,
    val completionRate: Double = 0.0,
)

@Serializable
data class CreditBucket(
    val name: String,
    val requiredCredits: Double? = null,
    val earnedCredits: Double = 0.0,
    val pendingCredits: Double? = null,
    val completionRate: Double? = null,
    val parent: String? = null,
)

@Serializable
data class AcademicProgressCourse(
    val term: String? = null,
    val courseCode: String? = null,
    val courseName: String,
    val credit: Double? = null,
    val examDate: String? = null,
    val score: String? = null,
    val status: String,
    val detail: String? = null,
    val groupInfo: String? = null,
    val source: String = "scores",
)

@Serializable
data class AcademicProgressData(
    val currentTerm: String? = null,
    val summary: CreditSummary = CreditSummary(),
    val buckets: List<CreditBucket> = emptyList(),
    val mergedBuckets: List<CreditBucket> = emptyList(),
    val detailBuckets: List<CreditBucket> = emptyList(),
    val courses: List<AcademicProgressCourse> = emptyList(),
    val replaceCourses: List<JsonObject> = emptyList(),
    val fields: List<ProfileField> = emptyList(),
)

@Serializable
data class EmptyRoomSlotHeader(
    val day: String,
    val date: String? = null,
    val period: Int,
)

@Serializable
data class EmptyRoomRow(
    val room: String,
    val seatLabel: String? = null,
    val availability: List<Boolean> = emptyList(),
)

@Serializable
data class EmptyRoomData(
    val query: Map<String, String?> = emptyMap(),
    val days: List<String> = emptyList(),
    val periods: List<Int> = emptyList(),
    val slots: List<EmptyRoomSlotHeader> = emptyList(),
    val rooms: List<EmptyRoomRow> = emptyList(),
)

@Serializable
data class ModuleEnvelope<T>(
    val module: String,
    val syncedAt: String? = null,
    val sourceSystem: String,
    val coverage: CoverageLevel,
    val sourceParams: JsonObject = buildJsonObject { },
    val data: T,
)

@Serializable
data class SyncModuleSummary(
    val status: String,
    val coverage: CoverageLevel? = null,
    val items: Int? = null,
    val error: String? = null,
)

@Serializable
data class SyncRun(
    val id: Long = 0,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val status: String,
    val moduleSummary: Map<String, SyncModuleSummary> = emptyMap(),
    val errorText: String? = null,
)

@Serializable
data class SnapshotRecord(
    val moduleKey: String,
    val syncedAt: String,
    val sourceSystem: String,
    val coverage: CoverageLevel,
    val sourceParams: JsonObject,
    val payload: JsonElement,
)

object ModuleKeys {
    const val Profile = "profile"
    const val AcademicProgress = "academic_progress"
    const val HistoryScores = "history_scores"
    const val Timetable = "timetable"
    const val Exams = "exams"
    const val Scores = "scores"
    const val Calendar = "calendar"
    const val Homework = "homework"
    const val CourseResources = "course_resources"
    const val EmptyRooms = "empty_rooms"
}
