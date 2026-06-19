package cn.edu.bjtu.mis

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.ui.BjtuMisApp
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme

class MainActivity : AppCompatActivity() {
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedRoute.value = intent.openRoute()
        val app = application as BjtuMisApplication
        val initialLoadStrategy = if (savedInstanceState == null) {
            ModuleLoadStrategy.NetworkFirst
        } else {
            ModuleLoadStrategy.CacheOnly
        }
        setContent {
            val themeOption by app.container.themeStore.theme.collectAsState(initial = AppThemeOption.Default)
            LaunchedEffect(Unit) {
                withFrameNanos { }
                app.startDeferredStartupTasks()
            }
            BjtuMisTheme(themeOption = themeOption) {
                BjtuMisApp(
                    container = app.container,
                    themeOption = themeOption,
                    initialLoadStrategy = initialLoadStrategy,
                    requestedRoute = requestedRoute.value,
                    onRouteHandled = { requestedRoute.value = null },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = intent.openRoute()
    }

    private fun Intent?.openRoute(): String? =
        this?.getStringExtra(EXTRA_OPEN_ROUTE)

    companion object {
        const val EXTRA_OPEN_ROUTE = "cn.edu.bjtu.mis.OPEN_ROUTE"
    }
}
