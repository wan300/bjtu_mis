package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.model.ZhixingLoginStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhixingProviderTest {
    @Test
    fun fastLoginPostsDiscuzFieldsAndValidatesRootWithCookie() = runBlocking {
        MockWebServer().use { server ->
            var loginPostBody = ""
            var rootCookie = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/member.php" && request.method == "POST") {
                        loginPostBody = request.body.readUtf8()
                        return html(
                            """<?xml version="1.0" encoding="utf-8"?><root><![CDATA[欢迎您回来，alice]]></root>"""
                        ).setHeader("Set-Cookie", "zhixing_9328_auth=ok; Path=/")
                    }
                    if (path == "/") {
                        rootCookie = request.getHeader("Cookie").orEmpty()
                        return html(
                            """
                            <html><head><script>var discuz_uid = '42';</script></head><body>
                              <div id="um"><a class="vwmy">alice</a><a href="member.php?mod=logging&action=logout">退出</a></div>
                            </body></html>
                            """.trimIndent()
                        )
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val provider = provider(server)

            val outcome = provider.login("alice", "secret")

            assertEquals(ZhixingLoginStatus.Success, outcome.status)
            assertEquals("alice", outcome.authState?.username)
            assertTrue(loginPostBody.contains("username=alice"))
            assertTrue(loginPostBody.contains("password=5ebe2294ecd0e0f08eab7690d2a6ee69"))
            assertTrue(loginPostBody.contains("quickforward=yes"))
            assertTrue(loginPostBody.contains("handlekey=ls"))
            assertTrue(rootCookie.contains("zhixing_9328_auth=ok"))
        }
    }

    @Test
    fun fastLoginFailureReturnsRemainingAttempts() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                html("""<?xml version="1.0" encoding="utf-8"?><root><![CDATA[登录失败，您还可以尝试 4 次]]></root>""")
            )
            val provider = provider(server)

            val outcome = provider.login("alice", "bad")

            assertEquals(ZhixingLoginStatus.Failure, outcome.status)
            assertEquals(4, outcome.remainingAttempts)
            assertTrue(outcome.message.orEmpty().contains("登录失败"))
        }
    }

    @Test
    fun captchaLoginBuildsChallengeAndSubmitsSecondStep() = runBlocking {
        MockWebServer().use { server ->
            val paths = mutableListOf<String>()
            var captchaPostBody = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths += request.path.orEmpty()
                    if (path == "/member.php" && request.method == "POST" && request.requestUrl?.queryParameter("lssubmit") == "yes") {
                        return html(
                            """
                            <?xml version="1.0" encoding="utf-8"?>
                            <root><![CDATA[
                              请输入验证码后继续登录
                              <script>location.href='member.php?mod=logging&action=login&auth=token123&referer=${server.url("/")}&cookietime=1'</script>
                            ]]></root>
                            """.trimIndent()
                        )
                    }
                    if (path == "/member.php" && request.method == "GET") {
                        return html(
                            """
                            <html><body>
                              <form id="loginform_LL5Uo" action="${server.url("/member.php?mod=logging&action=login&loginsubmit=yes&loginhash=LL5Uo")}" method="post">
                                <input type="hidden" name="formhash" value="fh456" />
                                <input type="hidden" name="referer" value="${server.url("/")}" />
                                <input type="hidden" name="auth" value="token123" />
                                <input type="checkbox" name="cookietime" value="2592000" checked="checked" />
                                <span id="seccode_cSewkoik"></span>
                              </form>
                            </body></html>
                            """.trimIndent()
                        )
                    }
                    if (path == "/misc.php" && request.requestUrl?.queryParameter("action") == "update") {
                        return html(
                            """
                            var string = '<input name="seccodehash" type="hidden" value="cSewkoik" />' +
                              '<input name="seccodemodid" type="hidden" value="member::logging" />' +
                              '<img src="misc.php?mod=seccode&update=24840&idhash=cSewkoik" />';
                            """.trimIndent()
                        )
                    }
                    if (path == "/misc.php" && request.requestUrl?.queryParameter("update") == "24840") {
                        return MockResponse()
                            .setHeader("Content-Type", "image/png")
                            .setBody("png")
                    }
                    if (path == "/misc.php" && request.requestUrl?.queryParameter("action") == "check") {
                        assertEquals("ETTT", request.requestUrl?.queryParameter("secverify"))
                        return html("""<?xml version="1.0" encoding="utf-8"?><root><![CDATA[succeed]]></root>""")
                    }
                    if (path == "/member.php" && request.method == "POST" && request.requestUrl?.queryParameter("loginhash") == "LL5Uo") {
                        captchaPostBody = request.body.readUtf8()
                        return html(
                            """<?xml version="1.0" encoding="utf-8"?><root><![CDATA[欢迎您回来，<font color="#229405">新手会员</font> alice]]></root>"""
                        ).setHeader("Set-Cookie", "zhixing_9328_auth=ok; Path=/")
                    }
                    if (path == "/") {
                        return html("""<html><script>var discuz_uid = '42';</script><body><a class="vwmy">alice</a><a>退出</a></body></html>""")
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val provider = provider(server)

            val challengeOutcome = provider.login("alice", "secret")
            val challenge = challengeOutcome.challenge!!
            val success = provider.submitLoginCaptcha(challenge, "ETTT")

            assertEquals(ZhixingLoginStatus.CaptchaRequired, challengeOutcome.status)
            assertTrue(challenge.imageDataUrl.startsWith("data:image/png;base64,"))
            assertEquals("token123", challenge.auth)
            assertEquals("fh456", challenge.formhash)
            assertEquals("LL5Uo", challenge.loginhash)
            assertEquals("cSewkoik", challenge.seccodeHash)
            assertEquals(ZhixingLoginStatus.Success, success.status)
            assertTrue(captchaPostBody.contains("auth=token123"))
            assertTrue(captchaPostBody.contains("seccodeverify=ETTT"))
            assertTrue(paths.any { it.contains("action=check") })
        }
    }

    @Test
    fun captchaCheckFailureRefreshesChallengeAndDoesNotSubmitSecondStep() = runBlocking {
        MockWebServer().use { server ->
            var loginPostCount = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/misc.php" && request.requestUrl?.queryParameter("action") == "check") {
                        return html("""<?xml version="1.0" encoding="utf-8"?><root><![CDATA[invalid]]></root>""")
                    }
                    if (path == "/misc.php" && request.requestUrl?.queryParameter("action") == "update") {
                        return html(
                            """
                            var string = '<input name="seccodehash" type="hidden" value="newhash" />' +
                              '<input name="seccodemodid" type="hidden" value="member::logging" />' +
                              '<img src="misc.php?mod=seccode&update=99&idhash=newhash" />';
                            """.trimIndent()
                        )
                    }
                    if (path == "/misc.php" && request.requestUrl?.queryParameter("update") == "99") {
                        return MockResponse()
                            .setHeader("Content-Type", "image/png")
                            .setBody("newpng")
                    }
                    if (path == "/member.php" && request.method == "POST") {
                        loginPostCount += 1
                        return MockResponse().setResponseCode(500)
                    }
                    return MockResponse().setResponseCode(500)
                }
            }
            val provider = provider(server)

            val outcome = provider.submitLoginCaptcha(
                cn.edu.bjtu.mis.model.ZhixingLoginChallenge(
                    challengeId = "c1",
                    auth = "auth",
                    formhash = "fh",
                    loginhash = "LH",
                    referer = server.url("/").toString(),
                    seccodeHash = "hash",
                    seccodeModId = "member::logging",
                    imageDataUrl = "data:image/png;base64,cG5n",
                ),
                "BAD",
            )

            assertEquals(ZhixingLoginStatus.CaptchaRequired, outcome.status)
            assertEquals("newhash", outcome.challenge?.seccodeHash)
            assertTrue(outcome.challenge?.imageDataUrl.orEmpty().startsWith("data:image/png;base64,"))
            assertEquals(0, loginPostCount)
        }
    }

    @Test
    fun searchPostsAndFetchViewthreadUrl() = runBlocking {
        MockWebServer().use { server ->
            var searchPostBody = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/") {
                        return html("""<html><body><form><input name="formhash" value="fh999" /></form></body></html>""")
                    }
                    if (path == "/search.php" && request.method == "POST") {
                        searchPostBody = request.body.readUtf8()
                        return MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", server.url("/search.php?mod=forum&searchid=25&orderby=lastpost&ascdesc=desc&searchsubmit=yes&kw=12"))
                    }
                    if (path == "/search.php" && request.method == "GET") {
                        return html(
                            """
                            <html><body>
                              <li><a href="forum.php?mod=viewthread&amp;tid=1234283&amp;highlight=12">2026年5月12日签到记录贴</a><p>知行足迹 lailaihuang 2026-05-12</p></li>
                            </body></html>
                            """.trimIndent()
                        )
                    }
                    if (path == "/forum.php") {
                        return html(
                            """
                            <html><head><link rel="canonical" href="${server.url("/thread-1234283-1-1.html")}" /></head>
                            <body><div id="pt"><a>论坛</a><a>版块列表</a><a>知行专区</a><a>知行足迹</a><em>2026年5月12日签到记录贴</em></div>
                            <h1 id="thread_subject">2026年5月12日签到记录贴</h1><table id="pid1"><tr><td class="pls"><a class="xw1">alice</a></td><td id="postmessage_1">正文内容</td></tr></table></body></html>
                            """.trimIndent()
                        )
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val provider = provider(server)

            val search = provider.search("12")
            val detail = provider.fetchThreadUrl(search.data.results.first().url, search.data.results.first().threadId)

            assertTrue(searchPostBody.contains("formhash=fh999"))
            assertTrue(searchPostBody.contains("srchtxt=12"))
            assertEquals("1234283", search.data.results.first().threadId)
            assertEquals("知行足迹", detail.data.forumName)
            assertEquals("alice", detail.data.posts.first().author)
        }
    }

    @Test
    fun fetchImageUsesRefererAndAuthenticatedCookies() = runBlocking {
        MockWebServer().use { server ->
            var imageReferer = ""
            var imageCookie = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/member.php" && request.method == "POST") {
                        return html("""<?xml version="1.0" encoding="utf-8"?><root><![CDATA[欢迎您回来，alice]]></root>""")
                            .setHeader("Set-Cookie", "zhixing_9328_auth=ok; Path=/")
                    }
                    if (path == "/") {
                        return html("""<html><script>var discuz_uid = '42';</script><body><a class="vwmy">alice</a><a>退出</a></body></html>""")
                    }
                    if (path == "/data/attachment/forum/pic.jpg") {
                        imageReferer = request.getHeader("Referer").orEmpty()
                        imageCookie = request.getHeader("Cookie").orEmpty()
                        return MockResponse()
                            .setHeader("Content-Type", "image/jpeg")
                            .setBody("jpg")
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val provider = provider(server)
            val referer = server.url("/thread-1234999-1-1.html").toString()

            val login = provider.login("alice", "secret")
            val image = provider.fetchImage(server.url("/data/attachment/forum/pic.jpg").toString(), referer)

            assertEquals(ZhixingLoginStatus.Success, login.status)
            assertEquals("jpg", image.toString(Charsets.UTF_8))
            assertEquals(referer, imageReferer)
            assertTrue(imageCookie.contains("zhixing_9328_auth=ok"))
        }
    }

    @Test
    fun fetchRestrictedThreadReturnsRestrictedDetail() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/thread-1234332-1-1.html") {
                        return html(
                            """
                            <html><head><title>提示信息 - 北京交通大学论坛-知行信息交流平台 - Powered by Discuz!</title></head>
                            <body><p>抱歉，您尚未登录，没有权限访问该版块</p></body></html>
                            """.trimIndent()
                        )
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            val provider = provider(server)

            val detail = provider.fetchThread("1234332")

            assertTrue(detail.data.restricted)
            assertTrue(detail.data.message.orEmpty().contains("尚未登录"))
            assertEquals("1234332", detail.data.threadId)
        }
    }

    private fun provider(server: MockWebServer): ZhixingProvider =
        ZhixingProvider(
            client = BjtuHttpClient(AppCookieJar()),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )

    private fun html(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody(body)
}
