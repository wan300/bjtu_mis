package cn.edu.bjtu.mis.data.provider

import android.util.Base64
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.normalizeSpace
import cn.edu.bjtu.mis.data.security.SecureCookieStore
import cn.edu.bjtu.mis.model.SessionCaptcha
import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.model.SessionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SessionManager(
    private val cookieStore: SecureCookieStore,
    private val cookieJar: AppCookieJar,
    private val httpClient: BjtuHttpClient,
) {
    private val mutex = Mutex()
    private var inlineLoginState: InlineLoginState? = null

    suspend fun fetchInlineLoginCaptcha(): SessionCaptcha = mutex.withLock {
        cookieJar.clear()
        val loginPage = httpClient.getText(ProviderConstants.MIS_HOME_URL)
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
        val dataUrl = "data:$mimeType;base64," + Base64.encodeToString(captcha.body, Base64.NO_WRAP)
        inlineLoginState = state.copy(cookiesJson = cookieJar.encodeSnapshot())
        SessionCaptcha(imageDataUrl = dataUrl, fetchedAt = nowIso())
    }

    suspend fun loginInline(loginName: String, password: String, captcha: String): SessionStatus = mutex.withLock {
        val state = inlineLoginState ?: throw SessionExpiredException("验证码已失效，请刷新后重试。")
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
                "Origin" to "https://cas.bjtu.edu.cn",
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
        inlineLoginState = null
        validateSession()
    }

    suspend fun validateSession(): SessionStatus {
        val payload = cookieStore.load()
            ?: return SessionStatus(SessionState.WaitingForLogin, "未找到可用会话，请先登录。")
        cookieJar.restoreFromJson(payload)
        return runCatching {
            val response = httpClient.getText(ProviderConstants.MIS_HOME_URL)
            val aaReady = ensureAaSessionReady()
            when {
                isCasLoginUrl(response.url) -> SessionStatus(SessionState.Expired, "会话已过期，请重新登录。")
                !aaReady.first -> SessionStatus(SessionState.Expired, aaReady.second)
                aaReady.second != null -> SessionStatus(SessionState.Ready, "会话可用：${aaReady.second}")
                else -> SessionStatus(SessionState.Ready, "会话可用。")
            }
        }.getOrElse { error ->
            SessionStatus(SessionState.Expired, "会话校验失败：${error.message}")
        }
    }

    suspend fun <T> withAuthenticatedClient(block: suspend (BjtuHttpClient) -> T): T {
        val status = validateSession()
        if (status.state != SessionState.Ready) {
            throw SessionExpiredException(status.detail ?: "会话未准备好。")
        }
        return block(httpClient)
    }

    fun logout() {
        inlineLoginState = null
        cookieJar.clear()
        cookieStore.clear()
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
        val response = httpClient.getText(ProviderConstants.AA_TIMETABLE_URL)
        val head = response.body.take(4096)
        when {
            response.url.contains("/client/login/") || isAaLoginPage(response.url, head) -> "login_required" to null
            else -> "ready" to null
        }
    }.getOrElse { "error" to "教学支撑平台会话校验失败：${it.message}" }

    private suspend fun bootstrapAaSession(): Boolean = runCatching {
        val bridge = httpClient.getText(
            ProviderConstants.MIS_AA_BRIDGE_URL,
            headers = mapOf("Referer" to ProviderConstants.MIS_HOME_URL),
        )
        val loginUrl = extractAaClientLoginUrl(bridge.body) ?: return false
        httpClient.getText(loginUrl, headers = mapOf("Referer" to "https://mis.bjtu.edu.cn/"))
        true
    }.getOrDefault(false)

    private suspend fun bootstrapVeSessionBestEffort(): Boolean = runCatching {
        httpClient.getText(
            ProviderConstants.BKSY_VE_BRIDGE_URL,
            headers = mapOf("Referer" to "https://bksy.bjtu.edu.cn/"),
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

    private fun isCasLoginUrl(url: String): Boolean =
        url.contains("cas.bjtu.edu.cn/auth/login")

    private fun isAaLoginPage(url: String, bodyHead: String): Boolean =
        url.contains("/client/login/") || (bodyHead.contains("用户登录") && bodyHead.contains("教学支撑平台"))

    private fun extractAaClientLoginUrl(html: String): String? =
        Regex("https://aa\\.bjtu\\.edu\\.cn/client/login/[^\\s\"'<]+").find(html)?.value

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
    )
}
