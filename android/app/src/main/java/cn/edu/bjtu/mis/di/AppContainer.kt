package cn.edu.bjtu.mis.di

import android.content.Context
import androidx.room.Room
import cn.edu.bjtu.mis.data.db.AppDatabase
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.provider.SessionManager
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.repository.SessionRepository
import cn.edu.bjtu.mis.data.repository.SyncRepository
import cn.edu.bjtu.mis.data.security.SecureCookieStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "bjtu_mis.sqlite3",
    ).build()

    val cookieJar = AppCookieJar()
    private val cookieStore = SecureCookieStore(appContext)
    val httpClient = BjtuHttpClient(cookieJar)
    val sessionManager = SessionManager(cookieStore, cookieJar, httpClient)
    val sessionRepository = SessionRepository(sessionManager)
    val syncRepository = SyncRepository(database.dao(), sessionManager)
    val moduleRepository = ModuleRepository(syncRepository, sessionManager)
    val courseResourceRepository = CourseResourceRepository(appContext, moduleRepository, sessionManager)
}
