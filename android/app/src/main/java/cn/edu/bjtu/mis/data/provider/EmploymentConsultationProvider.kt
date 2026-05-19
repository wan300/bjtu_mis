package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.parser.defaultEmploymentContacts
import cn.edu.bjtu.mis.data.parser.extractEmploymentAppointmentUrl
import cn.edu.bjtu.mis.data.parser.parseEmploymentArticleDetail
import cn.edu.bjtu.mis.data.parser.parseEmploymentArticleList
import cn.edu.bjtu.mis.data.parser.parseEmploymentInfoDetail
import cn.edu.bjtu.mis.data.parser.parseEmploymentInfoList
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.EmploymentArticleDetail
import cn.edu.bjtu.mis.model.EmploymentConsultationData
import cn.edu.bjtu.mis.model.EmploymentInfoDetail
import cn.edu.bjtu.mis.model.EmploymentInfoSection
import cn.edu.bjtu.mis.model.EmploymentSectionType
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException

class EmploymentConsultationProvider(
    private val client: BjtuHttpClient,
    private val baseUrl: String = ProviderConstants.JOB_BASE_URL,
) {
    suspend fun fetchConsultationHome(pageSize: Int = 10): ModuleEnvelope<EmploymentConsultationData> {
        val token = fetchToken()
        val normalizedPageSize = pageSize.coerceAtLeast(1)
        val homePageUrl = "${baseUrl}/frontpage/bjtu/html/index.html"

        val sections = listOf(
            EmploymentInfoSection(
                type = EmploymentSectionType.CareerTalk,
                title = "宣讲会",
                listUrl = url("/frontpage/bjtu/html/recruitmentFairList.html?type=3"),
                items = fetchInfoList(token, EmploymentSectionType.CareerTalk, normalizedPageSize, homePageUrl),
            ),
            EmploymentInfoSection(
                type = EmploymentSectionType.JobFair,
                title = "双选会",
                listUrl = url("/frontpage/bjtu/html/bilateralchosefairList.html?type=4"),
                items = fetchInfoList(token, EmploymentSectionType.JobFair, normalizedPageSize, homePageUrl),
            ),
            EmploymentInfoSection(
                type = EmploymentSectionType.Recruitment,
                title = "招聘信息",
                listUrl = url("/frontpage/bjtu/html/recruitmentinfoList.html?type=1"),
                items = fetchInfoList(token, EmploymentSectionType.Recruitment, normalizedPageSize, homePageUrl),
            ),
            EmploymentInfoSection(
                type = EmploymentSectionType.Internship,
                title = "实习信息",
                listUrl = url("/frontpage/bjtu/html/recruitmentinfoList.html?type=2"),
                items = fetchInfoList(token, EmploymentSectionType.Internship, normalizedPageSize, homePageUrl),
            ),
        )

        val listResponse = client.postForm(
            url("/f/newsCenter/ajax_list"),
            params = timestampParam(),
            form = mapOf(
                "categoryId" to ProviderConstants.JOB_GUIDANCE_CATEGORY_ID,
                "pageNo" to "1",
                "pageSize" to normalizedPageSize.toString(),
            ),
            headers = jobHeaders(token, referer = "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/newsList.html?id=${ProviderConstants.JOB_GUIDANCE_CATEGORY_ID}"),
        )
        ensureJobSuccess(listResponse.body)
        val articles = parseEmploymentArticleList(listResponse.body)
        val guide = articles
            .firstOrNull { it.title.contains("个性化咨询") }
            ?.let { summary -> runCatching { fetchArticleDetail(summary.id, token) }.getOrNull() }
        val appointmentUrl = extractEmploymentAppointmentUrl(guide)

        return ModuleEnvelope(
            module = ModuleKeys.EmploymentConsultation,
            sourceSystem = "job",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                put("category_id", ProviderConstants.JOB_GUIDANCE_CATEGORY_ID)
                put("page_size", normalizedPageSize)
            },
            data = EmploymentConsultationData(
                sections = sections,
                articles = articles,
                consultationGuide = guide,
                contacts = defaultEmploymentContacts(),
                appointmentUrl = appointmentUrl,
                sourceUrl = ProviderConstants.JOB_HOME_URL,
            ),
        )
    }

    suspend fun fetchInfoDetail(
        type: EmploymentSectionType,
        itemId: String,
    ): ModuleEnvelope<EmploymentInfoDetail> {
        val normalizedId = itemId.trim()
        if (normalizedId.isBlank()) throw IllegalArgumentException("itemId is blank")
        val token = fetchToken()
        val detailReferer = detailReferer(type, normalizedId)
        val response = when (type) {
            EmploymentSectionType.CareerTalk -> client.postForm(
                url("/f/recruitmentFair/ajax_show"),
                form = mapOf("recruitmentFairId" to normalizedId),
                headers = jobHeaders(token, referer = detailReferer),
            )
            EmploymentSectionType.JobFair -> client.postForm(
                url("/f/bilateralchosefair/ajax_show"),
                form = mapOf("bilateralchosefairId" to normalizedId),
                headers = jobHeaders(token, referer = detailReferer),
            )
            EmploymentSectionType.Recruitment,
            EmploymentSectionType.Internship -> client.postForm(
                url("/f/recruitmentinfo/ajax_show"),
                form = mapOf("recruitmentId" to normalizedId),
                headers = jobHeaders(token, referer = detailReferer),
            )
        }
        ensureJobSuccess(response.body)
        return ModuleEnvelope(
            module = "employment_info_detail",
            sourceSystem = "job",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject {
                put("type", type.name)
                put("item_id", normalizedId)
            },
            data = parseEmploymentInfoDetail(response.body, type, detailReferer),
        )
    }

    suspend fun fetchArticle(articleId: String): ModuleEnvelope<EmploymentArticleDetail> {
        val token = fetchToken()
        val detail = fetchArticleDetail(articleId, token)
        return ModuleEnvelope(
            module = "employment_consultation_article",
            sourceSystem = "job",
            coverage = CoverageLevel.Verified,
            sourceParams = buildJsonObject { put("article_id", articleId.trim()) },
            data = detail,
        )
    }

    private suspend fun fetchArticleDetail(articleId: String, token: String): EmploymentArticleDetail {
        val normalizedId = articleId.trim()
        if (normalizedId.isBlank()) throw IllegalArgumentException("articleId is blank")
        val response = client.getText(
            url("/f/newsCenter/ajax_view"),
            params = timestampParam() + ("id" to normalizedId),
            headers = jobHeaders(token, referer = "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/newsDetail.html?id=$normalizedId"),
        )
        ensureJobSuccess(response.body)
        return parseEmploymentArticleDetail(response.body)
    }

    private suspend fun fetchInfoList(
        token: String,
        type: EmploymentSectionType,
        pageSize: Int,
        pageUrl: String,
    ) = when (type) {
        EmploymentSectionType.CareerTalk -> client.getText(
            url("/f/ajaxHome/ajax_findRecruitmentFairLimitList"),
            params = timestampParam() + mapOf("num" to pageSize.toString(), "positionType" to "1"),
            headers = jobHeaders(token, referer = pageUrl),
        )
        EmploymentSectionType.JobFair -> client.getText(
            url("/f/ajaxHome/ajax_findBilateralchosefairLimitList"),
            params = timestampParam() + ("num" to pageSize.toString()),
            headers = jobHeaders(token, referer = pageUrl),
        )
        EmploymentSectionType.Recruitment -> client.postForm(
            url("/f/ajaxHome/ajax_findRecruitmentinfoLimitList"),
            params = timestampParam(),
            form = mapOf("num" to pageSize.toString(), "positionType" to "1"),
            headers = jobHeaders(token, referer = pageUrl),
        )
        EmploymentSectionType.Internship -> client.postForm(
            url("/f/ajaxHome/ajax_findRecruitmentinfoLimitList"),
            params = timestampParam(),
            form = mapOf("num" to pageSize.toString(), "positionType" to "2"),
            headers = jobHeaders(token, referer = pageUrl),
        )
    }.also {
        ensureJobSuccess(it.body)
    }.let {
        parseEmploymentInfoList(it.body, type, pageUrl)
    }

    private suspend fun fetchToken(): String {
        val response = client.getText(
            url("/f/ajaxHome/getToken"),
            params = timestampParam(),
            headers = jobHeaders(token = null, referer = ProviderConstants.JOB_HOME_URL),
        )
        ensureJobSuccess(response.body)
        val root = AppJson.parseToJsonElement(response.body) as? JsonObject
            ?: throw IOException("Invalid employment token response")
        return (root["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("Employment token response is empty")
    }

    private fun ensureJobSuccess(body: String) {
        val root = AppJson.parseToJsonElement(body) as? JsonObject
            ?: throw IOException("Invalid employment response")
        val state = (root["state"] as? JsonPrimitive)?.contentOrNull
        if (state != "1") {
            val message = (root["msg"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            throw IOException(message ?: "Employment service returned state=$state")
        }
    }

    private fun jobHeaders(token: String?, referer: String): Map<String, String> =
        buildMap {
            put("Referer", referer)
            put("Origin", baseUrl)
            put("X-Requested-With", "XMLHttpRequest")
            token?.takeIf { it.isNotBlank() }?.let { put("token", it) }
        }

    private fun timestampParam(): Map<String, String> =
        mapOf("ts" to System.currentTimeMillis().toString())

    private fun detailReferer(type: EmploymentSectionType, itemId: String): String =
        when (type) {
            EmploymentSectionType.CareerTalk -> url("/f/recruitmentFair/show?recruitmentFairId=$itemId")
            EmploymentSectionType.JobFair -> url("/f/bilateralchosefair/show?bilateralchosefairId=$itemId")
            EmploymentSectionType.Recruitment,
            EmploymentSectionType.Internship -> url("/f/recruitmentinfo/show?recruitmentId=$itemId")
        }

    private fun url(path: String): String =
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            path
        } else {
            "$baseUrl/${path.trimStart('/')}"
        }
}
