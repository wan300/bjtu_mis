package cn.edu.bjtu.mis.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.openwebui.OpenWebUiAgentFragment
import cn.edu.bjtu.mis.ui.theme.AppThemeOption

@Composable
fun OpenWebUiAgentScreen(
    repository: ModuleRepository,
    themeOption: AppThemeOption,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
) {
    var studentName by remember { mutableStateOf<String?>("同学") }
    val agentTheme = when (themeOption) {
        AppThemeOption.Default -> OpenWebUiAgentFragment.AGENT_THEME_LIGHT
        AppThemeOption.MascotGold -> OpenWebUiAgentFragment.AGENT_THEME_DARK
        AppThemeOption.IllustrationRose -> OpenWebUiAgentFragment.AGENT_THEME_LIGHT
    }

    LaunchedEffect(repository) {
        runCatching {
            repository.profile(strategy = ModuleLoadStrategy.CacheFirst).data.name?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let {
            studentName = it
        }
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
        ?: error("OpenWebUiAgentScreen requires a FragmentActivity context")
    val fragmentManager = activity.supportFragmentManager
    val containerId = remember { View.generateViewId() }
    var previousVisible by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(fragmentManager, containerId) {
        onBackHandlerChanged {
            (fragmentManager.findFragmentById(containerId) as? OpenWebUiAgentFragment)
                ?.goBackIfPossible() == true
        }
        onDispose {
            onBackHandlerChanged(null)
            val fragment = fragmentManager.findFragmentById(containerId)
            if (fragment != null) {
                fragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss()
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            FragmentContainerView(viewContext).apply {
                id = containerId
                visibility = if (visible) View.VISIBLE else View.INVISIBLE
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { container ->
            val becameVisible = previousVisible != true && visible
            previousVisible = visible
            container.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            var fragment = fragmentManager.findFragmentById(container.id) as? OpenWebUiAgentFragment
            if (fragment == null && !fragmentManager.isStateSaved) {
                fragmentManager.beginTransaction()
                    .replace(container.id, OpenWebUiAgentFragment.newInstance(studentName, agentTheme))
                    .commitNow()
                fragment = fragmentManager.findFragmentById(container.id) as? OpenWebUiAgentFragment
            } else {
                fragment?.updatePreferredTheme(agentTheme)
                fragment?.updateStudentName(studentName)
            }
            if (visible && becameVisible) {
                fragment?.notifyHomeworkHandoffAvailable()
            }
        },
    )
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
