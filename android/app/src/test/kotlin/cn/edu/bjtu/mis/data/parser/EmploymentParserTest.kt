package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.provider.ProviderConstants
import cn.edu.bjtu.mis.model.EmploymentInfoQuery
import cn.edu.bjtu.mis.model.EmploymentSectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentParserTest {
    @Test
    fun parseArticleListNormalizesUrlsAndText() {
        val articles = parseEmploymentArticleList(
            """
            {
              "state": 1,
              "msg": "操作成功",
              "object": {
                "newsPage": {
                  "list": [{
                    "id": "guide-id",
                    "title": "个性化咨询",
                    "releaseDate": "2020-10-08 14:53",
                    "url": "/frontpage/bjtu/html/newsDetail.html?id=guide-id",
                    "description": "<p>一对一 咨询</p>",
                    "publisher": "就业指导中心"
                  }, {
                    "id": "external-id",
                    "title": "微信推文",
                    "url": "https://mp.weixin.qq.com/s/demo"
                  }]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(2, articles.size)
        assertEquals("个性化咨询", articles.first().title)
        assertEquals("${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/newsDetail.html?id=guide-id", articles.first().url)
        assertEquals("一对一 咨询", articles.first().description)
        assertTrue(articles[1].isExternalLink)
    }

    @Test
    fun parseDetailExtractsPlainTextAttachmentsAndAppointmentUrl() {
        val detail = parseEmploymentArticleDetail(
            """
            {
              "state": 1,
              "msg": "操作成功",
              "object": {
                "article": {
                  "id": "guide-id",
                  "title": "个性化咨询",
                  "releaseDate": "2020-10-08 14:53",
                  "publisher": "就业指导中心",
                  "url": "/frontpage/bjtu/html/newsDetail.html?id=guide-id",
                  "articleData": {
                    "content": "<p>预约链接：http://bjtu.jysd.com/consult</p><a href=\"/userfiles/demo.pdf\">附件</a>"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("个性化咨询", detail.title)
        assertTrue(detail.contentText.contains("预约链接"))
        assertEquals("http://bjtu.jysd.com/consult", extractEmploymentAppointmentUrl(detail))
        assertTrue(detail.attachments.any { it.url == "${ProviderConstants.JOB_BASE_URL}/userfiles/demo.pdf" })
    }

    @Test
    fun parseEmploymentInfoListsForFourSections() {
        val careerTalks = parseEmploymentInfoList(
            """
            {
              "state": 1,
              "object": [{
                "id": "talk-id",
                "title": "校园宣讲会",
                "startTime": "2026-05-21 15:00:00",
                "field": {"name": "就业中心118"},
                "browseNumber": "22",
                "corporationinfo": {"name": "南山集团", "logoUrl": "/logo.png"},
                "url": "/f/recruitmentFair/show?recruitmentFairId=talk-id"
              }]
            }
            """.trimIndent(),
            EmploymentSectionType.CareerTalk,
        )
        val jobFairs = parseEmploymentInfoList(
            """
            {
              "state": 1,
              "object": [{
                "id": "fair-id",
                "title": "春季双选会",
                "startTime": "2026-05-22 09:00:00",
                "endTime": "2026-05-22 17:00:00",
                "place": "线上",
                "holdStatus": "进行中",
                "url": "/f/bilateralchosefair/show?bilateralchosefairId=fair-id"
              }]
            }
            """.trimIndent(),
            EmploymentSectionType.JobFair,
        )
        val recruitments = parseEmploymentInfoList(
            recruitmentListJson(positionType = "1", title = "正式招聘", id = "job-id"),
            EmploymentSectionType.Recruitment,
        )
        val internships = parseEmploymentInfoList(
            recruitmentListJson(positionType = "2", title = "实习招聘", id = "intern-id"),
            EmploymentSectionType.Internship,
        )

        assertEquals("就业中心118", careerTalks.single().location)
        assertEquals("南山集团", careerTalks.single().organization)
        assertEquals("进行中", jobFairs.single().statusLabel)
        assertEquals(2, recruitments.single().positionCount)
        assertEquals("本科", internships.single().education)
        assertFalse(recruitments.single().isExternalLink)
    }

    @Test
    fun parseEmploymentInfoPageReadsPagedObjectList() {
        val page = parseEmploymentInfoPage(
            """
            {
              "state": 1,
              "object": {
                "pageNo": 2,
                "pageSize": 15,
                "count": 31,
                "totalPage": 3,
                "list": [{
                  "id": "job-id-2",
                  "title": "算法工程师招聘",
                  "positionNum": 3,
                  "education": "硕士",
                  "cityName": "北京市",
                  "url": "/f/recruitmentinfo/show?recruitmentId=job-id-2",
                  "corporationinfo": {
                    "name": "招聘单位"
                  }
                }]
              }
            }
            """.trimIndent(),
            EmploymentInfoQuery(
                type = EmploymentSectionType.Recruitment,
                pageNo = 2,
                pageSize = 15,
                title = "算法",
            ),
        )

        assertEquals(2, page.pageNo)
        assertEquals(15, page.pageSize)
        assertEquals(31, page.totalCount)
        assertEquals(3, page.totalPage)
        assertTrue(page.hasNext)
        assertEquals("算法工程师招聘", page.items.single().title)
        assertEquals("北京市", page.items.single().location)
    }

    @Test
    fun parseEmploymentFilterOptionsReadsAdvancedFilters() {
        val options = parseEmploymentFilterOptions(
            """
            {
              "state": 1,
              "object": {
                "corporationNature": [
                  {"value": "01", "label": "机关"},
                  {"value": "02", "label": "国有企业"}
                ],
                "industry": [
                  {"value": "it", "label": "信息传输、软件和信息技术服务业"}
                ],
                "positionType": [
                  {"value": "1", "label": "招聘信息"},
                  {"value": "2", "label": "实习信息"}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("机关", options.corporationNatures.first().label)
        assertEquals("it", options.industries.single().value)
        assertEquals("实习信息", options.positionTypes[1].label)
    }

    @Test
    fun parseCareerTalkDetailExtractsPositionsContentAndAttachments() {
        val detail = parseEmploymentInfoDetail(
            """
            {
              "state": 1,
              "object": {
                "recruitmentFair": {
                  "id": "talk-id",
                  "title": "南山电力宣讲会",
                  "corporationName": "南山集团",
                  "startTime": "2026-05-21 15:00:00",
                  "field": {"name": "就业中心118"},
                  "content": "<p>招聘简章</p><a href=\"/files/talk.pdf\">附件</a>",
                  "fileUrl": "/files/extra.docx",
                  "url": "/f/recruitmentFair/show?recruitmentFairId=talk-id",
                  "recruitmentFairPositionList": [{
                    "positionName": "电气储备人才",
                    "studentType": "本科",
                    "demandNumber": "10",
                    "majorName": "电气工程",
                    "cityName": "山东省烟台市",
                    "positionDescription": "<p>设备维护</p>"
                  }],
                  "corporationinfo": {
                    "name": "南山集团",
                    "corporationNatureValue": "民营企业",
                    "corporationScaleValue": "500人以上"
                  }
                }
              }
            }
            """.trimIndent(),
            EmploymentSectionType.CareerTalk,
        )

        assertEquals("南山电力宣讲会", detail.title)
        assertEquals("就业中心118", detail.location)
        assertEquals("电气储备人才", detail.positions.single().name)
        assertTrue(detail.contentText.contains("招聘简章"))
        assertTrue(detail.attachments.any { it.url == "${ProviderConstants.JOB_BASE_URL}/files/talk.pdf" })
        assertTrue(detail.attachments.any { it.url == "${ProviderConstants.JOB_BASE_URL}/files/extra.docx" })
    }

    @Test
    fun parseJobFairDetailExtractsParticipants() {
        val detail = parseEmploymentInfoDetail(
            """
            {
              "state": 1,
              "object": {
                "bilateralchosefair": {
                  "id": "fair-id",
                  "title": "春季双选会",
                  "organizer": "就业中心",
                  "startTime": "2026-05-22 09:00:00",
                  "endTime": "2026-05-22 17:00:00",
                  "place": "线上",
                  "content": "<p>双选会说明</p>",
                  "url": "/f/bilateralchosefair/show?bilateralchosefairId=fair-id",
                  "bilateralchosefairList": [{
                    "demand": "5",
                    "standNo": "A01",
                    "corporationinfo": {"name": "参会企业"}
                  }]
                }
              }
            }
            """.trimIndent(),
            EmploymentSectionType.JobFair,
        )

        assertEquals("春季双选会", detail.title)
        assertEquals("参会企业", detail.positions.single().name)
        assertEquals("5", detail.positions.single().demandNumber)
        assertTrue(detail.contentText.contains("双选会说明"))
    }

    @Test
    fun parseRecruitmentDetailExtractsCompanyPositionsAndAttachment() {
        val detail = parseEmploymentInfoDetail(
            """
            {
              "state": 1,
              "object": {
                "recruitmentinfo": {
                  "id": "job-id",
                  "title": "宇树科技招聘",
                  "positionType": "1",
                  "startTime": "2026-05-19 16:28:37",
                  "endTime": "2026-11-15 00:00:00",
                  "content": "<p>招聘正文 <a href=\"https://example.com/apply\">网申</a></p>",
                  "fileURL": "/files/job.pdf",
                  "url": "/f/recruitmentinfo/show?recruitmentId=job-id",
                  "resumeReceiveEmail": "hr@example.com",
                  "onlineApplicationUrl": "https://example.com/apply",
                  "corporationinfo": {
                    "name": "宇树科技",
                    "officialWebsite": "https://www.unitree.com",
                    "corporationNatureValue": "民营企业",
                    "corporationScaleValue": "200人-500人",
                    "introduction": "<p>机器人企业</p>"
                  },
                  "recruitmentPositionList": [{
                    "positionName": "AI算法工程师",
                    "studentType": "本科",
                    "demandNumber": "20",
                    "majorName": "不限专业",
                    "cityName": "浙江省杭州市",
                    "positionDescription": "负责大模型训练"
                  }]
                }
              }
            }
            """.trimIndent(),
            EmploymentSectionType.Recruitment,
        )

        assertEquals("宇树科技招聘", detail.title)
        assertEquals("宇树科技", detail.company?.name)
        assertEquals("AI算法工程师", detail.positions.single().name)
        assertEquals("hr@example.com", detail.resumeReceiveEmail)
        assertTrue(detail.attachments.any { it.url == "https://example.com/apply" })
        assertTrue(detail.attachments.any { it.url == "${ProviderConstants.JOB_BASE_URL}/files/job.pdf" })
    }

    private fun recruitmentListJson(positionType: String, title: String, id: String): String =
        """
        {
          "state": 1,
          "object": [{
            "id": "$id",
            "title": "$title",
            "positionType": "$positionType",
            "startTime": "2026-05-19 16:28:37",
            "endTime": "2026-11-15 00:00:00",
            "positionNum": 2,
            "education": "本科",
            "majorName": "不限专业",
            "cityName": "北京市",
            "url": "/f/recruitmentinfo/show?recruitmentId=$id",
            "corporationinfo": {
              "name": "招聘单位",
              "corporationNatureValue": "民营企业",
              "corporationScaleValue": "200人-500人"
            }
          }]
        }
        """.trimIndent()
}
