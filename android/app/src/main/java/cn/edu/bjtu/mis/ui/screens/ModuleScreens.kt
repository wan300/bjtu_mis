package cn.edu.bjtu.mis.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.calendar.TaskCalendarBuckets
import cn.edu.bjtu.mis.data.calendar.groupTaskCalendarBuckets
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarSyncStore
import cn.edu.bjtu.mis.data.employment.employmentCalendarEventTypeLabel
import cn.edu.bjtu.mis.data.exporting.CalendarExportData
import cn.edu.bjtu.mis.data.exporting.CalendarExportScope
import cn.edu.bjtu.mis.data.exporting.ScheduleExportContentBuilder
import cn.edu.bjtu.mis.data.exporting.ScheduleExportDocument
import cn.edu.bjtu.mis.data.exporting.ScheduleExportFormat
import cn.edu.bjtu.mis.data.exporting.ScheduleExportStorage
import cn.edu.bjtu.mis.data.homework.HomeworkStatusKind
import cn.edu.bjtu.mis.data.homework.findHomeworkByIdentity
import cn.edu.bjtu.mis.data.homework.homeworkCalendarStatusLabel
import cn.edu.bjtu.mis.data.homework.homeworkDueDate
import cn.edu.bjtu.mis.data.homework.homeworkIdentityKey
import cn.edu.bjtu.mis.data.homework.homeworkMatchesStatusFilter
import cn.edu.bjtu.mis.data.homework.homeworkStatusKind
import cn.edu.bjtu.mis.data.repository.DocumentPreview
import cn.edu.bjtu.mis.data.repository.EmploymentConsultationRepository
import cn.edu.bjtu.mis.data.repository.HomeworkAttachmentPreview
import cn.edu.bjtu.mis.data.repository.HomeworkAttachmentRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.update.AppUpdateCheckResult
import cn.edu.bjtu.mis.data.update.AppUpdateChecker
import cn.edu.bjtu.mis.data.update.AppUpdateInfo
import cn.edu.bjtu.mis.data.update.AppUpdatePreferenceStore
import cn.edu.bjtu.mis.data.update.AppUpdatePromptPreference
import cn.edu.bjtu.mis.data.update.installedVersionName
import cn.edu.bjtu.mis.model.AcademicCalendarTerm
import cn.edu.bjtu.mis.model.AcademicCalendarWeek
import cn.edu.bjtu.mis.model.AcademicMonthCalendar
import cn.edu.bjtu.mis.model.AcademicMonthDay
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.DEFAULT_USER_COURSE_MAX_WEEK
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmptyRoomSlotHeader
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.HomeworkUploadFile
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.openwebui.NativeAgentHomeworkHandoff
import cn.edu.bjtu.mis.openwebui.NativeAgentHomeworkHandoffStore
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.model.ProfileField
import cn.edu.bjtu.mis.model.ProfileSection
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.ScoreDetailField
import cn.edu.bjtu.mis.model.ScoreDetailTable
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserCourseDraft
import cn.edu.bjtu.mis.model.UserCourseDurationType
import cn.edu.bjtu.mis.model.UserTodoDraft
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.model.buildAcademicCalendar
import cn.edu.bjtu.mis.model.buildAcademicMonthCalendar
import cn.edu.bjtu.mis.model.defaultAcademicMonth
import cn.edu.bjtu.mis.model.formatUserCourseWeeks
import cn.edu.bjtu.mis.model.normalizedTimetablePeriodNumber
import cn.edu.bjtu.mis.model.parseUserCourseWeeks
import cn.edu.bjtu.mis.model.timetableEntriesConflict
import cn.edu.bjtu.mis.model.userCourseWeekdayLabel
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.ProgressiveStatus
import cn.edu.bjtu.mis.ui.components.SectionTitle
import cn.edu.bjtu.mis.ui.components.AppUpdateAvailableDialog
import cn.edu.bjtu.mis.ui.components.AppUpdateDialogPreference
import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppEffectOverride
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.LocalAppDesign
import cn.edu.bjtu.mis.ui.theme.LocalAppEffects
import cn.edu.bjtu.mis.ui.theme.LocalAppUiStyle
import cn.edu.bjtu.mis.widget.TimetableWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private const val HISTORY_ALL_TERMS = "all"
private val ScoreTypeOptions = listOf(
    "lr" to "本学期成绩",
    "ln" to "历年成绩",
    "en" to "英语认定成绩",
    "rm" to "留级库成绩",
)
private val UserCourseColors = listOf(
    Color(0xFFE57373),
    Color(0xFF9575CD),
    Color(0xFF64B5F6),
    Color(0xFF4DB6AC),
    Color(0xFFFFB74D),
    Color(0xFF81C784),
    Color(0xFFF06292),
    Color(0xFF7986CB),
)

private data class PendingScheduleExport(
    val document: ScheduleExportDocument,
    val format: ScheduleExportFormat,
)

@Composable
private fun rememberScheduleExportLauncher(): (ScheduleExportDocument, ScheduleExportFormat) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<PendingScheduleExport?>(null) }

    fun saveExport(export: PendingScheduleExport) {
        scope.launch {
            runCatching {
                ScheduleExportStorage.save(context, export.document, export.format)
            }.onSuccess { saved ->
                if (export.format == ScheduleExportFormat.Pdf) {
                    runCatching { ScheduleExportStorage.sharePdf(context, saved) }
                }
                Toast.makeText(context, "已导出：${saved.displayName}", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(context, error.message ?: "导出失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val export = pendingExport
        pendingExport = null
        if (granted && export != null) {
            saveExport(export)
        } else {
            Toast.makeText(context, "需要存储权限才能导出文件", Toast.LENGTH_LONG).show()
        }
    }

    return { document, format ->
        val export = PendingScheduleExport(document, format)
        if (ScheduleExportStorage.needsLegacyWritePermission(context)) {
            pendingExport = export
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveExport(export)
        }
    }
}

@Composable
private fun SecondaryModuleLinks(
    title: String,
    links: List<Pair<String, String>>,
    onNavigate: (String) -> Unit,
) {
    InfoCard(title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (route, label) ->
                OutlinedButton(
                    onClick = { onNavigate(route) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    repository: ModuleRepository,
    appUpdateChecker: AppUpdateChecker,
    appUpdatePreferenceStore: AppUpdatePreferenceStore,
    selectedTheme: AppThemeOption = AppThemeOption.Default,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onLogout: () -> Unit,
    onOpenPersonalInfo: () -> Unit,
    onOpenTrainingInfo: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenHomeworkReminder: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val updatePreference by appUpdatePreferenceStore.preference.collectAsState(initial = AppUpdatePromptPreference())
    val installedVersion = remember(context) { context.installedVersionName()?.takeIf { it.isNotBlank() } ?: "未知" }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var updateCheckDialog by remember { mutableStateOf<ProfileUpdateCheckDialog?>(null) }

    fun applyUpdateDialogPreference(
        update: AppUpdateInfo,
        preference: AppUpdateDialogPreference,
        afterApply: () -> Unit = {},
    ) {
        scope.launch {
            runCatching {
                when {
                    preference.disableAutoPrompts -> appUpdatePreferenceStore.disableAutoPrompts()
                    preference.ignoreThisVersion -> appUpdatePreferenceStore.ignoreVersion(update.latestVersion)
                }
            }
            afterApply()
        }
    }

    fun restoreAutoPrompts() {
        scope.launch { appUpdatePreferenceStore.enableAutoPrompts() }
    }

    fun startUpdateCheck() {
        updateCheckDialog = ProfileUpdateCheckDialog.Checking
        scope.launch {
            updateCheckDialog = try {
                ProfileUpdateCheckDialog.Result(appUpdateChecker.checkForUpdateResult())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProfileUpdateCheckDialog.Result(AppUpdateCheckResult.Unavailable())
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前校园账号吗？退出后需要重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    },
                ) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    when (val dialog = updateCheckDialog) {
        null -> Unit
        ProfileUpdateCheckDialog.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检测更新") },
            text = { Text("正在检测最新版本...") },
            confirmButton = {},
        )
        is ProfileUpdateCheckDialog.Result -> ProfileUpdateResultDialog(
            result = dialog.result,
            autoPromptDisabled = updatePreference.autoPromptDisabled,
            onDismiss = { updateCheckDialog = null },
            onRestoreAutoPrompts = {
                restoreAutoPrompts()
            },
            onApplyUpdatePreference = { update, preference, afterApply ->
                updateCheckDialog = null
                applyUpdateDialogPreference(update, preference, afterApply)
            },
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        )
    }

    DataScreen(
        title = "我的",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.profile(strategy) },
    ) { envelope ->
        val profile = envelope.data
        item { ProfileHeaderCard(profile) }
        item {
            ProfileSettingsGroup(
                items = listOf(
                    ProfileSettingsItem(
                        title = "人员信息",
                        subtitle = "姓名、学号与身份资料",
                        iconLabel = "人",
                        iconColor = Color(0xFF58C7B4),
                        icon = Icons.Filled.Person,
                        onClick = onOpenPersonalInfo,
                    ),
                    ProfileSettingsItem(
                        title = "培养信息",
                        subtitle = "学院、专业与学籍资料",
                        iconLabel = "培",
                        iconColor = Color(0xFF4D8EF7),
                        icon = Icons.Filled.School,
                        onClick = onOpenTrainingInfo,
                    ),
                    ProfileSettingsItem(
                        title = "外观与显示",
                        subtitle = "界面、配色与插件显示",
                        iconLabel = "题",
                        iconColor = Color(0xFFE4B96A),
                        icon = Icons.Filled.Palette,
                        trailingText = themeOptionLabel(selectedTheme),
                        onClick = onOpenTheme,
                    ),
                    ProfileSettingsItem(
                        title = "作业提醒",
                        subtitle = "设置普通和紧急提醒阈值",
                        iconLabel = "醒",
                        iconColor = Color(0xFF7C58C2),
                        icon = Icons.Filled.Notifications,
                        onClick = onOpenHomeworkReminder,
                    ),
                    ProfileSettingsItem(
                        title = "检测更新",
                        subtitle = if (updatePreference.autoPromptDisabled) {
                            "自动提示已关闭，仍可手动检测"
                        } else {
                            "当前版本：$installedVersion"
                        },
                        iconLabel = "更",
                        iconColor = Color(0xFF39A86B),
                        icon = Icons.Filled.SystemUpdate,
                        trailingText = if (updateCheckDialog == ProfileUpdateCheckDialog.Checking) "检测中" else null,
                        onClick = { startUpdateCheck() },
                    ),
                ),
            )
        }
        item {
            ProfileSettingsGroup(
                items = listOf(
                    ProfileSettingsItem(
                        title = "学业进度",
                        subtitle = "查看培养完成情况",
                        iconLabel = "进",
                        iconColor = Color(0xFF6E62D6),
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        onClick = { onNavigate(ModuleKeys.AcademicProgress) },
                    ),
                    ProfileSettingsItem(
                        title = "主修成绩",
                        subtitle = "查看本学期成绩",
                        iconLabel = "绩",
                        iconColor = Color(0xFFE46B2D),
                        icon = Icons.Filled.Grade,
                        onClick = { onNavigate(ModuleKeys.Scores) },
                    ),
                    ProfileSettingsItem(
                        title = "查看课表",
                        subtitle = "进入本周课程",
                        iconLabel = "课",
                        iconColor = Color(0xFF18B7D8),
                        icon = Icons.Filled.CalendarMonth,
                        onClick = { onNavigate(ModuleKeys.Timetable) },
                    ),
                ),
            )
        }
        item {
            ProfileSettingsGroup(
                items = listOf(
                    ProfileSettingsItem(
                        title = "退出登录",
                        subtitle = "退出当前校园账号",
                        iconLabel = "退",
                        iconColor = Color(0xFFD95B5B),
                        icon = Icons.AutoMirrored.Filled.Logout,
                        destructive = true,
                        showChevron = false,
                        onClick = { showLogoutConfirm = true },
                    ),
                ),
            )
        }
    }
}

private sealed interface ProfileUpdateCheckDialog {
    data object Checking : ProfileUpdateCheckDialog
    data class Result(val result: AppUpdateCheckResult) : ProfileUpdateCheckDialog
}

@Composable
private fun ProfileUpdateResultDialog(
    result: AppUpdateCheckResult,
    autoPromptDisabled: Boolean,
    onDismiss: () -> Unit,
    onRestoreAutoPrompts: () -> Unit,
    onApplyUpdatePreference: (AppUpdateInfo, AppUpdateDialogPreference, () -> Unit) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    when (result) {
        is AppUpdateCheckResult.UpdateAvailable -> AppUpdateAvailableDialog(
            update = result.update,
            showAutoPromptRestore = autoPromptDisabled,
            onRestoreAutoPrompts = onRestoreAutoPrompts,
            onDismiss = { preference ->
                onApplyUpdatePreference(result.update, preference) {}
            },
            onOpenUpdate = { preference ->
                onApplyUpdatePreference(result.update, preference) {
                    onOpenUrl(result.update.releaseUrl)
                }
            },
        )
        is AppUpdateCheckResult.UpToDate -> AppUpdateStatusDialog(
            title = "已是最新版本",
            message = "当前版本：${result.currentVersion}",
            autoPromptDisabled = autoPromptDisabled,
            onDismiss = onDismiss,
            onRestoreAutoPrompts = onRestoreAutoPrompts,
        )
        is AppUpdateCheckResult.Unavailable -> AppUpdateStatusDialog(
            title = "检测失败",
            message = "暂时无法获取最新版本，请稍后重试。",
            autoPromptDisabled = autoPromptDisabled,
            onDismiss = onDismiss,
            onRestoreAutoPrompts = onRestoreAutoPrompts,
        )
    }
}

@Composable
private fun AppUpdateStatusDialog(
    title: String,
    message: String,
    autoPromptDisabled: Boolean,
    onDismiss: () -> Unit,
    onRestoreAutoPrompts: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            if (autoPromptDisabled) {
                TextButton(
                    onClick = {
                        onRestoreAutoPrompts()
                        onDismiss()
                    },
                ) {
                    Text("恢复自动提示")
                }
            }
        },
    )
}

@Composable
fun ProfilePersonalInfoScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    ProfileInfoDetailScreen(
        title = "人员信息",
        repository = repository,
        initialLoadStrategy = initialLoadStrategy,
        kind = ProfileInfoKind.Personal,
        emptyMessage = "暂无人员信息",
    )
}

@Composable
fun ProfileTrainingInfoScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    ProfileInfoDetailScreen(
        title = "培养信息",
        repository = repository,
        initialLoadStrategy = initialLoadStrategy,
        kind = ProfileInfoKind.Training,
        emptyMessage = "暂无培养信息",
    )
}

@Composable
fun ProfileThemeScreen(
    appearance: AppAppearancePreferences,
    onThemeSelected: (AppThemeOption) -> Unit,
    onUiStyleSelected: (AppUiStyle) -> Unit,
    onReduceMotionSelected: (AppEffectOverride) -> Unit,
    onReduceTransparencySelected: (AppEffectOverride) -> Unit,
    onHideThirdPartyServiceTopBarChanged: (Boolean) -> Unit = {},
) {
    val effects = LocalAppEffects.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ThemeSettingsSection(
                title = "界面",
                subtitle = "界面样式与配色相互独立，切换后立即生效",
            ) {
                InterfaceStyleChoice(
                    title = "经典界面",
                    description = "保留原有导航、角色图标与页面布局",
                    selected = appearance.uiStyle == AppUiStyle.Classic,
                    style = AppUiStyle.Classic,
                    onSelected = onUiStyleSelected,
                )
                InterfaceStyleChoice(
                    title = "Apple 风格界面",
                    description = "更清晰的层级、系统图标与自适应布局",
                    selected = appearance.uiStyle == AppUiStyle.Apple,
                    style = AppUiStyle.Apple,
                    onSelected = onUiStyleSelected,
                )
            }
        }
        item {
            ThemeSettingsSection(
                title = "配色",
                subtitle = "可与任一界面样式自由组合",
            ) {
                ProfileSettingsGroup(items = listOf(
                    ProfileSettingsItem(
                        title = "蓝白色",
                        subtitle = "浅色背景与蓝色主色",
                        iconLabel = "蓝",
                        iconColor = Color(0xFF0B74F6),
                        trailingText = if (appearance.theme == AppThemeOption.Default) "当前" else null,
                        showChevron = false,
                        onClick = { onThemeSelected(AppThemeOption.Default) },
                    ),
                    ProfileSettingsItem(
                        title = "暖金黑",
                        subtitle = "深色背景与暖金主色",
                        iconLabel = "金",
                        iconColor = Color(0xFFE4B96A),
                        trailingText = if (appearance.theme == AppThemeOption.MascotGold) "当前" else null,
                        showChevron = false,
                        onClick = { onThemeSelected(AppThemeOption.MascotGold) },
                    ),
                    ProfileSettingsItem(
                        title = "奶油粉",
                        subtitle = "奶油背景与玫瑰茶棕主色",
                        iconLabel = "粉",
                        iconColor = Color(0xFFB86B63),
                        trailingText = if (appearance.theme == AppThemeOption.IllustrationRose) "当前" else null,
                        showChevron = false,
                        onClick = { onThemeSelected(AppThemeOption.IllustrationRose) },
                    ),
                ))
            }
        }
        item {
            ThemeSettingsSection(
                title = "辅助效果",
                subtitle = "默认跟随系统；也可只为本应用覆盖",
            ) {
                EffectPreferenceRow(
                    title = "减少动态效果",
                    description = "移除位移、弹簧和循环动画，只保留短暂淡化",
                    override = appearance.reduceMotionOverride,
                    effectiveValue = effects.reduceMotion,
                    onOverrideChanged = onReduceMotionSelected,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                )
                EffectPreferenceRow(
                    title = "降低透明效果",
                    description = "使用实色表面，增强内容边界与对比度",
                    override = appearance.reduceTransparencyOverride,
                    effectiveValue = effects.reduceTransparency,
                    onOverrideChanged = onReduceTransparencySelected,
                )
            }
        }
        item {
            ThemeSettingsSection(
                title = "插件显示",
                subtitle = "调整第三方插件运行时的宿主界面",
            ) {
                PluginDisplayPreferenceRow(
                    checked = appearance.hideThirdPartyServiceTopBar,
                    onCheckedChange = onHideThirdPartyServiceTopBarChanged,
                )
            }
        }
    }
}

@Composable
private fun ThemeSettingsSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun InterfaceStyleChoice(
    title: String,
    description: String,
    selected: Boolean,
    style: AppUiStyle,
    onSelected: (AppUiStyle) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ui-style-${style.storageValue}")
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelected(style) },
            ),
        shape = MaterialTheme.shapes.large,
        color = if (selected) colorScheme.primaryContainer else colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.35f),
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InterfaceStylePreview(style)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                )
            }
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
    }
}

@Composable
private fun InterfaceStylePreview(style: AppUiStyle) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(width = 72.dp, height = 52.dp),
        shape = if (style == AppUiStyle.Apple) {
            MaterialTheme.shapes.medium
        } else {
            MaterialTheme.shapes.small
        },
        color = colorScheme.background,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (style == AppUiStyle.Apple) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(10.dp),
                    shape = CircleShape,
                    color = colorScheme.primary.copy(alpha = 0.18f),
                ) {}
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (style == AppUiStyle.Apple) 0.72f else 1f)
                        .height(8.dp),
                    shape = CircleShape,
                    color = colorScheme.primary,
                ) {}
                repeat(2) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = if (style == AppUiStyle.Apple) {
                            MaterialTheme.shapes.small
                        } else {
                            MaterialTheme.shapes.extraSmall
                        },
                        color = colorScheme.surfaceVariant,
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun EffectPreferenceRow(
    title: String,
    description: String,
    override: AppEffectOverride,
    effectiveValue: Boolean,
    onOverrideChanged: (AppEffectOverride) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = if (isApple) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (override != AppEffectOverride.FollowSystem) {
                    TextButton(
                        onClick = { onOverrideChanged(AppEffectOverride.FollowSystem) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("恢复跟随系统")
                    }
                } else {
                    Text(
                        text = "当前跟随系统",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(
                checked = effectiveValue,
                onCheckedChange = { enabled ->
                    onOverrideChanged(
                        if (enabled) AppEffectOverride.Enabled else AppEffectOverride.Disabled,
                    )
                },
            )
        }
    }
}

@Composable
private fun PluginDisplayPreferenceRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hide-third-party-service-top-bar")
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = if (isApple) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "隐藏插件页顶栏",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "插件将占满应用内容区；使用系统返回键或返回手势浏览历史并退出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun ProfileInfoDetailScreen(
    title: String,
    repository: ModuleRepository,
    kind: ProfileInfoKind,
    emptyMessage: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    DataScreen(
        title = title,
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.profile(strategy) },
    ) { envelope ->
        val sections = profileDetailSections(envelope.data, kind)
        if (sections.isEmpty()) {
            item {
                InfoCard(emptyMessage, subtitle = "当前教务系统资料中未返回可展示字段") {}
            }
        } else {
            items(sections) { section ->
                ProfileSectionCard(section)
            }
        }
    }
}

@Composable
private fun ProfileSectionCard(section: ProfileSection) {
    InfoCard(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            section.fields.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { field ->
                        KeyValue(field.label, field.value, Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class ProfileSettingsItem(
    val title: String,
    val subtitle: String? = null,
    val iconLabel: String,
    val iconColor: Color,
    val icon: ImageVector? = null,
    val trailingText: String? = null,
    val destructive: Boolean = false,
    val showChevron: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun ProfileSettingsGroup(items: List<ProfileSettingsItem>) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = if (isApple) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        },
        tonalElevation = if (isApple) 0.dp else 1.dp,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                ProfileSettingsRow(item)
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsRow(item: ProfileSettingsItem) {
    val colorScheme = MaterialTheme.colorScheme
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val titleColor = if (item.destructive) colorScheme.error else colorScheme.onSurface
    val subtitleColor = if (item.destructive) colorScheme.error.copy(alpha = 0.78f) else colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { item.onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileSettingsIcon(item.iconLabel, item.iconColor, item.icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = if (isApple) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = if (isApple) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!item.trailingText.isNullOrBlank()) {
            Text(
                text = item.trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                maxLines = if (isApple) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            )
        }
    }
}

@Composable
private fun ProfileSettingsIcon(
    label: String,
    color: Color,
    icon: ImageVector?,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (LocalAppUiStyle.current == AppUiStyle.Apple && icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class ProfileInfoKind {
    Personal,
    Training,
}

private fun profileDetailSections(
    profile: StudentProfileData,
    kind: ProfileInfoKind,
): List<ProfileSection> {
    val sourceSections = profile.sections.ifEmpty { listOf(ProfileSection("基本信息", profile.fields)) }
    val sectionNeedles = when (kind) {
        ProfileInfoKind.Personal -> listOf("人员", "个人", "基本", "身份")
        ProfileInfoKind.Training -> listOf("培养", "学籍", "学习", "专业", "院系")
    }
    val matchedSections = sourceSections
        .filter { section -> section.title.containsAny(sectionNeedles) }
        .mapNotNull { section ->
            val fields = distinctProfileFields(section.fields)
            if (fields.isEmpty()) null else section.copy(fields = fields)
        }
    if (matchedSections.isNotEmpty()) return matchedSections

    val allFields = distinctProfileFields(sourceSections.flatMap { it.fields } + profile.fields)
    val fieldNeedles = when (kind) {
        ProfileInfoKind.Personal -> listOf(
            "姓名",
            "学号",
            "账号",
            "性别",
            "出生",
            "生日",
            "拼音",
            "英文",
            "民族",
            "政治",
            "国籍",
            "留学生",
        )
        ProfileInfoKind.Training -> listOf(
            "学院",
            "院系",
            "专业",
            "班级",
            "年级",
            "培养",
            "层次",
            "学籍",
            "类别",
            "异动",
            "方式",
            "旁听",
            "语言",
            "校区",
        )
    }
    val filteredFields = allFields.filter { it.label.containsAny(fieldNeedles) }
        .ifEmpty { profileFallbackFields(profile, kind) }
    if (filteredFields.isEmpty()) return emptyList()

    val title = when (kind) {
        ProfileInfoKind.Personal -> "人员信息"
        ProfileInfoKind.Training -> "培养信息"
    }
    return listOf(ProfileSection(title, distinctProfileFields(filteredFields)))
}

private fun profileFallbackFields(
    profile: StudentProfileData,
    kind: ProfileInfoKind,
): List<ProfileField> = buildList {
    fun add(label: String, value: String?) {
        if (!value.isNullOrBlank()) add(ProfileField(label, value))
    }
    when (kind) {
        ProfileInfoKind.Personal -> {
            add("姓名", profile.name)
            add("学号", profile.studentId)
            add("账号", profile.account)
            add("性别", profile.gender)
            add("出生日期", profile.birthday)
            add("姓名拼音", profile.namePinyin)
            add("英文姓名", profile.englishName)
            add("民族", profile.ethnicity)
            add("政治面貌", profile.politicalStatus)
            add("国籍", profile.nationality)
            add("是否留学生", profile.isInternationalStudent)
        }
        ProfileInfoKind.Training -> {
            add("学院", profile.college)
            add("专业", profile.major)
            add("班级", profile.className)
            add("年级", profile.grade)
            add("培养层次", profile.educationLevel)
            add("学籍状态", profile.studentStatus ?: profile.hasStudentStatus)
            add("学生类别", profile.studentCategory)
            add("异动状态", profile.changeStatus)
            add("培养方式", profile.cultivationMethod)
            add("是否旁听生", profile.isAuditor)
            add("授课语言", profile.studyLanguage)
            add("校区", profile.campus)
        }
    }
}

private fun distinctProfileFields(fields: List<ProfileField>): List<ProfileField> =
    fields
        .filter { it.label.isNotBlank() && it.value.isNotBlank() }
        .distinctBy { it.label }

private fun String.containsAny(needles: List<String>): Boolean =
    needles.any { contains(it, ignoreCase = true) }

private fun themeOptionLabel(option: AppThemeOption): String =
    when (option) {
        AppThemeOption.Default -> "蓝白色"
        AppThemeOption.MascotGold -> "暖金黑"
        AppThemeOption.IllustrationRose -> "奶油粉"
    }

@Composable
private fun ProfileHeaderCard(profile: StudentProfileData) {
    InfoCard(
        title = profile.name?.takeIf { it.isNotBlank() } ?: "我的信息",
        subtitle = profile.studentId ?: profile.account,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.name?.trim()?.firstOrNull()?.toString() ?: "我",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = listOfNotNull(profile.college, profile.major).joinToString(" · ").ifBlank { "校园账号资料" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ProfileChip(profile.grade ?: profile.educationLevel ?: "在读", Modifier.weight(1f))
                    ProfileChip(profile.className ?: profile.campus ?: "北京交通大学", Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("培养层次", profile.educationLevel, Modifier.weight(1f))
            KeyValue("学籍状态", profile.studentStatus ?: profile.hasStudentStatus, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("联系电话", profile.phone, Modifier.weight(1f))
            KeyValue("邮箱", profile.email, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AcademicProgressScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    DataScreen(
        title = "学业进度",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.academicProgress(strategy) },
    ) { envelope ->
        val data = envelope.data
        item {
            InfoCard("学分概览", subtitle = "完成率 ${data.summary.completionRate}%") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("已获学分", data.summary.passedCredits.toString(), Modifier.weight(1f))
                    KeyValue("目标学分", data.summary.targetCredits?.toString(), Modifier.weight(1f))
                    KeyValue("需关注课程", data.summary.failedCourseCount.toString(), Modifier.weight(1f))
                }
            }
        }
        items(data.buckets, key = { it.name + it.parent.orEmpty() }) { bucket ->
            InfoCard(bucket.name, subtitle = bucket.parent) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("要求", bucket.requiredCredits?.toString(), Modifier.weight(1f))
                    KeyValue("已完成", bucket.earnedCredits.toString(), Modifier.weight(1f))
                    KeyValue("完成率", bucket.completionRate?.let { "$it%" }, Modifier.weight(1f))
                }
            }
        }
        items(data.courses.take(80), key = { it.courseName + it.term.orEmpty() }) { course ->
            InfoCard(course.courseName, subtitle = course.term) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("学分", course.credit?.toString(), Modifier.weight(1f))
                    KeyValue("成绩", course.score, Modifier.weight(1f))
                    KeyValue("状态", course.status, Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    repository: ModuleRepository,
    courseResourceRepository: CourseResourceRepository,
    homeworkAttachmentRepository: HomeworkAttachmentRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onOpenHomeworkDetail: (HomeworkItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val exportSchedule = rememberScheduleExportLauncher()
    val isWide = configuration.screenWidthDp >= 840
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<TimetableData>>>(LoadState.Loading) }
    var selectedCourses by remember { mutableStateOf<List<CourseEntry>>(emptyList()) }
    var homeworkStates by remember { mutableStateOf<Map<String, LoadState<List<HomeworkItem>>>>(emptyMap()) }
    var resourceStates by remember { mutableStateOf<Map<String, LoadState<ModuleEnvelope<CourseResourcesData>>>>(emptyMap()) }
    var currentWeek by remember { mutableStateOf<Int?>(null) }
    var courseEditorSeed by remember { mutableStateOf<UserCourseEditorSeed?>(null) }
    var courseEditorError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteCourse by remember { mutableStateOf<CourseEntry?>(null) }
    var timetableExportTarget by remember { mutableStateOf<TimetableData?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun loadTimetable(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.timetable(strategy) }
                .onSuccess {
                    state = LoadState.Data(it)
                    if (strategy != ModuleLoadStrategy.CacheOnly) {
                        TimetableWidgetProvider.refreshAll(context)
                    }
                }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    fun addTimetableWidget() {
        val supported = TimetableWidgetProvider.requestPin(context)
        if (!supported) {
            Toast.makeText(
                context,
                "当前桌面不支持自动添加，请长按桌面从小组件列表添加",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun loadDetail(entries: List<CourseEntry>) {
        val nextSelection = entries.ifEmpty { return }
        selectedCourses = nextSelection
        val selectionKey = courseSelectionKey(nextSelection)
        val remoteEntries = nextSelection.filterNot { it.isUserCreated }
        homeworkStates = nextSelection.associate { entry ->
            courseEntryKey(entry) to if (entry.isUserCreated) LoadState.Data(emptyList()) else LoadState.Loading
        }
        resourceStates = nextSelection.associate { entry ->
            courseEntryKey(entry) to if (entry.isUserCreated) {
                LoadState.Data(
                    ModuleEnvelope(
                        module = "course_resources",
                        sourceSystem = "local",
                        coverage = CoverageLevel.Provisional,
                        data = CourseResourcesData(),
                    )
                )
            } else {
                LoadState.Loading
            }
        }
        if (remoteEntries.isNotEmpty()) scope.launch {
            runCatching {
                repository.homework("all").data.items
            }.onSuccess { homework ->
                if (courseSelectionKey(selectedCourses) == selectionKey) {
                    homeworkStates = nextSelection.associate { entry ->
                        courseEntryKey(entry) to if (entry.isUserCreated) {
                            LoadState.Data(emptyList())
                        } else {
                            LoadState.Data(
                                homework.filter { matchesCourse(entry, it) }
                                    .sortedWith(compareBy<HomeworkItem> { it.dueAt ?: "9999" }.thenBy { it.title })
                            )
                        }
                    }
                }
            }.onFailure { error ->
                if (courseSelectionKey(selectedCourses) == selectionKey) {
                    homeworkStates = nextSelection.associate { entry ->
                        courseEntryKey(entry) to LoadState.Error(error.message ?: "作业加载失败")
                    }
                }
            }
        }
        remoteEntries.forEach { entry ->
            val entryKey = courseEntryKey(entry)
            scope.launch {
                runCatching {
                    courseResourceRepository.listing(courseId = courseLookupKey(entry), folderId = "0")
                }.onSuccess {
                    if (courseSelectionKey(selectedCourses) == selectionKey) {
                        resourceStates = resourceStates + (entryKey to LoadState.Data(it))
                    }
                }.onFailure {
                    if (courseSelectionKey(selectedCourses) == selectionKey) {
                        resourceStates = resourceStates + (entryKey to LoadState.Error(it.message ?: "资源加载失败"))
                    }
                }
            }
        }
    }

    fun saveUserCourse(draft: UserCourseDraft) {
        courseEditorError = null
        scope.launch {
            runCatching { repository.saveUserCourse(draft) }
                .onSuccess {
                    TimetableWidgetProvider.refreshAll(context)
                    courseEditorSeed = null
                    selectedCourses = emptyList()
                    loadTimetable()
                }
                .onFailure { courseEditorError = it.message ?: "保存课程失败" }
        }
    }

    fun deleteUserCourse(entry: CourseEntry) {
        val localId = entry.localId ?: return
        scope.launch {
            runCatching { repository.deleteUserCourse(localId) }
                .onSuccess {
                    TimetableWidgetProvider.refreshAll(context)
                    pendingDeleteCourse = null
                    selectedCourses = emptyList()
                    loadTimetable()
                }
                .onFailure { courseEditorError = it.message ?: "删除课程失败" }
        }
    }

    fun exportTimetable(data: TimetableData, format: ScheduleExportFormat) {
        if (data.entries.isEmpty()) {
            Toast.makeText(context, "当前课表为空", Toast.LENGTH_SHORT).show()
        }
        val document = ScheduleExportContentBuilder.buildTimetable(
            data = data,
            currentWeek = currentWeek,
        )
        exportSchedule(document, format)
    }

    LaunchedEffect(Unit) {
        loadTimetable(initialLoadStrategy)
        runCatching { repository.calendar(strategy = initialLoadStrategy) }
            .onSuccess { currentWeek = parseWeekNumber(it.data.currentWeek) }
    }

    when (val current = state) {
        LoadState.Loading, is LoadState.Error -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { LoadingOrError(current) }
        }
        is LoadState.Data -> {
            val envelope = current.value
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TimetableList(
                        envelope = envelope,
                        currentWeek = currentWeek,
                        selectedCourses = selectedCourses,
                        onSelect = ::loadDetail,
                        onAddToHomeWidget = ::addTimetableWidget,
                        onExport = { timetableExportTarget = envelope.data },
                        onAddUserCourse = { day, slot ->
                            courseEditorError = null
                            selectedCourses = emptyList()
                            courseEditorSeed = userCourseEditorSeedForSlot(
                                day = day,
                                slot = slot,
                                currentWeek = currentWeek,
                                maxWeek = timetableMaxWeek(envelope.data.entries),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedCourses.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            CourseDetailPanel(
                                entries = selectedCourses,
                                currentWeek = currentWeek,
                                homeworkStates = homeworkStates,
                                resourceStates = resourceStates,
                                courseResourceRepository = courseResourceRepository,
                                homeworkAttachmentRepository = homeworkAttachmentRepository,
                                onOpenHomeworkDetail = onOpenHomeworkDetail,
                                onEditUserCourse = {
                                    courseEditorError = null
                                    selectedCourses = emptyList()
                                    courseEditorSeed = userCourseEditorSeedForEntry(
                                        entry = it,
                                        currentWeek = currentWeek,
                                        maxWeek = timetableMaxWeek(envelope.data.entries),
                                    )
                                },
                                onDeleteUserCourse = { pendingDeleteCourse = it },
                            )
                        }
                    }
                }
            } else {
                TimetableList(
                    envelope = envelope,
                    currentWeek = currentWeek,
                    selectedCourses = selectedCourses,
                    onSelect = ::loadDetail,
                    onAddToHomeWidget = ::addTimetableWidget,
                    onExport = { timetableExportTarget = envelope.data },
                    onAddUserCourse = { day, slot ->
                        courseEditorError = null
                        selectedCourses = emptyList()
                        courseEditorSeed = userCourseEditorSeedForSlot(
                            day = day,
                            slot = slot,
                            currentWeek = currentWeek,
                            maxWeek = timetableMaxWeek(envelope.data.entries),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (selectedCourses.isNotEmpty()) {
                    ModalBottomSheet(
                        onDismissRequest = { selectedCourses = emptyList() },
                        sheetState = sheetState,
                    ) {
                        CourseDetailPanel(
                            entries = selectedCourses,
                            currentWeek = currentWeek,
                            homeworkStates = homeworkStates,
                            resourceStates = resourceStates,
                            courseResourceRepository = courseResourceRepository,
                            homeworkAttachmentRepository = homeworkAttachmentRepository,
                            onOpenHomeworkDetail = onOpenHomeworkDetail,
                            onEditUserCourse = {
                                courseEditorError = null
                                selectedCourses = emptyList()
                                courseEditorSeed = userCourseEditorSeedForEntry(
                                    entry = it,
                                    currentWeek = currentWeek,
                                    maxWeek = timetableMaxWeek(envelope.data.entries),
                                )
                            },
                            onDeleteUserCourse = { pendingDeleteCourse = it },
                            modifier = Modifier.heightIn(max = 720.dp),
                        )
                    }
                }
            }
        }
    }

    timetableExportTarget?.let { data ->
        TimetableExportDialog(
            onDismiss = { timetableExportTarget = null },
            onExport = { format ->
                timetableExportTarget = null
                exportTimetable(data, format)
            },
        )
    }

    courseEditorSeed?.let { seed ->
        val currentState = state
        val timetableData = if (currentState is LoadState.Data) currentState.value.data else TimetableData()
        ModalBottomSheet(
            onDismissRequest = {
                courseEditorSeed = null
                courseEditorError = null
            },
            sheetState = editorSheetState,
            modifier = Modifier.fillMaxHeight(0.96f),
        ) {
            UserCourseEditorSheet(
                seed = seed,
                data = timetableData,
                currentWeek = currentWeek,
                saveError = courseEditorError,
                onDismiss = {
                    courseEditorSeed = null
                    courseEditorError = null
                },
                onSave = ::saveUserCourse,
            )
        }
    }

    pendingDeleteCourse?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCourse = null },
            title = { Text("删除课程") },
            text = { Text("确定删除“${entry.courseName}”吗？") },
            confirmButton = {
                TextButton(onClick = { deleteUserCourse(entry) }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCourse = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
fun ExamsScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onNavigate: (String) -> Unit,
) {
    DataScreen(
        title = "考务",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.exams(strategy = strategy) },
    ) { envelope ->
        val data = envelope.data
        val currentTerm = data.currentTerm
        item {
            SecondaryModuleLinks(
                title = "考务相关",
                links = listOf(
                    ModuleKeys.AcademicProgress to "学业进度",
                    ModuleKeys.HistoryScores to "历史成绩",
                    ModuleKeys.Scores to "主修成绩",
                ),
                onNavigate = onNavigate,
            )
        }
        if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
        items(data.items, key = { it.courseName + it.schedule.orEmpty() }) { exam ->
            InfoCard(exam.courseName, subtitle = exam.schedule) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("方式", exam.examMode, Modifier.weight(1f))
                    KeyValue("状态", exam.status, Modifier.weight(1f))
                }
                KeyValue("备注", exam.remark)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoresScreen(
    repository: ModuleRepository,
    history: Boolean = false,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val scope = rememberCoroutineScope()
    var requestedTerm by remember(history) { mutableStateOf(if (history) HISTORY_ALL_TERMS else "") }
    var requestedScoreType by remember(history) { mutableStateOf("lr") }
    var refreshNonce by remember(history) { mutableStateOf(0) }
    var selectedScore by remember { mutableStateOf<ScoreItem?>(null) }
    var scoreDetailState by remember { mutableStateOf<LoadState<ModuleEnvelope<ScoreDetailData>>?>(null) }
    val scoreDetailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = if (history) "历史成绩" else "主修成绩"
    val selectedHistoryTerm = requestedTerm.takeIf { history && it != HISTORY_ALL_TERMS }
    val selectedScoreTerm = requestedTerm.takeIf { !history && it.isNotBlank() }

    fun openScoreDetail(score: ScoreItem) {
        selectedScore = score
        scoreDetailState = LoadState.Loading
        scope.launch {
            val detailPath = score.detailPath
            if (detailPath.isNullOrBlank()) {
                scoreDetailState = LoadState.Error("这条成绩没有可用的分数明细链接。")
                return@launch
            }
            runCatching { repository.scoreDetail(detailPath) }
                .onSuccess { scoreDetailState = LoadState.Data(it) }
                .onFailure { scoreDetailState = LoadState.Error(it.message ?: "分数详情加载失败") }
        }
    }

    fun androidx.compose.foundation.lazy.LazyListScope.scoreContent(
        envelope: ModuleEnvelope<ScoreData>,
        loading: Boolean,
    ) {
        val data = envelope.data
        val currentTerm = data.currentTerm
        if (history) {
            item {
                TermSelector(
                    terms = data.availableTerms,
                    value = requestedTerm,
                    onValueChange = { requestedTerm = it },
                    includeAllOption = true,
                )
            }
        } else {
            item {
                ScoreQueryCard(
                    terms = data.availableTerms,
                    selectedTerm = requestedTerm.ifBlank { data.currentTerm.orEmpty() },
                    scoreType = requestedScoreType,
                    onTermChange = {
                        requestedTerm = it
                        refreshNonce += 1
                    },
                    onScoreTypeChange = {
                        requestedScoreType = it
                        refreshNonce += 1
                    },
                )
            }
        }
        if (history && requestedTerm == HISTORY_ALL_TERMS) {
            item {
                AssistChip(onClick = {}, label = { Text("全部学期") })
            }
        } else if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
        if (data.items.isEmpty() && !loading) {
            item {
                InfoCard("暂无$title") {
                    Text("当前没有可展示的${title}记录。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(data.items, key = { it.courseName + it.term.orEmpty() + it.score.orEmpty() }) { score ->
                InfoCard(score.courseName, subtitle = score.term) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("学分", score.credit, Modifier.weight(1f))
                        KeyValue("成绩", score.score, Modifier.weight(1f))
                        if (history) {
                            KeyValue("加分成绩", score.bonusScore, Modifier.weight(1f))
                        } else {
                            KeyValue("教师", score.teacher, Modifier.weight(1f))
                        }
                    }
                    if (history) {
                        KeyValue("教师", score.teacher)
                    }
                    ScoreInlineDetailView(score.detailData)
                    if (score.detailData == null) {
                        KeyValue("详情", score.detail)
                    }
                    if (!score.detailPath.isNullOrBlank() && (history || score.detailData == null)) {
                        OutlinedButton(onClick = { openScoreDetail(score) }) {
                            Text("查看分数详情")
                        }
                    }
                }
            }
        }
    }

    if (history) {
        ProgressiveDataScreen(
            title = title,
            initialLoadStrategy = initialLoadStrategy,
            refreshKey = requestedTerm,
            loader = { strategy -> repository.historyScoresProgressive(selectedHistoryTerm, strategy) },
        ) { progressiveState, envelope ->
            scoreContent(envelope, progressiveState.loading)
        }
    } else {
        DataScreen(
            title = title,
            initialLoadStrategy = initialLoadStrategy,
            refreshKey = listOf(requestedTerm, requestedScoreType, refreshNonce),
            loader = { strategy -> repository.scores(term = selectedScoreTerm, ctype = requestedScoreType, strategy = strategy) },
        ) { envelope ->
            scoreContent(envelope, loading = false)
        }
    }

    selectedScore?.let { score ->
        ModalBottomSheet(
            onDismissRequest = {
                selectedScore = null
                scoreDetailState = null
            },
            sheetState = scoreDetailSheetState,
        ) {
            ScoreDetailSheetContent(
                score = score,
                state = scoreDetailState,
                onRetry = { openScoreDetail(score) },
                modifier = Modifier.heightIn(max = 720.dp),
            )
        }
    }
}

@Composable
private fun ScoreInlineDetailView(detail: ScoreDetailData?) {
    if (detail == null) return
    val tables = detail.tables.filter { table -> table.headers.isNotEmpty() || table.rows.isNotEmpty() }
    val extraFields = scoreDetailExtraFields(detail)
    if (tables.isEmpty() && extraFields.isEmpty()) {
        detail.rawText?.let { KeyValue("详情", it) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tables.forEach { table -> ScoreDetailInlineTableView(table) }
        extraFields.forEach { field -> KeyValue(field.label, field.value) }
    }
}

@Composable
private fun ScoreDetailInlineTableView(table: ScoreDetailTable) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            table.title?.takeIf { it.isNotBlank() } ?: "分数构成",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (table.headers.isNotEmpty()) {
                ScoreDetailTableRow(table.headers, emphasized = true)
                HorizontalDivider()
            }
            table.rows.forEach { row -> ScoreDetailTableRow(row) }
        }
    }
}

private fun scoreDetailExtraFields(detail: ScoreDetailData): List<ScoreDetailField> {
    val hasComponentTable = detail.tables.any(::isScoreComponentTable)
    return detail.fields.filterNot { field ->
        (hasComponentTable && isScoreComponentLabel(field.label)) ||
            isScoreComponentField(field.label) ||
            isScoreSummaryField(field.label)
    }
}

private fun isScoreComponentTable(table: ScoreDetailTable): Boolean {
    val text = (table.headers + table.rows.flatten()).joinToString(" ")
    return listOf("平时", "期末", "期中", "比例", "占比", "权重").any { text.contains(it) } &&
        listOf("成绩", "得分", "分数", "比例", "占比", "权重").any { text.contains(it) }
}

private fun isScoreComponentLabel(label: String): Boolean {
    val compact = label.replace(Regex("""\s+"""), "")
    return listOf("平时", "期末", "期中", "实验", "上机", "作业", "课堂", "考勤", "出勤", "小测", "测验", "报告", "论文", "答辩", "实践", "项目")
        .any { compact.contains(it) }
}

private fun isScoreComponentField(label: String): Boolean {
    val compact = label.replace(Regex("""\s+"""), "")
    return isScoreComponentLabel(label) &&
        listOf("成绩", "得分", "分数", "比例", "占比", "权重", "比重", "百分比")
            .any { compact.contains(it) }
}

private fun isScoreSummaryField(label: String): Boolean {
    val compact = label.replace(Regex("""\s+"""), "")
    return listOf("课程", "总评", "最终成绩", "成绩").any { compact == it }
}

@Composable
private fun ScoreDetailSheetContent(
    score: ScoreItem,
    state: LoadState<ModuleEnvelope<ScoreDetailData>>?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(score.courseName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(score.term.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("成绩", score.score, Modifier.weight(1f))
            KeyValue("加分成绩", score.bonusScore, Modifier.weight(1f))
            KeyValue("学分", score.credit, Modifier.weight(1f))
        }

        when (state) {
            null -> Unit
            LoadState.Loading, is LoadState.Error -> {
                LoadingOrError(state)
                if (state is LoadState.Error) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("重试")
                    }
                }
            }
            is LoadState.Data -> {
                val detail = state.value.data
                if (!detail.title.isNullOrBlank() && detail.title != "详情") {
                    Text(detail.title, style = MaterialTheme.typography.titleMedium)
                }
                if (detail.fields.isNotEmpty()) {
                    InfoCard("明细字段") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            detail.fields.forEach { field ->
                                KeyValue(field.label, field.value)
                            }
                        }
                    }
                }
                detail.tables.forEach { table ->
                    ScoreDetailTableView(table)
                }
                if (detail.fields.isEmpty() && detail.tables.isEmpty()) {
                    Text(
                        detail.rawText ?: "这条成绩没有可展示的明细内容。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreDetailTableView(table: ScoreDetailTable) {
    InfoCard(table.title ?: "分数构成") {
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (table.headers.isNotEmpty()) {
                ScoreDetailTableRow(table.headers, emphasized = true)
                HorizontalDivider()
            }
            table.rows.forEach { row ->
                ScoreDetailTableRow(row)
            }
        }
    }
}

@Composable
private fun ScoreDetailTableRow(row: List<String>, emphasized: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { cell ->
            Text(
                text = cell,
                modifier = Modifier.width(120.dp),
                style = if (emphasized) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScoreQueryCard(
    terms: List<TermOption>,
    selectedTerm: String?,
    scoreType: String,
    onTermChange: (String) -> Unit,
    onScoreTypeChange: (String) -> Unit,
) {
    InfoCard("筛选") {
        TermSelector(
            terms = terms,
            value = selectedTerm,
            onValueChange = onTermChange,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreTypeOptions.forEach { (key, label) ->
                FilterChip(
                    selected = scoreType == key,
                    onClick = { onScoreTypeChange(key) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermSelector(
    terms: List<TermOption>,
    value: String?,
    onValueChange: (String) -> Unit,
    includeAllOption: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (includeAllOption && value == HISTORY_ALL_TERMS) {
        "全部学期"
    } else {
        terms.firstOrNull { it.value == value }?.label ?: value.orEmpty()
    }
    val enabled = terms.isNotEmpty() || includeAllOption

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                expanded = !expanded
            }
        },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("选择学期") },
            placeholder = { Text("暂无可选学期") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (includeAllOption) {
                DropdownMenuItem(
                    text = { Text("全部学期") },
                    onClick = {
                        expanded = false
                        onValueChange(HISTORY_ALL_TERMS)
                    },
                )
            }
            terms.forEach { term ->
                DropdownMenuItem(
                    text = { Text(term.label.ifBlank { term.value }) },
                    onClick = {
                        expanded = false
                        onValueChange(term.value)
                    },
                )
            }
        }
    }
}

private data class CalendarDashboard(
    val calendarEnvelope: ModuleEnvelope<CalendarData>,
    val homework: List<HomeworkItem>,
    val exams: List<ExamItem>,
    val todos: List<UserTodoItem>,
)

private data class CalendarCellChip(
    val text: String,
    val color: Color,
)

private enum class CalendarViewMode {
    Month,
    Week,
}

private val CalendarMonthTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月")
private val CalendarDayTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
private val CalendarWeekdayLabels = listOf("日", "一", "二", "三", "四", "五", "六")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repository: ModuleRepository,
    employmentRepository: EmploymentConsultationRepository,
    employmentCalendarSyncStore: EmploymentCalendarSyncStore,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onOpenHomework: (HomeworkItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val exportSchedule = rememberScheduleExportLauncher()
    val employmentSyncEnabled by employmentCalendarSyncStore.enabled.collectAsState(initial = false)
    val today = remember { LocalDate.now() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCalendarTerm by remember { mutableStateOf<String?>(null) }
    var selectedMonth by remember { mutableStateOf<YearMonth?>(null) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var addTodoDate by remember { mutableStateOf<LocalDate?>(null) }
    var todoSaveError by remember { mutableStateOf<String?>(null) }
    var showTermOverview by remember { mutableStateOf(false) }
    var calendarViewMode by remember { mutableStateOf(CalendarViewMode.Month) }
    var showCalendarExportDialog by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<LoadState<CalendarDashboard>>(LoadState.Loading) }
    var employmentEventsState by remember {
        mutableStateOf<LoadState<List<EmploymentCalendarEvent>>>(LoadState.Data(emptyList()))
    }
    var employmentInitialLoadConsumed by remember { mutableStateOf(false) }

    fun loadDashboard(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            state = LoadState.Loading
            runCatching {
                val calendar = repository.calendar(strategy = strategy)
                CalendarDashboard(
                    calendarEnvelope = calendar,
                    homework = runCatching { repository.homework("all", strategy).data.items }.getOrDefault(emptyList()),
                    exams = runCatching { repository.exams(strategy = strategy).data.items }.getOrDefault(emptyList()),
                    todos = repository.userTodos(),
                )
            }.onSuccess {
                state = LoadState.Data(it)
            }.onFailure {
                state = LoadState.Error(it.message ?: "学年日历加载失败")
            }
        }
    }

    fun setEmploymentSyncEnabled(enabled: Boolean) {
        scope.launch {
            employmentCalendarSyncStore.save(enabled)
            if (!enabled) {
                employmentEventsState = LoadState.Data(emptyList())
            }
        }
    }

    fun openUri(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    suspend fun refreshTodosOnly() {
        val current = state
        if (current !is LoadState.Data) {
            loadDashboard()
            return
        }
        runCatching { repository.userTodos() }
            .onSuccess { todos ->
                state = LoadState.Data(current.value.copy(todos = todos))
            }
            .onFailure {
                todoSaveError = it.message ?: "待办刷新失败"
            }
    }

    fun saveTodo(draft: UserTodoDraft) {
        scope.launch {
            runCatching { repository.saveUserTodo(draft) }
                .onSuccess {
                    addTodoDate = null
                    todoSaveError = null
                    refreshTodosOnly()
                }
                .onFailure {
                    todoSaveError = it.message ?: "待办保存失败"
                }
        }
    }

    fun setTodoDone(todo: UserTodoItem, done: Boolean) {
        scope.launch {
            runCatching { repository.setUserTodoDone(todo.id, done) }
                .onSuccess {
                    todoSaveError = null
                    refreshTodosOnly()
                }
                .onFailure {
                    todoSaveError = it.message ?: "待办状态更新失败"
                }
        }
    }

    fun deleteTodo(todo: UserTodoItem) {
        scope.launch {
            runCatching { repository.deleteUserTodo(todo.id) }
                .onSuccess {
                    todoSaveError = null
                    refreshTodosOnly()
                }
                .onFailure {
                    todoSaveError = it.message ?: "待办删除失败"
                }
        }
    }

    LaunchedEffect(Unit) {
        loadDashboard(initialLoadStrategy)
    }

    LaunchedEffect(employmentSyncEnabled) {
        val strategy = if (employmentInitialLoadConsumed) ModuleLoadStrategy.NetworkFirst else initialLoadStrategy
        employmentInitialLoadConsumed = true
        if (employmentSyncEnabled) {
            employmentEventsState = LoadState.Loading
            runCatching { employmentRepository.calendarEvents(strategy = strategy) }
                .onSuccess { employmentEventsState = LoadState.Data(it) }
                .onFailure {
                    employmentEventsState = LoadState.Error(it.message ?: "就业活动同步失败")
                }
        } else {
            employmentEventsState = LoadState.Data(emptyList())
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> LoadingOrError(current)
            is LoadState.Data -> {
                val dashboard = current.value
                val data = dashboard.calendarEnvelope.data
                val terms = data.availableTerms
                val fallbackTerm = data.currentTerm?.takeIf { it.isNotBlank() }
                    ?: terms.firstOrNull { it.selected }?.value
                    ?: terms.firstOrNull()?.value
                val effectiveTerm = selectedCalendarTerm
                    ?.takeIf { candidate -> terms.isEmpty() || terms.any { it.value == candidate } }
                    ?: fallbackTerm
                val selectedTerm = terms.firstOrNull { it.value == effectiveTerm }
                val calendar = buildAcademicCalendar(effectiveTerm, selectedTerm?.label)
                val displayMonth = selectedMonth ?: defaultAcademicMonth(today, calendar)
                val monthCalendar = remember(displayMonth) { buildAcademicMonthCalendar(displayMonth) }
                val selectedIsCurrentTerm = effectiveTerm != null && effectiveTerm == data.currentTerm
                val currentWeek = if (selectedIsCurrentTerm) parseWeekNumber(data.currentWeek) else null
                val taskBucketsByDate = remember(dashboard.homework, dashboard.exams, data.items) {
                    groupTaskCalendarBuckets(
                        homework = dashboard.homework,
                        exams = dashboard.exams,
                        calendarItems = data.items,
                    )
                }
                val todosByDate = remember(dashboard.todos) { dashboard.todos.groupByTodoDate() }
                val employmentEvents = if (employmentSyncEnabled) {
                    (employmentEventsState as? LoadState.Data)?.value.orEmpty()
                } else {
                    emptyList()
                }
                val employmentEventsByDate = remember(employmentEvents) { employmentEvents.groupByEmploymentEventDate() }
                val calendarExportData = remember(taskBucketsByDate, todosByDate, employmentEventsByDate) {
                    CalendarExportData(
                        bucketsByDate = taskBucketsByDate,
                        todosByDate = todosByDate,
                        employmentEventsByDate = employmentEventsByDate,
                    )
                }

                fun exportCalendar(scopeValue: CalendarExportScope, format: ScheduleExportFormat) {
                    val document = when (scopeValue) {
                        CalendarExportScope.TermOverview -> {
                            val termCalendar = calendar
                            if (termCalendar == null) {
                                Toast.makeText(context, "无法生成学期周表", Toast.LENGTH_LONG).show()
                                return
                            }
                            ScheduleExportContentBuilder.buildTermOverview(
                                calendar = termCalendar,
                                currentWeek = currentWeek,
                                today = today,
                            )
                        }
                        CalendarExportScope.Month -> ScheduleExportContentBuilder.buildMonthView(
                            calendar = monthCalendar,
                            exportData = calendarExportData,
                            today = today,
                        )
                        CalendarExportScope.Week -> {
                            val anchor = ScheduleExportContentBuilder.weekAnchorDate(
                                selectedDate = selectedDate,
                                today = today,
                                displayMonth = displayMonth,
                            )
                            ScheduleExportContentBuilder.buildWeekView(
                                anchorDate = anchor,
                                exportData = calendarExportData,
                                today = today,
                            )
                        }
                    }
                    exportSchedule(document, format)
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    item {
                        EmploymentCalendarSyncCard(
                            enabled = employmentSyncEnabled,
                            eventsState = employmentEventsState,
                            onEnabledChange = ::setEmploymentSyncEnabled,
                        )
                    }
                    item {
                        CalendarMonthHeaderCard(
                            month = displayMonth,
                            terms = terms,
                            selectedTerm = effectiveTerm,
                            calendar = calendar,
                            today = today,
                            currentWeek = currentWeek,
                            onPreviousMonth = { selectedMonth = displayMonth.minusMonths(1) },
                            onNextMonth = { selectedMonth = displayMonth.plusMonths(1) },
                            onToday = {
                                selectedMonth = YearMonth.from(today)
                                selectedDate = today
                            },
                            onExport = { showCalendarExportDialog = true },
                            onTermChange = { value ->
                                selectedCalendarTerm = value
                                val term = terms.firstOrNull { it.value == value }
                                selectedMonth = buildAcademicCalendar(value, term?.label)
                                    ?.let { defaultAcademicMonth(today, it) }
                                selectedDate = null
                                showTermOverview = false
                            },
                        )
                    }
                    item {
                        CalendarMonthOverviewCard(
                            month = displayMonth,
                            taskBucketsByDate = taskBucketsByDate,
                            todos = dashboard.todos,
                            employmentEvents = employmentEvents,
                        )
                    }
                    item {
                        CalendarViewModeSelector(
                            value = calendarViewMode,
                            onValueChange = { calendarViewMode = it },
                        )
                    }
                    item {
                        if (calendarViewMode == CalendarViewMode.Month) {
                            AcademicMonthGrid(
                                calendar = monthCalendar,
                                today = today,
                                selectedDate = selectedDate,
                                taskBucketsByDate = taskBucketsByDate,
                                todosByDate = todosByDate,
                                employmentEventsByDate = employmentEventsByDate,
                                onDateClick = { selectedDate = it },
                            )
                        } else {
                            CalendarWeekTaskList(
                                anchorDate = selectedDate ?: if (YearMonth.from(today) == displayMonth) today else displayMonth.atDay(1),
                                today = today,
                                selectedDate = selectedDate,
                                taskBucketsByDate = taskBucketsByDate,
                                todosByDate = todosByDate,
                                employmentEventsByDate = employmentEventsByDate,
                                onDateClick = { selectedDate = it },
                            )
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = { showTermOverview = !showTermOverview },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (showTermOverview) "收起学期总览/周表" else "展开学期总览/周表")
                        }
                    }
                    if (showTermOverview) {
                        if (calendar == null) {
                            item {
                                InfoCard("无法生成校历", subtitle = effectiveTerm) {
                                    Text("当前学期编码或标签无法解析。", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            item {
                                AcademicCalendarTable(
                                    calendar = calendar,
                                    currentWeek = currentWeek,
                                    today = today,
                                )
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = {
                        todoSaveError = null
                        addTodoDate = selectedDate ?: today
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(18.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新增待办")
                }

                if (showCalendarExportDialog) {
                    CalendarExportDialog(
                        termOverviewAvailable = calendar != null,
                        onDismiss = { showCalendarExportDialog = false },
                        onExport = { scopeValue, format ->
                            showCalendarExportDialog = false
                            exportCalendar(scopeValue, format)
                        },
                    )
                }

                selectedDate?.let { date ->
                    ModalBottomSheet(
                        onDismissRequest = { selectedDate = null },
                        sheetState = sheetState,
                    ) {
                        CalendarDayDetail(
                            date = date,
                            buckets = taskBucketsByDate[date] ?: TaskCalendarBuckets(),
                            todos = todosByDate[date].orEmpty(),
                            employmentEvents = employmentEventsByDate[date].orEmpty(),
                            onAddTodo = {
                                todoSaveError = null
                                addTodoDate = date
                            },
                            onToggleTodo = ::setTodoDone,
                            onDeleteTodo = ::deleteTodo,
                            onOpenEmploymentUrl = ::openUri,
                            onOpenHomework = { item ->
                                selectedDate = null
                                onOpenHomework(item)
                            },
                        )
                    }
                }

                addTodoDate?.let { date ->
                    UserTodoEditorDialog(
                        initialDate = date,
                        errorMessage = todoSaveError,
                        onDismiss = {
                            addTodoDate = null
                            todoSaveError = null
                        },
                        onSave = ::saveTodo,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmploymentCalendarSyncCard(
    enabled: Boolean,
    eventsState: LoadState<List<EmploymentCalendarEvent>>,
    onEnabledChange: (Boolean) -> Unit,
) {
    InfoCard(
        title = "同步就业活动",
        subtitle = if (enabled) "宣讲会、双选会将显示在学年日历中" else "开启后在日历中展示宣讲会、双选会",
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        },
    ) {
        val statusText = when (eventsState) {
            LoadState.Loading -> "正在同步就业活动"
            is LoadState.Data -> if (enabled) "已同步 ${eventsState.value.size} 项就业活动" else "当前未开启同步"
            is LoadState.Error -> eventsState.message
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (eventsState is LoadState.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CalendarMonthHeaderCard(
    month: YearMonth,
    terms: List<TermOption>,
    selectedTerm: String?,
    calendar: AcademicCalendarTerm?,
    today: LocalDate,
    currentWeek: Int?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onExport: () -> Unit,
    onTermChange: (String) -> Unit,
) {
    InfoCard(
        title = month.format(CalendarMonthTitleFormatter),
        subtitle = calendar?.label ?: selectedTerm,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousMonth) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
            }
            Text(
                month.format(CalendarMonthTitleFormatter),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下个月")
            }
        }
        if (terms.isNotEmpty()) {
            TermSelector(
                terms = terms,
                value = selectedTerm,
                onValueChange = onTermChange,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("今天")
            }
            OutlinedButton(onClick = onExport, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出")
            }
            AssistChip(onClick = {}, label = { Text("今天 ${today.monthValue}月${today.dayOfMonth}日") })
            calendar?.let {
                AssistChip(onClick = {}, label = { Text("${it.weeks.size} 周") })
            }
            if (currentWeek != null) {
                AssistChip(onClick = {}, label = { Text("第 $currentWeek 教学周") })
            }
        }
    }
}

@Composable
private fun CalendarExportDialog(
    termOverviewAvailable: Boolean,
    onDismiss: () -> Unit,
    onExport: (CalendarExportScope, ScheduleExportFormat) -> Unit,
) {
    var scope by remember(termOverviewAvailable) {
        mutableStateOf(if (termOverviewAvailable) CalendarExportScope.TermOverview else CalendarExportScope.Month)
    }
    var format by remember { mutableStateOf(ScheduleExportFormat.Pdf) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出学年日历") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("导出范围")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalendarExportScope.values().forEach { option ->
                        FilterChip(
                            selected = scope == option,
                            enabled = option != CalendarExportScope.TermOverview || termOverviewAvailable,
                            onClick = { scope = option },
                            label = {
                                Text(
                                    if (option == CalendarExportScope.TermOverview && !termOverviewAvailable) {
                                        "${option.label}（无法生成）"
                                    } else {
                                        option.label
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text("导出格式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ScheduleExportFormat.values().forEach { option ->
                        FilterChip(
                            selected = format == option,
                            onClick = { format = option },
                            label = { Text(option.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(scope, format) }) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun CalendarMonthOverviewCard(
    month: YearMonth,
    taskBucketsByDate: Map<LocalDate, TaskCalendarBuckets>,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
) {
    val monthBuckets = taskBucketsByDate
        .filterKeys { YearMonth.from(it) == month }
        .values
    val monthTodos = todos.filter { item ->
        item.todoDate()?.let { YearMonth.from(it) == month } == true
    }
    val monthEmploymentEvents = employmentEvents.filter { YearMonth.from(it.date) == month }
    val monthHomework = monthBuckets.flatMap { it.homeworkDues }
    val homeworkStarts = monthBuckets.sumOf { it.homeworkStarts.size }
    val unsubmittedHomework = monthHomework.count { homeworkCalendarStatusLabel(it) == "未提交" }
    val homeworkDues = monthBuckets.sumOf { it.homeworkDues.size }
    val exams = monthBuckets.sumOf { it.exams.size }
    val schoolCalendarItems = monthBuckets.sumOf { it.calendarItems.size }
    val doneHomework = monthHomework.size - unsubmittedHomework
    val openTodos = monthTodos.count { !it.done }

    InfoCard(
        title = "本月任务",
        subtitle = month.format(CalendarMonthTitleFormatter),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CalendarMetric("作业开始", homeworkStarts.toString(), Color(0xFF2F8DD8), Modifier.weight(1f))
                CalendarMetric("作业截止", homeworkDues.toString(), Color(0xFFD64B6B), Modifier.weight(1f))
                CalendarMetric("考试", exams.toString(), Color(0xFFFF8A00), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CalendarMetric("校历", schoolCalendarItems.toString(), Color(0xFF0E9D9D), Modifier.weight(1f))
                CalendarMetric("待办", openTodos.toString(), Color(0xFF7C58C2), Modifier.weight(1f))
                CalendarMetric("就业", monthEmploymentEvents.size.toString(), Color(0xFF0E7490), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalendarMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(tint.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CalendarViewModeSelector(
    value: CalendarViewMode,
    onValueChange: (CalendarViewMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = value == CalendarViewMode.Month,
            onClick = { onValueChange(CalendarViewMode.Month) },
            label = { Text("月视图") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = value == CalendarViewMode.Week,
            onClick = { onValueChange(CalendarViewMode.Week) },
            label = { Text("周视图") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CalendarWeekTaskList(
    anchorDate: LocalDate,
    today: LocalDate,
    selectedDate: LocalDate?,
    taskBucketsByDate: Map<LocalDate, TaskCalendarBuckets>,
    todosByDate: Map<LocalDate, List<UserTodoItem>>,
    employmentEventsByDate: Map<LocalDate, List<EmploymentCalendarEvent>>,
    onDateClick: (LocalDate) -> Unit,
) {
    val weekStart = remember(anchorDate) {
        anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0L..6L).forEach { offset ->
            val date = weekStart.plusDays(offset)
            CalendarWeekDayRow(
                date = date,
                isToday = date == today,
                selected = date == selectedDate,
                buckets = taskBucketsByDate[date] ?: TaskCalendarBuckets(),
                todos = todosByDate[date].orEmpty(),
                employmentEvents = employmentEventsByDate[date].orEmpty(),
                onClick = { onDateClick(date) },
            )
        }
    }
}

private fun buildCalendarChips(
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
): List<CalendarCellChip> = buildList {
    buckets.homeworkStarts.forEach { item ->
        add(CalendarCellChip(text = "开始 ${item.title}", color = Color(0xFF2F8DD8)))
    }
    buckets.homeworkDues.forEach { item ->
        val submitted = homeworkStatusKind(item) == HomeworkStatusKind.Done
        add(
            CalendarCellChip(
                text = "截止 ${item.title}",
                color = if (submitted) Color(0xFF2AA876) else Color(0xFFD64B6B),
            )
        )
    }
    buckets.exams.forEach { exam ->
        add(CalendarCellChip(text = "考试 ${exam.courseName}", color = Color(0xFFFF8A00)))
    }
    buckets.calendarItems.forEach { item ->
        add(CalendarCellChip(text = item.note.orEmpty(), color = Color(0xFF0E9D9D)))
    }
    todos.forEach { todo ->
        add(CalendarCellChip(text = todo.title, color = if (todo.done) Color(0xFF6B7280) else Color(0xFF7C58C2)))
    }
    employmentEvents.forEach { event ->
        add(
            CalendarCellChip(
                text = "${employmentCalendarEventTypeLabel(event.type)} ${event.title}",
                color = Color(0xFF0E7490),
            )
        )
    }
}

@Composable
private fun CalendarWeekDayRow(
    date: LocalDate,
    isToday: Boolean,
    selected: Boolean,
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val borderColor = if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.32f)
    val chips = buildCalendarChips(buckets, todos, employmentEvents)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isToday) colorScheme.primaryContainer.copy(alpha = 0.28f) else colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isToday || selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isToday || selected) colorScheme.primary else colorScheme.onSurface,
                )
                Text(
                    text = CalendarWeekdayLabels[date.dayOfWeek.value % 7],
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chips.isEmpty()) {
                    Text("暂无任务", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
                } else {
                    chips.take(4).forEach { CalendarCellChipView(it) }
                    if (chips.size > 4) {
                        Text(
                            "+${chips.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicMonthGrid(
    calendar: AcademicMonthCalendar,
    today: LocalDate,
    selectedDate: LocalDate?,
    taskBucketsByDate: Map<LocalDate, TaskCalendarBuckets>,
    todosByDate: Map<LocalDate, List<UserTodoItem>>,
    employmentEventsByDate: Map<LocalDate, List<EmploymentCalendarEvent>>,
    onDateClick: (LocalDate) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.36f)),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                CalendarWeekdayLabels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            calendar.weeks.forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    week.days.forEach { day ->
                        AcademicMonthDayCell(
                            day = day,
                            today = today,
                            selected = selectedDate == day.date,
                            buckets = taskBucketsByDate[day.date] ?: TaskCalendarBuckets(),
                            todos = todosByDate[day.date].orEmpty(),
                            employmentEvents = employmentEventsByDate[day.date].orEmpty(),
                            onClick = { onDateClick(day.date) },
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicMonthDayCell(
    day: AcademicMonthDay,
    today: LocalDate,
    selected: Boolean,
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isToday = day.date == today
    val borderColor = when {
        selected -> colorScheme.primary
        isToday -> colorScheme.primary.copy(alpha = 0.62f)
        else -> colorScheme.outline.copy(alpha = 0.28f)
    }
    val background = when {
        selected -> colorScheme.primaryContainer.copy(alpha = 0.44f)
        isToday -> colorScheme.primaryContainer.copy(alpha = 0.28f)
        else -> colorScheme.surface
    }
    val dayColor = when {
        !day.inMonth -> colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
        isToday || selected -> colorScheme.primary
        else -> colorScheme.onSurface
    }
    val chips = remember(buckets, todos, employmentEvents) {
        buildList {
            buckets.homeworkStarts.forEach { item ->
                add(
                    CalendarCellChip(
                        text = "开始 ${item.title}",
                        color = Color(0xFF2F8DD8),
                    )
                )
            }
            buckets.homeworkDues.forEach { item ->
                val submitted = homeworkCalendarStatusLabel(item) == "已提交"
                add(
                    CalendarCellChip(
                        text = "截止 ${item.title}",
                        color = if (submitted) Color(0xFF2AA876) else Color(0xFFD64B6B),
                    )
                )
            }
            buckets.exams.forEach { exam ->
                add(
                    CalendarCellChip(
                        text = "考试 ${exam.courseName}",
                        color = Color(0xFFFF8A00),
                    )
                )
            }
            buckets.calendarItems.forEach { item ->
                add(
                    CalendarCellChip(
                        text = item.note.orEmpty(),
                        color = Color(0xFF0E9D9D),
                    )
                )
            }
            todos.forEach { todo ->
                add(
                    CalendarCellChip(
                        text = todo.title,
                        color = if (todo.done) Color(0xFF6B7280) else Color(0xFF7C58C2),
                    )
                )
            }
            employmentEvents.forEach { event ->
                add(
                    CalendarCellChip(
                        text = "${employmentCalendarEventTypeLabel(event.type)} ${event.title}",
                        color = Color(0xFF0E7490),
                    )
                )
            }
        }
    }

    Column(
        modifier = modifier
            .border(if (selected) 1.5.dp else 0.5.dp, borderColor, MaterialTheme.shapes.small)
            .background(background, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || selected) FontWeight.SemiBold else FontWeight.Normal,
            color = dayColor,
            maxLines = 1,
        )
        chips.take(2).forEach { chip ->
            CalendarCellChipView(chip)
        }
        if (chips.size > 2) {
            Text(
                text = "+${chips.size - 2}",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CalendarCellChipView(chip: CalendarCellChip) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(chip.color.copy(alpha = 0.16f), MaterialTheme.shapes.small)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            chip.text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = chip.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CalendarDayDetail(
    date: LocalDate,
    buckets: TaskCalendarBuckets,
    todos: List<UserTodoItem>,
    employmentEvents: List<EmploymentCalendarEvent>,
    onAddTodo: () -> Unit,
    onToggleTodo: (UserTodoItem, Boolean) -> Unit,
    onDeleteTodo: (UserTodoItem) -> Unit,
    onOpenEmploymentUrl: (String) -> Unit,
    onOpenHomework: (HomeworkItem) -> Unit,
) {
    val homeworkStarts = buckets.homeworkStarts
    val homework = buckets.homeworkDues
    val exams = buckets.exams
    val calendarItems = buckets.calendarItems
    val summary = buildList {
        if (homeworkStarts.isNotEmpty()) add("${homeworkStarts.size} 项作业开始")
        if (homework.isNotEmpty()) add("${homework.size} 项作业截止")
        if (exams.isNotEmpty()) add("${exams.size} 场考试")
        if (calendarItems.isNotEmpty()) add("${calendarItems.size} 项校历安排")
        if (todos.isNotEmpty()) add("${todos.size} 项自定义待办")
        if (employmentEvents.isNotEmpty()) add("${employmentEvents.size} 项就业活动")
    }.ifEmpty { listOf("当天暂无任务") }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    date.format(CalendarDayTitleFormatter),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onAddTodo) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增")
            }
        }

        if (buckets.isEmpty && todos.isEmpty() && employmentEvents.isEmpty()) {
            Text("当天暂无任务。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (homeworkStarts.isNotEmpty()) {
            Text("今天开始的作业", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            homeworkStarts.forEach { item ->
                InfoCard(
                    title = item.title,
                    modifier = Modifier.clickable { onOpenHomework(item) },
                    subtitle = item.course,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("开始", item.openedAt, Modifier.weight(1f))
                        KeyValue("截止", item.dueAt, Modifier.weight(1f))
                    }
                    KeyValue("内容", item.contentExcerpt)
                }
            }
        }

        if (exams.isNotEmpty()) {
            Text("今天的考试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            exams.forEach { exam ->
                InfoCard(
                    title = exam.courseName,
                    subtitle = exam.examMode,
                ) {
                    KeyValue("安排", exam.schedule)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("状态", exam.status, Modifier.weight(1f))
                        KeyValue("备注", exam.remark, Modifier.weight(1f))
                    }
                }
            }
        }

        if (calendarItems.isNotEmpty()) {
            Text("校历安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            calendarItems.forEach { item ->
                InfoCard(
                    title = item.note.orEmpty(),
                    subtitle = item.week,
                ) {
                    KeyValue("日期", item.date)
                }
            }
        }

        if (homework.isNotEmpty()) {
            Text("作业截止", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            homework.forEach { item ->
                InfoCard(
                    title = item.title,
                    modifier = Modifier.clickable { onOpenHomework(item) },
                    subtitle = item.course,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CalendarStatusPill(
                            text = homeworkCalendarStatusLabel(item),
                            color = if (homeworkCalendarStatusLabel(item) == "已提交") Color(0xFF2AA876) else Color(0xFFD64B6B),
                        )
                        item.submissionStatus?.takeIf { it.isNotBlank() }?.let {
                            CalendarStatusPill(text = it, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("截止", item.dueAt, Modifier.weight(1f))
                        KeyValue("提交", item.submittedAt, Modifier.weight(1f))
                    }
                    KeyValue("内容", item.contentExcerpt)
                }
            }
        }

        if (todos.isNotEmpty()) {
            Text("自定义待办", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            todos.forEach { todo ->
                UserTodoRow(
                    todo = todo,
                    onToggle = { onToggleTodo(todo, it) },
                    onDelete = { onDeleteTodo(todo) },
                )
            }
        }

        if (employmentEvents.isNotEmpty()) {
            Text("就业活动", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            employmentEvents.forEach { event ->
                InfoCard(
                    title = event.title,
                    subtitle = listOfNotNull(
                        employmentCalendarEventTypeLabel(event.type),
                        event.organization,
                    ).joinToString(" · ").ifBlank { null },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CalendarStatusPill(
                            text = employmentCalendarEventTypeLabel(event.type),
                            color = Color(0xFF0E7490),
                        )
                        event.statusLabel?.let {
                            CalendarStatusPill(text = it, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("时间", event.employmentTimeLabel(), Modifier.weight(1f))
                        KeyValue("地点", event.location, Modifier.weight(1f))
                    }
                    event.organization?.let { KeyValue("单位", it) }
                    event.url.takeIf { it.isNotBlank() }?.let { url ->
                        OutlinedButton(
                            onClick = { onOpenEmploymentUrl(url) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开详情")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarStatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun UserTodoRow(
    todo: UserTodoItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = todo.done, onCheckedChange = onToggle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                todo.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除待办", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun UserTodoEditorDialog(
    initialDate: LocalDate,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (UserTodoDraft) -> Unit,
) {
    var title by remember(initialDate) { mutableStateOf("") }
    var dateText by remember(initialDate) { mutableStateOf(initialDate.toString()) }
    var note by remember(initialDate) { mutableStateOf("") }
    var validationError by remember(initialDate) { mutableStateOf<String?>(null) }
    val visibleError = validationError ?: errorMessage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        validationError = null
                    },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = {
                        dateText = it
                        validationError = null
                    },
                    label = { Text("日期 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                visibleError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedDate = try {
                        LocalDate.parse(dateText.trim())
                    } catch (_: DateTimeParseException) {
                        null
                    }
                    when {
                        title.isBlank() -> validationError = "标题不能为空"
                        parsedDate == null -> validationError = "日期格式应为 yyyy-MM-dd"
                        else -> onSave(
                            UserTodoDraft(
                                title = title.trim(),
                                date = parsedDate.toString(),
                                note = note,
                            )
                        )
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun List<HomeworkItem>.groupByHomeworkDueDate(): Map<LocalDate, List<HomeworkItem>> =
    mapNotNull { item -> homeworkDueDate(item)?.let { it to item } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

private fun List<UserTodoItem>.groupByTodoDate(): Map<LocalDate, List<UserTodoItem>> =
    mapNotNull { item -> item.todoDate()?.let { it to item } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

private fun List<EmploymentCalendarEvent>.groupByEmploymentEventDate(): Map<LocalDate, List<EmploymentCalendarEvent>> =
    groupBy { it.date }

private fun UserTodoItem.todoDate(): LocalDate? =
    try {
        LocalDate.parse(date)
    } catch (_: DateTimeParseException) {
        null
    }

private fun EmploymentCalendarEvent.employmentTimeLabel(): String? =
    when {
        !startTime.isNullOrBlank() && !endTime.isNullOrBlank() ->
            "${startTime.trim().removeSuffix(":00")} - ${endTime.trim().removeSuffix(":00")}"
        !startTime.isNullOrBlank() -> startTime.trim().removeSuffix(":00")
        !endTime.isNullOrBlank() -> endTime.trim().removeSuffix(":00")
        else -> null
    }

@Composable
private fun AcademicCalendarTable(
    calendar: AcademicCalendarTerm,
    currentWeek: Int?,
    today: LocalDate,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = calendar.label,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val leftColumnWidth = 74.dp
                val minDayWidth = 42.dp
                val availableDayWidth = (maxWidth - leftColumnWidth) / 7
                val dayWidth = if (availableDayWidth > minDayWidth) availableDayWidth else minDayWidth
                val contentWidth = leftColumnWidth + dayWidth * 7f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Column(Modifier.width(contentWidth)) {
                        AcademicCalendarHeaderRow(
                            leftColumnWidth = leftColumnWidth,
                            dayWidth = dayWidth,
                        )
                        calendar.weeks.forEach { week ->
                            AcademicCalendarWeekRow(
                                week = week,
                                leftColumnWidth = leftColumnWidth,
                                dayWidth = dayWidth,
                                currentWeek = currentWeek,
                                today = today,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicCalendarHeaderRow(leftColumnWidth: Dp, dayWidth: Dp) {
    val borderColor = MaterialTheme.colorScheme.surfaceVariant
    Row {
        AcademicCalendarCell(
            modifier = Modifier
                .width(leftColumnWidth)
                .height(42.dp),
            background = MaterialTheme.colorScheme.surfaceVariant,
            borderColor = borderColor,
        ) {
            Text("周", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
            AcademicCalendarCell(
                modifier = Modifier
                    .width(dayWidth)
                    .height(42.dp),
                background = MaterialTheme.colorScheme.surfaceVariant,
                borderColor = borderColor,
            ) {
                Text("周$weekday", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AcademicCalendarWeekRow(
    week: AcademicCalendarWeek,
    leftColumnWidth: Dp,
    dayWidth: Dp,
    currentWeek: Int?,
    today: LocalDate,
) {
    val isCurrentWeek = currentWeek == week.termWeekNumber
    val colorScheme = MaterialTheme.colorScheme
    val rowBackground = if (isCurrentWeek) {
        colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        colorScheme.surface
    }
    val borderColor = if (isCurrentWeek) colorScheme.primary else colorScheme.surfaceVariant

    Row(Modifier.height(IntrinsicSize.Min)) {
        AcademicCalendarCell(
            modifier = Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .heightIn(min = 54.dp),
            background = if (isCurrentWeek) colorScheme.primaryContainer else colorScheme.surfaceVariant.copy(alpha = 0.45f),
            borderColor = borderColor,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${week.seasonLabel}${week.seasonWeekNumber}周",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = week.monthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        week.dates.forEachIndexed { index, date ->
            val isToday = date == today
            val isWeekend = index >= 5
            AcademicCalendarCell(
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .heightIn(min = 54.dp),
                background = if (isToday) colorScheme.primaryContainer else rowBackground,
                borderColor = borderColor,
                contentPadding = 2.dp,
            ) {
                val label = academicCalendarDateLabel(week, date)
                val isCompactLabel = '/' in label
                val dateTextStyle = if (isCompactLabel) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                }
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .background(colorScheme.primary, CircleShape)
                            .padding(
                                horizontal = if (isCompactLabel) 4.dp else 8.dp,
                                vertical = 4.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = colorScheme.onPrimary,
                            style = dateTextStyle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        text = label,
                        color = if (isWeekend) Color(0xFFC62828) else colorScheme.onSurface,
                        style = dateTextStyle,
                        fontWeight = if (isCurrentWeek) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AcademicCalendarCell(
    modifier: Modifier,
    background: Color,
    borderColor: Color,
    contentAlignment: Alignment = Alignment.Center,
    contentPadding: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, borderColor)
            .background(background)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

private fun academicCalendarDateLabel(week: AcademicCalendarWeek, date: LocalDate): String =
    if (week.dates.map { it.monthValue }.distinct().size > 1) {
        "${date.monthValue}/${date.dayOfMonth}"
    } else {
        date.dayOfMonth.toString()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onNavigate: (String) -> Unit,
    onOpenHomeworkDetail: (HomeworkItem) -> Unit,
) {
    var status by remember { mutableStateOf("all") }
    var expiredStatus by remember { mutableStateOf("expired") }
    val realNow = LocalDateTime.now()

    ProgressiveDataScreen(
        title = "作业",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.homeworkProgressive("all", strategy) },
    ) { progressiveState, envelope ->
        val data = envelope.data
        val currentTerm = data.currentTerm
        val activeFilter = if (status == "expired") expiredStatus else status
        val displayItems = if (activeFilter != "all") {
            data.items.filter { homeworkMatchesStatusFilter(it, activeFilter, realNow) }
        } else {
            data.items
        }
        val groups = groupHomeworkItems(displayItems, realNow)
        item {
            SecondaryModuleLinks(
                title = "作业相关",
                links = listOf(ModuleKeys.OpenWebUiAgent to "作业助手"),
                onNavigate = onNavigate,
            )
        }
        if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "open" to "待完成", "done" to "已完成").forEach { (key, label) ->
                    FilterChip(selected = status == key, onClick = { status = key }, label = { Text(label) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = status == "expired",
                    onClick = { status = "expired" },
                    label = { Text("已过期") },
                )
                if (status == "expired") {
                    listOf(
                        "expired" to "全部过期",
                        HomeworkStatusKind.ExpiredCanSubmit.code to "可补交",
                        HomeworkStatusKind.ExpiredClosed.code to "不可补交",
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = expiredStatus == key,
                            onClick = { expiredStatus = key },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        if (groups.isEmpty() && !progressiveState.loading) {
            item {
                InfoCard("暂无作业") {
                    Text("当前没有可展示的作业记录。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            groups.forEach { group ->
                item(key = "homework-group-${group.courseId}-${group.courseName}") {
                    SectionTitle(
                        title = group.courseName,
                        subtitle = buildHomeworkGroupSubtitle(group),
                    )
                }
                items(group.items, key = { it.homeworkId ?: (it.title + it.courseId).hashCode() }) { item ->
                    HomeworkSummaryCard(
                        item = item,
                        now = realNow,
                        onClick = { onOpenHomeworkDetail(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeworkSummaryCard(
    item: HomeworkItem,
    now: LocalDateTime,
    onClick: () -> Unit,
) {
    InfoCard(
        title = item.title,
        modifier = Modifier.clickable(onClick = onClick),
        subtitle = item.course,
        trailing = {
            if (item.attachments.isNotEmpty()) {
                AssistChip(onClick = {}, label = { Text("${item.attachments.size} 个附件") })
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("开始", item.openedAt, Modifier.weight(1f))
            KeyValue("截止", item.dueAt, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("状态", homeworkStatusLabel(item, now), Modifier.weight(1f))
            KeyValue("提交时间", item.submittedAt ?: "未提交", Modifier.weight(1f))
        }
        KeyValue("内容", item.contentExcerpt)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(
    initialHomework: HomeworkItem,
    repository: ModuleRepository,
    attachmentRepository: HomeworkAttachmentRepository,
    onOpenAgent: () -> Unit,
) {
    val identity = remember(initialHomework) { homeworkIdentityKey(initialHomework) }
    var homework by remember(identity) { mutableStateOf(initialHomework) }
    var refreshNonce by remember(identity) { mutableStateOf(0) }
    var refreshError by remember(identity) { mutableStateOf<String?>(null) }
    var submitTarget by remember { mutableStateOf<HomeworkItem?>(null) }
    var resubmitConfirmTarget by remember { mutableStateOf<HomeworkItem?>(null) }
    var submitContent by remember { mutableStateOf("") }
    var pickedFiles by remember { mutableStateOf<List<HomeworkPickedFile>>(emptyList()) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var attachmentBusyKey by remember { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var previewTarget by remember { mutableStateOf<HomeworkAttachmentPreviewTarget?>(null) }
    val realNow = LocalDateTime.now()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickedFiles = uris.map { context.describeHomeworkFile(it) }
    }

    fun openSubmitDialog(item: HomeworkItem) {
        submitTarget = item
        submitContent = ""
        pickedFiles = emptyList()
        submitError = null
    }

    fun previewAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("preview", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                attachmentRepository.preview(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { preview ->
                previewTarget = HomeworkAttachmentPreviewTarget(item, attachment, preview)
            }.onFailure { error ->
                attachmentError = error.message ?: "预览附件失败"
            }
            attachmentBusyKey = null
        }
    }

    fun downloadAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("download", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                attachmentRepository.download(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { file ->
                if (!openFile(context, file)) {
                    attachmentError = "已下载，但未找到可打开该文件的应用"
                }
            }.onFailure { error ->
                attachmentError = error.message ?: "下载附件失败"
            }
            attachmentBusyKey = null
        }
    }

    LaunchedEffect(identity, refreshNonce) {
        refreshError = null
        runCatching { repository.homework("all").data.items }
            .onSuccess { items ->
                homework = findHomeworkByIdentity(items, identity) ?: homework
            }
            .onFailure { error ->
                refreshError = error.message ?: "作业刷新失败"
            }
    }

    previewTarget?.let { target ->
        HomeworkAttachmentPreviewScreen(
            target = target,
            busyKey = attachmentBusyKey,
            error = attachmentError,
            onClose = {
                previewTarget = null
                attachmentError = null
            },
            onDownload = { downloadAttachment(target.homework, target.attachment) },
        )
        return
    }

    resubmitConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { resubmitConfirmTarget = null },
            title = { Text("确认重新提交") },
            text = { Text("该作业已于 ${target.submittedAt.orEmpty().ifBlank { "此前" }} 提交。继续操作会再次提交作业。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        resubmitConfirmTarget = null
                        openSubmitDialog(target)
                    },
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { resubmitConfirmTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    submitTarget?.let { target ->
        HomeworkSubmitDialog(
            homework = target,
            content = submitContent,
            pickedFiles = pickedFiles,
            submitting = submitting,
            error = submitError,
            onContentChange = { submitContent = it },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onRemoveFile = { file -> pickedFiles = pickedFiles.filterNot { it.uri == file.uri } },
            onDismiss = {
                if (!submitting) {
                    submitTarget = null
                    submitError = null
                    pickedFiles = emptyList()
                    submitContent = ""
                }
            },
            onSubmit = {
                val homeworkId = target.homeworkId ?: return@HomeworkSubmitDialog
                submitting = true
                submitError = null
                scope.launch {
                    runCatching {
                        val uploads = pickedFiles.map { context.readHomeworkUploadFile(it) }
                        repository.submitHomework(
                            homeworkId = homeworkId,
                            courseId = target.courseId,
                            content = submitContent,
                            files = uploads,
                        )
                    }.onSuccess { response ->
                        submitting = false
                        submitTarget = null
                        pickedFiles = emptyList()
                        submitContent = ""
                        homework = homework.copy(
                            submittedAt = response.submittedAt ?: homework.submittedAt,
                            status = "done",
                        )
                        refreshNonce += 1
                    }.onFailure { error ->
                        submitting = false
                        submitError = homeworkSubmitErrorMessage(error)
                    }
                }
            },
        )
    }

    val itemStatus = homeworkStatusKind(homework, realNow)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (!refreshError.isNullOrBlank()) {
            item {
                Text(
                    refreshError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!attachmentError.isNullOrBlank()) {
            item {
                Text(
                    attachmentError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            InfoCard("作业信息", subtitle = homework.course) {
                Text(
                    homework.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CalendarStatusPill(
                        text = homeworkStatusLabel(homework, realNow),
                        color = homeworkStatusColor(itemStatus),
                    )
                    homework.submissionStatus?.takeIf { it.isNotBlank() }?.let {
                        CalendarStatusPill(text = it, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("开始", homework.openedAt, Modifier.weight(1f))
                    KeyValue("截止", homework.dueAt, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("提交时间", homework.submittedAt ?: "未提交", Modifier.weight(1f))
                    KeyValue("类型", homeworkTypeLabel(homework), Modifier.weight(1f))
                }
            }
        }
        item {
            InfoCard("内容") {
                Text(
                    homeworkDetailRequirement(homework),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            InfoCard("附件", subtitle = "${homework.attachments.size} 个") {
                HomeworkAttachmentsSection(
                    attachments = homework.attachments,
                    busyKey = attachmentBusyKey,
                    showHeader = false,
                    onPreview = { previewAttachment(homework, it) },
                    onDownload = { downloadAttachment(homework, it) },
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = homework.homeworkId != null,
                    onClick = {
                        NativeAgentHomeworkHandoffStore.set(NativeAgentHomeworkHandoff(homework))
                        onOpenAgent()
                    },
                ) {
                    Text("Agent 协助")
                }
                Button(
                    enabled = homework.homeworkId != null &&
                        homework.canSubmit &&
                        itemStatus != HomeworkStatusKind.ExpiredClosed &&
                        !submitting,
                    onClick = {
                        if (itemStatus == HomeworkStatusKind.Done) {
                            resubmitConfirmTarget = homework
                        } else {
                            openSubmitDialog(homework)
                        }
                    },
                ) {
                    Text(homeworkSubmitButtonLabel(itemStatus))
                }
            }
        }
    }
}

@Composable
private fun homeworkStatusColor(status: HomeworkStatusKind): Color =
    when (status) {
        HomeworkStatusKind.Done -> Color(0xFF2AA876)
        HomeworkStatusKind.Open -> MaterialTheme.colorScheme.primary
        HomeworkStatusKind.ExpiredCanSubmit -> Color(0xFFFF8A00)
        HomeworkStatusKind.ExpiredClosed -> MaterialTheme.colorScheme.error
    }

private fun homeworkDetailRequirement(item: HomeworkItem): String =
    item.requirementText?.takeIf { it.isNotBlank() }
        ?: item.contentExcerpt?.takeIf { it.isNotBlank() }
        ?: "暂无内容"

private fun homeworkTypeLabel(item: HomeworkItem): String =
    buildList {
        if (item.isGroup) add("小组作业")
        if (item.returnNum > 0) add("已退回 ${item.returnNum} 次")
    }.joinToString(" · ").ifBlank { "普通作业" }

@Composable
private fun HomeworkAttachmentsSection(
    attachments: List<HomeworkAttachment>,
    busyKey: String?,
    showHeader: Boolean = true,
    onPreview: (HomeworkAttachment) -> Unit,
    onDownload: (HomeworkAttachment) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (showHeader) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeader) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("附件", style = MaterialTheme.typography.titleSmall)
                AssistChip(onClick = {}, label = { Text("${attachments.size} 个") })
            }
        }
        if (attachments.isEmpty()) {
            Text(
                "暂无附件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else attachments.forEach { attachment ->
            val previewKey = homeworkAttachmentActionKey("preview", attachment)
            val downloadKey = homeworkAttachmentActionKey("download", attachment)
            val previewBusy = busyKey == previewKey
            val downloadBusy = busyKey == downloadKey
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                attachment.size?.takeIf { it.isNotBlank() }?.let { size ->
                    Text(
                        size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = busyKey == null && attachment.attachmentId.isNotBlank(),
                        onClick = { onPreview(attachment) },
                    ) {
                        Text(if (previewBusy) "预览中" else "预览")
                    }
                    Button(
                        enabled = busyKey == null && attachment.attachmentId.isNotBlank(),
                        onClick = { onDownload(attachment) },
                    ) {
                        Text(if (downloadBusy) "下载中" else "下载")
                    }
                }
            }
        }
    }
}

private fun homeworkAttachmentActionKey(action: String, attachment: HomeworkAttachment): String =
    "$action:${attachment.attachmentId}:${attachment.filename}"

private fun homeworkSubmitErrorMessage(error: Throwable): String {
    val message = error.message?.takeIf { it.isNotBlank() } ?: return "提交失败"
    val maxLength = 1200
    return if (message.length <= maxLength) {
        message
    } else {
        message.take(maxLength) + "\n…更多诊断信息请查看 Logcat：adb logcat -s VeProvider"
    }
}

private data class HomeworkAttachmentPreviewTarget(
    val homework: HomeworkItem,
    val attachment: HomeworkAttachment,
    val preview: HomeworkAttachmentPreview,
)

@Composable
private fun HomeworkAttachmentPreviewScreen(
    target: HomeworkAttachmentPreviewTarget,
    busyKey: String?,
    error: String?,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    val downloadBusy = busyKey == homeworkAttachmentActionKey("download", target.attachment)
    DocumentPreviewScreen(
        title = target.attachment.filename,
        subtitle = target.homework.title,
        preview = target.preview,
        downloadBusy = downloadBusy,
        error = error,
        onClose = onClose,
        onDownload = onDownload,
    )
}

private data class HomeworkCourseGroup(
    val courseId: String,
    val courseName: String,
    val items: List<HomeworkItem>,
    val total: Int,
    val openCount: Int,
    val expiredCount: Int,
)

private data class HomeworkPickedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long?,
)

private fun groupHomeworkItems(items: List<HomeworkItem>, now: LocalDateTime): List<HomeworkCourseGroup> =
    items
        .groupBy { item ->
            val courseId = item.courseId.toString()
            val courseName = item.course.trim().ifBlank { "未命名课程" }
            courseId to courseName
        }
        .map { (key, groupItems) ->
            val sortedItems = groupItems.sortedWith(
                compareBy<HomeworkItem> { it.dueAt ?: "9999" }
                    .thenBy { it.title },
            )
            HomeworkCourseGroup(
                courseId = key.first,
                courseName = key.second,
                items = sortedItems,
                total = sortedItems.size,
                openCount = sortedItems.count { homeworkStatusKind(it, now) == HomeworkStatusKind.Open },
                expiredCount = sortedItems.count {
                    val status = homeworkStatusKind(it, now)
                    status == HomeworkStatusKind.ExpiredCanSubmit || status == HomeworkStatusKind.ExpiredClosed
                },
            )
        }
        .sortedBy { it.courseName }

private fun buildHomeworkGroupSubtitle(group: HomeworkCourseGroup): String =
    buildList {
        add("共 ${group.total} 条")
        if (group.openCount > 0) add("待完成 ${group.openCount} 条")
        if (group.expiredCount > 0) add("已过期 ${group.expiredCount} 条")
    }.joinToString(" · ")

private fun homeworkStatusLabel(item: HomeworkItem, now: LocalDateTime): String =
    homeworkStatusKind(item, now).label

private fun homeworkSubmitButtonLabel(status: HomeworkStatusKind): String =
    when (status) {
        HomeworkStatusKind.Done -> "重新提交"
        HomeworkStatusKind.ExpiredCanSubmit -> "补交作业"
        HomeworkStatusKind.ExpiredClosed -> "不可补交"
        HomeworkStatusKind.Open -> "提交作业"
    }

@Composable
private fun HomeworkSubmitDialog(
    homework: HomeworkItem,
    content: String,
    pickedFiles: List<HomeworkPickedFile>,
    submitting: Boolean,
    error: String?,
    onContentChange: (String) -> Unit,
    onPickFiles: () -> Unit,
    onRemoveFile: (HomeworkPickedFile) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (homework.submittedAt.isNullOrBlank()) "提交作业" else "重新提交作业") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(homework.title, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("提交内容") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !submitting,
                    onClick = onPickFiles,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pickedFiles.isEmpty()) "选择附件" else "重新选择附件")
                }
                pickedFiles.forEach { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                file.size?.let { formatHomeworkFileSize(it) } ?: "未知大小",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(enabled = !submitting, onClick = { onRemoveFile(file) }) {
                            Text("移除")
                        }
                    }
                }
                if (!error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(enabled = !submitting && homework.homeworkId != null, onClick = onSubmit) {
                Text(if (submitting) "提交中" else "提交")
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun Context.describeHomeworkFile(uri: Uri): HomeworkPickedFile {
    var name: String? = null
    var size: Long? = null
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return HomeworkPickedFile(
        uri = uri,
        name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment",
        mimeType = contentResolver.getType(uri),
        size = size,
    )
}

private suspend fun Context.readHomeworkUploadFile(file: HomeworkPickedFile): HomeworkUploadFile =
    withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取附件 ${file.name}")
        HomeworkUploadFile(
            filename = file.name,
            content = bytes,
            contentType = file.mimeType ?: contentResolver.getType(file.uri),
        )
    }

private fun formatHomeworkFileSize(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

@Composable
fun EmptyRoomsScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val scope = rememberCoroutineScope()
    var term by remember { mutableStateOf("") }
    var week by remember { mutableStateOf("") }
    var building by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<EmptyRoomData>>>(LoadState.Loading) }

    fun load(
        targetWeek: String = week,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ) {
        scope.launch {
            state = LoadState.Loading
            runCatching {
                repository.emptyRooms(
                    term = term.ifBlank { null },
                    week = targetWeek.ifBlank { null },
                    building = building.ifBlank { null },
                    room = room.ifBlank { null },
                    strategy = strategy,
                )
            }
                .onSuccess {
                    page = 0
                    val query = it.data.query
                    term = query["term"].orEmpty().ifBlank { term }
                    week = query["week"].orEmpty().ifBlank { week }
                    building = query["building"].orEmpty().ifBlank { building }
                    room = query["room"].orEmpty().ifBlank { room }
                    state = LoadState.Data(it)
                }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    LaunchedEffect(Unit) {
        val defaultWeek = runCatching { repository.calendar(strategy = initialLoadStrategy).data.currentWeek.orEmpty() }.getOrDefault("")
        if (week.isBlank() && defaultWeek.isNotBlank()) {
            week = defaultWeek
        }
        load(defaultWeek.ifBlank { week }, initialLoadStrategy)
    }

    val queryData = when (val current = state) {
        is LoadState.Data -> current.value.data
        else -> null
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyRoomQueryField(
                    value = term,
                    onValueChange = { term = it },
                    label = "学期",
                    placeholder = "可留空",
                    options = queryData?.availableTerms.orEmpty(),
                )
                EmptyRoomQueryField(
                    value = week,
                    onValueChange = { week = it },
                    label = "周次",
                    options = queryData?.availableWeeks.orEmpty(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                EmptyRoomQueryField(
                    value = building,
                    onValueChange = { building = it },
                    label = "教学楼",
                    options = queryData?.availableBuildings.orEmpty(),
                )
                OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("教室") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { load() }) { Text("刷新空教室") }
            }
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val envelope = current.value
                item {
                    EmptyRoomsMatrix(
                        data = envelope.data,
                        page = page,
                        onPageChange = { page = it },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyRoomQueryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<TermOption>,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    if (options.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == value }?.let(::emptyRoomOptionLabel)
        ?: value
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(emptyRoomOptionLabel(option)) },
                    onClick = {
                        expanded = false
                        onValueChange(option.value)
                    },
                )
            }
        }
    }
}

private fun emptyRoomOptionLabel(option: TermOption): String =
    option.label.ifBlank { option.value.ifBlank { "不限" } }

private const val EmptyRoomsPageSize = 20
private const val EmptyRoomStateFree = "free"
private const val EmptyRoomStateBusy = "busy"
private const val EmptyRoomStateNotice = "notice"

@Composable
private fun EmptyRoomsMatrix(
    data: EmptyRoomData,
    page: Int,
    onPageChange: (Int) -> Unit,
) {
    val rooms = data.rooms
    val slotColumns = remember(data.slots, rooms) { emptyRoomSlotColumns(data) }
    if (rooms.isEmpty() || slotColumns.isEmpty()) {
        InfoCard("暂无空教室矩阵") {
            Text("当前查询没有返回可显示的空教室数据。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val pageCount = ((rooms.size + EmptyRoomsPageSize - 1) / EmptyRoomsPageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    LaunchedEffect(page, pageCount) {
        if (page != currentPage) onPageChange(currentPage)
    }

    val pageRooms = remember(rooms, currentPage) {
        rooms.drop(currentPage * EmptyRoomsPageSize).take(EmptyRoomsPageSize)
    }
    val dayGroups = remember(slotColumns) { emptyRoomDayGroups(slotColumns) }
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyRoomLegend()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val leftColumnWidth = 92.dp
                val minSlotWidth = 38.dp
                val availableSlotWidth = (maxWidth - leftColumnWidth) / slotColumns.size
                val slotWidth = if (availableSlotWidth > minSlotWidth) availableSlotWidth else minSlotWidth
                val dayHeaderHeight = 48.dp
                val periodHeaderHeight = 40.dp
                val roomRowHeight = 56.dp

                Column(Modifier.fillMaxWidth()) {
                    Row {
                        EmptyRoomCornerHeader(
                            width = leftColumnWidth,
                            dayHeaderHeight = dayHeaderHeight,
                            periodHeaderHeight = periodHeaderHeight,
                        )
                        Column(Modifier.horizontalScroll(horizontalScrollState)) {
                            EmptyRoomDayHeaderRow(
                                groups = dayGroups,
                                slotWidth = slotWidth,
                                height = dayHeaderHeight,
                            )
                            EmptyRoomPeriodHeaderRow(
                                slots = slotColumns,
                                slotWidth = slotWidth,
                                height = periodHeaderHeight,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .heightIn(max = 560.dp)
                            .verticalScroll(verticalScrollState),
                    ) {
                        Column(Modifier.width(leftColumnWidth)) {
                            pageRooms.forEach { row ->
                                EmptyRoomRoomCell(row = row, height = roomRowHeight)
                            }
                        }
                        Column(Modifier.horizontalScroll(horizontalScrollState)) {
                            pageRooms.forEach { row ->
                                EmptyRoomAvailabilityRow(
                                    row = row,
                                    slotCount = slotColumns.size,
                                    slotWidth = slotWidth,
                                    height = roomRowHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
        EmptyRoomPager(
            currentPage = currentPage,
            pageCount = pageCount,
            totalRooms = rooms.size,
            onPageChange = onPageChange,
        )
    }
}

@Composable
private fun EmptyRoomLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptyRoomLegendItem(state = EmptyRoomStateFree, label = "空闲")
        EmptyRoomLegendItem(state = EmptyRoomStateBusy, label = "占用")
        EmptyRoomLegendItem(state = EmptyRoomStateNotice, label = "特殊")
    }
}

@Composable
private fun EmptyRoomLegendItem(state: String, label: String) {
    val colors = emptyRoomCellColors(state)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .background(colors.first),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyRoomCornerHeader(
    width: Dp,
    dayHeaderHeight: Dp,
    periodHeaderHeight: Dp,
) {
    Column(Modifier.width(width)) {
        EmptyRoomHeaderCell(
            text = "星期",
            modifier = Modifier
                .width(width)
                .height(dayHeaderHeight),
            fontWeight = FontWeight.Bold,
        )
        EmptyRoomHeaderCell(
            text = "教室/\n节次",
            modifier = Modifier
                .width(width)
                .height(periodHeaderHeight),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyRoomDayHeaderRow(
    groups: List<EmptyRoomDayGroup>,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        groups.forEach { group ->
            EmptyRoomHeaderCell(
                text = group.label,
                modifier = Modifier.width(slotWidth * group.span.toFloat()),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyRoomPeriodHeaderRow(
    slots: List<EmptyRoomSlotHeader>,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        slots.forEach { slot ->
            EmptyRoomHeaderCell(
                text = slot.period.toString(),
                modifier = Modifier.width(slotWidth),
            )
        }
    }
}

@Composable
private fun EmptyRoomHeaderCell(
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyRoomRoomCell(row: EmptyRoomRow, height: Dp) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .height(height)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = row.room,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!row.seatLabel.isNullOrBlank()) {
            Text(
                text = "(${row.seatLabel})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyRoomAvailabilityRow(
    row: EmptyRoomRow,
    slotCount: Int,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        repeat(slotCount) { index ->
            val state = emptyRoomCellState(row, index)
            val colors = emptyRoomCellColors(state)
            Box(
                modifier = Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(colors.first),
                contentAlignment = Alignment.Center,
            ) {
                if (state == EmptyRoomStateNotice) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.second,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRoomPager(
    currentPage: Int,
    pageCount: Int,
    totalRooms: Int,
    onPageChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onPageChange(currentPage - 1) },
            enabled = currentPage > 0,
        ) {
            Text("上一页")
        }
        Text(
            text = "第 ${currentPage + 1} / $pageCount 页 · 共 $totalRooms 间",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onPageChange(currentPage + 1) },
            enabled = currentPage < pageCount - 1,
        ) {
            Text("下一页")
        }
    }
}

@Composable
private fun emptyRoomCellColors(state: String): Pair<Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    return when (state) {
        EmptyRoomStateBusy -> Color(0xFFE56666) to Color.White
        EmptyRoomStateNotice -> Color(0xFFD8CF4D) to Color(0xFF2F2B12)
        else -> Color.White to colorScheme.onSurface
    }
}

private data class EmptyRoomDayGroup(
    val label: String,
    val span: Int,
)

private fun emptyRoomSlotColumns(data: EmptyRoomData): List<EmptyRoomSlotHeader> {
    val maxRoomCells = data.rooms.maxOfOrNull { maxOf(it.cellStates.size, it.availability.size) } ?: 0
    if (data.slots.size >= maxRoomCells) return data.slots
    return data.slots + (data.slots.size until maxRoomCells).map { index ->
        EmptyRoomSlotHeader(day = "", date = null, period = index + 1)
    }
}

private fun emptyRoomDayGroups(slots: List<EmptyRoomSlotHeader>): List<EmptyRoomDayGroup> {
    if (slots.isEmpty()) return emptyList()
    val groups = mutableListOf<EmptyRoomDayGroup>()
    var currentLabel = emptyRoomSlotDayLabel(slots.first())
    var currentSpan = 0
    slots.forEach { slot ->
        val label = emptyRoomSlotDayLabel(slot)
        if (label != currentLabel && currentSpan > 0) {
            groups += EmptyRoomDayGroup(label = currentLabel, span = currentSpan)
            currentLabel = label
            currentSpan = 0
        }
        currentSpan += 1
    }
    groups += EmptyRoomDayGroup(label = currentLabel, span = currentSpan)
    return groups
}

private fun emptyRoomSlotDayLabel(slot: EmptyRoomSlotHeader): String =
    listOf(slot.day, slot.date)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { "日期" }

private fun emptyRoomCellState(row: EmptyRoomRow, index: Int): String =
    row.cellStates.getOrNull(index)
        ?: row.availability.getOrNull(index)?.let { if (it) EmptyRoomStateFree else EmptyRoomStateBusy }
        ?: EmptyRoomStateFree

@Composable
private fun TimetableList(
    envelope: ModuleEnvelope<TimetableData>,
    currentWeek: Int?,
    selectedCourses: List<CourseEntry>,
    onSelect: (List<CourseEntry>) -> Unit,
    onAddToHomeWidget: () -> Unit,
    onExport: () -> Unit,
    onAddUserCourse: (TimetableDayColumn, TimetablePeriodSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = envelope.data
    val selectedKeys = selectedCourses.map(::courseEntryKey).toSet()
    val entries = data.entries.sortedWith(
        compareBy<CourseEntry> { timetableWeekdayIndex(data.days, it.weekday) }
            .thenBy { timetablePeriodIndex(data.periods, it.period) }
            .thenBy { it.courseName },
    )
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val currentTerm = data.currentTerm
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!currentTerm.isNullOrBlank() || currentWeek != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!currentTerm.isNullOrBlank()) {
                            AssistChip(onClick = {}, label = { Text(currentTerm) })
                        }
                        if (currentWeek != null) {
                            AssistChip(onClick = {}, label = { Text("第 $currentWeek 周") })
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(onClick = {}, label = { Text("${entries.size} 门课程") })
                    if (data.days.isNotEmpty()) {
                        AssistChip(onClick = {}, label = { Text("${data.days.size} 天") })
                    }
                    AssistChip(
                        onClick = onAddToHomeWidget,
                        label = { Text("添加到桌面") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.AddToHomeScreen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    AssistChip(
                        onClick = onExport,
                        label = { Text("导出") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                InfoCard("暂无课表") {
                    Text("当前没有可显示的课程。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            TimetableWeekCalendar(
                data = data,
                entries = entries,
                currentWeek = currentWeek,
                selectedKeys = selectedKeys,
                onSelect = onSelect,
                onAddUserCourse = onAddUserCourse,
            )
        }
    }
}

@Composable
private fun TimetableExportDialog(
    onDismiss: () -> Unit,
    onExport: (ScheduleExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出课表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择导出格式。")
                OutlinedButton(
                    onClick = { onExport(ScheduleExportFormat.Pdf) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出 PDF")
                }
                OutlinedButton(
                    onClick = { onExport(ScheduleExportFormat.Png) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出 PNG")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun TimetableWeekCalendar(
    data: TimetableData,
    entries: List<CourseEntry>,
    currentWeek: Int?,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
    onAddUserCourse: (TimetableDayColumn, TimetablePeriodSlot) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val dayColumns = remember(today) { timetableWeekColumns(today) }
    val slots = remember(data.periods, entries) { timetablePeriodSlots(data, entries) }
    val entriesByCell = remember(data.days, entries, currentWeek) {
        entries.groupBy { entry ->
            timetableCellKey(timetableWeekdayIndex(data.days, entry.weekday), entry.period)
        }.mapValues { (_, cellEntries) ->
            timetableVisibleCellEntries(cellEntries, currentWeek)
        }
    }
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val leftColumnWidth = 64.dp
            val minDayWidth = 108.dp
            val availableDayWidth = (maxWidth - leftColumnWidth) / dayColumns.size
            val dayWidth = if (availableDayWidth > minDayWidth) availableDayWidth else minDayWidth
            val contentWidth = leftColumnWidth + dayWidth * dayColumns.size.toFloat()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Column(Modifier.width(contentWidth)) {
                    TimetableHeaderRow(
                        dayColumns = dayColumns,
                        today = today,
                        leftColumnWidth = leftColumnWidth,
                        dayWidth = dayWidth,
                    )
                    slots.forEach { slot ->
                        TimetablePeriodRow(
                            slot = slot,
                            dayColumns = dayColumns,
                            dayWidth = dayWidth,
                            leftColumnWidth = leftColumnWidth,
                            entriesByCell = entriesByCell,
                            selectedKeys = selectedKeys,
                            onSelect = onSelect,
                            onAddUserCourse = onAddUserCourse,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableHeaderRow(
    dayColumns: List<TimetableDayColumn>,
    today: LocalDate,
    leftColumnWidth: androidx.compose.ui.unit.Dp,
    dayWidth: androidx.compose.ui.unit.Dp,
) {
    val lineColor = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.height(64.dp)) {
        Box(
            modifier = Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .border(0.5.dp, lineColor)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("节次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        dayColumns.forEach { day ->
            val isToday = day.date == today
            Column(
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .border(0.5.dp, lineColor)
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = day.date.format(DateTimeFormatter.ofPattern("M/d")),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TimetablePeriodRow(
    slot: TimetablePeriodSlot,
    dayColumns: List<TimetableDayColumn>,
    dayWidth: androidx.compose.ui.unit.Dp,
    leftColumnWidth: androidx.compose.ui.unit.Dp,
    entriesByCell: Map<String, List<TimetableDisplayEntry>>,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
    onAddUserCourse: (TimetableDayColumn, TimetablePeriodSlot) -> Unit,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        TimetablePeriodCell(
            slot = slot,
            modifier = Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .heightIn(min = 112.dp),
        )
        dayColumns.forEach { day ->
            TimetableCourseCell(
                entries = entriesByCell[timetableCellKey(day.index, slot.period)].orEmpty(),
                selectedKeys = selectedKeys,
                onSelect = onSelect,
                onAdd = { onAddUserCourse(day, slot) },
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .heightIn(min = 112.dp),
            )
        }
    }
}

@Composable
private fun TimetablePeriodCell(
    slot: TimetablePeriodSlot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.surfaceVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = timetablePeriodNumber(slot.period),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (!slot.timeRange.isNullOrBlank()) {
            Text(
                text = slot.timeRange.replace("-", "\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TimetableCourseCell(
    entries: List<TimetableDisplayEntry>,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val selectableEntries = entries.map { it.entry }
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onAdd() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            entries.forEach { displayEntry ->
                TimetableCourseBlock(
                    entry = displayEntry.entry,
                    activeInCurrentWeek = displayEntry.activeInCurrentWeek,
                    selected = courseEntryKey(displayEntry.entry) in selectedKeys,
                    onSelect = { onSelect(selectableEntries) },
                )
            }
        }
    }
}

@Composable
private fun TimetableCourseBlock(
    entry: CourseEntry,
    activeInCurrentWeek: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = timetableCourseColors(entry)
    val contentAlpha = if (activeInCurrentWeek) 1f else 0.42f
    val container = if (activeInCurrentWeek) colors.container else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val content = if (activeInCurrentWeek) colors.content else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
            .clickable { onSelect() },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.courseName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.teacher.isNullOrBlank()) {
                Text(
                    text = entry.teacher,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.locationLabel()?.let { location ->
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class UserCourseEditorSeed(
    val id: Long? = null,
    val courseName: String = "",
    val weekdayIndex: Int,
    val period: String,
    val periodNumber: Int,
    val timeRange: String? = null,
    val startWeek: Int,
    val endWeek: Int,
    val weeksText: String? = null,
    val durationType: UserCourseDurationType,
    val teacher: String = "",
    val locationText: String = "",
    val remark: String = "",
    val colorIndex: Int = 0,
)

private data class TimetablePeriodChoice(
    val period: String,
    val periodNumber: Int,
    val timeRange: String?,
)

@Composable
private fun UserCourseEditorSheet(
    seed: UserCourseEditorSeed,
    data: TimetableData,
    currentWeek: Int?,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (UserCourseDraft) -> Unit,
) {
    var courseName by remember(seed) { mutableStateOf(seed.courseName) }
    var weekdayIndex by remember(seed) { mutableStateOf(seed.weekdayIndex.coerceIn(0, 6)) }
    var period by remember(seed) { mutableStateOf(seed.period) }
    var periodNumber by remember(seed) { mutableStateOf(seed.periodNumber.coerceAtLeast(1)) }
    var timeRange by remember(seed) { mutableStateOf(seed.timeRange) }
    var durationType by remember(seed) { mutableStateOf(seed.durationType) }
    var weekText by remember(seed) { mutableStateOf(seed.startWeek.toString()) }
    val weekPickerMaxWeek = remember(data.entries, seed.endWeek) {
        maxOf(DEFAULT_USER_COURSE_MAX_WEEK, seed.endWeek, timetableMaxWeek(data.entries))
    }
    var longTermWeeks by remember(seed, weekPickerMaxWeek) {
        mutableStateOf(parseUserCourseWeeks(seed.weeksText, seed.startWeek, seed.endWeek, weekPickerMaxWeek))
    }
    var showWeekPicker by remember(seed) { mutableStateOf(false) }
    var teacher by remember(seed) { mutableStateOf(seed.teacher) }
    var locationText by remember(seed) { mutableStateOf(seed.locationText) }
    var remark by remember(seed) { mutableStateOf(seed.remark) }
    var colorIndex by remember(seed) { mutableStateOf(seed.colorIndex.coerceIn(0, UserCourseColors.lastIndex)) }

    val periodChoices = remember(data.periods, data.entries, seed) {
        val choices = timetablePeriodSlots(data, data.entries).map {
            TimetablePeriodChoice(
                period = it.period,
                periodNumber = normalizedTimetablePeriodNumber(it.period) ?: 1,
                timeRange = it.timeRange,
            )
        }
        (choices + TimetablePeriodChoice(seed.period, seed.periodNumber, seed.timeRange))
            .distinctBy { it.periodNumber }
            .sortedBy { it.periodNumber }
    }
    val longTermWeekText = remember(longTermWeeks, weekPickerMaxWeek) {
        formatUserCourseWeeks(longTermWeeks, weekPickerMaxWeek)
    }
    val temporaryWeek = weekText.toIntOrNull()
    val chosenStartWeek = if (durationType == UserCourseDurationType.Temporary) {
        temporaryWeek
    } else {
        longTermWeeks.minOrNull()
    }
    val chosenEndWeek = if (durationType == UserCourseDurationType.Temporary) {
        temporaryWeek
    } else {
        longTermWeeks.maxOrNull()
    }
    val weekError = if (durationType == UserCourseDurationType.Temporary) {
        temporaryWeek == null || temporaryWeek < 1
    } else {
        longTermWeeks.isEmpty()
    }
    val candidateWeeksText = if (durationType == UserCourseDurationType.Temporary) {
        chosenStartWeek?.let { formatUserCourseWeeks(it, it) }
    } else {
        longTermWeekText
    }
    val candidateEntry = CourseEntry(
        weekday = userCourseWeekdayLabel(weekdayIndex),
        period = period,
        timeRange = timeRange,
        courseCode = "LOCAL-${seed.id ?: "new"}",
        section = durationType.name,
        courseName = courseName.ifBlank { "未命名课程" },
        teacher = teacher.takeIf { it.isNotBlank() },
        weeks = if (!weekError) candidateWeeksText else null,
        locationText = locationText.takeIf { it.isNotBlank() },
        localId = seed.id,
        remark = remark.takeIf { it.isNotBlank() },
        colorIndex = colorIndex,
        isUserCreated = true,
    )
    val conflicts = if (courseName.isBlank() || weekError) {
        emptyList()
    } else {
        data.entries.filter { timetableEntriesConflict(candidateEntry, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (seed.id == null) "添加课程" else "编辑课程", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        OutlinedTextField(
            value = courseName,
            onValueChange = { courseName = it },
            label = { Text("课程名称") },
            singleLine = true,
            isError = courseName.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("星期", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0..6).forEach { index ->
                FilterChip(
                    selected = weekdayIndex == index,
                    onClick = { weekdayIndex = index },
                    label = { Text(userCourseWeekdayLabel(index)) },
                )
            }
        }
        Text("节次", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            periodChoices.forEach { choice ->
                FilterChip(
                    selected = periodNumber == choice.periodNumber,
                    onClick = {
                        period = choice.period
                        periodNumber = choice.periodNumber
                        timeRange = choice.timeRange
                    },
                    label = { Text(timetablePeriodNumber(choice.period)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = durationType == UserCourseDurationType.Temporary,
                onClick = {
                    durationType = UserCourseDurationType.Temporary
                    val fallbackWeek = currentWeek ?: seed.startWeek.coerceAtLeast(1)
                    weekText = fallbackWeek.toString()
                },
                label = { Text("临时") },
            )
            FilterChip(
                selected = durationType == UserCourseDurationType.LongTerm,
                onClick = {
                    val wasLongTerm = durationType == UserCourseDurationType.LongTerm
                    durationType = UserCourseDurationType.LongTerm
                    if (!wasLongTerm) {
                        longTermWeeks = (1..DEFAULT_USER_COURSE_MAX_WEEK).toSet()
                    }
                },
                label = { Text("长期") },
            )
        }
        if (durationType == UserCourseDurationType.Temporary) {
            OutlinedTextField(
                value = weekText,
                onValueChange = { weekText = it.filter(Char::isDigit) },
                label = { Text("周次") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = weekError,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            WeekSelectionField(
                weekText = longTermWeekText,
                isError = weekError,
                onClick = { showWeekPicker = true },
            )
            if (showWeekPicker) {
                WeekSelectionDialog(
                    maxWeek = weekPickerMaxWeek,
                    selectedWeeks = longTermWeeks,
                    onDismiss = { showWeekPicker = false },
                    onConfirm = { weeks ->
                        longTermWeeks = weeks
                        showWeekPicker = false
                    },
                )
            }
        }
        OutlinedTextField(
            value = teacher,
            onValueChange = { teacher = it },
            label = { Text("授课老师（可不填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = locationText,
            onValueChange = { locationText = it },
            label = { Text("上课地点（可不填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = remark,
            onValueChange = { remark = it },
            label = { Text("备注（可不填）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("颜色", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserCourseColors.forEachIndexed { index, color ->
                FilterChip(
                    selected = colorIndex == index,
                    onClick = { colorIndex = index },
                    label = { Text("颜色 ${index + 1}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.28f),
                    ),
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "该时段与 ${conflicts.take(3).joinToString("、") { it.courseName }} 冲突，仍可保存。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!saveError.isNullOrBlank()) {
            Text(saveError, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(
                enabled = courseName.isNotBlank() && !weekError,
                onClick = {
                    val start = chosenStartWeek ?: return@Button
                    val end = chosenEndWeek ?: return@Button
                    val first = minOf(start, end).coerceAtLeast(1)
                    val last = maxOf(start, end).coerceAtLeast(1)
                    onSave(
                        UserCourseDraft(
                            id = seed.id,
                            courseName = courseName,
                            weekday = userCourseWeekdayLabel(weekdayIndex),
                            weekdayIndex = weekdayIndex,
                            period = period,
                            periodNumber = periodNumber,
                            timeRange = timeRange,
                            startWeek = first,
                            endWeek = if (durationType == UserCourseDurationType.Temporary) first else last,
                            weeksText = candidateWeeksText,
                            durationType = durationType,
                            teacher = teacher,
                            locationText = locationText,
                            remark = remark,
                            colorIndex = colorIndex,
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("保存")
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun WeekSelectionField(
    weekText: String,
    isError: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "周次",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = weekText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "选择",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun WeekSelectionDialog(
    maxWeek: Int,
    selectedWeeks: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    val safeMaxWeek = maxWeek.coerceIn(DEFAULT_USER_COURSE_MAX_WEEK, 60)
    var draftWeeks by remember(selectedWeeks, safeMaxWeek) {
        mutableStateOf(selectedWeeks.filter { it in 1..safeMaxWeek }.toSet())
    }

    fun applyWeeks(weeks: Iterable<Int>) {
        draftWeeks = weeks.filter { it in 1..safeMaxWeek }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请选择周次") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val weekRows = (1..safeMaxWeek).chunked(6)
                weekRows.forEach { rowWeeks ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowWeeks.forEach { week ->
                            WeekNumberButton(
                                week = week,
                                selected = week in draftWeeks,
                                onClick = {
                                    draftWeeks = if (week in draftWeeks) {
                                        draftWeeks - week
                                    } else {
                                        draftWeeks + week
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(6 - rowWeeks.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = draftWeeks == (1..safeMaxWeek).toSet(),
                        onClick = { applyWeeks(1..safeMaxWeek) },
                        label = { Text("全周") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = draftWeeks == (1..safeMaxWeek).filter { it % 2 == 1 }.toSet(),
                        onClick = { applyWeeks((1..safeMaxWeek).filter { it % 2 == 1 }) },
                        label = { Text("单周") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = draftWeeks == (1..safeMaxWeek).filter { it % 2 == 0 }.toSet(),
                        onClick = { applyWeeks((1..safeMaxWeek).filter { it % 2 == 0 }) },
                        label = { Text("双周") },
                        modifier = Modifier.weight(1f),
                    )
                }

                val draftSummary = draftWeeks
                    .takeIf { it.isNotEmpty() }
                    ?.let { formatUserCourseWeeks(it, safeMaxWeek) }
                    ?: "未选择"
                Text(
                    text = "已选：$draftSummary",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = draftWeeks.isNotEmpty(),
                onClick = { onConfirm(draftWeeks) },
            ) {
                Text("确定")
            }
        },
    )
}

@Composable
private fun WeekNumberButton(
    week: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(48.dp)
                .height(48.dp)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = if (selected) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = week.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CourseDetailPanel(
    entries: List<CourseEntry>,
    currentWeek: Int?,
    homeworkStates: Map<String, LoadState<List<HomeworkItem>>>,
    resourceStates: Map<String, LoadState<ModuleEnvelope<CourseResourcesData>>>,
    courseResourceRepository: CourseResourceRepository,
    homeworkAttachmentRepository: HomeworkAttachmentRepository,
    onOpenHomeworkDetail: (HomeworkItem) -> Unit,
    onEditUserCourse: (CourseEntry) -> Unit,
    onDeleteUserCourse: (CourseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val panelKey = courseSelectionKey(entries)
    var downloading by remember(panelKey) { mutableStateOf<String?>(null) }
    var previewing by remember(panelKey) { mutableStateOf<String?>(null) }
    var previewTarget by remember(panelKey) { mutableStateOf<TimetableResourcePreviewTarget?>(null) }
    var downloadError by remember(panelKey) { mutableStateOf<String?>(null) }
    var attachmentBusyKey by remember(panelKey) { mutableStateOf<String?>(null) }
    var attachmentError by remember(panelKey) { mutableStateOf<String?>(null) }
    var attachmentPreviewTarget by remember(panelKey) { mutableStateOf<HomeworkAttachmentPreviewTarget?>(null) }

    fun downloadResource(resource: CourseResourceItem, actionKey: String) {
        scope.launch {
            downloading = actionKey
            downloadError = null
            runCatching { courseResourceRepository.download(resource.rpId, resource.name, resource.extension) }
                .onSuccess {
                    if (!openFile(context, it)) {
                        downloadError = "已下载，但未找到可打开该文件的应用"
                    }
                }
                .onFailure { downloadError = it.message ?: "下载失败" }
            downloading = null
        }
    }

    fun previewResource(entry: CourseEntry, resource: CourseResourceItem, actionKey: String) {
        scope.launch {
            previewing = actionKey
            downloadError = null
            runCatching { courseResourceRepository.preview(resource) }
                .onSuccess { preview ->
                    previewTarget = TimetableResourcePreviewTarget(entry.courseName, resource, actionKey, preview)
                }
                .onFailure { downloadError = it.message ?: "预览失败" }
            previewing = null
        }
    }

    fun previewAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("preview", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                homeworkAttachmentRepository.preview(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { preview ->
                attachmentPreviewTarget = HomeworkAttachmentPreviewTarget(item, attachment, preview)
            }.onFailure { error ->
                attachmentError = error.message ?: "预览附件失败"
            }
            attachmentBusyKey = null
        }
    }

    fun downloadAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("download", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                homeworkAttachmentRepository.download(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { file ->
                if (!openFile(context, file)) {
                    attachmentError = "已下载，但未找到可打开该文件的应用"
                }
            }.onFailure { error ->
                attachmentError = error.message ?: "下载附件失败"
            }
            attachmentBusyKey = null
        }
    }

    attachmentPreviewTarget?.let { target ->
        HomeworkAttachmentPreviewScreen(
            target = target,
            busyKey = attachmentBusyKey,
            error = attachmentError,
            onClose = {
                attachmentPreviewTarget = null
                attachmentError = null
            },
            onDownload = { downloadAttachment(target.homework, target.attachment) },
        )
        return
    }

    previewTarget?.let { target ->
        DocumentPreviewScreen(
            title = target.resource.name,
            subtitle = listOfNotNull(target.courseName, target.resource.teacherName).joinToString(" · "),
            preview = target.preview,
            downloadBusy = downloading == target.actionKey,
            error = downloadError,
            onClose = {
                previewTarget = null
                downloadError = null
            },
            onDownload = { downloadResource(target.resource, target.actionKey) },
        )
        return
    }

    LazyColumn(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEachIndexed { index, entry ->
            val entryKey = courseEntryKey(entry)
            val homeworkState = homeworkStates[entryKey] ?: LoadState.Loading
            val resourceState = resourceStates[entryKey] ?: LoadState.Loading

            if (index > 0) {
                item(key = "$entryKey-divider") { HorizontalDivider() }
            }
            item(key = "$entryKey-title") {
                SectionTitle(
                    title = entry.courseName,
                    subtitle = listOfNotNull(
                        "${index + 1}/${entries.size}".takeIf { entries.size > 1 },
                        entry.courseCode,
                        entry.teacher,
                    ).joinToString(" · "),
                )
            }
            item(key = "$entryKey-info") {
                InfoCard("课程信息") {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("星期", entry.weekday, Modifier.weight(1f))
                        KeyValue("节次", entry.period, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("当前周", currentWeek?.toString(), Modifier.weight(1f))
                        KeyValue("上课时间", entry.timeRange, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue(
                            "本周状态",
                            currentWeek?.let { if (timetableEntryMatchesWeek(entry, it)) "本周上课" else "本周不上课" },
                            Modifier.weight(1f),
                        )
                    }
                    KeyValue("周次", entry.weeks)
                    KeyValue("地点", entry.locationLabel())
                }
            }
            if (entry.isUserCreated) {
                item(key = "$entryKey-user-actions") {
                    InfoCard("本地课程") {
                        KeyValue("备注", entry.remark)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onEditUserCourse(entry) }, modifier = Modifier.weight(1f)) {
                                Text("编辑")
                            }
                            OutlinedButton(onClick = { onDeleteUserCourse(entry) }, modifier = Modifier.weight(1f)) {
                                Text("删除")
                            }
                        }
                    }
                }
            } else {
            item(key = "$entryKey-homework-divider") { HorizontalDivider() }
            item(key = "$entryKey-homework-title") { Text("作业", style = MaterialTheme.typography.titleMedium) }
            if (!attachmentError.isNullOrBlank()) {
                item(key = "$entryKey-attachment-error") {
                    Text(attachmentError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            when (val current = homeworkState) {
                LoadState.Loading, is LoadState.Error -> item(key = "$entryKey-homework-state") { LoadingOrError(current) }
                is LoadState.Data -> {
                    if (current.value.isEmpty()) {
                        item(key = "$entryKey-homework-empty") { Text("暂无匹配作业。", style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(
                            current.value,
                            key = { "${entryKey}-homework-${it.homeworkId ?: "${it.courseId}-${it.title}".hashCode()}" },
                        ) { homework ->
                            InfoCard(
                                title = homework.title,
                                modifier = Modifier.clickable { onOpenHomeworkDetail(homework) },
                                subtitle = homework.course,
                            ) {
                                KeyValue("开始", homework.openedAt)
                                KeyValue("截止", homework.dueAt)
                                KeyValue("状态", homework.status)
                                KeyValue("内容", homework.contentExcerpt)
                                HomeworkAttachmentsSection(
                                    attachments = homework.attachments,
                                    busyKey = attachmentBusyKey,
                                    onPreview = { previewAttachment(homework, it) },
                                    onDownload = { downloadAttachment(homework, it) },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "$entryKey-resource-divider") { HorizontalDivider() }
            item(key = "$entryKey-resource-title") { Text("课程资源", style = MaterialTheme.typography.titleMedium) }
            if (!downloadError.isNullOrBlank()) {
                item(key = "$entryKey-download-error") { Text(downloadError.orEmpty(), color = MaterialTheme.colorScheme.error) }
            }
            when (val current = resourceState) {
                LoadState.Loading, is LoadState.Error -> item(key = "$entryKey-resource-state") { LoadingOrError(current) }
                is LoadState.Data -> {
                    val data = current.value.data
                    if (data.folders.isEmpty() && data.resources.isEmpty()) {
                        item(key = "$entryKey-resource-empty") { Text("暂无匹配资源。", style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(data.folders, key = { "$entryKey-folder-${it.categoryKey}-${it.folderId}" }) { folder ->
                            InfoCard(folder.name, subtitle = "${folder.categoryLabel} · 目录 ${folder.folderId}") {
                                Text("请到课程资源页继续浏览该目录。", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(data.resources, key = { "$entryKey-resource-${it.categoryKey}-${it.rpId}" }) { resource ->
                            val downloadKey = "$entryKey|${resource.categoryKey}|${resource.rpId}"
                            val subtitle = listOf(resource.categoryLabel, resource.uploadedAt)
                                .filter { !it.isNullOrBlank() }
                                .joinToString(" · ")
                            InfoCard(
                                title = resource.name,
                                subtitle = subtitle.ifBlank { null },
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            enabled = downloading == null && previewing == null,
                                            onClick = { previewResource(entry, resource, downloadKey) },
                                        ) {
                                            Text(if (previewing == downloadKey) "预览中" else "预览")
                                        }
                                        Button(
                                            enabled = resource.canDownload && downloading == null && previewing == null,
                                            onClick = { downloadResource(resource, downloadKey) },
                                        ) {
                                            Text(if (downloading == downloadKey) "下载中" else "下载")
                                        }
                                    }
                                },
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                                    KeyValue("类型", resource.extension, Modifier.weight(1f))
                                    KeyValue("大小", resource.size?.let { "$it MB" }, Modifier.weight(1f))
                                    KeyValue("下载", resource.downloadCount?.toString(), Modifier.weight(1f))
                                }
                                KeyValue("教师", resource.teacherName)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private data class TimetableResourcePreviewTarget(
    val courseName: String,
    val resource: CourseResourceItem,
    val actionKey: String,
    val preview: DocumentPreview,
)

private data class TimetableDayColumn(
    val index: Int,
    val label: String,
    val date: LocalDate,
)

private data class TimetablePeriodSlot(
    val period: String,
    val timeRange: String?,
)

private data class TimetableCourseColors(
    val container: Color,
    val content: Color,
)

private data class TimetableDisplayEntry(
    val entry: CourseEntry,
    val activeInCurrentWeek: Boolean,
)

private fun timetableWeekColumns(today: LocalDate): List<TimetableDayColumn> {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    return labels.mapIndexed { index, label ->
        TimetableDayColumn(
            index = index,
            label = label,
            date = monday.plusDays(index.toLong()),
        )
    }
}

private fun userCourseEditorSeedForSlot(
    day: TimetableDayColumn,
    slot: TimetablePeriodSlot,
    currentWeek: Int?,
    maxWeek: Int,
): UserCourseEditorSeed {
    val week = currentWeek ?: 1
    return UserCourseEditorSeed(
        weekdayIndex = day.index,
        period = slot.period,
        periodNumber = normalizedTimetablePeriodNumber(slot.period) ?: 1,
        timeRange = slot.timeRange,
        startWeek = week,
        endWeek = week.coerceAtMost(maxWeek.coerceAtLeast(week)),
        durationType = UserCourseDurationType.Temporary,
    )
}

private fun userCourseEditorSeedForEntry(
    entry: CourseEntry,
    currentWeek: Int?,
    maxWeek: Int,
): UserCourseEditorSeed {
    val (startWeek, endWeek) = timetableEntryWeekBounds(entry.weeks, currentWeek, maxWeek)
    return UserCourseEditorSeed(
        id = entry.localId,
        courseName = entry.courseName,
        weekdayIndex = parseWeekdayIndex(entry.weekday) ?: 0,
        period = entry.period,
        periodNumber = normalizedTimetablePeriodNumber(entry.period) ?: 1,
        timeRange = entry.timeRange,
        startWeek = startWeek,
        endWeek = endWeek,
        weeksText = entry.weeks,
        durationType = if (startWeek == endWeek && entry.section != UserCourseDurationType.LongTerm.name) {
            UserCourseDurationType.Temporary
        } else {
            UserCourseDurationType.LongTerm
        },
        teacher = entry.teacher.orEmpty(),
        locationText = entry.locationLabel().orEmpty(),
        remark = entry.remark.orEmpty(),
        colorIndex = entry.colorIndex ?: 0,
    )
}

private fun timetableMaxWeek(entries: List<CourseEntry>): Int =
    entries
        .flatMap { entry ->
            Regex("""\d{1,2}""").findAll(entry.weeks.orEmpty()).mapNotNull { it.value.toIntOrNull() }.toList()
        }
        .filter { it > 0 }
        .maxOrNull()
        ?: 21

private fun timetableEntryWeekBounds(weeks: String?, currentWeek: Int?, maxWeek: Int): Pair<Int, Int> {
    val parsed = Regex("""\d{1,2}""").findAll(weeks.orEmpty()).mapNotNull { it.value.toIntOrNull() }.toList()
    if (parsed.isEmpty()) {
        val fallback = currentWeek ?: 1
        return fallback to fallback.coerceAtMost(maxWeek.coerceAtLeast(fallback))
    }
    return (parsed.minOrNull() ?: 1).coerceAtLeast(1) to (parsed.maxOrNull() ?: parsed.first()).coerceAtLeast(1)
}

private fun timetablePeriodSlots(data: TimetableData, entries: List<CourseEntry>): List<TimetablePeriodSlot> {
    val periods = (data.periods + entries.map { it.period })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { (1..5).map { "Period $it" } }
    return periods.map { period ->
        TimetablePeriodSlot(
            period = period,
            timeRange = entries.firstOrNull { it.period == period && !it.timeRange.isNullOrBlank() }?.timeRange,
        )
    }
}

private fun timetableVisibleCellEntries(entries: List<CourseEntry>, currentWeek: Int?): List<TimetableDisplayEntry> {
    val displayEntries = entries.map { entry ->
        TimetableDisplayEntry(
            entry = entry,
            activeInCurrentWeek = timetableEntryMatchesWeek(entry, currentWeek),
        )
    }
    if (currentWeek == null) return displayEntries
    return displayEntries.filter { it.activeInCurrentWeek }.ifEmpty { displayEntries }
}

private fun timetableCellKey(dayIndex: Int, period: String): String =
    "$dayIndex|$period"

private fun timetableWeekdayIndex(days: List<String>, weekday: String): Int {
    parseWeekdayIndex(weekday)?.let { return it }
    val sourceIndex = days.indexOf(weekday)
    return sourceIndex.takeIf { it in 0..6 } ?: Int.MAX_VALUE
}

private fun parseWeekdayIndex(value: String): Int? {
    val text = value.trim().lowercase()
    return when {
        text.contains("周一") || text.contains("星期一") || text.contains("mon") || text == "day 1" -> 0
        text.contains("周二") || text.contains("星期二") || text.contains("tue") || text == "day 2" -> 1
        text.contains("周三") || text.contains("星期三") || text.contains("wed") || text == "day 3" -> 2
        text.contains("周四") || text.contains("星期四") || text.contains("thu") || text == "day 4" -> 3
        text.contains("周五") || text.contains("星期五") || text.contains("fri") || text == "day 5" -> 4
        text.contains("周六") || text.contains("星期六") || text.contains("sat") || text == "day 6" -> 5
        text.contains("周日") || text.contains("星期日") || text.contains("星期天") || text.contains("sun") || text == "day 7" -> 6
        else -> Regex("""\d+""").find(text)?.value?.toIntOrNull()?.minus(1)?.takeIf { it in 0..6 }
    }
}

private fun timetablePeriodIndex(periods: List<String>, period: String): Int {
    val sourceIndex = periods.indexOf(period)
    if (sourceIndex >= 0) return sourceIndex
    return Regex("""\d+""").find(period)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}

private fun timetablePeriodNumber(period: String): String =
    Regex("""\d+""").find(period)?.value ?: period

@Composable
private fun timetableCourseColors(entry: CourseEntry): TimetableCourseColors {
    val colorScheme = MaterialTheme.colorScheme
    if (entry.isUserCreated) {
        val container = UserCourseColors[entry.colorIndex?.coerceIn(0, UserCourseColors.lastIndex) ?: 0]
        return TimetableCourseColors(container = container, content = Color.White)
    }
    val palette = listOf(
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer,
        colorScheme.secondaryContainer to colorScheme.onSecondaryContainer,
        colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer,
        colorScheme.errorContainer to colorScheme.onErrorContainer,
        colorScheme.surfaceVariant to colorScheme.onSurfaceVariant,
    )
    val key = listOf(entry.courseCode, entry.section.orEmpty(), entry.courseName).joinToString("|")
    val index = (key.hashCode() and Int.MAX_VALUE) % palette.size
    val (container, content) = palette[index]
    return TimetableCourseColors(container = container, content = content)
}

private fun courseEntryKey(entry: CourseEntry): String =
    listOf(entry.courseCode, entry.section.orEmpty(), entry.courseName, entry.weekday, entry.period, entry.weeks.orEmpty(), entry.locationLabel().orEmpty())
        .joinToString("|")

private fun courseSelectionKey(entries: List<CourseEntry>): String =
    entries.joinToString("||", transform = ::courseEntryKey)

private fun courseLookupKey(entry: CourseEntry): String? =
    entry.courseCode.takeIf { it.isNotBlank() }

private fun matchesCourse(entry: CourseEntry, homework: HomeworkItem): Boolean {
    val entryCode = entry.courseCode.normalizedCourseText()
    val homeworkCode = homework.courseCode.normalizedCourseText()
    if (entryCode.isNotBlank() && entryCode == homeworkCode) return true

    val entryName = entry.courseName.normalizedCourseText()
    val homeworkCourse = homework.course.normalizedCourseText()
    return entryName.isNotBlank() && homeworkCourse.isNotBlank() &&
        (entryName == homeworkCourse || entryName.contains(homeworkCourse) || homeworkCourse.contains(entryName))
}

private fun parseWeekNumber(value: String?): Int? =
    value?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

private fun timetableEntryMatchesWeek(entry: CourseEntry, currentWeek: Int?): Boolean =
    currentWeek?.let { timetableEntryMatchesWeek(entry, it) } ?: true

private fun timetableEntryMatchesWeek(entry: CourseEntry, currentWeek: Int): Boolean =
    timetableWeeksContain(entry.weeks, currentWeek)

private fun timetableWeeksContain(weeks: String?, currentWeek: Int): Boolean {
    val text = weeks?.trim().orEmpty()
    if (text.isBlank()) return true

    val normalized = text
        .lowercase()
        .replace('（', '(')
        .replace('）', ')')
        .replace('，', ',')
        .replace('、', ',')
    val oddOnly = normalized.contains("单") || normalized.contains("odd")
    val evenOnly = normalized.contains("双") || normalized.contains("even")
    if (oddOnly && currentWeek % 2 == 0) return false
    if (evenOnly && currentWeek % 2 != 0) return false

    val weekRanges = Regex("""(\d{1,2})(?:\s*[-~－—]\s*(\d{1,2}))?""")
        .findAll(normalized)
        .mapNotNull { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: start
            minOf(start, end)..maxOf(start, end)
        }
        .toList()
    if (weekRanges.isEmpty()) return true
    return weekRanges.any { currentWeek in it }
}

private fun CourseEntry.locationLabel(): String? =
    locationText ?: listOfNotNull(campus, building, room).joinToString(" ").takeIf { it.isNotBlank() }

private fun String?.normalizedCourseText(): String =
    this?.trim()?.lowercase()?.replace(Regex("""\s+"""), "").orEmpty()

@Composable
private fun <T> DataScreen(
    title: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    refreshKey: Any? = Unit,
    loader: suspend (ModuleLoadStrategy) -> ModuleEnvelope<T>,
    leadingContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.(ModuleEnvelope<T>) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val design = LocalAppDesign.current
    var retryVersion by remember(refreshKey) { mutableStateOf(0) }
    var state by remember(refreshKey, retryVersion) {
        mutableStateOf<LoadState<ModuleEnvelope<T>>>(LoadState.Loading)
    }
    var initialLoadConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey, retryVersion) {
        val strategy = if (initialLoadConsumed) ModuleLoadStrategy.NetworkFirst else initialLoadStrategy
        initialLoadConsumed = true
        state = LoadState.Loading
        runCatching { loader(strategy) }
            .onSuccess { state = LoadState.Data(it) }
            .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(
            if (isApple) design.itemSpacing else 14.dp,
        ),
    ) {
        leadingContent()
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item {
                LoadingOrError(
                    state = current,
                    onRetry = if (current is LoadState.Error) {
                        { retryVersion += 1 }
                    } else {
                        null
                    },
                )
            }
            is LoadState.Data -> content(current.value)
        }
    }
}

@Composable
private fun <T> ProgressiveDataScreen(
    title: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    refreshKey: Any? = Unit,
    loader: (ModuleLoadStrategy) -> Flow<ProgressiveModuleState<T>>,
    leadingContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.(ProgressiveModuleState<T>, ModuleEnvelope<T>) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val design = LocalAppDesign.current
    var retryVersion by remember(refreshKey) { mutableStateOf(0) }
    var state by remember(refreshKey, retryVersion) {
        mutableStateOf(ProgressiveModuleState<T>())
    }
    var initialLoadConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey, retryVersion) {
        val strategy = if (initialLoadConsumed) ModuleLoadStrategy.NetworkFirst else initialLoadStrategy
        initialLoadConsumed = true
        state = ProgressiveModuleState<T>()
        runCatching {
            loader(strategy).collect { state = it }
        }.onFailure { error ->
            state = state.copy(
                loading = false,
                complete = true,
                errors = state.errors + (error.message ?: "加载失败"),
            )
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(
            if (isApple) design.itemSpacing else 14.dp,
        ),
    ) {
        leadingContent()
        val envelope = state.envelope
        if (envelope != null) {
            item(key = "$title-progressive-status") {
                ProgressiveStatus(state)
            }
            content(state, envelope)
        } else {
            when {
                state.loading -> item { LoadingOrError(LoadState.Loading) }
                state.errors.isNotEmpty() -> item {
                    LoadingOrError(
                        LoadState.Error(state.errors.joinToString("；")),
                        onRetry = { retryVersion += 1 },
                    )
                }
                else -> item {
                    LoadingOrError(
                        LoadState.Error("加载失败"),
                        onRetry = { retryVersion += 1 },
                    )
                }
            }
        }
    }
}
