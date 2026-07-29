package cn.edu.bjtu.mis.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyIconSource
import cn.edu.bjtu.mis.ui.theme.LocalAppMotion
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun ThirdPartyPluginIcon(
    source: ThirdPartyIconSource?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    fallbackTint: Color = MaterialTheme.colorScheme.primary,
    contentPadding: Dp = 6.dp,
) {
    val context = LocalContext.current
    val motion = LocalAppMotion.current
    val resolvedContainerColor = if (containerColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    } else {
        containerColor
    }
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    }
    val request = remember(context, source, motion.reduceMotion) {
        val data: Any? = when (source) {
            is ThirdPartyIconSource.LocalFile -> source.file
            is ThirdPartyIconSource.RemoteUrl -> source.url
            null -> null
        }
        data?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(!motion.reduceMotion)
                .build()
        }
    }
    var loaded by remember(source) { mutableStateOf(false) }

    Surface(
        modifier = modifier.then(semanticsModifier),
        color = resolvedContainerColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (!loaded) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                    tint = fallbackTint,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    onLoading = { loaded = false },
                    onSuccess = { loaded = true },
                    onError = { loaded = false },
                )
            }
        }
    }
}
