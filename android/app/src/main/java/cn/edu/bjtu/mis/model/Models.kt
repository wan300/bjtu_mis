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

enum class AutoLoginStatus {
    Ready,
    ManualRequired,
    AutoFailed,
}

data class AutoLoginResult(
    val status: AutoLoginStatus,
    val message: String? = null,
    val attempts: Int = 0,
    val session: SessionStatus? = null,
    val captcha: SessionCaptcha? = null,
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
    val localId: Long? = null,
    val remark: String? = null,
    val colorIndex: Int? = null,
    val isUserCreated: Boolean = false,
)

@Serializable
data class CourseSelectionCourse(
    val key: String,
    val status: String,
    val selected: Boolean = false,
    val courseName: String,
    val courseCode: String? = null,
    val section: String? = null,
    val remaining: Int? = null,
    val remainingText: String? = null,
    val credit: String? = null,
    val courseType: String? = null,
    val examType: String? = null,
    val teacher: String? = null,
    val timeLocation: String? = null,
    val note: String? = null,
)

@Serializable
data class CourseSelectionData(
    val selectedCourses: List<CourseSelectionCourse> = emptyList(),
    val availableCourses: List<CourseSelectionCourse> = emptyList(),
    val canSubmit: Boolean = false,
    val submitError: String? = null,
)

@Serializable
data class CourseSelectionCaptchaChallenge(
    val challengeId: String,
    val imageDataUrl: String,
    val prompt: String? = null,
    val fetchedAt: String,
)

@Serializable
data class CourseSelectionAttemptResult(
    val status: String,
    val message: String? = null,
    val course: CourseSelectionCourse? = null,
    val captchaChallenge: CourseSelectionCaptchaChallenge? = null,
)

data class CourseSelectionTarget(
    val key: String,
    val courseName: String,
)

data class CourseSelectionReplaceRule(
    val id: String,
    val target: CourseSelectionTarget,
    val drop: CourseSelectionTarget,
)

data class CourseSelectionRunConfig(
    val targets: List<CourseSelectionTarget> = emptyList(),
    val replaceRules: List<CourseSelectionReplaceRule> = emptyList(),
    val retryIntervalMillis: Long = 2_000L,
    val maxRounds: Int = 100,
)

data class CourseSelectionRunState(
    val running: Boolean = false,
    val stopping: Boolean = false,
    val doneKeys: Set<String> = emptySet(),
    val doneReplaceRuleIds: Set<String> = emptySet(),
    val logs: List<String> = emptyList(),
    val awaitingCaptcha: CourseSelectionCaptchaChallenge? = null,
    val awaitingCaptchaCourse: CourseSelectionTarget? = null,
    val captchaSubmitting: Boolean = false,
    val captchaError: String? = null,
    val error: String? = null,
    val completed: Boolean = false,
)

enum class UserCourseDurationType {
    Temporary,
    LongTerm,
}

data class UserCourseDraft(
    val id: Long? = null,
    val courseName: String,
    val weekday: String,
    val weekdayIndex: Int,
    val period: String,
    val periodNumber: Int,
    val timeRange: String? = null,
    val startWeek: Int,
    val endWeek: Int,
    val weeksText: String? = null,
    val durationType: UserCourseDurationType,
    val teacher: String? = null,
    val locationText: String? = null,
    val remark: String? = null,
    val colorIndex: Int = 0,
)

data class UserTodoItem(
    val id: Long,
    val title: String,
    val date: String,
    val note: String? = null,
    val done: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

data class UserTodoDraft(
    val id: Long? = null,
    val title: String,
    val date: String,
    val note: String? = null,
    val done: Boolean = false,
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
    val detailPath: String? = null,
)

@Serializable
data class ScoreData(
    val currentTerm: String? = null,
    val availableTerms: List<TermOption> = emptyList(),
    val items: List<ScoreItem> = emptyList(),
)

@Serializable
data class ScoreDetailField(
    val label: String,
    val value: String,
)

@Serializable
data class ScoreDetailTable(
    val title: String? = null,
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
)

@Serializable
data class ScoreDetailData(
    val title: String? = null,
    val fields: List<ScoreDetailField> = emptyList(),
    val tables: List<ScoreDetailTable> = emptyList(),
    val rawText: String? = null,
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
data class HomeworkAttachment(
    val attachmentId: String,
    val filename: String,
    val url: String? = null,
    val size: String? = null,
)

@Serializable
data class HomeworkItem(
    val homeworkId: Int? = null,
    val course: String,
    val courseId: Int,
    val courseCode: String? = null,
    val title: String,
    val contentExcerpt: String? = null,
    val requirementText: String? = null,
    val openedAt: String? = null,
    val dueAt: String? = null,
    val submittedAt: String? = null,
    val status: String,
    val subType: Int,
    val submissionStatus: String? = null,
    val canSubmit: Boolean = true,
    val contentType: Int = 0,
    val isGroup: Boolean = false,
    val returnNum: Int = 0,
    val attachments: List<HomeworkAttachment> = emptyList(),
)

@Serializable
data class HomeworkData(
    val currentTerm: String? = null,
    val courses: List<CourseSummary> = emptyList(),
    val items: List<HomeworkItem> = emptyList(),
)

data class HomeworkUploadFile(
    val filename: String,
    val content: ByteArray,
    val contentType: String? = null,
)

@Serializable
data class HomeworkSubmitResponse(
    val status: String,
    val message: String? = null,
    val homeworkId: Int,
    val submittedAt: String? = null,
    val upstream: JsonObject = buildJsonObject {},
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
    val playUrl: String? = null,
    val resUrl: String? = null,
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
data class CourseReplayStreamChoice(
    val kind: String,
    val label: String,
    val hlsUrl: String,
    val rtmpUrl: String? = null,
)

@Serializable
data class CourseReplayPlaybackInfo(
    val courseSchedId: String,
    val timeTableId: String? = null,
    val courseId: Int? = null,
    val userId: String? = null,
    val listenUserId: String? = null,
    val streams: List<CourseReplayStreamChoice> = emptyList(),
    val rpSize: String? = null,
    val haveStream: String? = null,
    val rpStatus: String? = null,
    val referer: String? = null,
)

@Serializable
data class CourseReplayLesson(
    val courseSchedId: String,
    val timeTableId: String? = null,
    val uuid: String? = null,
    val videoId: String? = null,
    val courseId: Int? = null,
    val courseName: String? = null,
    val courseCode: String? = null,
    val teacherId: String? = null,
    val teacherName: String? = null,
    val classroomId: String? = null,
    val classroomName: String? = null,
    val teachTimeStr: String? = null,
    val classBeginTime: String? = null,
    val classEndTime: String? = null,
    val hasVideo: Boolean = false,
)

@Serializable
data class CourseReplayData(
    val currentTerm: String? = null,
    val courses: List<CourseSummary> = emptyList(),
    val selectedCourseId: Int? = null,
    val userId: String? = null,
    val listenUserId: String? = null,
    val lessons: List<CourseReplayLesson> = emptyList(),
)

@Serializable
data class MailFolder(
    val folderId: String,
    val name: String,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val messageSize: Int = 0,
    val unreadSize: Int = 0,
    val system: Boolean = false,
)

@Serializable
data class MailMessageSummary(
    val messageId: String,
    val folderId: String,
    val subject: String = "",
    val fromText: String = "",
    val toText: String = "",
    val sender: String? = null,
    val sentAt: String? = null,
    val receivedAt: String? = null,
    val modifiedAt: String? = null,
    val size: Int = 0,
    val read: Boolean = false,
    val attached: Boolean = false,
    val priority: Int? = null,
    val summary: String? = null,
)

@Serializable
data class MailAttachment(
    val attachmentId: String,
    val filename: String,
    val contentType: String? = null,
    val size: Int = 0,
    val part: String,
)

@Serializable
data class MailMessageDetail(
    val messageId: String,
    val folderId: String,
    val subject: String = "",
    val fromText: String = "",
    val toText: String = "",
    val sender: String? = null,
    val sentAt: String? = null,
    val receivedAt: String? = null,
    val modifiedAt: String? = null,
    val size: Int = 0,
    val read: Boolean = false,
    val attached: Boolean = false,
    val priority: Int? = null,
    val summary: String? = null,
    val fromList: List<String> = emptyList(),
    val toList: List<String> = emptyList(),
    val ccList: List<String> = emptyList(),
    val bccList: List<String> = emptyList(),
    val htmlContent: String = "",
    val headers: JsonObject = buildJsonObject {},
    val attachments: List<MailAttachment> = emptyList(),
)

@Serializable
data class MailComposeAttachment(
    val attachmentId: String,
    val filename: String,
    val size: Int = 0,
    val contentType: String? = null,
    val type: String = "upload",
    val securityLevel: String? = null,
)

@Serializable
data class MailComposeRequest(
    val composeId: String? = null,
    val account: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val content: String? = null,
    val htmlContent: String? = null,
    val isHtml: Boolean = false,
    val attachments: List<MailComposeAttachment> = emptyList(),
    val saveSentCopy: Boolean = true,
    val requestReadReceipt: Boolean = false,
    val scheduleDate: String? = null,
    val showOneRcpt: Boolean = false,
    val forbidDownload: Boolean = false,
    val mboxa: String = "",
    val autosaveHitCounter: Boolean = true,
)

@Serializable
data class MailComposeResponse(
    val status: String,
    val composeId: String,
    val draftId: String? = null,
    val sentMessageId: String? = null,
    val upstream: JsonObject = buildJsonObject {},
)

@Serializable
data class MailAttachmentUploadResponse(
    val status: String,
    val composeId: String,
    val attachment: MailAttachment,
    val upstream: JsonObject = buildJsonObject {},
)

@Serializable
data class MailDeleteResponse(
    val status: String,
    val messageIds: List<String> = emptyList(),
    val targetFolderId: String = "4",
    val upstream: JsonObject = buildJsonObject {},
)

@Serializable
data class MailContactSuggestion(
    val contactId: String? = null,
    val displayName: String = "",
    val email: String = "",
    val type: String? = null,
    val location: String? = null,
    val raw: JsonObject = buildJsonObject {},
)

@Serializable
data class MailFoldersData(
    val folders: List<MailFolder> = emptyList(),
)

@Serializable
data class MailMessagesData(
    val folderId: String,
    val start: Int = 0,
    val limit: Int = 20,
    val total: Int = 0,
    val messages: List<MailMessageSummary> = emptyList(),
)

@Serializable
data class MailContactsData(
    val keyword: String,
    val contacts: List<MailContactSuggestion> = emptyList(),
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
    val cellStates: List<String> = emptyList(),
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
    const val CourseSelection = "course_selection"
    const val Exams = "exams"
    const val Scores = "scores"
    const val Calendar = "calendar"
    const val Homework = "homework"
    const val Agent = "agent"
    const val CourseResources = "course_resources"
    const val CourseReplay = "course_replay"
    const val EmptyRooms = "empty_rooms"
    const val Mail = "mail"
}
