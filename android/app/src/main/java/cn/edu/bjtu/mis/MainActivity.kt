package cn.edu.bjtu.mis

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import cn.edu.bjtu.mis.ui.BjtuMisApp
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme

class MainActivity : ComponentActivity() {
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedRoute.value = intent.openRoute()
        val app = application as BjtuMisApplication
        setContent {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                app.startDeferredStartupTasks()
            }
            BjtuMisTheme {
                BjtuMisApp(
                    container = app.container,
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
