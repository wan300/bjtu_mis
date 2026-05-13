package cn.edu.bjtu.mis

import android.app.Application
import cn.edu.bjtu.mis.data.homework.HomeworkReminderWorker
import cn.edu.bjtu.mis.data.perf.PerfTrace
import cn.edu.bjtu.mis.data.sync.SessionKeepAliveWorker
import cn.edu.bjtu.mis.data.sync.SyncWorker
import cn.edu.bjtu.mis.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BjtuMisApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferredStartupStarted = AtomicBoolean(false)

    override fun onCreate() {
        PerfTrace.measure("Application.onCreate") {
            super.onCreate()
            container = PerfTrace.measure("Application.AppContainer") { AppContainer(this) }
        }
    }

    fun startDeferredStartupTasks() {
        if (!deferredStartupStarted.compareAndSet(false, true)) return
        startupScope.launch {
            PerfTrace.measureSuspend("Application.deferredStartup") {
                coroutineScope {
                    launch {
                        runCatching { container.agentRepository.markStaleActiveTasks() }
                    }
                    launch {
                        SyncWorker.schedule(this@BjtuMisApplication)
                        SessionKeepAliveWorker.schedule(this@BjtuMisApplication)
                        HomeworkReminderWorker.schedule(this@BjtuMisApplication)
                    }
                }
            }
        }
    }
}
