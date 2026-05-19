package cn.edu.bjtu.mis.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhixingParserTest {
    @Test
    fun parsePortalLatestPostsAndForumEntrances() {
        val data = parseZhixingHome(text("zhixing_portal.html"), "https://zhixing.bjtu.edu.cn/portal.php")

        assertFalse(data.authState.loggedIn)
        assertEquals(3, data.latestPosts.size)
        assertEquals("1234342", data.latestPosts.first().threadId)
        assertEquals("狗叫", data.latestPosts.first().title)
        assertEquals("漫话水区", data.latestPosts.first().forumName)
        assertEquals("lidongbaoer", data.latestPosts.first().author)
        assertTrue(data.forums.any { it.name == "PT" && it.id == "363" })
    }

    @Test
    fun parseRanklistThreads() {
        val items = parseZhixingRanklist(text("zhixing_ranklist.html"))

        assertEquals(2, items.size)
        assertEquals("1234332", items.first().threadId)
        assertEquals("外债还剩10万了", items.first().title)
        assertEquals("第 1 位", items.first().rankLabel)
    }

    @Test
    fun parseRestrictedThreadAsRestrictedDetail() {
        val detail = parseZhixingThreadDetail(
            text("zhixing_thread_restricted.html"),
            "https://zhixing.bjtu.edu.cn/thread-1234332-1-1.html",
        )

        assertTrue(detail.restricted)
        assertEquals("1234332", detail.threadId)
        assertTrue(detail.message.orEmpty().contains("尚未登录"))
        assertTrue(detail.posts.isEmpty())
    }

    @Test
    fun parseDiscuzLoginErrorsFromXmlCdata() {
        val failed = parseZhixingLoginResponse(
            """<?xml version="1.0" encoding="utf-8"?><root><![CDATA[登录失败，您还可以尝试 4 次<script>ignored()</script>]]></root>"""
        )
        val locked = parseZhixingLoginResponse(
            """<?xml version="1.0" encoding="utf-8"?><root><![CDATA[密码错误次数过多，请 15 分钟后重新登录<script>ignored()</script>]]></root>"""
        )

        assertFalse(failed.success)
        assertTrue(failed.message.contains("登录失败"))
        assertEquals(4, failed.remainingAttempts)
        assertFalse(locked.success)
        assertTrue(locked.message.contains("15 分钟"))
    }

    @Test
    fun parseCaptchaRequiredLoginResponseAndSeccodeUpdate() {
        val response = parseZhixingLoginResponse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <root><![CDATA[
              请输入验证码后继续登录
              <script>location.href='member.php?mod=logging&action=login&auth=abc%2Fdef&referer=https%3A%2F%2Fzhixing.bjtu.edu.cn%2F&cookietime=1'</script>
            ]]></root>
            """.trimIndent()
        )
        val seccode = parseZhixingSeccodeUpdate(
            """
            if(${'$'}('seccode_cSewkoik')) {
              var string = '<input name="seccodehash" type="hidden" value="cSewkoik" />' +
                '<input name="seccodemodid" type="hidden" value="member::logging" />' +
                '<img src="misc.php?mod=seccode&update=24840&idhash=cSewkoik" />';
            }
            """.trimIndent(),
            "https://zhixing.bjtu.edu.cn/member.php",
        )

        assertFalse(response.success)
        assertTrue(response.captchaRequired)
        assertTrue(response.redirectUrl.orEmpty().contains("auth=abc%2Fdef"))
        assertEquals("cSewkoik", seccode.seccodeHash)
        assertEquals("member::logging", seccode.seccodeModId)
        assertEquals("https://zhixing.bjtu.edu.cn/misc.php?mod=seccode&update=24840&idhash=cSewkoik", seccode.imageUrl)
    }

    @Test
    fun parseViewthreadUrlSearchResultsAndThreadDetail() {
        assertEquals("1234283", threadIdFromUrl("https://zhixing.bjtu.edu.cn/forum.php?mod=viewthread&tid=1234283&highlight=12"))

        val search = parseZhixingSearchResults(
            """
            <html><body>
              <ul>
                <li><a href="forum.php?mod=viewthread&amp;tid=1234283&amp;highlight=12">2026年5月12日签到记录贴</a><p>知行足迹 lailaihuang 2026-05-12</p></li>
              </ul>
            </body></html>
            """.trimIndent(),
            "https://zhixing.bjtu.edu.cn/search.php?mod=forum&searchid=25",
            "12",
        )
        val detail = parseZhixingThreadDetail(
            """
            <html>
              <head>
                <title>2026年5月12日签到记录贴 - 知行足迹 - 北京交通大学论坛-知行信息交流平台 - Powered by Discuz!</title>
                <link href="https://zhixing.bjtu.edu.cn/thread-1234283-1-1.html" rel="canonical" />
              </head>
              <body>
                <div id="pt"><a>北京交通大学论坛-知行信息交流平台</a><a>版块列表</a><a>知行专区</a><a>知行足迹</a><em>2026年5月12日签到记录贴</em></div>
                <h1 id="thread_subject">2026年5月12日签到记录贴</h1>
                <table id="pid111">
                  <tr><td class="pls"><a class="xw1">lailaihuang</a></td><td>
                    <div class="authi"><em id="authorposton111">发表于 2026-5-12 00:01</em></div>
                    <td id="postmessage_111">本贴是论坛每日签到系统在每天的第一位签到者签到时所自动生成的</td>
                    <dl class="tattl"><a href="attachment.php?aid=1">sign.png</a> (336.31 KB) 下载附件 保存到相册</dl>
                  </td></tr>
                </table>
              </body>
            </html>
            """.trimIndent(),
            "https://zhixing.bjtu.edu.cn/forum.php?mod=viewthread&tid=1234283&highlight=12",
        )

        assertEquals(1, search.results.size)
        assertEquals("1234283", search.results.first().threadId)
        assertEquals("https://zhixing.bjtu.edu.cn/forum.php?mod=viewthread&tid=1234283&highlight=12", search.results.first().url)
        assertEquals("1234283", detail.threadId)
        assertEquals("知行足迹", detail.forumName)
        assertEquals("https://zhixing.bjtu.edu.cn/thread-1234283-1-1.html", detail.canonicalUrl)
        assertEquals(1, detail.totalPosts)
        assertEquals("lailaihuang", detail.posts.first().author)
        assertTrue(detail.posts.first().content.contains("每日签到系统"))
        assertEquals("sign.png", detail.attachments.first().name)
        assertEquals("336.31 KB", detail.attachments.first().size)
    }

    @Test
    fun parseThreadContentBlocksImagesAndFloors() {
        val detail = parseZhixingThreadDetail(
            """
            <html>
              <head><title>图文帖 - 漫话水区 - 北京交通大学论坛-知行信息交流平台 - Powered by Discuz!</title></head>
              <body>
                <div id="pt"><a>论坛</a><a>版块列表</a><a>漫话水区</a><em>图文帖</em></div>
                <h1 id="thread_subject">图文帖</h1>
                <table id="pid1">
                  <tr>
                    <td class="pls"><a class="xw1">alice</a></td>
                    <td>
                      <div class="pi"><strong><a href="forum.php?mod=redirect&goto=findpost&pid=1">#1</a></strong></div>
                      <div class="authi"><em id="authorposton1">发表于 2026-5-12 00:01</em></div>
                      <td id="postmessage_1">第一段<br><img file="data/attachment/forum/pic.jpg" alt="配图" />第二段</td>
                    </td>
                  </tr>
                </table>
                <table id="pid2">
                  <tr>
                    <td class="pls"><a class="xw1">bob</a></td>
                    <td>
                      <div class="pi"><strong><a href="forum.php?mod=redirect&goto=findpost&pid=2">#2</a></strong></div>
                      <div class="authi"><em id="authorposton2">发表于 2026-5-12 00:02</em></div>
                      <td id="postmessage_2">二楼回复</td>
                    </td>
                  </tr>
                </table>
              </body>
            </html>
            """.trimIndent(),
            "https://zhixing.bjtu.edu.cn/thread-1234999-1-1.html",
        )

        assertEquals(2, detail.posts.size)
        assertEquals("#1", detail.posts.first().floor)
        assertEquals("2026-5-12 00:01", detail.posts.first().postedAt)
        assertTrue(detail.posts.first().content.contains("第一段"))
        assertTrue(detail.posts.first().content.contains("第二段"))
        val image = detail.posts.first().contentBlocks.first { it.type.name == "Image" }
        assertEquals("https://zhixing.bjtu.edu.cn/data/attachment/forum/pic.jpg", image.imageUrl)
        assertEquals("配图", image.alt)
        assertEquals("#2", detail.posts[1].floor)
        assertEquals("bob", detail.posts[1].author)
        assertEquals("二楼回复", detail.posts[1].content)
    }

    private fun text(name: String): String {
        val resource = javaClass.getResource("/fixtures/$name")
            ?: error("Missing test fixture: $name")
        return resource.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
