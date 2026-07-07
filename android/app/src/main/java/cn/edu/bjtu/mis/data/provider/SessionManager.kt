package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.captcha.CaptchaAnswerSolver
import cn.edu.bjtu.mis.data.captcha.CaptchaSolveException
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.normalizeSpace
import cn.edu.bjtu.mis.data.perf.PerfTrace
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.data.security.SessionCookieStore
import cn.edu.bjtu.mis.model.AutoLoginResult
import cn.edu.bjtu.mis.model.AutoLoginStatus
import cn.edu.bjtu.mis.model.SessionCaptcha
import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.model.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class SessionValidationPolicy {
    Fresh,
    UseRecentOrValidate,
}

data class SessionEndpoints(
    val misHomeUrl: String = ProviderConstants.MIS_HOME_URL,
    val misAaBridgeUrl: String = ProviderConstants.MIS_AA_BRIDGE_URL,
    val aaTimetableUrl: String = ProviderConstants.AA_TIMETABLE_URL,
    val bksyVeBridgeUrl: String = ProviderConstants.BKSY_VE_BRIDGE_URL,
    val casLoginPath: String = "/auth/login",
    val casOrigin: String = "https://cas.bjtu.edu.cn",
    val misReferer: String = "https://mis.bjtu.edu.cn/",
    val bksyReferer: String = "https://bksy.bjtu.edu.cn/",
)

internal data class SessionValidationRecord(
    val status: SessionStatus,
    val savedAtMillis: Long,
)

internal class SessionValidationCache(
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
) {
    private var record: SessionValidationRecord? = null

    @Synchronized
    fun getFresh(): SessionStatus? {
        val current = record ?: return null
        if (nowMillis() - current.savedAtMillis > ttlMillis) return null
        return current.status
    }

    @Synchronized
    fun remember(status: SessionStatus) {
        if (status.state == SessionState.Ready) {
            record = SessionValidationRecord(status, nowMillis())
        } else {
            clear()
        }
    }

    @Synchronized
    fun clear() {
        record = null
    }
}

class SessionManager(
    private val cookieStore: SessionCookieStore,
    private val credentialStore: CredentialStore,
    private val cookieJar: AppCookieJar,
    private val httpClient: BjtuHttpClient,
    private val captchaSolver: CaptchaAnswerSolver,
    private val endpoints: SessionEndpoints = SessionEndpoints(),
) {
    private val mutex = Mutex()
    private val validationMutex = Mutex()
    private val reauthMutex = Mutex()
    private val validationCache = SessionValidationCache(
        ttlMillis = TimeUnit.MINUTES.toMillis(5),
        nowMillis = { PerfTrace.nowMillis() },
    )
    private var inlineLoginState: InlineLoginState? = null

    suspend fun fetchInlineLoginCaptcha(): SessionCaptcha = mutex.withLock {
        validationCache.clear()
        VeSessionContextCache.clear()
        cookieJar.clear()
        val loginPage = httpClient.getText(endpoints.misHomeUrl)
        if (!isCasLoginUrl(loginPage.url)) {
            throw SessionExpiredException("当前会话似乎已登录，请直接同步或先退出登录。")
        }

        val state = parseInlineLoginPage(loginPage.url, loginPage.body)
        val captcha = httpClient.getBytes(
            state.captchaUrl,
            headers = mapOf("Referer" to loginPage.url),
        )
        val mimeType = captcha.headers["Content-Type"]?.substringBefore(";")?.trim().orEmpty()
            .ifBlank { "image/jpeg" }
        val encoded = Base64.getEncoder().encodeToString(captcha.body)
        val dataUrl = "data:$mimeType;base64,$encoded"
        inlineLoginState = state.copy(
            cookiesJson = cookieJar.encodeSnapshot(),
            captchaImageBase64 = encoded,
            captchaMimeType = mimeType,
        )
        SessionCaptcha(imageDataUrl = dataUrl, fetchedAt = nowIso())
    }

    suspend fun loginInline(
        loginName: String,
        password: String,
        captcha: String,
        persistCredentials: Boolean = true,
    ): SessionStatus = mutex.withLock {
        val state = inlineLoginState ?: throw SessionExpiredException("验证码已失效，请刷新后重试。")
        VeSessionContextCache.clear()
        cookieJar.restoreFromJson(state.cookiesJson)
        val response = httpClient.postForm(
            state.loginUrl,
            form = mapOf(
                "next" to state.next,
                "csrfmiddlewaretoken" to state.csrf,
                "loginname" to loginName.trim(),
                "password" to password,
                "captcha_0" to state.captcha0,
                "captcha_1" to captcha.trim(),
            ),
            headers = mapOf(
                "Referer" to state.referer,
                "Origin" to endpoints.casOrigin,
            ),
        )

        if (isCasLoginUrl(response.url)) {
            inlineLoginState = null
            throw SessionExpiredException(extractInlineLoginError(response.body) ?: "登录失败，请检查账号、密码和验证码。")
        }

        val aaReady = ensureAaSessionReady()
        if (!aaReady.first) {
            inlineLoginState = null
            throw SessionExpiredException(aaReady.second ?: "教学支撑平台会话未准备好。")
        }

        bootstrapVeSessionBestEffort()
        cookieStore.save(cookieJar.encodeSnapshot())
        if (persistCredentials) {
            credentialStore.save(LoginCredentials(loginName.trim(), password))
        }
        inlineLoginState = null
        validateSession()
    }

    suspend fun loginAuto(loginName: String? = null, password: String? = null): AutoLoginResult {
        val explicitCredentials = !loginName.isNullOrBlank() && !password.isNullOrBlank()
        val credentials = if (explicitCredentials) {
            LoginCredentials(loginName = loginName!!.trim(), password = password!!)
        } else {
            credentialStore.load()
        } ?: return AutoLoginResult(
            status = AutoLoginStatus.AutoFailed,
            message = "未保存登录凭据，请手动输入学号和密码。",
        )

        val maxAttempts = if (explicitCredentials) 2 else 3
        var lastMessage = ""
        repeat(maxAttempts) { index ->
            try {
                fetchInlineLoginCaptcha()
                val captchaBytes = currentCaptchaBytes()
                val solved = withContext(Dispatchers.Default) { captchaSolver.solve(captchaBytes) }
                val status = loginInline(
                    credentials.loginName,
                    credentials.password,
                    solved.answer,
                    persistCredentials = true,
                )
                return AutoLoginResult(
                    status = AutoLoginStatus.Ready,
                    message = "已自动识别验证码算式 ${solved.expression} 并完成登录。",
                    attempts = index + 1,
                    session = status,
                )
            } catch (error: CaptchaSolveException) {
                lastMessage = error.message.orEmpty()
                inlineLoginState = null
                if (!error.retryable) {
                    val attempts = index + 1
                    if (explicitCredentials) {
                        val captcha = runCatching { fetchInlineLoginCaptcha() }.getOrNull()
                        return AutoLoginResult(
                            status = AutoLoginStatus.ManualRequired,
                            message = "自动识别验证码不可用，请手动输入验证码。$lastMessage",
                            attempts = attempts,
                            captcha = captcha,
                        )
                    }
                    return AutoLoginResult(
                        status = AutoLoginStatus.AutoFailed,
                        message = "自动识别验证码不可用：${lastMessage.ifBlank { "未知错误" }}",
                        attempts = attempts,
                    )
                }
            } catch (error: SessionExpiredException) {
                lastMessage = error.message.orEmpty()
            } catch (error: IOException) {
                lastMessage = error.message.orEmpty()
                inlineLoginState = null
                return AutoLoginResult(
                    status = AutoLoginStatus.AutoFailed,
                    message = lastMessage.ifBlank { "网络连接失败，请检查网络后重试。" },
                    attempts = index + 1,
                )
            }
        }

        if (explicitCredentials) {
            val captcha = runCatching { fetchInlineLoginCaptcha() }.getOrNull()
            return AutoLoginResult(
                status = AutoLoginStatus.ManualRequired,
                message = "自动识别连续失败，请手动输入验证码。$lastMessage",
                attempts = maxAttempts,
                captcha = captcha,
            )
        }

        return AutoLoginResult(
            status = AutoLoginStatus.AutoFailed,
            message = "自动登录连续失败 $maxAttempts 次：${lastMessage.ifBlank { "未知错误" }}",
            attempts = maxAttempts,
        )
    }

    private fun currentCaptchaBytes(): ByteArray {
        val encoded = inlineLoginState?.captchaImageBase64
            ?: throw CaptchaSolveException("验证码图片上下文缺失。")
        return runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw CaptchaSolveException("验证码图片上下文损坏。", it) }
    }

    fun cachedSessionStatus(): SessionStatus {
        val payload = cookieStore.load()
            ?: return SessionStatus(SessionState.WaitingForLogin, "No saved local session.")
        cookieJar.restoreFromJson(payload)
        return SessionStatus(SessionState.Ready, "Using cached local session while validating in background.")
    }

    suspend fun validateSession(
        policy: SessionValidationPolicy = SessionValidationPolicy.Fresh,
    ): SessionStatus {
        if (policy == SessionValidationPolicy.UseRecentOrValidate) {
            validationCache.getFresh()?.let {
                PerfTrace.mark("Session.validate", "cache_hit")
                return it
            }
        }

        return validationMutex.withLock {
            if (policy == SessionValidationPolicy.UseRecentOrValidate) {
                validationCache.getFresh()?.let {
                    PerfTrace.mark("Session.validate", "cache_hit_after_lock")
                    return@withLock it
                }
            }
            PerfTrace.measureSuspend("Session.validate") {
                validateSessionFresh().also { validationCache.remember(it) }
            }
        }
    }

    private suspend fun validateSessionFresh(): SessionStatus {
        val payload = cookieStore.load()
            ?: return SessionStatus(SessionState.WaitingForLogin, "未找到可用会话，请先登录。")
        cookieJar.restoreFromJson(payload)
        return runCatching {
            val response = httpClient.getText(endpoints.misHomeUrl)
            if (isCasLoginUrl(response.url)) {
                VeSessionContextCache.clear()
                SessionStatus(SessionState.Expired, "会话已过期，请重新登录。")
            } else {
                val aaReady = ensureAaSessionReady()
                when {
                    !aaReady.first -> SessionStatus(SessionState.Expired, aaReady.second)
                    aaReady.second != null -> {
                        cookieStore.save(cookieJar.encodeSnapshot())
                        SessionStatus(SessionState.Ready, "会话可用：${aaReady.second}")
                    }
                    else -> {
                        cookieStore.save(cookieJar.encodeSnapshot())
                        SessionStatus(SessionState.Ready, "会话可用。")
                    }
                }
            }
        }.getOrElse { error ->
            SessionStatus(SessionState.Expired, "会话校验失败：${error.message}")
        }
    }

    suspend fun recoverSession(
        policy: SessionValidationPolicy = SessionValidationPolicy.UseRecentOrValidate,
    ): AutoLoginResult = PerfTrace.measureSuspend("Session.recover") {
        val current = validateSession(policy)
        if (current.state == SessionState.Ready) {
            return@measureSuspend AutoLoginResult(
                status = AutoLoginStatus.Ready,
                message = current.detail,
                attempts = 0,
                session = current,
            )
        }

        return@measureSuspend reauthMutex.withLock {
            if (policy == SessionValidationPolicy.UseRecentOrValidate) {
                validationCache.getFresh()?.let { afterLock ->
                    return@withLock AutoLoginResult(
                        status = AutoLoginStatus.Ready,
                        message = afterLock.detail,
                        attempts = 0,
                        session = afterLock,
                    )
                }
            }
            validationCache.clear()
            loginAuto()
        }
    }

    suspend fun <T> withAuthenticatedClient(
        policy: SessionValidationPolicy = SessionValidationPolicy.UseRecentOrValidate,
        block: suspend (BjtuHttpClient) -> T,
    ): T {
        val recovered = recoverSession(policy)
        if (recovered.status != AutoLoginStatus.Ready) {
            throw SessionExpiredException(recovered.message ?: "会话未准备好，自动重新登录失败。")
        }

        return try {
            block(httpClient).also {
                cookieStore.save(cookieJar.encodeSnapshot())
            }
        } catch (error: SessionExpiredException) {
            validationCache.clear()
            VeSessionContextCache.clear()
            val retried = recoverSession(SessionValidationPolicy.Fresh)
            if (retried.status != AutoLoginStatus.Ready) {
                throw SessionExpiredException(retried.message ?: error.message ?: "会话已过期，自动重新登录失败。")
            }
            block(httpClient).also {
                cookieStore.save(cookieJar.encodeSnapshot())
            }
        }
    }

    fun logout() {
        inlineLoginState = null
        validationCache.clear()
        VeSessionContextCache.clear()
        cookieJar.clear()
        cookieStore.clear()
        credentialStore.clear()
    }

    private suspend fun ensureAaSessionReady(): Pair<Boolean, String?> {
        val state = checkAaSessionState()
        if (state.first == "ready" || state.first == "service_unavailable") return true to state.second
        if (state.first == "error") return false to state.second
        if (state.first == "login_required") {
            val bootstrapped = bootstrapAaSession()
            if (!bootstrapped) return false to "教学支撑平台单点登录未完成。"
            val after = checkAaSessionState()
            if (after.first == "ready" || after.first == "service_unavailable") return true to after.second
            return false to (after.second ?: "教学支撑平台仍要求登录。")
        }
        return false to state.second
    }

    private suspend fun checkAaSessionState(): Pair<String, String?> = runCatching {
        val response = httpClient.getText(endpoints.aaTimetableUrl)
        val head = response.body.take(4096)
        when {
            response.url.contains("/client/login/") || isAaLoginPage(response.url, head) -> "login_required" to null
            else -> "ready" to null
        }
    }.getOrElse { "error" to "教学支撑平台会话校验失败：${it.message}" }

    private suspend fun bootstrapAaSession(): Boolean = runCatching {
        val bridge = httpClient.getText(
            endpoints.misAaBridgeUrl,
            headers = mapOf("Referer" to endpoints.misHomeUrl),
        )
        val loginUrl = extractAaClientLoginUrl(bridge.body) ?: return false
        httpClient.getText(loginUrl, headers = mapOf("Referer" to endpoints.misReferer))
        true
    }.getOrDefault(false)

    private suspend fun bootstrapVeSessionBestEffort(): Boolean = runCatching {
        httpClient.getText(
            endpoints.bksyVeBridgeUrl,
            headers = mapOf("Referer" to endpoints.bksyReferer),
        )
        true
    }.getOrDefault(false)

    private fun parseInlineLoginPage(pageUrl: String, html: String): InlineLoginState {
        val document = Jsoup.parse(html, pageUrl)
        val form = document.selectFirst("form#login")
            ?: document.selectFirst("form[name=login]")
            ?: document.selectFirst("form:has(input[name=loginname]):has(input[name=password])")
            ?: document.selectFirst("form")
            ?: throw SessionExpiredException("未能解析统一认证登录表单。")
        fun inputValue(name: String): String =
            form.selectFirst("input[name=$name]")?.attr("value")?.let(::normalizeSpace)
                ?.takeIf { it.isNotBlank() }
                ?: throw SessionExpiredException("登录表单缺少字段 $name。")

        val captchaImg = form.selectFirst("img.captcha, img[alt=captcha]")
            ?: document.selectFirst("img.captcha, img[alt=captcha]")
            ?: throw SessionExpiredException("未找到验证码图片。")
        val captchaUrl = captchaImg.absUrl("src")
            .takeIf { it.isNotBlank() }
            ?: throw SessionExpiredException("验证码图片地址为空。")
        return InlineLoginState(
            loginUrl = form.absUrl("action").ifBlank { pageUrl },
            captchaUrl = captchaUrl,
            next = inputValue("next"),
            csrf = inputValue("csrfmiddlewaretoken"),
            captcha0 = inputValue("captcha_0"),
            referer = pageUrl,
            cookiesJson = cookieJar.encodeSnapshot(),
            captchaImageBase64 = "",
            captchaMimeType = "image/jpeg",
        )
    }

    private fun extractInlineLoginError(html: String): String? {
        val document = Jsoup.parse(html)
        val tip = normalizeSpace(document.selectFirst(".tishi")?.text())
        if (tip.isNotBlank()) return tip
        val text = normalizeSpace(document.text())
        return listOf("验证码", "用户名或密码", "登录失败", "密码错误", "账号")
            .firstOrNull { text.contains(it) }
            ?.let { "登录失败：$it 校验未通过。" }
    }

    private fun isCasLoginUrl(url: String): Boolean {
        if (url.contains("cas.bjtu.edu.cn${endpoints.casLoginPath}")) return true
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path == endpoints.casLoginPath || path.startsWith("${endpoints.casLoginPath}/")
    }

    private fun isAaLoginPage(url: String, bodyHead: String): Boolean =
        url.contains("/client/login/") || (bodyHead.contains("用户登录") && bodyHead.contains("教学支撑平台"))

    private fun extractAaClientLoginUrl(html: String): String? =
        Regex("https?://[^\\s\"'<]+/client/login/[^\\s\"'<]+").find(html)?.value

    private fun nowIso(): String =
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString()

    private data class InlineLoginState(
        val loginUrl: String,
        val captchaUrl: String,
        val next: String,
        val csrf: String,
        val captcha0: String,
        val referer: String,
        val cookiesJson: String,
        val captchaImageBase64: String,
        val captchaMimeType: String,
    )
}
