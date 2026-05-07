package cn.edu.bjtu.mis

import android.app.Application
import cn.edu.bjtu.mis.di.AppContainer

class BjtuMisApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
