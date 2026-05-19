package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.isZhixingRestricted
import cn.edu.bjtu.mis.data.parser.parseZhixingAuthState
import cn.edu.bjtu.mis.data.parser.parseZhixingCaptchaForm
import cn.edu.bjtu.mis.data.parser.parseZhixingFormHash
import cn.edu.bjtu.mis.data.parser.parseZhixingForumEntries
import cn.edu.bjtu.mis.data.parser.parseZhixingHome
import cn.edu.bjtu.mis.data.parser.parseZhixingLoginResponse
import cn.edu.bjtu.mis.data.parser.parseZhixingRanklist
import cn.edu.bjtu.mis.data.parser.parseZhixingSearchResults
import cn.edu.bjtu.mis.data.parser.parseZhixingSeccodeUpdate
import cn.edu.bjtu.mis.data.parser.parseZhixingThreadDetail
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.ZhixingAuthState
import cn.edu.bjtu.mis.model.ZhixingHomeData
import cn.edu.bjtu.mis.model.ZhixingLoginChallenge
import cn.edu.bjtu.mis.model.ZhixingLoginOutcome
import cn.edu.bjtu.mis.model.ZhixingLoginStatus
import cn.edu.bjtu.mis.model.ZhixingRankItem
import cn.edu.bjtu.mis.model.ZhixingSearchData
import cn.edu.bjtu.mis.model.ZhixingThreadDetail
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class ZhixingAuthException(message: String) : IOException(message)

class ZhixingProvider(
    private val client: BjtuHttpClient,
    private val baseUrl: String = ProviderConstants.ZHIXING_BASE_URL,
) {
    suspend fun fetchHome(): ModuleEnvelope<ZhixingHomeData> {
        val portal = getText("/portal.php")
        val home = parseZhixingHome(portal.body, portal.url)
        val rankItems = runCatching { fetchRankItems() }.getOrDefault(emptyList())
        val navForums = runCatching {
            parseZhixingForumEntries(
                getText(
                    "/forum.php",
                    params = mapOf(
                        "mod" to "misc",
                        "action" to "nav",
                        "special" to "0",
                        "infloat" to "yes",
                        "handlekey" to "nav",
                        "inajax" to "1",
                        "ajaxtarget" to "fwin_content_nav",
                    ),
                ).body,
                "$baseUrl/forum.php",
            )
        }.getOrDefault(emptyList())
        val groupForums = runCatching {
            parseZhixingForumEntries(getText("/forum.php", params = mapOf("gid" to "363")).body, "$baseUrl/forum.php?gid=363")
        }.getOrDefault(emptyList())
        val forums = (home.forums + navForums + groupForums).distinctBy { it.id }
        return ModuleEnvelope(
            module = ModuleKeys.Zhixing,
            sourceSystem = "zhixing",
            coverage = CoverageLevel.Provisional,
            data = home.copy(rankItems = rankItems, forums = forums),
        )
    }

    suspend fun fetchRanklist(): ModuleEnvelope<ZhixingHomeData> {
        val rankItems = fetchRankItems()
        return ModuleEnvelope(
            module = ModuleKeys.Zhixing,
            sourceSystem = "zhixing",
            coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject { put("view", "ranklist") },
            data = ZhixingHomeData(rankItems = rankItems),
        )
    }

    suspend fun fetchThread(threadId: String, page: Int = 1): ModuleEnvelope<ZhixingThreadDetail> {
        val normalizedPage = page.coerceAtLeast(1)
        val path = "/thread-${threadId.trim()}-$normalizedPage-1.html"
        return fetchThreadUrl(path, threadId, normalizedPage)
    }

    suspend fun fetchThreadUrl(threadUrl: String, threadId: String, page: Int = 1): ModuleEnvelope<ZhixingThreadDetail> {
        val response = getText(threadUrl)
        val detail = parseZhixingThreadDetail(response.body, response.url, threadId)
        return ModuleEnvelope(
            module = "zhixing_thread",
            sourceSystem = "zhixing",
            coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("thread_id", detail.threadId.ifBlank { threadId })
                put("page", page.coerceAtLeast(1))
            },
            data = detail,
        )
    }

    suspend fun fetchImage(imageUrl: String, referer: String): ByteArray =
        client.getBytes(
            resolveZhixingUrl(imageUrl),
            headers = mapOf("Referer" to referer.ifBlank { "$baseUrl/" }),
        ).body

    suspend fun search(keyword: String, page: Int = 1): ModuleEnvelope<ZhixingSearchData> {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isBlank()) {
            return ModuleEnvelope(
                module = "zhixing_search",
                sourceSystem = "zhixing",
                coverage = CoverageLevel.Provisional,
                data = ZhixingSearchData(keyword = normalizedKeyword),
            )
        }
        val home = getText("/")
        val formhash = parseZhixingFormHash(home.body, home.url).orEmpty()
        val response = client.postForm(
            url("/search.php"),
            params = mapOf("searchsubmit" to "yes"),
            form = mapOf(
                "formhash" to formhash,
                "mod" to "forum",
                "searchsubmit" to "true",
                "srchtxt" to normalizedKeyword,
                "srchtype" to "title",
                "srhfid" to "0",
                "srhlocality" to "portal::index",
            ),
            headers = mapOf(
                "Referer" to home.url,
                "Origin" to baseUrl,
            ),
        )
        return ModuleEnvelope(
            module = "zhixing_search",
            sourceSystem = "zhixing",
            coverage = CoverageLevel.Provisional,
            sourceParams = buildJsonObject {
                put("keyword", normalizedKeyword)
                put("page", page.coerceAtLeast(1))
            },
            data = parseZhixingSearchResults(response.body, response.url, normalizedKeyword),
        )
    }

    suspend fun authState(): ZhixingAuthState {
        val response = getText("/")
        val state = parseZhixingAuthState(response.body, response.url)
        return if (state.loggedIn || !isZhixingRestricted(state.message.orEmpty())) {
            state
        } else {
            ZhixingAuthState(loggedIn = false, message = state.message ?: "未登录")
        }
    }

    suspend fun login(username: String, password: String): ZhixingLoginOutcome {
        val normalizedUsername = username.trim()
        val response = client.postForm(
            url("/member.php"),
            params = mapOf(
                "mod" to "logging",
                "action" to "login",
                "loginsubmit" to "yes",
                "infloat" to "yes",
                "lssubmit" to "yes",
                "inajax" to "1",
            ),
            form = mapOf(
                "username" to normalizedUsername,
                "password" to md5Hex(password),
                "cookietime" to "2592000",
                "quickforward" to "yes",
                "handlekey" to "ls",
            ),
            headers = mapOf(
                "Referer" to "$baseUrl/",
                "Origin" to baseUrl,
            ),
        )
        val result = parseZhixingLoginResponse(response.body)
        if (result.success) {
            return validateLogin(username = normalizedUsername, fallbackMessage = result.message)
        }
        if (result.captchaRequired && !result.redirectUrl.isNullOrBlank()) {
            return buildCaptchaOutcome(result.redirectUrl, result.message)
        }
        return ZhixingLoginOutcome(
            status = ZhixingLoginStatus.Failure,
            message = result.message,
            remainingAttempts = result.remainingAttempts,
        )
    }

    suspend fun submitLoginCaptcha(challenge: ZhixingLoginChallenge, answer: String): ZhixingLoginOutcome {
        val normalizedAnswer = answer.trim()
        val check = getText(
            "/misc.php",
            params = mapOf(
                "mod" to "seccode",
                "action" to "check",
                "inajax" to "1",
                "modid" to challenge.seccodeModId,
                "idhash" to challenge.seccodeHash,
                "secverify" to normalizedAnswer,
            ),
        )
        if (!check.body.contains("succeed", ignoreCase = true)) {
            return ZhixingLoginOutcome(
                status = ZhixingLoginStatus.Failure,
                message = "验证码错误，请重新输入。",
            )
        }
        val response = client.postForm(
            url("/member.php"),
            params = mapOf(
                "mod" to "logging",
                "action" to "login",
                "loginsubmit" to "yes",
                "loginhash" to challenge.loginhash,
                "inajax" to "1",
            ),
            form = mapOf(
                "formhash" to challenge.formhash,
                "referer" to challenge.referer,
                "auth" to challenge.auth,
                "cookietime" to "2592000",
                "seccodehash" to challenge.seccodeHash,
                "seccodemodid" to challenge.seccodeModId,
                "seccodeverify" to normalizedAnswer,
            ),
            headers = mapOf(
                "Referer" to challenge.referer.ifBlank { "$baseUrl/" },
                "Origin" to baseUrl,
            ),
        )
        val result = parseZhixingLoginResponse(response.body)
        return if (result.success) {
            validateLogin(fallbackMessage = result.message)
        } else {
            ZhixingLoginOutcome(
                status = ZhixingLoginStatus.Failure,
                message = result.message,
                remainingAttempts = result.remainingAttempts,
            )
        }
    }

    fun logout() {
        client.cookieJar.clearForDomain("zhixing.bjtu.edu.cn")
    }

    private suspend fun buildCaptchaOutcome(redirectUrl: String, message: String): ZhixingLoginOutcome {
        val challengePage = getText(resolveZhixingUrl(redirectUrl))
        val form = parseZhixingCaptchaForm(challengePage.body, challengePage.url)
        val update = getText(
            "/misc.php",
            params = mapOf(
                "mod" to "seccode",
                "action" to "update",
                "idhash" to form.seccodeHash,
                "modid" to form.seccodeModId,
            ),
        )
        val seccode = parseZhixingSeccodeUpdate(update.body, update.url)
        val image = client.getBytes(
            seccode.imageUrl,
            headers = mapOf("Referer" to challengePage.url),
        )
        val mimeType = image.headers["Content-Type"]?.substringBefore(";")?.trim().orEmpty()
            .ifBlank { "image/png" }
        val imageDataUrl = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(image.body)}"
        return ZhixingLoginOutcome(
            status = ZhixingLoginStatus.CaptchaRequired,
            message = message,
            challenge = ZhixingLoginChallenge(
                challengeId = "${seccode.seccodeHash}-${System.currentTimeMillis()}",
                auth = form.auth,
                formhash = form.formhash,
                loginhash = form.loginhash,
                referer = form.referer.ifBlank { "$baseUrl/" },
                seccodeHash = seccode.seccodeHash,
                seccodeModId = seccode.seccodeModId,
                imageDataUrl = imageDataUrl,
                message = message,
            ),
        )
    }

    private suspend fun validateLogin(username: String? = null, fallbackMessage: String): ZhixingLoginOutcome {
        val state = authState()
        return if (state.loggedIn) {
            ZhixingLoginOutcome(
                status = ZhixingLoginStatus.Success,
                authState = state.copy(username = username?.takeIf { it.isNotBlank() } ?: state.username),
                message = fallbackMessage.takeIf { it.isNotBlank() } ?: "知行登录成功。",
            )
        } else {
            ZhixingLoginOutcome(
                status = ZhixingLoginStatus.Failure,
                message = fallbackMessage.takeIf { it.isNotBlank() } ?: state.message ?: "知行登录失败。",
            )
        }
    }

    private suspend fun fetchRankItems(): List<ZhixingRankItem> {
        val rank = getText("/misc.php", params = mapOf("mod" to "ranklist"))
        return parseZhixingRanklist(rank.body, rank.url)
    }

    private suspend fun getText(path: String, params: Map<String, String?> = emptyMap()) =
        client.getText(url(path), params = params, headers = mapOf("Referer" to "$baseUrl/"))

    private fun url(path: String): String =
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            path
        } else {
            "$baseUrl/${path.trimStart('/')}"
        }

    private fun resolveZhixingUrl(href: String): String =
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            href
        } else {
            url(href)
        }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
