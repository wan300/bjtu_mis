package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.network.FileResponse
import cn.edu.bjtu.mis.data.network.MultipartFilePart
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.MailAttachment
import cn.edu.bjtu.mis.model.MailAttachmentUploadResponse
import cn.edu.bjtu.mis.model.MailComposeAttachment
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailComposeResponse
import cn.edu.bjtu.mis.model.MailContactSuggestion
import cn.edu.bjtu.mis.model.MailContactsData
import cn.edu.bjtu.mis.model.MailDeleteResponse
import cn.edu.bjtu.mis.model.MailFolder
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMarkReadResponse
import cn.edu.bjtu.mis.model.MailMessageDetail
import cn.edu.bjtu.mis.model.MailMessageSummary
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.net.URLConnection
import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

class CoremailError(message: String) : IOException(message)

class CoremailProvider(
    private val client: BjtuHttpClient,
    private val coremailBaseUrl: String = COREMAIL_BASE_URL,
    private val ssoUrl: String = COREMAIL_SSO_URL,
) {
    private var sid: String? = null

    suspend fun fetchFolders(): ModuleEnvelope<MailFoldersData> {
        val payload = postForm(
            "/coremail/XT/jsp/mail.jsp",
            data = mapOf("stats" to "true", "threads" to "false"),
            params = mapOf("func" to "getAllFolders"),
            includeSid = true,
        )
        val folders = payload.array("var").mapNotNull { it.jsonObjectOrNull()?.let(::folderFromRaw) }
        return ModuleEnvelope(
            module = "mail_folders",
            sourceSystem = "coremail",
            coverage = CoverageLevel.Verified,
            data = MailFoldersData(folders),
        )
    }

    suspend fun fetchMessages(
        folderId: String,
        start: Int = 0,
        limit: Int = 20,
        mboxa: String = "",
    ): ModuleEnvelope<MailMessagesData> {
        val fid = folderId.toIntOrNull() ?: 1
        val payload = postJsonFunc(
            "mbox:listMessages",
            buildJsonObject {
                put("fid", fid)
                put("start", start)
                put("limit", limit)
                put("mode", "count")
                put("order", "date")
                put("desc", true)
                put("returnTotal", true)
                put("returnTag", false)
                put("summaryWindowSize", limit)
                put("mboxa", mboxa)
                put("topFirst", true)
            },
        )
        val messages = payload.array("var").mapNotNull { it.jsonObjectOrNull()?.let(::summaryFromRaw) }
        return ModuleEnvelope(
            module = "mail_messages",
            sourceSystem = "coremail",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                put("folder_id", folderId)
                put("start", start)
                put("limit", limit)
                put("mboxa", mboxa)
            },
            data = MailMessagesData(
                folderId = folderId,
                start = start,
                limit = limit,
                total = payload.int("total"),
                messages = messages,
            ),
        )
    }

    suspend fun fetchMessageDetail(messageId: String, mboxa: String = ""): ModuleEnvelope<MailMessageDetail> {
        val payload = postForm(
            "/coremail/XT/jsp/readMessage.jsp",
            data = mapOf(
                "mid" to messageId,
                "mboxa" to mboxa,
                "part" to "",
                "mailCipherPassword" to "",
            ),
        )
        val value = payload.obj("var")
        val mail = value.obj("mail")
        val info = value.obj("mailInfo")
        val summary = summaryFromRaw(info)
        val content = mail.obj("mainPartData")
        val detail = MailMessageDetail(
            messageId = summary.messageId,
            folderId = summary.folderId,
            subject = summary.subject,
            fromText = summary.fromText,
            toText = summary.toText,
            sender = summary.sender,
            sentAt = summary.sentAt,
            receivedAt = summary.receivedAt,
            modifiedAt = summary.modifiedAt,
            size = summary.size,
            read = summary.read,
            attached = summary.attached,
            priority = summary.priority,
            summary = summary.summary,
            fromList = mail.stringList("from"),
            toList = mail.stringList("to"),
            ccList = mail.stringList("cc"),
            bccList = mail.stringList("bcc"),
            htmlContent = content.text("content").orEmpty(),
            headers = mail.obj("headers"),
            attachments = mail.array("attachments").mapNotNull { it.jsonObjectOrNull()?.let(::attachmentFromRaw) },
        )
        return ModuleEnvelope(
            module = "mail_message",
            sourceSystem = "coremail",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                put("message_id", messageId)
                put("mboxa", mboxa)
            },
            data = detail,
        )
    }

    internal suspend fun hydrateInlineImages(
        envelope: ModuleEnvelope<MailMessageDetail>,
    ): ModuleEnvelope<MailMessageDetail> {
        val detail = envelope.data
        if (detail.htmlContent.isBlank()) return envelope
        val hydratedHtml = hydrateMailImages(
            html = detail.htmlContent,
            messageId = detail.messageId,
            attachments = detail.attachments,
        )
        return envelope.copy(data = detail.copy(htmlContent = hydratedHtml))
    }

    private suspend fun hydrateMailImages(
        html: String,
        messageId: String,
        attachments: List<MailAttachment>,
    ): String {
        val document = try {
            Jsoup.parse(html)
        } catch (_: Exception) {
            return html
        }
        var changed = false
        for (image in document.select("img[src]")) {
            val source = image.attr("src").trim()
            val request = resolveMailImage(source, messageId, attachments) ?: continue
            val response = try {
                ensureReady()
                client.getBytes(
                    url = request.url.toString(),
                    headers = mapOf("Referer" to referer()),
                    followRedirects = false,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                continue
            }
            val contentType = inlineImageContentType(
                responseContentType = response.headers["Content-Type"],
                attachmentContentType = request.contentType,
                filename = request.filename,
            ) ?: continue
            if (response.body.isEmpty()) continue
            image.attr(
                "src",
                "data:$contentType;base64,${Base64.getEncoder().encodeToString(response.body)}",
            )
            changed = true
        }
        if (!changed) return html
        document.outputSettings().prettyPrint(false)
        return document.outerHtml()
    }

    private fun resolveMailImage(
        source: String,
        messageId: String,
        attachments: List<MailAttachment>,
    ): MailImageRequest? {
        if (source.isBlank() || source.startsWith("data:", ignoreCase = true)) return null
        val base = coremailBaseUrl.toHttpUrl()
        if (source.startsWith("cid:", ignoreCase = true)) {
            val contentId = normalizeContentId(source.substring(4)) ?: return null
            val attachment = attachments.firstOrNull {
                normalizeContentId(it.contentId) == contentId
            } ?: return null
            return mailImageRequest(
                base = base,
                messageId = messageId,
                part = attachment.part,
                contentType = attachment.contentType,
                filename = attachment.filename,
            )
        }
        val resolved = runCatching { base.resolve(source) }.getOrNull() ?: return null
        if (!resolved.hasSameOrigin(base) || resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
        val segments = resolved.pathSegments
        if (segments.size !in 2..3 || segments[0] != "coremail" || segments[1] != "mbox-data") return null
        if (resolved.queryParameterNames.any {
                it !in INLINE_IMAGE_QUERY_PARAMETERS || resolved.queryParameterValues(it).size != 1
            }
        ) return null
        if ("mid" in resolved.queryParameterNames && resolved.queryParameter("mid") != messageId) return null
        if ("mode" in resolved.queryParameterNames && resolved.queryParameter("mode") != "download") return null
        return mailImageRequest(
            base = base,
            messageId = messageId,
            part = resolved.queryParameter("part") ?: return null,
            filename = segments.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun mailImageRequest(
        base: HttpUrl,
        messageId: String,
        part: String,
        filename: String?,
        contentType: String? = null,
    ): MailImageRequest? {
        if (messageId.isBlank() || part.isBlank() || part.any(Char::isISOControl)) return null
        if (filename != null && (filename in setOf(".", "..") || filename.any {
                it == '/' || it == '\\' || it == ';' || it == '%' || it.isISOControl()
            })
        ) return null
        // Reconstruct a fixed, read-only endpoint; never forward sender-supplied API parameters.
        val url = base.newBuilder()
            .encodedPath("/coremail/mbox-data")
            .query(null)
            .fragment(null)
            .apply { filename?.let(::addPathSegment) }
            .addQueryParameter("part", part)
            .addQueryParameter("mid", messageId)
            .addQueryParameter("mode", "download")
            .build()
        return MailImageRequest(url, contentType, filename)
    }

    private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun inlineImageContentType(
        responseContentType: String?,
        attachmentContentType: String?,
        filename: String?,
    ): String? {
        val responseType = normalizeMediaType(responseContentType)
        if (responseType != null && responseType != "application/octet-stream" && !responseType.startsWith("image/")) {
            return null
        }
        return responseType?.takeIf { it.startsWith("image/") }
            ?: normalizeMediaType(attachmentContentType)?.takeIf { it.startsWith("image/") }
            ?: URLConnection.guessContentTypeFromName(filename.orEmpty())?.lowercase()?.takeIf { it.startsWith("image/") }
    }

    private fun normalizeMediaType(value: String?): String? =
        value?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun normalizeContentId(value: String?): String? =
        value?.trim()?.trim('<', '>')?.lowercase()?.takeIf { it.isNotBlank() }

    private data class MailImageRequest(
        val url: HttpUrl,
        val contentType: String?,
        val filename: String?,
    )

    suspend fun downloadAttachment(
        messageId: String,
        part: String,
        filename: String?,
        target: File,
    ): FileResponse {
        ensureReady()
        val safeName = filename?.takeIf { it.isNotBlank() }?.let { "/" + pathQuote(it) }.orEmpty()
        return client.downloadToFile(
            "$coremailBaseUrl/coremail/mbox-data$safeName",
            target,
            params = mapOf(
                "part" to part,
                "mid" to messageId,
                "mode" to "download",
            ),
            headers = mapOf("Referer" to referer()),
        )
    }

    suspend fun deleteMessages(messageIds: List<String>, mboxa: String = ""): MailDeleteResponse {
        val payload = postJsonFunc(
            "mbox:updateMessageInfos",
            buildJsonObject {
                put("attrs", buildJsonObject { put("fid", COREMAIL_TRASH_FOLDER_ID) })
                put("ids", buildJsonArray { messageIds.forEach { add(JsonPrimitive(it)) } })
                put("mboxa", mboxa)
                put("returnOriginalMsgInfos", true)
                put("expandThreadMid", false)
            },
        )
        return MailDeleteResponse(
            status = "deleted",
            messageIds = messageIds,
            targetFolderId = COREMAIL_TRASH_FOLDER_ID.toString(),
            upstream = upstream("var" to payload["var"]),
        )
    }

    suspend fun markMessagesRead(messageIds: List<String>, mboxa: String = ""): MailMarkReadResponse {
        if (messageIds.isEmpty()) throw CoremailError("Coremail message ids missing")
        val payload = postJsonFunc(
            "mbox:updateMessageInfos",
            buildJsonObject {
                put("attrs", buildJsonObject {
                    put("flags", buildJsonObject {
                        put("read", true)
                    })
                })
                put("ids", buildJsonArray { messageIds.forEach { add(JsonPrimitive(it)) } })
                put("mboxa", mboxa)
                put("returnOriginalMsgInfos", true)
                put("expandThreadMid", false)
            },
        )
        val updatedCount = payload.obj("var").firstInt("updated", "updatedCount") ?: messageIds.size
        return MailMarkReadResponse(
            status = "read",
            messageIds = messageIds,
            updatedCount = updatedCount,
            upstream = upstream("var" to payload["var"]),
        )
    }

    suspend fun uploadAttachment(
        filename: String,
        content: ByteArray,
        contentType: String?,
        composeId: String? = null,
    ): MailAttachmentUploadResponse {
        val activeComposeId = composeId?.trim()?.takeIf { it.isNotBlank() } ?: createCompose()
        val prepared = postJsonFunc(
            "upload:prepare",
            buildJsonObject {
                put("composeId", activeComposeId)
                put("attachmentId", -1)
                put("fileName", filename)
                put("contentType", contentType.orEmpty())
                put("securityLevel", null as String?)
                put("size", content.size)
            },
        )
        val preparedVar = prepared.obj("var")
        val attachmentId = preparedVar.int("attachmentId", -1)
        if (attachmentId < 0) throw CoremailError("Coremail attachment id missing")

        var uploadedVar = preparedVar
        val total = content.size
        var offset = 0
        do {
            val chunk = content.copyOfRange(offset, minOf(offset + COREMAIL_CHUNK_SIZE, total))
            val end = if (chunk.isNotEmpty()) offset + chunk.size - 1 else offset
            val response = client.postMultipart(
                "$coremailBaseUrl/coremail/XT/jsp/upload.jsp",
                files = listOf(
                    MultipartFilePart(
                        formName = "files[]",
                        fileName = filename,
                        content = chunk,
                        contentType = contentType ?: "application/octet-stream",
                    )
                ),
                params = mapOf(
                    "func" to "directData",
                    "sid" to ensureReady(),
                    "composeId" to activeComposeId,
                    "attachmentId" to attachmentId.toString(),
                    "offset" to offset.toString(),
                ),
                headers = mapOf(
                    "Accept" to "text/plain, */*; q=0.01",
                    "Content-Disposition" to contentDispositionForUpload(filename),
                    "Content-Range" to "bytes $offset-$end/$total",
                    "Referer" to referer(),
                    "X-Requested-With" to "XMLHttpRequest",
                ),
            )
            val uploadPayload = parseObject(response.body, "upload:directData")
            ensureSuccess(uploadPayload, "upload:directData")
            uploadPayload["var"]?.jsonObjectOrNull()?.let { uploadedVar = mergeObjects(uploadedVar, it) }
            offset += chunk.size
        } while (offset < total)

        val attachment = MailAttachment(
            attachmentId = attachmentId.toString(),
            filename = filename,
            contentType = uploadedVar.text("contentType") ?: contentType,
            size = uploadedVar.int("size", total),
            part = attachmentId.toString(),
        )
        val composed = postJsonFunc(
            "mbox:compose",
            buildJsonObject {
                put("id", activeComposeId)
                put(
                    "attrs",
                    buildJsonObject {
                        put(
                            "attachments",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", attachmentId)
                                        put("type", "upload")
                                        put("name", filename)
                                        put("displayName", filename)
                                        put("size", total)
                                    }
                                )
                            },
                        )
                    },
                )
                put("returnInfo", buildJsonArray { add(JsonPrimitive("attachments")) })
            },
        )
        return MailAttachmentUploadResponse(
            status = "uploaded",
            composeId = activeComposeId,
            attachment = attachment,
            upstream = upstream("prepare" to preparedVar, "compose" to composed["var"]),
        )
    }

    suspend fun sendMessage(request: MailComposeRequest): MailComposeResponse {
        val (composeId, attrs) = composeBody(request)
        val payload = postJsonPath(
            "/coremail/common/mbox/compose.jsp",
            buildJsonObject {
                put("action", "deliver")
                put("id", composeId)
                put("returnInfo", true)
                put("autosaveHitCounter", request.autosaveHitCounter)
                put("encryptPassword", "")
                put("attrs", attrs)
            },
            params = mapOf("isUserConfirmed" to "true"),
        )
        val savedSent = payload.obj("savedSent")
        return MailComposeResponse(
            status = "sent",
            composeId = composeId,
            sentMessageId = savedSent.text("mid"),
            upstream = upstream(
                "var" to payload["var"],
                "savedSent" to payload["savedSent"],
                "sentTInfo" to payload["sentTInfo"],
            ),
        )
    }

    suspend fun saveDraft(request: MailComposeRequest): MailComposeResponse {
        val (composeId, attrs) = composeBody(request)
        val payload = postJsonPath(
            "/coremail/common/mbox/compose.jsp",
            buildJsonObject {
                put("action", "save")
                put("id", composeId)
                put("returnInfo", true)
                put("encryptPassword", "")
                put("attrs", attrs)
            },
            params = mapOf("isUserConfirmed" to "true"),
        )
        return MailComposeResponse(
            status = "saved",
            composeId = composeId,
            draftId = payload.text("draftId"),
            upstream = upstream("var" to payload["var"]),
        )
    }

    suspend fun autocompleteContacts(keyword: String, limit: Int = 20): ModuleEnvelope<MailContactsData> {
        val payload = postJsonFunc(
            "oab:autoMatch",
            buildJsonObject {
                put("@type", "U,L,X")
                put("attrIds", buildJsonArray {
                    listOf("m", "@id", "@type", "location").forEach { add(JsonPrimitive(it)) }
                })
                put("enableAliasAC", true)
                put("enableNickNameAC", true)
                put("enableVirtualUserAC", false)
                put("keyword", keyword)
                put("limit", limit)
                put("matchDuty", false)
                put("matchPhone", false)
            },
        )
        val contacts = payload.array("var")
            .mapNotNull { item ->
                item.jsonObjectOrNull()?.let(::contactFromRaw)
                    ?: item.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let {
                        MailContactSuggestion(displayName = it, email = it)
                    }
            }
        return ModuleEnvelope(
            module = "mail_contacts",
            sourceSystem = "coremail",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                put("keyword", keyword)
                put("limit", limit)
            },
            data = MailContactsData(keyword = keyword, contacts = contacts),
        )
    }

    suspend fun createCompose(mboxa: String = ""): String {
        val payload = postForm(
            "/coremail/XT/jsp/compose.jsp",
            data = mapOf("ctype" to "normal", "mboxa" to mboxa),
            includeSid = true,
        )
        return payload.obj("var").text("id")
            ?: throw CoremailError("Coremail compose id missing")
    }

    private suspend fun composeBody(request: MailComposeRequest): Pair<String, JsonObject> {
        val composeId = request.composeId?.trim()?.takeIf { it.isNotBlank() } ?: createCompose(request.mboxa)
        val account = request.account?.trim()?.takeIf { it.isNotBlank() } ?: defaultAccount()
        val attrs = buildJsonObject {
            put("account", account)
            putStringArray("to", request.to)
            putStringArray("cc", request.cc)
            putStringArray("bcc", request.bcc)
            put("subject", request.subject)
            put("content", request.htmlContent ?: request.content.orEmpty())
            put(
                "attachments",
                buildJsonArray {
                    request.attachments.forEach { add(attachmentPayload(it)) }
                },
            )
            put("isHtml", request.isHtml)
            put("saveSentCopy", request.saveSentCopy)
            put("requestReadReceipt", request.requestReadReceipt)
            put("scheduleDate", request.scheduleDate)
            put("showOneRcpt", request.showOneRcpt)
            put("forbidDownload", request.forbidDownload)
            put("smimeEncrypt", false)
            put("smimeSign", false)
            put("smimeEnvelopId", "")
        }
        return composeId to attrs
    }

    private suspend fun defaultAccount(): String {
        val payload = postJsonFunc(
            "user:getAttrs",
            buildJsonObject {
                put(
                    "optionalAttrIds",
                    buildJsonArray {
                        listOf("email", "true_name", "default_sender_address").forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        )
        val value = payload.obj("var")
        value.firstText("default_sender_address")?.let { return it }
        val email = value.firstText("email") ?: throw CoremailError("Coremail sender account missing")
        val name = value.firstText("true_name")
        return if (name.isNullOrBlank()) email else "\"$name\" <$email>"
    }

    private suspend fun postJsonFunc(func: String, body: JsonObject): JsonObject {
        val payload = client.postJson(
            "$coremailBaseUrl/coremail/s/json",
            body.toString(),
            params = mapOf("sid" to ensureReady(), "func" to func),
            headers = jsonHeaders(accept = "*/*"),
            contentType = COREMAIL_JSON_CONTENT_TYPE,
        )
        return parseObject(payload.body, func).also { ensureSuccess(it, func) }
    }

    private suspend fun postJsonPath(path: String, body: JsonObject, params: Map<String, String?> = emptyMap()): JsonObject {
        val payload = client.postJson(
            "$coremailBaseUrl$path",
            body.toString(),
            params = params + ("sid" to ensureReady()),
            headers = jsonHeaders(accept = "text/x-json"),
            contentType = COREMAIL_JSON_CONTENT_TYPE,
        )
        return parseObject(payload.body, path).also { ensureSuccess(it, path) }
    }

    private suspend fun postForm(
        path: String,
        data: Map<String, String>,
        params: Map<String, String?> = emptyMap(),
        includeSid: Boolean = false,
    ): JsonObject {
        val requestParams = if (includeSid) {
            params + ("sid" to ensureReady())
        } else {
            ensureReady()
            params
        }
        val payload = client.postForm(
            "$coremailBaseUrl$path",
            form = data,
            params = requestParams,
            headers = mapOf(
                "Accept" to "*/*",
                "Referer" to referer(),
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        return parseObject(payload.body, path).also { ensureSuccess(it, path) }
    }

    private suspend fun ensureReady(): String {
        sid?.let { return it }
        val response = client.getText(ssoUrl, headers = mapOf("Referer" to "https://mis.bjtu.edu.cn/home/"))
        val extracted = extractSid(response.url, response.body) ?: run {
            if (isBjtuCasLoginUrl(response.url)) {
                throw SessionExpiredException("Coremail SSO redirected to CAS login.")
            }
            throw CoremailError("Coremail sid missing after SSO: ${response.url}")
        }
        sid = extracted
        return extracted
    }

    internal fun extractSid(vararg values: String): String? {
        val pattern = Regex("""(?:[?&]sid=|sid["']?\s*[:=]\s*["'])([A-Za-z0-9_-]+)""")
        return values.asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { pattern.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()
    }

    private fun referer(): String = "$coremailBaseUrl/coremail/XT/index.jsp?sid=${sid.orEmpty()}"

    private fun jsonHeaders(accept: String): Map<String, String> =
        mapOf(
            "Accept" to accept,
            "Referer" to referer(),
            "X-Requested-With" to "XMLHttpRequest",
        )

    private fun ensureSuccess(payload: JsonObject, context: String) {
        val code = payload.text("code").orEmpty()
        if (code.isNotBlank() && code != "S_OK") {
            throw CoremailError("$context failed with Coremail code=$code")
        }
    }

    private fun parseObject(body: String, context: String): JsonObject =
        runCatching { AppJson.parseToJsonElement(body).jsonObject }
            .getOrElse { throw CoremailError("$context returned invalid JSON") }

    private fun folderFromRaw(raw: JsonObject): MailFolder {
        val stats = raw.obj("stats")
        val flags = raw.obj("flags")
        return MailFolder(
            folderId = raw.text("id").orEmpty(),
            name = raw.text("name").orEmpty(),
            messageCount = stats.int("messageCount"),
            unreadCount = stats.int("unreadMessageCount"),
            messageSize = stats.int("messageSize"),
            unreadSize = stats.int("unreadMessageSize"),
            system = flags.bool("system"),
        )
    }

    private fun summaryFromRaw(raw: JsonObject): MailMessageSummary {
        val flags = raw.obj("flags")
        return MailMessageSummary(
            messageId = raw.text("id").orEmpty(),
            folderId = raw.text("fid").orEmpty(),
            subject = raw.text("subject").orEmpty(),
            fromText = raw.text("from").orEmpty(),
            toText = raw.text("to").orEmpty(),
            sender = raw.text("sender"),
            sentAt = raw.text("sentDate"),
            receivedAt = raw.text("receivedDate"),
            modifiedAt = raw.text("modifiedDate"),
            size = raw.int("size"),
            read = flags.bool("read"),
            attached = flags.bool("attached") || raw.bool("attached"),
            priority = raw.intOrNull("priority"),
            summary = raw.text("summary"),
        )
    }

    private fun attachmentFromRaw(raw: JsonObject): MailAttachment {
        val attachmentId = raw.firstText("id", "attachmentId", "part").orEmpty()
        return MailAttachment(
            attachmentId = attachmentId,
            filename = raw.firstText("filename", "name", "fileName") ?: "attachment",
            contentType = raw.text("contentType"),
            size = raw.firstInt("contentLength", "size", "estimateSize") ?: 0,
            part = raw.text("part") ?: attachmentId,
            contentId = raw.firstText("contentId", "contentID", "cid", "content-id")
                ?.trim()?.trim('<', '>'),
        )
    }

    private fun contactFromRaw(raw: JsonObject): MailContactSuggestion {
        val email = raw.firstText("email", "EMAIL;PREF", "m", "mail").orEmpty()
        val displayName = raw.firstText(
            "name",
            "FN",
            "cn",
            "true_name",
            "displayName",
            "@id",
            "email",
            "m",
        ).orEmpty()
        return MailContactSuggestion(
            contactId = raw.firstText("id", "@id"),
            displayName = displayName,
            email = email,
            type = raw.firstText("@type", "type"),
            location = raw.firstText("location", "@location"),
            raw = raw,
        )
    }

    private fun attachmentPayload(attachment: MailComposeAttachment): JsonObject {
        val id = attachment.attachmentId.toIntOrNull()
        return buildJsonObject {
            if (id != null) put("id", id) else put("id", attachment.attachmentId)
            put("type", attachment.type.ifBlank { "upload" })
            put("name", attachment.filename)
            put("displayName", attachment.filename)
            put("size", attachment.size)
            attachment.contentType?.let { put("contentType", it) }
            attachment.securityLevel?.let { put("securityLevel", it) }
        }
    }

    private fun upstream(vararg pairs: Pair<String, JsonElement?>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
        }

    private fun pathQuote(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun contentDispositionForUpload(filename: String): String {
        val quoted = pathQuote(filename)
        return "attachment; filename=\"$quoted\"; filename*=UTF-8''$quoted"
    }

    private fun mergeObjects(first: JsonObject, second: JsonObject): JsonObject =
        buildJsonObject {
            first.forEach { (key, value) -> put(key, value) }
            second.forEach { (key, value) -> put(key, value) }
        }

    private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObjectOrNull() ?: buildJsonObject {}

    private fun JsonObject.array(key: String): List<JsonElement> = this[key]?.jsonArrayOrNull().orEmpty()

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        intOrNull(key) ?: default

    private fun JsonObject.intOrNull(key: String): Int? =
        text(key)?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        when (val value = this[key]) {
            is JsonPrimitive -> value.contentOrNull?.let { it == "true" || it == "1" } ?: false
            else -> false
        }

    private fun JsonObject.firstText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { text(it) }

    private fun JsonObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { intOrNull(it) }

    private fun JsonObject.stringList(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        value.jsonArrayOrNull()?.let { array ->
            return array.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
        }
        return value.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull() = runCatching { jsonArray }.getOrNull()

    private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

    private fun JsonObjectBuilder.putStringArray(name: String, values: List<String>) {
        put(name, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        const val COREMAIL_BASE_URL = "https://mail.bjtu.edu.cn"
        const val COREMAIL_SSO_URL = "https://mis.bjtu.edu.cn/osys_sso_email/"
        const val COREMAIL_CHUNK_SIZE = 2 * 1024 * 1024
        const val COREMAIL_TRASH_FOLDER_ID = 4
        const val COREMAIL_JSON_CONTENT_TYPE = "text/x-json; tz=\"Asia/Shanghai\""
        private val INLINE_IMAGE_QUERY_PARAMETERS = setOf("part", "mid", "mode")
    }
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder
