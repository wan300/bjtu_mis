package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.provider.ProviderConstants
import cn.edu.bjtu.mis.model.EmploymentArticleAttachment
import cn.edu.bjtu.mis.model.EmploymentArticleDetail
import cn.edu.bjtu.mis.model.EmploymentArticleSummary
import cn.edu.bjtu.mis.model.EmploymentCompanyInfo
import cn.edu.bjtu.mis.model.EmploymentContactInfo
import cn.edu.bjtu.mis.model.EmploymentInfoDetail
import cn.edu.bjtu.mis.model.EmploymentInfoSummary
import cn.edu.bjtu.mis.model.EmploymentPositionInfo
import cn.edu.bjtu.mis.model.EmploymentSectionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import java.net.URI

fun parseEmploymentArticleList(
    body: String,
    pageUrl: String = "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/newsList.html?id=${ProviderConstants.JOB_GUIDANCE_CATEGORY_ID}",
): List<EmploymentArticleSummary> {
    val root = AppJson.parseToJsonElement(body).asObject() ?: return emptyList()
    val list = root.obj("object")
        ?.obj("newsPage")
        ?.array("list")
        ?: return emptyList()
    return list.mapNotNull { item ->
        val article = item.asObject() ?: return@mapNotNull null
        val id = article.string("id") ?: return@mapNotNull null
        val title = article.string("title") ?: article.string("name") ?: return@mapNotNull null
        val rawUrl = article.string("url") ?: "/frontpage/bjtu/html/newsDetail.html?id=$id"
        EmploymentArticleSummary(
            id = id,
            title = normalizeSpace(title),
            url = absoluteEmploymentUrl(rawUrl, pageUrl),
            releaseDate = article.string("releaseDate"),
            description = stripHtmlExcerpt(article.string("description"), limit = 220).takeIf { it.isNotBlank() },
            publisher = article.string("publisher"),
            imageUrl = article.string("imageSrc")?.let { absoluteEmploymentUrl(it, pageUrl) },
            isExternalLink = article.employmentBool("isExternalLink") || isExternalEmploymentUrl(rawUrl),
        )
    }
}

fun parseEmploymentArticleDetail(
    body: String,
    pageUrl: String = ProviderConstants.JOB_HOME_URL,
): EmploymentArticleDetail {
    val root = AppJson.parseToJsonElement(body).asObject()
        ?: error("Invalid employment article response")
    val article = root.obj("object")?.obj("article")
        ?: error("Employment article response has no article")
    val id = article.string("id").orEmpty()
    val title = normalizeSpace(article.string("title") ?: article.string("name") ?: "")
        .ifBlank { "就业指导详情" }
    val contentHtml = article.obj("articleData")?.string("content")
        ?: article.string("content")
        ?: ""
    val contentText = normalizeSpace(Jsoup.parse(contentHtml, pageUrl).text())
    val rawUrl = article.string("link")?.takeIf { it.isNotBlank() }
        ?: article.string("url")
        ?: "/frontpage/bjtu/html/newsDetail.html?id=$id"
    val attachments = collectEmploymentLinks(contentHtml, pageUrl)
        .plus(article.fileAttachment("fileUrl", pageUrl))
        .filterNotNull()
        .distinctBy { it.url }

    return EmploymentArticleDetail(
        id = id,
        title = title,
        url = absoluteEmploymentUrl(rawUrl, pageUrl),
        releaseDate = article.string("releaseDate"),
        description = article.string("description")?.takeIf { it.isNotBlank() },
        publisher = article.string("publisher"),
        contentText = contentText,
        contentHtml = contentHtml,
        attachments = attachments,
        isExternalLink = article.employmentBool("isExternalLink") || isExternalEmploymentUrl(rawUrl),
    )
}

fun parseEmploymentInfoList(
    body: String,
    type: EmploymentSectionType,
    pageUrl: String = ProviderConstants.JOB_HOME_URL,
): List<EmploymentInfoSummary> {
    val list = AppJson.parseToJsonElement(body)
        .asObject()
        ?.array("object")
        ?: return emptyList()
    return list.mapNotNull { item ->
        val row = item.asObject() ?: return@mapNotNull null
        val id = row.string("id") ?: return@mapNotNull null
        val title = row.string("title") ?: return@mapNotNull null
        val company = row.obj("corporationinfo")
        val rawUrl = row.string("url") ?: defaultEmploymentDetailPath(type, id)
        EmploymentInfoSummary(
            id = id,
            type = type,
            title = normalizeSpace(title),
            url = absoluteEmploymentUrl(rawUrl, pageUrl),
            organization = row.string("corporationNameExport")
                ?: row.string("corporationName")
                ?: company?.string("name")
                ?: row.string("organizer"),
            startTime = row.string("startTime"),
            endTime = row.string("endTime"),
            location = employmentLocation(row),
            browseNumber = row.string("browseNumber"),
            logoUrl = company?.string("logoUrl")?.let { absoluteEmploymentUrl(it, pageUrl) },
            statusLabel = row.string("holdStatus"),
            education = row.string("education"),
            majorName = row.string("majorName"),
            positionCount = row.employmentInt("positionNum"),
            isExternalLink = isExternalEmploymentUrl(rawUrl),
        )
    }
}

fun parseEmploymentInfoDetail(
    body: String,
    type: EmploymentSectionType,
    pageUrl: String = ProviderConstants.JOB_HOME_URL,
): EmploymentInfoDetail {
    val root = AppJson.parseToJsonElement(body).asObject()
        ?: error("Invalid employment detail response")
    val data = root.obj("object")
        ?: error("Employment detail response has no object")
    return when (type) {
        EmploymentSectionType.CareerTalk -> parseCareerTalkDetail(data, pageUrl)
        EmploymentSectionType.JobFair -> parseJobFairDetail(data, pageUrl)
        EmploymentSectionType.Recruitment,
        EmploymentSectionType.Internship -> parseRecruitmentDetail(data, type, pageUrl)
    }
}

fun extractEmploymentAppointmentUrl(detail: EmploymentArticleDetail?): String =
    detail?.attachments
        ?.firstOrNull { it.url.contains("jysd.com/consult", ignoreCase = true) }
        ?.url
        ?: Regex("""https?://[^\s，。；;）)>\"']+""")
            .find(detail?.contentText.orEmpty())
            ?.value
            ?.takeIf { it.contains("jysd.com/consult", ignoreCase = true) }
        ?: ProviderConstants.JOB_CONSULT_APPOINTMENT_URL

fun defaultEmploymentContacts(): List<EmploymentContactInfo> = listOf(
    EmploymentContactInfo(
        title = "个性化咨询",
        phone = "010-51682911",
        email = "bjtujybm@126.com",
        location = "招生与就业工作处 206 室（南门小院）",
        description = "职业生涯规划、求职择业技巧、生涯测评解读等一对一咨询",
    ),
    EmploymentContactInfo(
        title = "签约服务（学生咨询）",
        phone = "010-51685913",
        email = "bjtujob@163.com",
        location = "招生与就业工作处 117 室",
        description = "毕业去向、签约手续、档案转递等学生就业事务",
    ),
    EmploymentContactInfo(
        title = "招聘服务（用人单位）",
        phone = "010-51688431",
        email = "jyb113@126.com",
        location = "招生与就业工作处 113 室",
        description = "招聘信息发布、宣讲会、双选会等单位服务",
    ),
    EmploymentContactInfo(
        title = "就业指导工作室",
        phone = "010-51682911",
        location = "招生与就业工作处 114 室",
        description = "择业指导与职业发展咨询",
    ),
    EmploymentContactInfo(
        title = "创业指导工作室",
        phone = "010-51682914",
        description = "创业指导与相关咨询",
    ),
)

private fun parseCareerTalkDetail(data: JsonObject, pageUrl: String): EmploymentInfoDetail {
    val item = data.obj("recruitmentFair") ?: error("Employment detail response has no recruitmentFair")
    val id = item.string("id").orEmpty()
    val rawUrl = item.string("url") ?: defaultEmploymentDetailPath(EmploymentSectionType.CareerTalk, id)
    val resolvedUrl = absoluteEmploymentUrl(rawUrl, pageUrl)
    val contentHtml = item.string("content").orEmpty()
    val company = employmentCompany(item.obj("corporationinfo"), pageUrl)
    return EmploymentInfoDetail(
        id = id,
        type = EmploymentSectionType.CareerTalk,
        title = normalizeSpace(item.string("title").orEmpty()).ifBlank { "宣讲会详情" },
        url = resolvedUrl,
        organization = item.string("corporationName") ?: company?.name,
        startTime = item.string("startTime"),
        endTime = item.string("endTime"),
        location = employmentLocation(item),
        browseNumber = item.string("browseNumber"),
        contentText = normalizeSpace(Jsoup.parse(contentHtml, resolvedUrl).text()),
        contentHtml = contentHtml,
        company = company,
        positions = employmentPositions(item.array("recruitmentFairPositionList")),
        contactsName = item.string("contactsName"),
        telephone = item.string("telephone"),
        email = item.string("email"),
        phoneNumber = item.string("phoneNumber"),
        resumeReceiveEmail = item.string("resumeReceiveEmail"),
        onlineApplicationUrl = item.string("onlineApplicationUrl"),
        attachments = collectEmploymentLinks(contentHtml, resolvedUrl)
            .plus(item.fileAttachment("fileUrl", resolvedUrl))
            .filterNotNull()
            .distinctBy { it.url },
        isExternalLink = isExternalEmploymentUrl(rawUrl),
    )
}

private fun parseJobFairDetail(data: JsonObject, pageUrl: String): EmploymentInfoDetail {
    val item = data.obj("bilateralchosefair") ?: error("Employment detail response has no bilateralchosefair")
    val id = item.string("id").orEmpty()
    val rawUrl = item.string("url") ?: defaultEmploymentDetailPath(EmploymentSectionType.JobFair, id)
    val resolvedUrl = absoluteEmploymentUrl(rawUrl, pageUrl)
    val contentHtml = item.string("content").orEmpty()
    return EmploymentInfoDetail(
        id = id,
        type = EmploymentSectionType.JobFair,
        title = normalizeSpace(item.string("title").orEmpty()).ifBlank { "双选会详情" },
        url = resolvedUrl,
        organization = item.string("organizer"),
        startTime = item.string("startTime"),
        endTime = item.string("endTime"),
        location = employmentLocation(item),
        browseNumber = item.string("browseNumber"),
        statusLabel = item.string("holdStatus"),
        contentText = normalizeSpace(Jsoup.parse(contentHtml, resolvedUrl).text()),
        contentHtml = contentHtml,
        positions = jobFairParticipants(item.array("bilateralchosefairList")),
        contactsName = item.string("contactsName"),
        telephone = item.string("telephone"),
        attachments = collectEmploymentLinks(contentHtml, resolvedUrl)
            .plus(item.fileAttachment("fileUrl", resolvedUrl))
            .filterNotNull()
            .distinctBy { it.url },
        isExternalLink = isExternalEmploymentUrl(rawUrl),
    )
}

private fun parseRecruitmentDetail(
    data: JsonObject,
    type: EmploymentSectionType,
    pageUrl: String,
): EmploymentInfoDetail {
    val item = data.obj("recruitmentinfo") ?: error("Employment detail response has no recruitmentinfo")
    val id = item.string("id").orEmpty()
    val rawUrl = item.string("url") ?: defaultEmploymentDetailPath(type, id)
    val resolvedUrl = absoluteEmploymentUrl(rawUrl, pageUrl)
    val contentHtml = item.string("content").orEmpty()
    val company = employmentCompany(item.obj("corporationinfo"), pageUrl)
    return EmploymentInfoDetail(
        id = id,
        type = type,
        title = normalizeSpace(item.string("title").orEmpty()).ifBlank {
            if (type == EmploymentSectionType.Internship) "实习信息详情" else "招聘信息详情"
        },
        url = resolvedUrl,
        organization = item.string("corporationName") ?: company?.name,
        startTime = item.string("startTime"),
        endTime = item.string("endTime"),
        location = item.string("cityName") ?: employmentLocation(item),
        browseNumber = item.string("browseNumber"),
        statusLabel = item.string("positionTypeValue"),
        contentText = normalizeSpace(Jsoup.parse(contentHtml, resolvedUrl).text()),
        contentHtml = contentHtml,
        company = company,
        positions = employmentPositions(item.array("recruitmentPositionList")),
        contactsName = item.string("contactsName"),
        telephone = item.string("telephone"),
        email = item.string("email"),
        phoneNumber = item.string("phoneNumber"),
        resumeReceiveEmail = item.string("resumeReceiveEmail"),
        onlineApplicationUrl = item.string("onlineApplicationUrl"),
        attachments = collectEmploymentLinks(contentHtml, resolvedUrl)
            .plus(item.fileAttachment("fileURL", resolvedUrl))
            .plus(item.fileAttachment("fileUrl", resolvedUrl))
            .filterNotNull()
            .distinctBy { it.url },
        isExternalLink = isExternalEmploymentUrl(rawUrl),
    )
}

private fun employmentCompany(company: JsonObject?, pageUrl: String): EmploymentCompanyInfo? {
    if (company == null) return null
    val name = company.string("name")
    val logoUrl = company.string("logoUrl")?.let { absoluteEmploymentUrl(it, pageUrl) }
    val website = company.string("officialWebsite")
    val rawUrl = company.string("url")
    if (name == null && logoUrl == null && website == null && rawUrl == null) return null
    return EmploymentCompanyInfo(
        name = name,
        logoUrl = logoUrl,
        nature = company.string("corporationNatureValue"),
        scale = company.string("corporationScaleValue"),
        website = website,
        introduction = company.string("corporationinfoIntroduction") ?: htmlToText(company.string("introduction"), pageUrl),
        url = rawUrl?.let { absoluteEmploymentUrl(it, pageUrl) },
        address = company.string("address"),
    )
}

private fun employmentPositions(list: JsonArray?): List<EmploymentPositionInfo> =
    list.orEmpty().mapNotNull { item ->
        val row = item.asObject() ?: return@mapNotNull null
        val name = row.string("positionName") ?: return@mapNotNull null
        EmploymentPositionInfo(
            name = normalizeSpace(name),
            education = row.string("studentType"),
            demandNumber = row.string("demandNumber"),
            majorName = row.string("majorName"),
            cityName = row.string("cityName"),
            description = htmlToText(row.string("positionDescription"), ProviderConstants.JOB_HOME_URL),
        )
    }

private fun jobFairParticipants(list: JsonArray?): List<EmploymentPositionInfo> =
    list.orEmpty().mapNotNull { item ->
        val row = item.asObject() ?: return@mapNotNull null
        val company = row.obj("corporationinfo")
        val name = row.string("corporationName") ?: company?.string("name") ?: return@mapNotNull null
        EmploymentPositionInfo(
            name = normalizeSpace(name),
            demandNumber = row.string("demand"),
            description = row.string("standNo")?.let { "展位号：$it" },
        )
    }

private fun employmentLocation(row: JsonObject): String? =
    row.obj("field")?.string("name")
        ?: row.string("realPlace")
        ?: row.string("place")
        ?: row.string("fieldExport")

private fun defaultEmploymentDetailPath(type: EmploymentSectionType, id: String): String =
    when (type) {
        EmploymentSectionType.CareerTalk -> "/f/recruitmentFair/show?recruitmentFairId=$id"
        EmploymentSectionType.JobFair -> "/f/bilateralchosefair/show?bilateralchosefairId=$id"
        EmploymentSectionType.Recruitment,
        EmploymentSectionType.Internship -> "/f/recruitmentinfo/show?recruitmentId=$id"
    }

private fun collectEmploymentLinks(contentHtml: String, pageUrl: String): List<EmploymentArticleAttachment> {
    val document = Jsoup.parse(contentHtml, pageUrl)
    val linked = document.select("a[href]").mapNotNull { element ->
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        EmploymentArticleAttachment(
            name = normalizeSpace(element.text()).ifBlank { filenameFromUrl(href).ifBlank { href } },
            url = absoluteEmploymentUrl(href, pageUrl),
        )
    }
    val plain = normalizeSpace(document.text())
    val rawUrls = Regex("""https?://[^\s，。；;）)>\"']+""")
        .findAll(plain)
        .map { it.value.trimEnd('.', ',', ';', ':') }
        .map { EmploymentArticleAttachment(name = it, url = it) }
        .toList()
    return (linked + rawUrls).distinctBy { it.url }
}

private fun htmlToText(value: String?, pageUrl: String): String? =
    value?.let { normalizeSpace(Jsoup.parse(it, pageUrl).text()) }?.takeIf { it.isNotBlank() }

private fun absoluteEmploymentUrl(value: String, pageUrl: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return trimmed
    return runCatching {
        URI(pageUrl).resolve(trimmed).toString()
    }.getOrElse {
        if (trimmed.startsWith("/")) ProviderConstants.JOB_BASE_URL + trimmed else trimmed
    }
}

private fun isExternalEmploymentUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host ?: return false
    return !host.equals("job.bjtu.edu.cn", ignoreCase = true)
}

private fun filenameFromUrl(value: String): String =
    runCatching {
        URI(value).path.substringAfterLast('/').substringBefore('?')
    }.getOrDefault(value.substringAfterLast('/')).trim()

private fun JsonObject.fileAttachment(key: String, pageUrl: String): EmploymentArticleAttachment? {
    val rawUrl = string(key) ?: return null
    return EmploymentArticleAttachment(
        name = filenameFromUrl(rawUrl).ifBlank { "附件下载" },
        url = absoluteEmploymentUrl(rawUrl, pageUrl),
    )
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonObject.obj(key: String): JsonObject? = this[key]?.asObject()

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.employmentInt(key: String): Int? =
    string(key)?.toIntOrNull()

private fun JsonObject.employmentBool(key: String): Boolean =
    string(key)?.equals("true", ignoreCase = true) == true || string(key) == "1"
