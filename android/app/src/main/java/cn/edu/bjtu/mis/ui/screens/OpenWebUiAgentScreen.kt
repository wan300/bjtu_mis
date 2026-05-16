package cn.edu.bjtu.mis.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.openwebui.OpenWebUiAgentFragment

@Composable
fun OpenWebUiAgentScreen(
    repository: ModuleRepository,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit,
) {
    var studentName by remember { mutableStateOf<String?>(null) }
    var profileLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        studentName = runCatching {
            repository.profile().data.name?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
        profileLoaded = true
    }

    if (!profileLoaded) {
        LaunchedEffect(Unit) {
            onBackHandlerChanged(null)
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
        ?: error("OpenWebUiAgentScreen requires a FragmentActivity context")
    val fragmentManager = activity.supportFragmentManager
    val containerId = remember { View.generateViewId() }

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
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            FragmentContainerView(viewContext).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { container ->
            if (fragmentManager.findFragmentById(container.id) == null && !fragmentManager.isStateSaved) {
                fragmentManager.beginTransaction()
                    .replace(container.id, OpenWebUiAgentFragment.newInstance(studentName))
                    .commitNow()
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
