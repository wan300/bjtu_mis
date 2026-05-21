package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.model.EmploymentInfoQuery
import cn.edu.bjtu.mis.model.EmploymentSectionType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentConsultationProviderTest {
    @Test
    fun fetchHomeUsesPagedCoreListsSearchOptionsAndGuidanceData() = runBlocking {
        MockWebServer().use { server ->
            val requestBodies = mutableMapOf<String, MutableList<String>>()
            var guidanceDetailToken = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    requestBodies.getOrPut(path) { mutableListOf() } += request.body.readUtf8()
                    return when (path) {
                        "/f/ajaxHome/getToken" -> json("""{"state":1,"msg":"ok","data":"TOKEN"}""")
                        "/f/recruitmentFair/ajax_frontRecruitfair" -> json(pagedListJson("talk-id", "校园宣讲会", EmploymentSectionType.CareerTalk))
                        "/f/bilateralchosefair/ajax_frontBilateralchosefair" -> json(pagedListJson("fair-id", "春季双选会", EmploymentSectionType.JobFair))
                        "/f/recruitmentinfo/ajax_frontRecruitinfo" -> {
                            val body = requestBodies.getValue(path).last()
                            val positionType = Regex("""positionType=([^&]+)""").find(body)?.groupValues?.get(1)
                            json(pagedListJson(if (positionType == "2") "intern-id" else "job-id", if (positionType == "2") "实习信息" else "招聘信息", EmploymentSectionType.Recruitment))
                        }
                        "/f/recruitmentinfo/ajax_search" -> json(filterOptionsJson())
                        "/f/newsCenter/ajax_list" -> json(guidanceListJson())
                        "/f/newsCenter/ajax_view" -> {
                            guidanceDetailToken = request.getHeader("token").orEmpty()
                            assertEquals("guide-id", request.requestUrl?.queryParameter("id"))
                            json(guidanceDetailJson())
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val provider = provider(server)

            val home = provider.fetchConsultationHome(pageSize = 10)

            assertTrue(requestBodies.getValue("/f/recruitmentFair/ajax_frontRecruitfair").single().contains("pageSize=10"))
            assertTrue(requestBodies.getValue("/f/bilateralchosefair/ajax_frontBilateralchosefair").single().contains("pageSize=10"))
            assertTrue(requestBodies.getValue("/f/recruitmentinfo/ajax_frontRecruitinfo").any { it.contains("positionType=1") })
            assertTrue(requestBodies.getValue("/f/recruitmentinfo/ajax_frontRecruitinfo").any { it.contains("positionType=2") })
            assertTrue(requestBodies.getValue("/f/recruitmentinfo/ajax_search").single().contains("positionType=1"))
            assertEquals("TOKEN", guidanceDetailToken)
            assertEquals("employment_consultation", home.module)
            assertEquals(4, home.data.sections.size)
            assertEquals("校园宣讲会", home.data.sections.first { it.type == EmploymentSectionType.CareerTalk }.items.single().title)
            assertEquals("机关", home.data.filters.corporationNatures.first().label)
            assertEquals("http://bjtu.jysd.com/consult", home.data.appointmentUrl)
        }
    }

    @Test
    fun fetchInfoPageSendsAdvancedRecruitmentFilters() = runBlocking {
        MockWebServer().use { server ->
            var body = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    return when (path) {
                        "/f/ajaxHome/getToken" -> json("""{"state":1,"msg":"ok","data":"TOKEN"}""")
                        "/f/recruitmentinfo/ajax_frontRecruitinfo" -> {
                            body = request.body.readUtf8()
                            json(pagedListJson("job-id", "算法工程师招聘", EmploymentSectionType.Recruitment))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val page = provider(server).fetchInfoPage(
                EmploymentInfoQuery(
                    type = EmploymentSectionType.Recruitment,
                    pageNo = 3,
                    pageSize = 15,
                    title = "算法",
                    city = "110000",
                    corporationNature = "02",
                    industry = "it",
                ),
            )

            assertTrue(body.contains("pageNo=3"))
            assertTrue(body.contains("pageSize=15"))
            assertTrue(body.contains("positionType=1"))
            assertTrue(body.contains("city=110000"))
            assertTrue(body.contains("title=%E7%AE%97%E6%B3%95"))
            assertTrue(body.contains("corporationNature=02"))
            assertTrue(body.contains("corporationinfo.industry=it"))
            assertEquals(3, page.data.pageNo)
            assertEquals("算法工程师招聘", page.data.items.single().title)
        }
    }

    @Test
    fun fetchInfoDetailUsesTypeSpecificEndpoints() = runBlocking {
        MockWebServer().use { server ->
            val requestBodies = mutableMapOf<String, String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    return when (path) {
                        "/f/ajaxHome/getToken" -> json("""{"state":1,"msg":"ok","data":"TOKEN"}""")
                        "/f/recruitmentFair/ajax_show" -> {
                            requestBodies[path] = request.body.readUtf8()
                            json(careerTalkDetailJson())
                        }
                        "/f/bilateralchosefair/ajax_show" -> {
                            requestBodies[path] = request.body.readUtf8()
                            json(jobFairDetailJson())
                        }
                        "/f/recruitmentinfo/ajax_show" -> {
                            requestBodies[path] = request.body.readUtf8()
                            json(recruitmentDetailJson())
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val provider = provider(server)

            val careerTalk = provider.fetchInfoDetail(EmploymentSectionType.CareerTalk, "talk-id")
            val jobFair = provider.fetchInfoDetail(EmploymentSectionType.JobFair, "fair-id")
            val recruitment = provider.fetchInfoDetail(EmploymentSectionType.Recruitment, "job-id")

            assertTrue(requestBodies.getValue("/f/recruitmentFair/ajax_show").contains("recruitmentFairId=talk-id"))
            assertTrue(requestBodies.getValue("/f/bilateralchosefair/ajax_show").contains("bilateralchosefairId=fair-id"))
            assertTrue(requestBodies.getValue("/f/recruitmentinfo/ajax_show").contains("recruitmentId=job-id"))
            assertEquals("校园宣讲会", careerTalk.data.title)
            assertEquals("春季双选会", jobFair.data.title)
            assertEquals("宇树科技招聘", recruitment.data.title)
        }
    }

    private fun provider(server: MockWebServer): EmploymentConsultationProvider =
        EmploymentConsultationProvider(
            client = BjtuHttpClient(AppCookieJar()),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )

    private fun pagedListJson(id: String, title: String, type: EmploymentSectionType): String {
        val url = when (type) {
            EmploymentSectionType.CareerTalk -> "/f/recruitmentFair/show?recruitmentFairId=$id"
            EmploymentSectionType.JobFair -> "/f/bilateralchosefair/show?bilateralchosefairId=$id"
            EmploymentSectionType.Recruitment,
            EmploymentSectionType.Internship -> "/f/recruitmentinfo/show?recruitmentId=$id"
        }
        return """
        {
          "state": 1,
          "object": {
            "pageNo": 3,
            "pageSize": 15,
            "count": 31,
            "totalPage": 3,
            "list": [{
              "id": "$id",
              "title": "$title",
              "startTime": "2026-05-21 15:00:00",
              "positionNum": 1,
              "education": "本科",
              "place": "线上",
              "url": "$url",
              "corporationinfo": {"name": "招聘单位"}
            }]
          }
        }
        """.trimIndent()
    }

    private fun filterOptionsJson(): String =
        """
        {
          "state": 1,
          "object": {
            "corporationNature": [{"value": "01", "label": "机关"}],
            "industry": [{"value": "it", "label": "信息传输、软件和信息技术服务业"}],
            "positionType": [{"value": "1", "label": "招聘信息"}, {"value": "2", "label": "实习信息"}]
          }
        }
        """.trimIndent()

    private fun guidanceListJson(): String =
        """
        {
          "state": 1,
          "msg": "ok",
          "object": {
            "newsPage": {
              "list": [{
                "id": "guide-id",
                "title": "个性化咨询",
                "releaseDate": "2020-10-08 14:53",
                "url": "/frontpage/bjtu/html/newsDetail.html?id=guide-id",
                "description": "咨询说明"
              }]
            }
          }
        }
        """.trimIndent()

    private fun guidanceDetailJson(): String =
        """
        {
          "state": 1,
          "msg": "ok",
          "object": {
            "article": {
              "id": "guide-id",
              "title": "个性化咨询",
              "url": "/frontpage/bjtu/html/newsDetail.html?id=guide-id",
              "articleData": {
                "content": "<p>预约：http://bjtu.jysd.com/consult</p>"
              }
            }
          }
        }
        """.trimIndent()

    private fun careerTalkDetailJson(): String =
        """
        {"state":1,"object":{"recruitmentFair":{
          "id":"talk-id",
          "title":"校园宣讲会",
          "url":"/f/recruitmentFair/show?recruitmentFairId=talk-id",
          "content":"<p>宣讲详情</p>"
        }}}
        """.trimIndent()

    private fun jobFairDetailJson(): String =
        """
        {"state":1,"object":{"bilateralchosefair":{
          "id":"fair-id",
          "title":"春季双选会",
          "url":"/f/bilateralchosefair/show?bilateralchosefairId=fair-id",
          "content":"<p>双选会详情</p>"
        }}}
        """.trimIndent()

    private fun recruitmentDetailJson(): String =
        """
        {"state":1,"object":{"recruitmentinfo":{
          "id":"job-id",
          "title":"宇树科技招聘",
          "url":"/f/recruitmentinfo/show?recruitmentId=job-id",
          "content":"<p>招聘详情</p>",
          "corporationinfo":{"name":"宇树科技"}
        }}}
        """.trimIndent()

    private fun json(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
}
