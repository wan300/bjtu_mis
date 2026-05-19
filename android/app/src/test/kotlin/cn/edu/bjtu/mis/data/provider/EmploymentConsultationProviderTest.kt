package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
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
    fun fetchHomeUsesFourCoreListsAndGuidanceData() = runBlocking {
        MockWebServer().use { server ->
            var careerToken = ""
            var jobFairToken = ""
            val recruitmentBodies = mutableListOf<String>()
            var guidanceBody = ""
            var guidanceDetailToken = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    return when (path) {
                        "/f/ajaxHome/getToken" -> json("""{"state":1,"msg":"ok","data":"TOKEN"}""")
                        "/f/ajaxHome/ajax_findRecruitmentFairLimitList" -> {
                            careerToken = request.getHeader("token").orEmpty()
                            assertEquals("10", request.requestUrl?.queryParameter("num"))
                            assertEquals("1", request.requestUrl?.queryParameter("positionType"))
                            json(
                                """
                                {"state":1,"object":[{
                                  "id":"talk-id",
                                  "title":"校园宣讲会",
                                  "startTime":"2026-05-21 15:00:00",
                                  "field":{"name":"就业中心118"},
                                  "corporationinfo":{"name":"宣讲单位"},
                                  "url":"/f/recruitmentFair/show?recruitmentFairId=talk-id"
                                }]}
                                """.trimIndent(),
                            )
                        }
                        "/f/ajaxHome/ajax_findBilateralchosefairLimitList" -> {
                            jobFairToken = request.getHeader("token").orEmpty()
                            assertEquals("10", request.requestUrl?.queryParameter("num"))
                            json(
                                """
                                {"state":1,"object":[{
                                  "id":"fair-id",
                                  "title":"春季双选会",
                                  "startTime":"2026-05-22 09:00:00",
                                  "place":"线上",
                                  "url":"/f/bilateralchosefair/show?bilateralchosefairId=fair-id"
                                }]}
                                """.trimIndent(),
                            )
                        }
                        "/f/ajaxHome/ajax_findRecruitmentinfoLimitList" -> {
                            val body = request.body.readUtf8()
                            recruitmentBodies += body
                            val positionType = Regex("""positionType=([^&]+)""").find(body)?.groupValues?.get(1)
                            json(recruitmentListJson(positionType ?: "1"))
                        }
                        "/f/newsCenter/ajax_list" -> {
                            guidanceBody = request.body.readUtf8()
                            json(
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
                                """.trimIndent(),
                            )
                        }
                        "/f/newsCenter/ajax_view" -> {
                            guidanceDetailToken = request.getHeader("token").orEmpty()
                            assertEquals("guide-id", request.requestUrl?.queryParameter("id"))
                            json(
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
                                """.trimIndent(),
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val provider = EmploymentConsultationProvider(
                client = BjtuHttpClient(AppCookieJar()),
                baseUrl = server.url("/").toString().trimEnd('/'),
            )

            val home = provider.fetchConsultationHome(pageSize = 10)

            assertEquals("TOKEN", careerToken)
            assertEquals("TOKEN", jobFairToken)
            assertEquals("TOKEN", guidanceDetailToken)
            assertTrue(recruitmentBodies.any { it.contains("positionType=1") })
            assertTrue(recruitmentBodies.any { it.contains("positionType=2") })
            assertEquals(ProviderConstants.JOB_GUIDANCE_CATEGORY_ID, Regex("""categoryId=([^&]+)""").find(guidanceBody)?.groupValues?.get(1))
            assertEquals("employment_consultation", home.module)
            assertEquals(4, home.data.sections.size)
            assertEquals("校园宣讲会", home.data.sections.first { it.type == EmploymentSectionType.CareerTalk }.items.single().title)
            assertEquals("招聘信息", home.data.sections.first { it.type == EmploymentSectionType.Recruitment }.title)
            assertEquals("http://bjtu.jysd.com/consult", home.data.appointmentUrl)
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

            val provider = EmploymentConsultationProvider(
                client = BjtuHttpClient(AppCookieJar()),
                baseUrl = server.url("/").toString().trimEnd('/'),
            )

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

    private fun recruitmentListJson(positionType: String): String =
        """
        {"state":1,"object":[{
          "id":"${if (positionType == "2") "intern-id" else "job-id"}",
          "title":"${if (positionType == "2") "实习信息" else "招聘信息"}",
          "positionType":"$positionType",
          "positionNum":1,
          "education":"本科",
          "url":"/f/recruitmentinfo/show?recruitmentId=${if (positionType == "2") "intern-id" else "job-id"}",
          "corporationinfo":{"name":"招聘单位"}
        }]}
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
