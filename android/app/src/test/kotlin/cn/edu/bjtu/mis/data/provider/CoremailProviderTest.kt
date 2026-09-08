package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.MailAttachment
import cn.edu.bjtu.mis.model.MailComposeAttachment
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailMessageDetail
import cn.edu.bjtu.mis.model.ModuleEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class CoremailProviderTest {
    private val sid = "MAIL-SID"

    @Test
    fun foldersAndMessagesUseSidAndParsePayloads() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/coremail/XT/jsp/mail.jsp") == true) {
                        assertEquals("getAllFolders", request.requestUrl?.queryParameter("func"))
                        assertEquals(sid, request.requestUrl?.queryParameter("sid"))
                        assertEquals("stats=true&threads=false", request.body.readUtf8())
                        return json(
                            """
                            {
                              "code": "S_OK",
                              "var": [{
                                "id": 1,
                                "name": "收件箱",
                                "flags": {"system": true},
                                "stats": {"messageCount": 8, "unreadMessageCount": 3, "messageSize": 1024, "unreadMessageSize": 512}
                              }]
                            }
                            """.trimIndent()
                        )
                    }
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "mbox:listMessages"
                    ) {
                        assertEquals(sid, request.requestUrl?.queryParameter("sid"))
                        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("text/x-json"))
                        val payload = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                        assertEquals("1", payload["fid"]?.jsonPrimitive?.content)
                        assertEquals("5", payload["start"]?.jsonPrimitive?.content)
                        assertEquals("10", payload["limit"]?.jsonPrimitive?.content)
                        return json(
                            """
                            {
                              "code": "S_OK",
                              "total": 1,
                              "var": [{
                                "id": "2:abc+",
                                "fid": 1,
                                "from": "Alice <alice@example.edu>",
                                "to": "Bob <bob@example.edu>",
                                "sender": "alice@example.edu",
                                "subject": "hello",
                                "sentDate": "2026-05-10 10:00:00",
                                "receivedDate": "2026-05-10 10:00:01",
                                "modifiedDate": "2026-05-10 10:00:02",
                                "size": 123,
                                "priority": 3,
                                "summary": "preview",
                                "flags": {"read": false, "attached": true}
                              }]
                            }
                            """.trimIndent()
                        )
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            val folders = provider.fetchFolders()
            val messages = provider.fetchMessages(folderId = "1", start = 5, limit = 10)

            assertEquals("收件箱", folders.data.folders.single().name)
            assertEquals(3, folders.data.folders.single().unreadCount)
            assertEquals(1, messages.data.total)
            assertEquals("2:abc+", messages.data.messages.single().messageId)
            assertTrue(messages.data.messages.single().attached)
        }
    }

    @Test
    fun detailAndAttachmentDownloadParsePayloads() = runBlocking {
        MockWebServer().use { server ->
            val target = File.createTempFile("mail-attachment", ".txt")
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.path == "/coremail/XT/jsp/readMessage.jsp") {
                        val form = request.body.readUtf8()
                        assertTrue(form.contains("mid=2%3Aabc%2B"))
                        return json(
                            """
                            {
                              "code": "S_OK",
                              "var": {
                                "mail": {
                                  "from": ["Alice <alice@example.edu>"],
                                  "to": ["Bob <bob@example.edu>"],
                                  "cc": ["Carol <carol@example.edu>"],
                                  "headers": {"From": "Alice <alice@example.edu>"},
                                  "attachments": [{"id": "3", "filename": "report.txt", "contentType": "text/plain", "contentLength": 11, "contentId": "<report-content>"}],
                                  "mainPartData": {"content": "<p>mail body</p>"}
                                },
                                "mailInfo": {
                                  "id": "2:abc+",
                                  "fid": 1,
                                  "from": "Alice <alice@example.edu>",
                                  "to": "Bob <bob@example.edu>",
                                  "subject": "detail subject",
                                  "size": 456,
                                  "flags": {"read": true, "attached": true}
                                }
                              }
                            }
                            """.trimIndent()
                        )
                    }
                    if (request.requestUrl?.encodedPath == "/coremail/mbox-data/report.txt") {
                        assertEquals("3", request.requestUrl?.queryParameter("part"))
                        assertEquals("2:abc+", request.requestUrl?.queryParameter("mid"))
                        return MockResponse()
                            .setHeader("Content-Type", "text/plain")
                            .setBody("hello world")
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            val detail = provider.fetchMessageDetail("2:abc+")
            provider.downloadAttachment("2:abc+", "3", "report.txt", target)

            assertEquals("<p>mail body</p>", detail.data.htmlContent)
            assertEquals("3", detail.data.attachments.single().attachmentId)
            assertEquals("report-content", detail.data.attachments.single().contentId)
            assertEquals("hello world", target.readText())
        }
    }

    @Test
    fun inlineImageHydrationUsesCoremailSessionForSameOriginAndCidImages() = runBlocking {
        MockWebServer().use { server ->
            val imageRequests = AtomicInteger()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    return when (request.requestUrl?.encodedPath) {
                        "/coremail/mbox-data/banner.png" -> {
                            imageRequests.incrementAndGet()
                            assertEquals("MAIL-SID", request.getHeader("Referer")?.substringAfter("sid="))
                            MockResponse()
                                .setHeader("Content-Type", "image/png; charset=binary")
                                .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3)))
                        }

                        "/coremail/mbox-data/inline.png" -> {
                            imageRequests.incrementAndGet()
                            assertEquals("inline-part", request.requestUrl?.queryParameter("part"))
                            assertEquals("message-1", request.requestUrl?.queryParameter("mid"))
                            assertEquals("download", request.requestUrl?.queryParameter("mode"))
                            MockResponse()
                                .setHeader("Content-Type", "application/octet-stream")
                                .setBody(okio.Buffer().write(byteArrayOf(4, 5, 6)))
                        }

                        else -> unexpected(request)
                    }
                }
            }
            val provider = provider(server)

            val hydrated = provider.hydrateInlineImages(
                mailEnvelope(
                    html = """
                        <img src="/coremail/mbox-data/banner.png?part=main">
                        <img src="CID:&lt;inline-image&gt;">
                        <img src="https://images.example.test/tracker.png">
                        <img src="data:image/png;base64,AA==">
                    """.trimIndent(),
                    attachments = listOf(
                        MailAttachment(
                            attachmentId = "inline-part",
                            filename = "inline.png",
                            contentType = "image/png",
                            part = "inline-part",
                            contentId = "inline-image",
                        )
                    ),
                )
            )

            val images = Jsoup.parse(hydrated.data.htmlContent).select("img")
            assertEquals("data:image/png;base64,AQID", images[0].attr("src"))
            assertEquals("data:image/png;base64,BAUG", images[1].attr("src"))
            assertEquals("https://images.example.test/tracker.png", images[2].attr("src"))
            assertEquals("data:image/png;base64,AA==", images[3].attr("src"))
            assertEquals(2, imageRequests.get())
        }
    }

    @Test
    fun inlineImageHydrationLeavesUnsafeAndFailedImageSourcesUnchanged() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return when (request.requestUrl?.encodedPath) {
                        "/coremail/mbox-data/fail.png" -> MockResponse().setResponseCode(500)
                        "/coremail/mbox-data/not-image.png" -> MockResponse()
                            .setHeader("Content-Type", "text/html")
                            .setBody("not an image")

                        else -> unexpected(request)
                    }
                }
            }
            val provider = provider(server)
            val differentPort = server.port + 1
            val hydrated = provider.hydrateInlineImages(
                mailEnvelope(
                    """
                    <img src="https://images.example.test/tracker.png">
                    <img src="http://localhost:$differentPort/coremail/mbox-data/other.png">
                    <img src="/not-coremail/logo.png">
                    <img src="/coremail/mbox-data/fail.png?part=1">
                    <img src="/coremail/mbox-data/not-image.png?part=2">
                    <img src="cid:missing-image">
                    """.trimIndent()
                )
            )

            val images = Jsoup.parse(hydrated.data.htmlContent).select("img")
            assertEquals("https://images.example.test/tracker.png", images[0].attr("src"))
            assertEquals("http://localhost:$differentPort/coremail/mbox-data/other.png", images[1].attr("src"))
            assertEquals("/not-coremail/logo.png", images[2].attr("src"))
            assertEquals("/coremail/mbox-data/fail.png?part=1", images[3].attr("src"))
            assertEquals("/coremail/mbox-data/not-image.png?part=2", images[4].attr("src"))
            assertEquals("cid:missing-image", images[5].attr("src"))
            assertEquals(2, requests.get())
        }
    }

    @Test
    fun inlineImageHydrationLoadsImageLargerThanTwoMiB() = runBlocking {
        MockWebServer().use { server ->
            val imageSize = 2 * 1024 * 1024 + 1
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/coremail/mbox-data/large.png") {
                        return MockResponse()
                            .setHeader("Content-Type", "image/png")
                            .setBody(okio.Buffer().write(ByteArray(imageSize)))
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)
            val hydrated = provider.hydrateInlineImages(
                mailEnvelope("<img src=\"/coremail/mbox-data/large.png?part=1\">")
            )

            val source = Jsoup.parse(hydrated.data.htmlContent).selectFirst("img")!!.attr("src")
            assertTrue(source.startsWith("data:image/png;base64,"))
            assertEquals(imageSize, java.util.Base64.getDecoder().decode(source.substringAfter(',')).size)
        }
    }

    @Test
    fun inlineImageHydrationRejectsApiPathsAndUntrustedDownloadParameters() = runBlocking {
        MockWebServer().use { server ->
            val sources = listOf(
                "/coremail/s/json?func=user:logout",
                "/coremail/XT/jsp/readMessage.jsp?mid=message-1",
                "/coremail/mbox-data/../s/json?part=1",
                "/coremail/mbox-data/%2e%2e/s/json?part=1",
                "/coremail/mbox-data-extra/image.png?part=1",
                "/coremail/mbox-data/path/image.png?part=1",
                "/coremail/mbox-data/%2Fimage.png?part=1",
                "/coremail/mbox-data/%252Fimage.png?part=1",
                "/coremail/mbox-data/image.png;action=delete?part=1",
                "/coremail/mbox-data/image.png",
                "/coremail/mbox-data/image.png?part=",
                "/coremail/mbox-data/image.png?part=1&part=2",
                "/coremail/mbox-data/image.png?part=1&mid=other-message",
                "/coremail/mbox-data/image.png?part=1&mid=message-1&mid=other-message",
                "/coremail/mbox-data/image.png?part=1&mode=delete",
                "/coremail/mbox-data/image.png?part=1&func=delete",
                "/coremail/mbox-data/image.png?part=1&sid=UNTRUSTED-SID",
                "/coremail/mbox-data/image.png?part=1&redirect=https://example.test/",
                server.url("/coremail/mbox-data/image.png?part=1")
                    .newBuilder().username("untrusted").build().toString(),
            )
            val html = sources.joinToString("") { "<img src=\"$it\">" }

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            assertEquals(html, hydrated.data.htmlContent)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun inlineImageHydrationReconstructsDownloadUrlForCurrentMessage() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    assertEquals("GET", request.method)
                    assertEquals(setOf("part", "mid", "mode"), request.requestUrl?.queryParameterNames)
                    assertEquals("message-1", request.requestUrl?.queryParameter("mid"))
                    assertEquals("download", request.requestUrl?.queryParameter("mode"))
                    return MockResponse().setHeader("Content-Type", "image/png").setBody("image")
                }
            }
            val html = """
                <img src="/coremail/mbox-data?part=1">
                <img src="/coremail/mbox-data/image.png?mid=message-1&part=2&mode=download#ignored">
            """.trimIndent()

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            assertEquals(2, requests.get())
            assertTrue(Jsoup.parse(hydrated.data.htmlContent).select("img").all {
                it.attr("src").startsWith("data:image/png;base64,")
            })
        }
    }

    @Test
    fun inlineImageHydrationDoesNotFollowSameOriginOrCrossOriginRedirects() = runBlocking {
        MockWebServer().use { destination ->
            MockWebServer().use { server ->
                val requests = AtomicInteger()
                val codes = listOf(301, 302, 303, 307, 308)
                destination.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse()
                        .setHeader("Content-Type", "image/png").setBody("must not be downloaded")
                }
                server.dispatcher = object : CoremailDispatcher(server) {
                    override fun route(request: RecordedRequest): MockResponse {
                        requests.incrementAndGet()
                        val code = request.requestUrl?.queryParameter("part")?.toIntOrNull()
                        if (code !in codes) return unexpected(request)
                        val target = if (code == 301) {
                            server.url("/coremail/s/json?func=user:logout")
                        } else {
                            destination.url("/receive.png").newBuilder()
                                .host(if (server.hostName == "localhost") "127.0.0.1" else "localhost")
                                .build()
                        }
                        return MockResponse().setResponseCode(code!!).setHeader("Location", target)
                    }
                }
                val html = codes.joinToString("") {
                    "<img src=\"/coremail/mbox-data/image.png?part=$it\">"
                }

                val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

                assertEquals(html, hydrated.data.htmlContent)
                assertEquals(codes.size, requests.get())
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test
    fun inlineImageHydrationRejectsBlankCidsAndUnsafeAttachmentNames() = runBlocking {
        MockWebServer().use { server ->
            val html = """
                <img src="cid:"><img src="CID: &lt;&gt; "><img src="cid: &lt; &gt;">
                <img src="cid:unsafe-path"><img src="cid:empty-part">
            """.trimIndent()
            val attachments = listOf(
                MailAttachment("1", "unrelated.png", "image/png", part = "1"),
                MailAttachment("2", "empty.png", "image/png", part = "2", contentId = ""),
                MailAttachment("3", "../image.png", "image/png", part = "3", contentId = "unsafe-path"),
                MailAttachment("4", "image.png", "image/png", part = "", contentId = "empty-part"),
            )

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html, attachments))

            assertEquals(html, hydrated.data.htmlContent)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun inlineImageHydrationContinuesPastEightFailedOrEmptyImages() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse =
                    if (requests.incrementAndGet() == 20) {
                        MockResponse().setHeader("Content-Type", "image/png").setBody("last-image")
                    } else if (requests.get() % 2 == 0) {
                        MockResponse().setHeader("Content-Type", "image/png").setBody("")
                    } else {
                        MockResponse().setResponseCode(404)
                    }
            }
            val html = (1..20).joinToString("") { "<img src=\"/coremail/mbox-data/image.png?part=$it\">" }

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            val images = Jsoup.parse(hydrated.data.htmlContent).select("img")
            assertEquals(20, requests.get())
            assertEquals(1, images.count { it.attr("src").startsWith("data:") })
            assertEquals("data:image/png;base64,bGFzdC1pbWFnZQ==", images.last()!!.attr("src"))
        }
    }

    @Test
    fun inlineImageHydrationContinuesAfterDiscardingMoreThanSixMiBOfNonImages() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            val body = ByteArray(2 * 1024 * 1024)
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (requests.incrementAndGet() == 5) {
                        return MockResponse().setHeader("Content-Type", "image/png").setBody("last-image")
                    }
                    return MockResponse().setHeader("Content-Type", "text/html")
                        .setBody(okio.Buffer().write(body))
                }
            }
            val html = (1..5).joinToString("") { "<img src=\"/coremail/mbox-data/image.png?part=$it\">" }

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            val images = Jsoup.parse(hydrated.data.htmlContent).select("img")
            assertEquals(5, requests.get())
            assertEquals(1, images.count { it.attr("src").startsWith("data:") })
            assertEquals("data:image/png;base64,bGFzdC1pbWFnZQ==", images.last()!!.attr("src"))
        }
    }

    @Test
    fun inlineImageHydrationLoadsLargeChunkedImagesBeyondSixMiBTotal() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            val body = ByteArray(2 * 1024 * 1024 + 1)
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png")
                        .setChunkedBody(okio.Buffer().write(body), 8192)
                }
            }
            val html = (1..4).joinToString("") { "<img src=\"/coremail/mbox-data/image.png?part=$it\">" }

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            val images = Jsoup.parse(hydrated.data.htmlContent).select("img")
            assertEquals(4, images.size)
            assertEquals(4, requests.get())
            images.forEach {
                val source = it.attr("src")
                assertTrue(source.startsWith("data:image/png;base64,"))
                assertEquals(body.size, java.util.Base64.getDecoder().decode(source.substringAfter(',')).size)
            }
        }
    }

    @Test
    fun inlineImageHydrationLoadsEveryImageBeyondEightImagesAndSixMiBTotal() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    val size = 1024 * 1024
                    return MockResponse().setHeader("Content-Type", "image/png")
                        .setChunkedBody(okio.Buffer().write(ByteArray(size)), 8192)
                }
            }
            val html = (1..9).joinToString("") { "<img src=\"/coremail/mbox-data/image.png?part=$it\">" }

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            val images = Jsoup.parse(hydrated.data.htmlContent).select("img")
            assertEquals(9, images.count { it.attr("src").startsWith("data:") })
            assertEquals(9, requests.get())
        }
    }

    @Test
    fun inlineImageHydrationAllowsSlowImagesBeyondPreviousPerImageAndTotalTimeouts() = runBlocking {
        MockWebServer().use { server ->
            val requests = AtomicInteger()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody("image")
                        .setBodyDelay(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            val html = (1..3).joinToString("") { "<img src=\"/coremail/mbox-data/image.png?part=$it\">" }

            val hydrated = provider(server).hydrateInlineImages(mailEnvelope(html))

            assertEquals(3, requests.get())
            assertEquals(3, Jsoup.parse(hydrated.data.htmlContent).select("img").count {
                it.attr("src") == "data:image/png;base64,aW1hZ2U="
            })
        }
    }

    @Test
    fun deleteMovesMessagesToTrashFolder() = runBlocking {
        MockWebServer().use { server ->
            var captured = ""
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "mbox:updateMessageInfos"
                    ) {
                        captured = request.body.readUtf8()
                        return json("""{"code":"S_OK","var":{"updated":1}}""")
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            val response = provider.deleteMessages(listOf("2:abc+"))

            val payload = AppJson.parseToJsonElement(captured).jsonObject
            assertEquals("4", payload["attrs"]?.jsonObject?.get("fid")?.jsonPrimitive?.content)
            assertEquals("deleted", response.status)
            assertEquals("4", response.targetFolderId)
        }
    }

    @Test
    fun markMessagesReadUpdatesReadFlag() = runBlocking {
        MockWebServer().use { server ->
            var captured = ""
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "mbox:updateMessageInfos"
                    ) {
                        captured = request.body.readUtf8()
                        return json("""{"code":"S_OK","var":{"updated":2}}""")
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            val response = provider.markMessagesRead(listOf("2:abc+", "3:def"), mboxa = "box-token")

            val payload = AppJson.parseToJsonElement(captured).jsonObject
            assertEquals("true", payload["attrs"]?.jsonObject?.get("flags")?.jsonObject?.get("read")?.jsonPrimitive?.content)
            assertEquals(
                listOf("2:abc+", "3:def"),
                payload["ids"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals("box-token", payload["mboxa"]?.jsonPrimitive?.content)
            assertEquals("read", response.status)
            assertEquals(2, response.updatedCount)
        }
    }

    @Test
    fun uploadAttachmentCreatesComposeAndUploadsChunks() = runBlocking {
        MockWebServer().use { server ->
            val content = ByteArray(2 * 1024 * 1024) { 1 } + byteArrayOf(2, 3, 4)
            val offsets = mutableListOf<String?>()
            val ranges = mutableListOf<String?>()
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/coremail/XT/jsp/compose.jsp") {
                        assertEquals(sid, request.requestUrl?.queryParameter("sid"))
                        return json("""{"code":"S_OK","var":{"id":"compose-1"}}""")
                    }
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "upload:prepare"
                    ) {
                        val payload = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                        assertEquals("compose-1", payload["composeId"]?.jsonPrimitive?.content)
                        assertEquals("upload.txt", payload["fileName"]?.jsonPrimitive?.content)
                        assertEquals(content.size.toString(), payload["size"]?.jsonPrimitive?.content)
                        return json("""{"code":"S_OK","var":{"attachmentId":1,"fileName":"upload.txt","contentType":"text/plain","size":${content.size}}}""")
                    }
                    if (request.path?.startsWith("/coremail/XT/jsp/upload.jsp") == true) {
                        offsets += request.requestUrl?.queryParameter("offset")
                        ranges += request.getHeader("Content-Range")
                        return json("""{"code":"S_OK","var":{"attachmentId":1,"fileName":"upload.txt","contentType":"text/plain","size":${content.size}}}""")
                    }
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "mbox:compose"
                    ) {
                        return json("""{"code":"S_OK","var":{"attachments":[]}}""")
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            val response = provider.uploadAttachment("upload.txt", content, "text/plain")

            assertEquals(listOf("0", (2 * 1024 * 1024).toString()), offsets)
            assertEquals("bytes 0-${2 * 1024 * 1024 - 1}/${content.size}", ranges[0])
            assertEquals("bytes ${2 * 1024 * 1024}-${content.size - 1}/${content.size}", ranges[1])
            assertEquals("compose-1", response.composeId)
            assertEquals("1", response.attachment.attachmentId)
        }
    }

    @Test
    fun sendDraftAndContactsUseExpectedPayloads() = runBlocking {
        MockWebServer().use { server ->
            var sendPayload = ""
            var draftPayload = ""
            server.dispatcher = object : CoremailDispatcher(server) {
                override fun route(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "user:getAttrs"
                    ) {
                        return json("""{"code":"S_OK","var":{"email":"sender@example.edu","true_name":"Sender"}}""")
                    }
                    if (request.requestUrl?.encodedPath == "/coremail/common/mbox/compose.jsp") {
                        val body = request.body.readUtf8()
                        val action = AppJson.parseToJsonElement(body).jsonObject["action"]?.jsonPrimitive?.content
                        if (action == "deliver") {
                            sendPayload = body
                            return json("""{"code":"S_OK","savedSent":{"mid":"2:sent-id"},"var":{}}""")
                        }
                        draftPayload = body
                        return json("""{"code":"S_OK","draftId":"2:draft-id","var":{}}""")
                    }
                    if (request.requestUrl?.encodedPath == "/coremail/XT/jsp/compose.jsp") {
                        return json("""{"code":"S_OK","var":{"id":"draft-compose"}}""")
                    }
                    if (request.path?.startsWith("/coremail/s/json") == true &&
                        request.requestUrl?.queryParameter("func") == "oab:autoMatch"
                    ) {
                        return json("""{"code":"S_OK","var":[{"m":"to@example.edu","name":"To User","@id":"1"}]}""")
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            val sent = provider.sendMessage(
                MailComposeRequest(
                    composeId = "compose-1",
                    to = listOf("to@example.edu"),
                    subject = "hello",
                    content = "body",
                    attachments = listOf(MailComposeAttachment("1", "report.pdf", 123, "application/pdf")),
                )
            )
            val draft = provider.saveDraft(MailComposeRequest(subject = "draft"))
            val contacts = provider.autocompleteContacts("to")

            val sentJson = AppJson.parseToJsonElement(sendPayload).jsonObject
            assertEquals("deliver", sentJson["action"]?.jsonPrimitive?.content)
            assertEquals("sent", sent.status)
            assertEquals("2:sent-id", sent.sentMessageId)
            assertEquals("saved", draft.status)
            assertEquals("2:draft-id", draft.draftId)
            assertTrue(draftPayload.contains("\"action\":\"save\""))
            assertEquals("to@example.edu", contacts.data.contacts.single().email)
        }
    }

    @Test
    fun ssoRedirectToLoginReportsExpiredSession() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/osys_sso_email/") {
                        return MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", server.url("/auth/login?next=/osys_sso_email/"))
                    }
                    if (request.path?.startsWith("/auth/login") == true) {
                        return MockResponse().setBody("<form id=\"login\"></form>")
                    }
                    return unexpected(request)
                }
            }
            val provider = provider(server)

            try {
                provider.fetchFolders()
                fail("Expected SessionExpiredException")
            } catch (error: SessionExpiredException) {
                assertTrue(error.message.orEmpty().contains("Coremail SSO"))
            }
        }
    }

    private fun provider(server: MockWebServer): CoremailProvider {
        val baseUrl = server.url("/").toString().trimEnd('/')
        return CoremailProvider(
            client = BjtuHttpClient(AppCookieJar()),
            coremailBaseUrl = baseUrl,
            ssoUrl = "$baseUrl/osys_sso_email/",
        )
    }

    private fun mailEnvelope(
        html: String,
        attachments: List<MailAttachment> = emptyList(),
    ): ModuleEnvelope<MailMessageDetail> = ModuleEnvelope(
        module = "mail_message",
        sourceSystem = "coremail",
        coverage = CoverageLevel.Verified,
        data = MailMessageDetail(
            messageId = "message-1",
            folderId = "1",
            htmlContent = html,
            attachments = attachments,
        ),
    )

    private abstract inner class CoremailDispatcher(private val server: MockWebServer) : Dispatcher() {
        final override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/osys_sso_email/") {
                return MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/coremail/XT/index.jsp?sid=$sid"))
            }
            if (request.path?.startsWith("/coremail/XT/index.jsp") == true) {
                return MockResponse().setBody("<script>var sid='$sid'</script>")
            }
            return route(request)
        }

        abstract fun route(request: RecordedRequest): MockResponse
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun unexpected(request: RecordedRequest): MockResponse =
        MockResponse()
            .setResponseCode(500)
            .setBody("Unexpected request: ${request.method} ${request.path}")
}
