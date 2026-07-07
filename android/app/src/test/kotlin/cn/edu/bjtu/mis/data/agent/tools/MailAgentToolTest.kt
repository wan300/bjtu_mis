package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailComposeResponse
import cn.edu.bjtu.mis.model.MailContactsData
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMarkReadResponse
import cn.edu.bjtu.mis.model.MailMessageDetail
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.MailMessageSummary
import cn.edu.bjtu.mis.model.ModuleEnvelope
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailAgentToolTest {
    @Test
    fun listRecentLimitsByDefaultCountAndRecentWindow() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.messages = (1..55).map { index ->
            MailMessageSummary(
                messageId = "recent-$index",
                folderId = "1",
                subject = "Recent $index",
                receivedAt = "2026-05-16T12:00:00Z",
            )
        } + MailMessageSummary(
            messageId = "old",
            folderId = "1",
            subject = "Old",
            receivedAt = "2026-05-01T12:00:00Z",
        ) + MailMessageSummary(
            messageId = "older-than-thirty",
            folderId = "1",
            subject = "Older than thirty days",
            receivedAt = "2026-04-10T12:00:00Z",
        )
        val tools = MailAgentTool(gateway, fixedClock()).tools()
        val listRecent = tools.single { it.name == "mail.list_recent" }

        val defaultOutput = listRecent.execute("", buildJsonObject { }).output
        assertEquals(50, defaultOutput["messages"]!!.jsonArray.size)

        val windowOutput = listRecent.execute("", buildJsonObject { put("limit", 100) }).output
        val messages = windowOutput["messages"]!!.jsonArray
        assertEquals(55, messages.size)
        assertFalse(messages.any { it.jsonObject["message_id"]!!.jsonPrimitive.contentOrNull == "old" })

        val extendedOutput = listRecent.execute(
            "",
            buildJsonObject {
                put("days", 60)
                put("limit", 100)
            },
        ).output
        val extendedMessages = extendedOutput["messages"]!!.jsonArray
        assertTrue(extendedMessages.any { it.jsonObject["message_id"]!!.jsonPrimitive.contentOrNull == "older-than-thirty" })
    }

    @Test
    fun listRecentSupportsExplicitDateWindowAndReportsScanLimit() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.messages = listOf(
            MailMessageSummary(
                messageId = "too-new",
                folderId = "1",
                subject = "Too new",
                receivedAt = "2026-05-13T00:00:00Z",
            ),
            MailMessageSummary(
                messageId = "in-window",
                folderId = "1",
                subject = "In window",
                receivedAt = "2026-05-12T12:00:00Z",
            ),
            MailMessageSummary(
                messageId = "also-in-window",
                folderId = "1",
                subject = "Also in window",
                receivedAt = "2026-05-11T12:00:00Z",
            ),
            MailMessageSummary(
                messageId = "too-old",
                folderId = "1",
                subject = "Too old",
                receivedAt = "2026-05-09T12:00:00Z",
            ),
        )
        val listRecent = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.list_recent" }

        val output = listRecent.execute(
            "",
            buildJsonObject {
                put("start_date", "2026-05-10T00:00:00Z")
                put("end_date", "2026-05-12T23:59:59Z")
                put("scan_limit", 2)
                put("limit", 10)
            },
        ).output

        val messageIds = output["messages"]!!.jsonArray.map { it.jsonObject["message_id"]!!.jsonPrimitive.contentOrNull }
        assertEquals(listOf("in-window"), messageIds)
        assertEquals(2, output["scanned_count"]!!.jsonPrimitive.int)
        assertTrue(output["scan_truncated"]!!.jsonPrimitive.boolean)
        assertEquals("2026-05-10T00:00:00Z", output["range"]!!.jsonObject["start_at"]!!.jsonPrimitive.contentOrNull)
        assertFalse(output.containsKey("days"))
    }

    @Test
    fun readConvertsHtmlToPlainTextAndTruncates() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.details["m1"] = MailMessageDetail(
            messageId = "m1",
            folderId = "1",
            subject = "HTML message",
            htmlContent = "<p>Hello <b>body</b></p><p>Second &amp; more</p>",
        )
        val read = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.read" }

        val output = read.execute(
            "",
            buildJsonObject {
                put("message_id", "m1")
                put("max_body_chars", 12)
            },
        ).output["message"]!!.jsonObject

        assertEquals("Hello body\nS", output["body_text"]!!.jsonPrimitive.contentOrNull)
        assertTrue(output["body_truncated"]!!.jsonPrimitive.boolean)
        assertTrue(output["html_truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun digestPrioritizesUnreadUrgentMessagesForBodyReads() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.messages = listOf(
            MailMessageSummary(
                messageId = "normal",
                folderId = "1",
                subject = "Weekly newsletter",
                read = true,
                receivedAt = "2026-05-16T12:00:00Z",
            ),
            MailMessageSummary(
                messageId = "urgent",
                folderId = "1",
                subject = "Deadline reminder for exam registration",
                read = false,
                receivedAt = "2026-05-15T12:00:00Z",
            ),
        )
        gateway.details["normal"] = MailMessageDetail(
            messageId = "normal",
            folderId = "1",
            subject = "Weekly newsletter",
            htmlContent = "<p>Normal body</p>",
        )
        gateway.details["urgent"] = MailMessageDetail(
            messageId = "urgent",
            folderId = "1",
            subject = "Deadline reminder for exam registration",
            htmlContent = "<p>Urgent registration body</p>",
        )
        val digest = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.digest_context" }

        val output = digest.execute(
            "",
            buildJsonObject {
                put("max_messages_with_body", 1)
            },
        ).output
        val items = output["items"]!!.jsonArray
        val urgent = items.single { it.jsonObject["message_id"]!!.jsonPrimitive.contentOrNull == "urgent" }.jsonObject
        val normal = items.single { it.jsonObject["message_id"]!!.jsonPrimitive.contentOrNull == "normal" }.jsonObject

        assertTrue(urgent["body_loaded"]!!.jsonPrimitive.boolean)
        assertEquals("Urgent registration body", urgent["body_excerpt"]!!.jsonPrimitive.contentOrNull)
        assertFalse(normal["body_loaded"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun digestUsesReducedDefaultScanAndBodyLimits() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.messages = (1..60).map { index ->
            MailMessageSummary(
                messageId = "m-$index",
                folderId = "1",
                subject = "Message $index",
                read = false,
                receivedAt = "2026-05-16T12:00:00Z",
            )
        }
        gateway.messages.forEach { summary ->
            gateway.details[summary.messageId] = MailMessageDetail(
                messageId = summary.messageId,
                folderId = "1",
                subject = summary.subject,
                htmlContent = "<p>Body ${summary.messageId}</p>",
            )
        }
        val digest = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.digest_context" }

        val output = digest.execute("", buildJsonObject { }).output

        assertEquals(150, output["scan_limit"]!!.jsonPrimitive.int)
        assertEquals(8, output["read_body_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun digestAllowsExplicitlyExpandedScanAndBodyLimits() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.messages = (1..25).map { index ->
            MailMessageSummary(
                messageId = "m-$index",
                folderId = "1",
                subject = "Message $index",
                read = false,
                receivedAt = "2026-05-16T12:00:00Z",
            )
        }
        gateway.messages.forEach { summary ->
            gateway.details[summary.messageId] = MailMessageDetail(
                messageId = summary.messageId,
                folderId = "1",
                subject = summary.subject,
                htmlContent = "<p>Body ${summary.messageId}</p>",
            )
        }
        val digest = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.digest_context" }

        val output = digest.execute(
            "",
            buildJsonObject {
                put("scan_limit", 500)
                put("max_messages_with_body", 20)
            },
        ).output

        assertEquals(500, output["scan_limit"]!!.jsonPrimitive.int)
        assertEquals(20, output["read_body_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun digestReportsTruncatedWhenDefaultScanLimitIsReached() = runBlocking {
        val gateway = FakeMailGateway()
        gateway.messages = (1..200).map { index ->
            MailMessageSummary(
                messageId = "future-$index",
                folderId = "1",
                subject = "Future $index",
                receivedAt = "2026-06-01T12:00:00Z",
            )
        }
        val digest = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.digest_context" }

        val output = digest.execute("", buildJsonObject { }).output

        assertEquals(150, output["scanned_count"]!!.jsonPrimitive.int)
        assertTrue(output["scan_truncated"]!!.jsonPrimitive.boolean)
        assertEquals(0, output["items"]!!.jsonArray.size)
    }

    @Test
    fun saveDraftDoesNotSendAndSendReturnsSentResult() = runBlocking {
        val gateway = FakeMailGateway()
        val tools = MailAgentTool(gateway, fixedClock()).tools()
        val saveDraft = tools.single { it.name == "mail.save_draft" }
        val send = tools.single { it.name == "mail.send" }

        val draftOutput = saveDraft.execute(
            "",
            buildJsonObject {
                put("to", JsonArray(listOf(JsonPrimitive("teacher@example.edu"))))
                put("subject", "Question")
                put("body", "Draft body")
            },
        ).output["draft"]!!.jsonObject

        assertNotNull(gateway.savedRequest)
        assertNull(gateway.sentRequest)
        assertEquals("draft-1", draftOutput["draft_id"]!!.jsonPrimitive.contentOrNull)

        val sentOutput = send.execute(
            "",
            buildJsonObject {
                put("to", JsonArray(listOf(JsonPrimitive("teacher@example.edu"))))
                put("subject", "Question")
                put("body", "Final body")
            },
        ).output["sent"]!!.jsonObject

        assertNotNull(gateway.sentRequest)
        assertEquals("Final body", gateway.sentRequest!!.content)
        assertEquals("sent-1", sentOutput["sent_message_id"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun markReadRequiresMessageIdsAndPassesIdsToGateway() = runBlocking {
        val gateway = FakeMailGateway()
        val markRead = MailAgentTool(gateway, fixedClock()).tools().single { it.name == "mail.mark_read" }

        val missingError = runCatching {
            markRead.execute("", buildJsonObject { put("message_ids", JsonArray(emptyList())) })
        }.exceptionOrNull()
        assertNotNull(missingError)
        assertTrue(missingError!!.message.orEmpty().contains("message_ids"))

        val output = markRead.execute(
            "",
            buildJsonObject {
                put("message_ids", JsonArray(listOf(JsonPrimitive("m1"), JsonPrimitive("m2"))))
                put("mboxa", "box-token")
            },
        ).output

        assertEquals(listOf("m1", "m2"), gateway.markedReadIds)
        assertEquals("box-token", gateway.markedReadMboxa)
        assertEquals("read", output["status"]!!.jsonPrimitive.contentOrNull)
        assertEquals(2, output["updated_count"]!!.jsonPrimitive.int)
    }

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-05-17T00:00:00Z"), ZoneOffset.UTC)

    private class FakeMailGateway : MailAgentGateway {
        var messages: List<MailMessageSummary> = emptyList()
        val details = mutableMapOf<String, MailMessageDetail>()
        var savedRequest: MailComposeRequest? = null
        var sentRequest: MailComposeRequest? = null
        var markedReadIds: List<String> = emptyList()
        var markedReadMboxa: String = ""

        override suspend fun folders(): ModuleEnvelope<MailFoldersData> =
            envelope("mail_folders", MailFoldersData())

        override suspend fun messages(folderId: String, start: Int, limit: Int): ModuleEnvelope<MailMessagesData> =
            envelope(
                "mail_messages",
                MailMessagesData(
                    folderId = folderId,
                    start = start,
                    limit = limit,
                    total = messages.size,
                    messages = messages.drop(start).take(limit),
                ),
            )

        override suspend fun detail(messageId: String, mboxa: String): ModuleEnvelope<MailMessageDetail> =
            envelope(
                "mail_detail",
                details[messageId] ?: error("Missing detail $messageId"),
            )

        override suspend fun markRead(messageIds: List<String>, mboxa: String): MailMarkReadResponse {
            markedReadIds = messageIds
            markedReadMboxa = mboxa
            return MailMarkReadResponse(status = "read", messageIds = messageIds, updatedCount = messageIds.size)
        }

        override suspend fun contacts(keyword: String, limit: Int): ModuleEnvelope<MailContactsData> =
            envelope("mail_contacts", MailContactsData(keyword = keyword))

        override suspend fun saveDraft(request: MailComposeRequest): MailComposeResponse {
            savedRequest = request
            return MailComposeResponse(status = "draft", composeId = "compose-1", draftId = "draft-1")
        }

        override suspend fun send(request: MailComposeRequest): MailComposeResponse {
            sentRequest = request
            return MailComposeResponse(status = "sent", composeId = "compose-2", sentMessageId = "sent-1")
        }

        private fun <T> envelope(module: String, data: T): ModuleEnvelope<T> =
            ModuleEnvelope(
                module = module,
                sourceSystem = "test",
                coverage = CoverageLevel.Verified,
                data = data,
            )
    }
}
