package cn.edu.bjtu.mis.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.SessionRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    sessionRepository: SessionRepository,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captchaText by remember { mutableStateOf("") }
    var captchaDataUrl by remember { mutableStateOf<String?>(null) }
    var fetchedAt by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadCaptcha() {
        scope.launch {
            busy = true
            error = null
            runCatching { sessionRepository.captcha() }
                .onSuccess {
                    captchaDataUrl = it.imageDataUrl
                    fetchedAt = it.fetchedAt
                    captchaText = ""
                }
                .onFailure { error = it.message ?: "验证码加载失败" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        loadCaptcha()
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("BJTU MIS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("校园信息本地采集与离线查看", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = loginName,
                    onValueChange = { loginName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("学号 / 工号") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = captchaText,
                        onValueChange = { captchaText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("验证码") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Spacer(Modifier.width(12.dp))
                    CaptchaImage(
                        dataUrl = captchaDataUrl,
                        modifier = Modifier
                            .width(132.dp)
                            .height(48.dp)
                            .clickable(enabled = !busy) { loadCaptcha() },
                    )
                }
                if (!fetchedAt.isNullOrBlank()) {
                    Text("验证码更新时间：$fetchedAt", style = MaterialTheme.typography.bodySmall)
                }
                if (!error.isNullOrBlank()) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && loginName.isNotBlank() && password.isNotBlank() && captchaText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { sessionRepository.login(loginName, password, captchaText) }
                                .onSuccess { onLoggedIn() }
                                .onFailure {
                                    error = it.message ?: "登录失败"
                                    loadCaptcha()
                                }
                            busy = false
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("登录")
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { loadCaptcha() },
                ) {
                    Text("刷新验证码")
                }
            }
        }
    }
}

@Composable
private fun CaptchaImage(dataUrl: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(dataUrl) {
        val base64 = dataUrl?.substringAfter("base64,", missingDelimiterValue = "").orEmpty()
        if (base64.isBlank()) null else runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "验证码", modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("点击加载", style = MaterialTheme.typography.bodySmall)
        }
    }
}
