package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.model.MailComposeAttachment
import cn.edu.bjtu.mis.model.MailComposeRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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
                                  "attachments": [{"id": "3", "filename": "report.txt", "contentType": "text/plain", "contentLength": 11}],
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
            assertEquals("hello world", target.readText())
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
