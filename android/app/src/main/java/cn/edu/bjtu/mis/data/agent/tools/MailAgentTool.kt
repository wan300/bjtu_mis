package cn.edu.bjtu.mis.data.agent.tools

import cn.edu.bjtu.mis.data.repository.MailRepository
import cn.edu.bjtu.mis.model.MailAttachment
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailComposeResponse
import cn.edu.bjtu.mis.model.MailContactsData
import cn.edu.bjtu.mis.model.MailContactSuggestion
import cn.edu.bjtu.mis.model.MailFolder
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMessageDetail
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.MailMessageSummary
import cn.edu.bjtu.mis.model.ModuleEnvelope
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

interface MailAgentGateway {
    suspend fun folders(): ModuleEnvelope<MailFoldersData>
    suspend fun messages(folderId: String, start: Int, limit: Int): ModuleEnvelope<MailMessagesData>
    suspend fun detail(messageId: String, mboxa: String = ""): ModuleEnvelope<MailMessageDetail>
    suspend fun contacts(keyword: String, limit: Int): ModuleEnvelope<MailContactsData>
    suspend fun saveDraft(request: MailComposeRequest): MailComposeResponse
    suspend fun send(request: MailComposeRequest): MailComposeResponse
}

private class RepositoryMailAgentGateway(
    private val repository: MailRepository,
) : MailAgentGateway {
    override suspend fun folders(): ModuleEnvelope<MailFoldersData> = repository.folders()

    override suspend fun messages(folderId: String, start: Int, limit: Int): ModuleEnvelope<MailMessagesData> =
        repository.messages(folderId = folderId, start = start, limit = limit)

    override suspend fun detail(messageId: String, mboxa: String): ModuleEnvelope<MailMessageDetail> =
        repository.detail(messageId = messageId, mboxa = mboxa)

    override suspend fun contacts(keyword: String, limit: Int): ModuleEnvelope<MailContactsData> =
        repository.contacts(keyword = keyword, limit = limit)

    override suspend fun saveDraft(request: MailComposeRequest): MailComposeResponse =
        repository.saveDraft(request)

    override suspend fun send(request: MailComposeRequest): MailComposeResponse =
        repository.send(request)
}

class MailAgentTool(
    private val gateway: MailAgentGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    constructor(repository: MailRepository) : this(RepositoryMailAgentGateway(repository))

    fun tools(): List<AgentTool> = listOf(
        ListFoldersTool(),
        ListRecentTool(),
        ReadTool(),
        DigestContextTool(),
        SearchContactsTool(),
        SaveDraftTool(),
        SendTool(),
    )

    private abstract inner class BaseMailTool : AgentTool {
        override val requiresWorkspace: Boolean = false
    }

    private inner class ListFoldersTool : BaseMailTool() {
        override val name = "mail.list_folders"
        override val description = "List Coremail folders available to the signed-in user."
        override val parameters = objectSchema()

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val folders = gateway.folders().data.folders
            ToolResult(
                okOutput("folders" to JsonArray(folders.map { it.toJson() }))
            )
        }
    }

    private inner class ListRecentTool : BaseMailTool() {
        override val name = "mail.list_recent"
        override val description = "List recent Coremail messages. Defaults to inbox, last 7 days, max 50 messages."
        override val parameters = objectSchema(
            "folder_id" to stringSchema("Coremail folder id. Defaults to inbox folder 1."),
            "days" to integerSchema("Recent window in days. Defaults to 7.", minimum = 1, maximum = 30),
            "limit" to integerSchema("Maximum messages to return. Defaults to 50.", minimum = 1, maximum = 100),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val request = RecentMailRequest.from(arguments)
            val messages = listRecentMessages(request)
            ToolResult(
                okOutput(
                    "folder_id" to JsonPrimitive(request.folderId),
                    "days" to JsonPrimitive(request.days),
                    "limit" to JsonPrimitive(request.limit),
                    "messages" to JsonArray(messages.map { it.toJson() }),
                )
            )
        }
    }

    private inner class ReadTool : BaseMailTool() {
        override val name = "mail.read"
        override val description = "Read a Coremail message body and attachment metadata."
        override val parameters = objectSchema(
            "message_id" to stringSchema("Coremail message id."),
            "mboxa" to stringSchema("Optional Coremail mailbox token."),
            "max_body_chars" to integerSchema("Maximum body characters. Defaults to 12000; capped at 30000.", minimum = 1, maximum = MAX_BODY_CHARS),
            "include_html" to booleanSchema(defaultValue = false),
            required = listOf("message_id"),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val messageId = arguments.requiredString("message_id")
            val mboxa = arguments.string("mboxa").orEmpty()
            val maxBodyChars = arguments.int("max_body_chars", DEFAULT_BODY_CHARS).coerceIn(1, MAX_BODY_CHARS)
            val includeHtml = arguments.boolean("include_html", false)
            val detail = gateway.detail(messageId = messageId, mboxa = mboxa).data
            ToolResult(okOutput("message" to detail.toReadJson(maxBodyChars, includeHtml)))
        }
    }

    private inner class DigestContextTool : BaseMailTool() {
        override val name = "mail.digest_context"
        override val description =
            "Collect recent high-signal mail context for an LLM digest. The model should write the final summary."
        override val parameters = objectSchema(
            "folder_id" to stringSchema("Coremail folder id. Defaults to inbox folder 1."),
            "days" to integerSchema("Recent window in days. Defaults to 7.", minimum = 1, maximum = 30),
            "limit" to integerSchema("Maximum messages to inspect. Defaults to 50.", minimum = 1, maximum = 100),
            "max_messages_with_body" to integerSchema("Maximum prioritized messages to read fully. Defaults to 20.", minimum = 1, maximum = 50),
            "max_body_chars" to integerSchema("Maximum body excerpt characters per message. Defaults to 3000.", minimum = 1, maximum = MAX_BODY_CHARS),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val request = RecentMailRequest.from(arguments)
            val maxBodies = arguments.int("max_messages_with_body", DEFAULT_DIGEST_BODY_COUNT).coerceIn(1, 50)
            val maxBodyChars = arguments.int("max_body_chars", DEFAULT_DIGEST_BODY_CHARS).coerceIn(1, MAX_BODY_CHARS)
            val recentMessages = listRecentMessages(request)
            val prioritized = recentMessages.sortedWith(
                compareByDescending<MailMessageSummary> { it.digestPriority() }
                    .thenByDescending { it.messageInstant()?.toEpochMilli() ?: Long.MIN_VALUE }
            )
            val detailsById = prioritized.take(maxBodies).mapNotNull { summary ->
                runCatching { summary.messageId to gateway.detail(summary.messageId, "").data }.getOrNull()
            }.toMap()
            val items = recentMessages.map { summary ->
                val detail = detailsById[summary.messageId]
                val bodyText = detail?.plainBody().orEmpty()
                summary.toDigestJson(
                    detail = detail,
                    bodyExcerpt = truncateText(bodyText, maxBodyChars).text,
                    bodyTruncated = detail != null && bodyText.length > maxBodyChars,
                )
            }
            ToolResult(
                okOutput(
                    "folder_id" to JsonPrimitive(request.folderId),
                    "days" to JsonPrimitive(request.days),
                    "limit" to JsonPrimitive(request.limit),
                    "read_body_count" to JsonPrimitive(detailsById.size),
                    "items" to JsonArray(items),
                )
            )
        }
    }

    private inner class SearchContactsTool : BaseMailTool() {
        override val name = "mail.search_contacts"
        override val description = "Search Coremail contact autocomplete suggestions."
        override val parameters = objectSchema(
            "keyword" to stringSchema("Name or email search keyword."),
            "limit" to integerSchema("Maximum contacts to return. Defaults to 8.", minimum = 1, maximum = 20),
            required = listOf("keyword"),
        )

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val keyword = arguments.requiredString("keyword")
            val limit = arguments.int("limit", DEFAULT_CONTACT_LIMIT).coerceIn(1, 20)
            val contacts = gateway.contacts(keyword = keyword, limit = limit).data.contacts
            ToolResult(
                okOutput(
                    "keyword" to JsonPrimitive(keyword),
                    "contacts" to JsonArray(contacts.map { it.toJson() }),
                )
            )
        }
    }

    private inner class SaveDraftTool : BaseMailTool() {
        override val name = "mail.save_draft"
        override val description = "Save a Coremail draft. This does not send the message."
        override val parameters = composeParameters(required = listOf("subject", "body"))

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val request = arguments.toComposeRequest(requireRecipient = false)
            val response = gateway.saveDraft(request)
            ToolResult(okOutput("draft" to response.toDraftJson(request)))
        }
    }

    private inner class SendTool : BaseMailTool() {
        override val name = "mail.send"
        override val description = "Send a Coremail message after the OpenWebUI confirmation dialog approves it."
        override val parameters = composeParameters(required = listOf("to", "subject", "body"))

        override suspend fun execute(taskId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val request = arguments.toComposeRequest(requireRecipient = true)
            val response = gateway.send(request)
            ToolResult(okOutput("sent" to response.toSentJson(request)))
        }
    }

    private suspend fun listRecentMessages(request: RecentMailRequest): List<MailMessageSummary> {
        val cutoff = Instant.now(clock).minusSeconds(request.days.toLong() * 24L * 60L * 60L)
        val messages = mutableListOf<MailMessageSummary>()
        var start = 0
        var reachedTimeLimit = false
        while (messages.size < request.limit && !reachedTimeLimit) {
            val pageSize = minOf(PAGE_SIZE, request.limit - messages.size)
            val page = gateway.messages(folderId = request.folderId, start = start, limit = pageSize).data.messages
            if (page.isEmpty()) break
            for (message in page) {
                val instant = message.messageInstant()
                if (instant != null && instant.isBefore(cutoff)) {
                    reachedTimeLimit = true
                    break
                }
                messages += message
                if (messages.size >= request.limit) break
            }
            if (page.size < pageSize) break
            start += pageSize
        }
        return messages
    }

    private data class RecentMailRequest(
        val folderId: String,
        val days: Int,
        val limit: Int,
    ) {
        companion object {
            fun from(arguments: JsonObject): RecentMailRequest =
                RecentMailRequest(
                    folderId = arguments.string("folder_id")?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER_ID,
                    days = arguments.int("days", DEFAULT_DAYS).coerceIn(1, 30),
                    limit = arguments.int("limit", DEFAULT_LIMIT).coerceIn(1, 100),
                )
        }
    }
}

private const val DEFAULT_FOLDER_ID = "1"
private const val DEFAULT_DAYS = 7
private const val DEFAULT_LIMIT = 50
private const val PAGE_SIZE = 50
private const val DEFAULT_BODY_CHARS = 12000
private const val MAX_BODY_CHARS = 30000
private const val DEFAULT_DIGEST_BODY_COUNT = 20
private const val DEFAULT_DIGEST_BODY_CHARS = 3000
private const val DEFAULT_CONTACT_LIMIT = 8

private val localDateTimeFormats = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
)

private val urgentKeywords = listOf(
    "urgent",
    "asap",
    "deadline",
    "due",
    "expire",
    "expires",
    "today",
    "tomorrow",
    "review",
    "approval",
    "exam",
    "registration",
    "reminder",
    "notice",
    "meeting",
    "紧急",
    "尽快",
    "马上",
    "立即",
    "截止",
    "今天",
    "明天",
    "本周",
    "审核",
    "审批",
    "考试",
    "报名",
    "缴费",
    "逾期",
    "过期",
    "提醒",
    "通知",
    "会议",
)

private val timeSensitiveKeywords = listOf(
    "deadline",
    "due",
    "today",
    "tomorrow",
    "this week",
    "registration",
    "exam",
    "interview",
    "meeting",
    "截止",
    "截止日期",
    "今天",
    "明天",
    "本周",
    "报名",
    "考试",
    "面试",
    "会议",
)

private val valueKeywords = listOf(
    "scholarship",
    "grant",
    "internship",
    "recruiting",
    "offer",
    "competition",
    "grade",
    "project",
    "lecture",
    "training",
    "opportunity",
    "奖学金",
    "资助",
    "就业",
    "实习",
    "招聘",
    "竞赛",
    "成绩",
    "学籍",
    "项目",
    "讲座",
    "培训",
    "机会",
)

private fun composeParameters(required: List<String>): JsonObject = objectSchema(
    "to" to stringArraySchema("Recipient email addresses."),
    "cc" to stringArraySchema("CC email addresses."),
    "bcc" to stringArraySchema("BCC email addresses."),
    "subject" to stringSchema("Message subject."),
    "body" to stringSchema("Message body."),
    "is_html" to booleanSchema(defaultValue = false),
    "compose_id" to stringSchema("Optional existing Coremail compose id."),
    required = required,
)

private fun stringArraySchema(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", stringSchema())
}

private fun MailFolder.toJson(): JsonObject = buildJsonObject {
    put("folder_id", folderId)
    put("name", name)
    put("message_count", messageCount)
    put("unread_count", unreadCount)
    put("message_size", messageSize)
    put("unread_size", unreadSize)
    put("system", system)
}

private fun MailMessageSummary.toJson(): JsonObject = buildJsonObject {
    put("message_id", messageId)
    put("folder_id", folderId)
    put("subject", subject)
    put("from_text", fromText)
    put("to_text", toText)
    sender?.let { put("sender", it) }
    sentAt?.let { put("sent_at", it) }
    receivedAt?.let { put("received_at", it) }
    modifiedAt?.let { put("modified_at", it) }
    put("read", read)
    put("attached", attached)
    put("size", size)
    priority?.let { put("priority", it) }
    summary?.let { put("summary", it) }
}

private fun MailMessageDetail.toReadJson(maxBodyChars: Int, includeHtml: Boolean): JsonObject {
    val body = plainBody()
    val bodySlice = truncateText(body, maxBodyChars)
    val htmlSlice = truncateText(htmlContent, maxBodyChars)
    return buildJsonObject {
        put("message_id", messageId)
        put("folder_id", folderId)
        put("subject", subject)
        put("from_text", fromText)
        put("to_text", toText)
        sender?.let { put("sender", it) }
        sentAt?.let { put("sent_at", it) }
        receivedAt?.let { put("received_at", it) }
        modifiedAt?.let { put("modified_at", it) }
        put("read", read)
        put("attached", attached)
        put("size", size)
        priority?.let { put("priority", it) }
        summary?.let { put("summary", it) }
        put("from_list", JsonArray(fromList.map { JsonPrimitive(it) }))
        put("to_list", JsonArray(toList.map { JsonPrimitive(it) }))
        put("cc_list", JsonArray(ccList.map { JsonPrimitive(it) }))
        put("bcc_list", JsonArray(bccList.map { JsonPrimitive(it) }))
        put("body_text", bodySlice.text)
        put("body_truncated", bodySlice.truncated)
        put("html_truncated", htmlSlice.truncated)
        if (includeHtml) put("html_content", htmlSlice.text)
        put("attachments", JsonArray(attachments.map { it.toJson() }))
    }
}

private fun MailMessageSummary.toDigestJson(
    detail: MailMessageDetail?,
    bodyExcerpt: String,
    bodyTruncated: Boolean,
): JsonObject {
    val signalText = listOfNotNull(subject, summary, detail?.plainBody()).joinToString("\n")
    return buildJsonObject {
        toJson().forEach { (key, value) -> put(key, value) }
        put("body_loaded", detail != null)
        if (detail != null) {
            put("body_excerpt", bodyExcerpt)
            put("body_truncated", bodyTruncated)
            put("attachments", JsonArray(detail.attachments.map { it.toJson() }))
        }
        put("signals", buildJsonObject {
            put("unread", !read)
            put("has_attachment", attached)
            put("urgency_keywords", JsonArray(matchingKeywords(signalText, urgentKeywords).map { JsonPrimitive(it) }))
            put("time_sensitive_keywords", JsonArray(matchingKeywords(signalText, timeSensitiveKeywords).map { JsonPrimitive(it) }))
            put("value_keywords", JsonArray(matchingKeywords(signalText, valueKeywords).map { JsonPrimitive(it) }))
        })
    }
}

private fun MailAttachment.toJson(): JsonObject = buildJsonObject {
    put("attachment_id", attachmentId)
    put("filename", filename)
    contentType?.let { put("content_type", it) }
    put("size", size)
    put("part", part)
}

private fun MailContactSuggestion.toJson(): JsonObject = buildJsonObject {
    contactId?.let { put("contact_id", it) }
    put("display_name", displayName)
    put("email", email)
    type?.let { put("type", it) }
    location?.let { put("location", it) }
}

private fun MailComposeResponse.toDraftJson(request: MailComposeRequest): JsonObject = buildJsonObject {
    put("status", status)
    put("compose_id", composeId)
    draftId?.let { put("draft_id", it) }
    put("to", JsonArray(request.to.map { JsonPrimitive(it) }))
    put("subject", request.subject)
}

private fun MailComposeResponse.toSentJson(request: MailComposeRequest): JsonObject = buildJsonObject {
    put("status", status)
    put("compose_id", composeId)
    sentMessageId?.let { put("sent_message_id", it) }
    put("to", JsonArray(request.to.map { JsonPrimitive(it) }))
    put("cc", JsonArray(request.cc.map { JsonPrimitive(it) }))
    put("bcc", JsonArray(request.bcc.map { JsonPrimitive(it) }))
    put("subject", request.subject)
}

private fun JsonObject.toComposeRequest(requireRecipient: Boolean): MailComposeRequest {
    val to = stringArray("to")
    if (requireRecipient && to.isEmpty()) throw IllegalArgumentException("Missing parameter to")
    val body = requiredString("body")
    val isHtml = boolean("is_html", false)
    return MailComposeRequest(
        composeId = string("compose_id")?.takeIf { it.isNotBlank() },
        to = to,
        cc = stringArray("cc"),
        bcc = stringArray("bcc"),
        subject = requiredString("subject"),
        content = if (isHtml) htmlToPlainText(body) else body,
        htmlContent = if (isHtml) body else null,
        isHtml = isHtml,
        attachments = emptyList(),
    )
}

private fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { item ->
        (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    } ?: emptyList()

private fun MailMessageSummary.digestPriority(): Int {
    val signalText = listOfNotNull(subject, summary).joinToString("\n")
    return (if (!read) 40 else 0) +
        (matchingKeywords(signalText, urgentKeywords).size * 10) +
        (matchingKeywords(signalText, timeSensitiveKeywords).size * 8) +
        (matchingKeywords(signalText, valueKeywords).size * 4) +
        (if (attached) 1 else 0)
}

private fun MailMessageSummary.messageInstant(): Instant? =
    parseMailInstant(receivedAt) ?: parseMailInstant(sentAt) ?: parseMailInstant(modifiedAt)

private fun parseMailInstant(value: String?): Instant? {
    val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()?.let { return it }
    localDateTimeFormats.forEach { formatter ->
        runCatching {
            LocalDateTime.parse(text, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        }.getOrNull()?.let { return it }
    }
    return null
}

private fun MailMessageDetail.plainBody(): String =
    htmlToPlainText(htmlContent)

private fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    return decodeHtmlEntities(
        html
            .replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|li|tr|h[1-6])\s*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
    )
        .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun decodeHtmlEntities(value: String): String {
    val named = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
    val decimalDecoded = Regex("""&#(\d+);""").replace(named) { match ->
        match.groupValues[1].toIntOrNull()?.let { code -> runCatching { code.toChar().toString() }.getOrNull() } ?: match.value
    }
    return Regex("""&#x([0-9a-fA-F]+);""").replace(decimalDecoded) { match ->
        match.groupValues[1].toIntOrNull(16)?.let { code -> runCatching { code.toChar().toString() }.getOrNull() } ?: match.value
    }
}

private data class TextSlice(
    val text: String,
    val truncated: Boolean,
)

private fun truncateText(value: String, maxChars: Int): TextSlice =
    if (value.length <= maxChars) {
        TextSlice(value, truncated = false)
    } else {
        TextSlice(value.take(maxChars), truncated = true)
    }

private fun matchingKeywords(text: String, keywords: List<String>): List<String> {
    if (text.isBlank()) return emptyList()
    val normalized = text.lowercase()
    return keywords.filter { keyword -> normalized.contains(keyword.lowercase()) }.distinct()
}
