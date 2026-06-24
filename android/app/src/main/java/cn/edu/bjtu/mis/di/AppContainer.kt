package cn.edu.bjtu.mis.di

import android.content.Context
import androidx.room.Room
import cn.edu.bjtu.mis.data.agent.document.DocumentTool
import cn.edu.bjtu.mis.data.agent.runtime.RuntimeManager
import cn.edu.bjtu.mis.data.agent.tools.ArchiveTool
import cn.edu.bjtu.mis.data.agent.tools.CodeTool
import cn.edu.bjtu.mis.data.agent.tools.FileTool
import cn.edu.bjtu.mis.data.agent.tools.MailAgentTool
import cn.edu.bjtu.mis.data.agent.tools.PackageTool
import cn.edu.bjtu.mis.data.agent.tools.ToolRegistry
import cn.edu.bjtu.mis.data.agent.tools.WorkspaceManager
import cn.edu.bjtu.mis.data.course.CourseSelectionRunner
import cn.edu.bjtu.mis.data.captcha.TorchScriptCaptchaSolver
import cn.edu.bjtu.mis.data.db.AppDatabase
import cn.edu.bjtu.mis.data.db.MIGRATION_1_2
import cn.edu.bjtu.mis.data.db.MIGRATION_2_3
import cn.edu.bjtu.mis.data.db.MIGRATION_3_4
import cn.edu.bjtu.mis.data.db.MIGRATION_4_5
import cn.edu.bjtu.mis.data.db.MIGRATION_5_6
import cn.edu.bjtu.mis.data.db.MIGRATION_6_7
import cn.edu.bjtu.mis.data.db.MIGRATION_7_8
import cn.edu.bjtu.mis.data.db.MIGRATION_8_9
import cn.edu.bjtu.mis.data.db.MIGRATION_9_10
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarSyncStore
import cn.edu.bjtu.mis.data.homework.HomeworkReminderCoordinator
import cn.edu.bjtu.mis.data.homework.HomeworkReminderNotifier
import cn.edu.bjtu.mis.data.homework.HomeworkReminderPreferenceStore
import cn.edu.bjtu.mis.data.homework.HomeworkReminderRunner
import cn.edu.bjtu.mis.data.homework.HomeworkReminderStateStore
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.perf.PerfTrace
import cn.edu.bjtu.mis.data.provider.SessionManager
import cn.edu.bjtu.mis.data.repository.CourseReplayRepository
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.repository.CourseSelectionRepository
import cn.edu.bjtu.mis.data.repository.EmploymentConsultationRepository
import cn.edu.bjtu.mis.data.repository.HomeworkAttachmentRepository
import cn.edu.bjtu.mis.data.repository.MailRepository
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.repository.OverviewRepository
import cn.edu.bjtu.mis.data.repository.SessionRepository
import cn.edu.bjtu.mis.data.repository.SyncRepository
import cn.edu.bjtu.mis.data.repository.ZhixingRepository
import cn.edu.bjtu.mis.data.security.SecureCookieStore
import cn.edu.bjtu.mis.data.security.SecureCredentialStore
import cn.edu.bjtu.mis.data.thirdparty.AssetThirdPartyBundledServiceProvider
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceApiRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceInstaller
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceRepository
import cn.edu.bjtu.mis.data.update.AppUpdateChecker
import cn.edu.bjtu.mis.data.update.AppUpdatePreferenceStore
import cn.edu.bjtu.mis.data.update.installedVersionName
import cn.edu.bjtu.mis.ui.theme.AppThemeStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val thirdPartyServicesRoot = java.io.File(appContext.filesDir, "third-party-services")
    val database: AppDatabase = PerfTrace.measure("AppContainer.database") {
        Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "bjtu_mis.sqlite3",
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        ).build()
    }

    val cookieJar = AppCookieJar()
    private val cookieStore = SecureCookieStore(appContext)
    private val credentialStore = SecureCredentialStore(appContext)
    private val captchaSolver = TorchScriptCaptchaSolver(appContext)
    val httpClient = BjtuHttpClient(cookieJar)
    val appUpdateChecker = AppUpdateChecker(
        client = httpClient,
        currentVersionProvider = { appContext.installedVersionName() },
    )
    val appUpdatePreferenceStore = AppUpdatePreferenceStore(appContext)
    val sessionManager = SessionManager(cookieStore, credentialStore, cookieJar, httpClient, captchaSolver)
    val sessionRepository = SessionRepository(sessionManager)
    val syncRepository = SyncRepository(database.dao(), sessionManager)
    val moduleRepository = ModuleRepository(syncRepository, sessionManager)
    val overviewRepository = OverviewRepository(syncRepository)
    val courseResourceRepository: CourseResourceRepository by lazy {
        PerfTrace.measure("AppContainer.courseResourceRepository") {
            CourseResourceRepository(appContext, moduleRepository, sessionManager)
        }
    }
    val homeworkAttachmentRepository: HomeworkAttachmentRepository by lazy {
        PerfTrace.measure("AppContainer.homeworkAttachmentRepository") {
            HomeworkAttachmentRepository(appContext, sessionManager)
        }
    }
    val courseReplayRepository: CourseReplayRepository by lazy {
        PerfTrace.measure("AppContainer.courseReplayRepository") {
            CourseReplayRepository(syncRepository, moduleRepository, sessionManager)
        }
    }
    val mailRepository: MailRepository by lazy {
        PerfTrace.measure("AppContainer.mailRepository") {
            MailRepository(appContext, database.dao(), sessionManager)
        }
    }
    val zhixingRepository: ZhixingRepository by lazy {
        PerfTrace.measure("AppContainer.zhixingRepository") {
            ZhixingRepository(
                syncRepository = syncRepository,
                sessionManager = sessionManager,
                credentialStore = SecureCredentialStore(
                    appContext,
                    alias = "bjtu_mis_zhixing_credentials_key",
                    fileName = "zhixing_credentials.bin",
                ),
            )
        }
    }
    val employmentConsultationRepository: EmploymentConsultationRepository by lazy {
        PerfTrace.measure("AppContainer.employmentConsultationRepository") {
            EmploymentConsultationRepository(syncRepository, httpClient)
        }
    }
    val homeworkReminderPreferenceStore = HomeworkReminderPreferenceStore(appContext)
    val homeworkReminderRunner = HomeworkReminderRunner(
        dao = database.dao(),
        coordinator = HomeworkReminderCoordinator(
            state = HomeworkReminderStateStore(appContext),
            sender = HomeworkReminderNotifier(appContext),
            configProvider = { homeworkReminderPreferenceStore.config() },
        ),
    )
    val courseSelectionRepository: CourseSelectionRepository by lazy {
        PerfTrace.measure("AppContainer.courseSelectionRepository") {
            CourseSelectionRepository(sessionManager, syncRepository)
        }
    }
    val courseSelectionRunner: CourseSelectionRunner by lazy {
        PerfTrace.measure("AppContainer.courseSelectionRunner") {
            CourseSelectionRunner(
                selectCourse = { target -> courseSelectionRepository.select(target.key, target.courseName) },
                replaceCourse = { rule ->
                    courseSelectionRepository.replace(
                        targetCourseKey = rule.target.key,
                        targetCourseName = rule.target.courseName,
                        dropCourseKey = rule.drop.key,
                        dropCourseName = rule.drop.courseName,
                    )
                },
                submitCaptchaAnswer = { challengeId, captcha -> courseSelectionRepository.submitCaptcha(challengeId, captcha) },
            )
        }
    }
    val employmentCalendarSyncStore: EmploymentCalendarSyncStore by lazy {
        PerfTrace.measure("AppContainer.employmentCalendarSyncStore") {
            EmploymentCalendarSyncStore(appContext)
        }
    }
    val themeStore: AppThemeStore by lazy {
        PerfTrace.measure("AppContainer.themeStore") { AppThemeStore(appContext) }
    }
    val agentWorkspaceManager: WorkspaceManager by lazy {
        PerfTrace.measure("AppContainer.agentWorkspaceManager") { WorkspaceManager(appContext) }
    }
    val agentRuntimeManager: RuntimeManager by lazy {
        PerfTrace.measure("AppContainer.agentRuntimeManager") { RuntimeManager(appContext) }
    }
    val agentToolRegistry: ToolRegistry by lazy {
        PerfTrace.measure("AppContainer.agentToolRegistry") {
            ToolRegistry(
                    FileTool(agentWorkspaceManager).tools() +
                    ArchiveTool(agentWorkspaceManager).tools() +
                    DocumentTool(appContext, agentWorkspaceManager).tools() +
                    CodeTool(agentRuntimeManager).tools() +
                    MailAgentTool(mailRepository).tools() +
                    PackageTool(agentWorkspaceManager).tools()
            )
        }
    }
    val thirdPartyServiceInstaller: ThirdPartyServiceInstaller by lazy {
        PerfTrace.measure("AppContainer.thirdPartyServiceInstaller") {
            ThirdPartyServiceInstaller(
                client = httpClient,
                servicesRoot = thirdPartyServicesRoot,
            )
        }
    }
    val thirdPartyServiceRepository: ThirdPartyServiceRepository by lazy {
        PerfTrace.measure("AppContainer.thirdPartyServiceRepository") {
            ThirdPartyServiceRepository(
                dao = database.thirdPartyServiceDao(),
                installer = thirdPartyServiceInstaller,
                bundledProvider = AssetThirdPartyBundledServiceProvider(
                    context = appContext,
                    installer = thirdPartyServiceInstaller,
                    servicesRoot = thirdPartyServicesRoot,
                ),
            )
        }
    }
    val thirdPartyServiceApiRegistry: ThirdPartyServiceApiRegistry by lazy {
        PerfTrace.measure("AppContainer.thirdPartyServiceApiRegistry") {
            ThirdPartyServiceApiRegistry(
                moduleRepository = moduleRepository,
                mailRepository = mailRepository,
                credentialStore = credentialStore,
            )
        }
    }
}
