package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.model.ScoreItem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AaProviderTest {
    @Test
    fun mergeScoreItemsDeduplicatesAcrossProgressiveTerms() {
        val duplicate = score("2025-2026-1", "软件工程", "90")
        val merged = mergeScoreItems(
            listOf(
                duplicate,
                score("2025-2026-1", "软件工程", "90"),
                score("2025-2026-2", "软件工程", "91"),
            )
        )

        assertEquals(listOf(duplicate, score("2025-2026-2", "软件工程", "91")), merged)
    }

    @Test
    fun fetchCourseSelectionReadsCourses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertEquals("course_selection", envelope.module)
            assertTrue(envelope.data.canSubmit)
            assertEquals("M410003B_01", envelope.data.availableCourses.first().key)
            assertEquals("/course_selection/courseselecttask/selects/", server.takeRequest().path)
        }
    }

    @Test
    fun attemptCourseSelectionPostsParsedForm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(selectedHtml()))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelection(courseKey = "M410003B_01")

            assertEquals("success", result.status)
            server.takeRequest()
            val submit = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/submit/", submit.path)
            val body = submit.body.readUtf8()
            assertTrue(body.contains("csrfmiddlewaretoken=csrf-token"))
            assertTrue(body.contains("selects=target-1"))
        }
    }

    @Test
    fun attemptCourseSelectionReturnsCaptchaChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody(captchaHtml("1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-1"))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelection(courseKey = "M410003B_01")

            assertEquals("captcha_required", result.status)
            assertTrue(result.captchaChallenge?.imageDataUrl?.startsWith("data:image/png;base64,") == true)
            server.takeRequest()
            server.takeRequest()
            assertEquals("/captcha/image/1.png", server.takeRequest().path)
        }
    }

    @Test
    fun submitCourseSelectionCaptchaCanSucceed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody(captchaHtml("1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-1"))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(selectedHtml()))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelection(courseKey = "M410003B_01").captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "1234")

            assertEquals("success", result.status)
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            val captchaSubmit = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/captcha/", captchaSubmit.path)
            assertTrue(captchaSubmit.body.readUtf8().contains("captcha=1234"))
        }
    }

    @Test
    fun submitCourseSelectionCaptchaCanReturnNextChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody(captchaHtml("1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-1"))
            server.enqueue(MockResponse().setBody(captchaHtml("2")))
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-2"))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelection(courseKey = "M410003B_01").captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "wrong")

            assertEquals("captcha_required", result.status)
            assertTrue(result.captchaChallenge?.challengeId != challenge.challengeId)
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            assertEquals("/captcha/image/2.png", server.takeRequest().path)
        }
    }

    @Test
    fun dropCourseSelectionPostsDeleteForm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false)))
            server.start()
            val provider = provider(server)

            val result = provider.dropCourseSelection(courseKey = "M310005B_01")

            assertEquals("drop_success", result.status)
            server.takeRequest()
            val drop = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/delete/", drop.path)
            val body = drop.body.readUtf8()
            assertTrue(body.contains("csrfmiddlewaretoken=csrf-token"))
            assertTrue(body.contains("pk=selected-1"))
        }
    }

    @Test
    fun replaceCourseSelectionDropsThenSelectsTarget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = true, selectedTarget = false)))
            server.enqueue(MockResponse().setBody("<div class='alert'>drop ok</div>"))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false, selectedTarget = false)))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false, selectedTarget = false)))
            server.enqueue(MockResponse().setBody("<div class='alert'>select ok</div>"))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false, selectedTarget = true)))
            server.start()
            val provider = provider(server)

            val result = provider.replaceCourseSelection(
                targetCourseKey = "M410003B_01",
                dropCourseKey = "M310005B_01",
            )

            assertEquals("replace_success", result.status)
            server.takeRequest()
            assertEquals("/course_selection/courseselecttask/delete/", server.takeRequest().path)
            server.takeRequest()
            server.takeRequest()
            val select = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/submit/", select.path)
            assertTrue(select.body.readUtf8().contains("selects=target-1"))
        }
    }

    @Test
    fun replaceCourseSelectionTargetNoRemainingDoesNotDrop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = true, selectedTarget = false, targetRemaining = 0)))
            server.start()
            val provider = provider(server)

            val result = provider.replaceCourseSelection(
                targetCourseKey = "M410003B_01",
                dropCourseKey = "M310005B_01",
            )

            assertEquals("target_no_remaining", result.status)
            assertEquals(1, server.requestCount)
        }
    }

    private fun provider(server: MockWebServer): AaProvider {
        val baseUrl = server.url("/").toString().trimEnd('/')
        return AaProvider(BjtuHttpClient(AppCookieJar()), aaBaseUrl = baseUrl)
    }

    private fun score(term: String, courseName: String, score: String): ScoreItem =
        ScoreItem(
            term = term,
            courseName = courseName,
            credit = "2",
            score = score,
            bonusScore = null,
            teacher = "Teacher",
            detail = null,
            detailPath = null,
        )

    private fun text(name: String): String {
        val resource = javaClass.getResource("/fixtures/$name")
            ?: error("Missing test fixture: $name")
        return resource.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun captchaHtml(id: String): String = """
        <html><body>
          <div class="modal">
            <form method="post" action="/course_selection/courseselecttask/captcha/">
              <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
              <img src="/captcha/image/$id.png" />
              <input type="text" name="captcha" />
            </form>
          </div>
        </body></html>
    """.trimIndent()

    private fun selectedHtml(): String = """
        <html><body>
          <form method="post" action="/course_selection/courseselecttask/submit/">
            <div id="selected-container">
              <table class="table table-bordered">
                <tr><th>Action</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
                <tr><td></td><td>M410003B Platform Software Design 01 Software School</td><td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td></tr>
              </table>
            </div>
            <table class="table table-bordered"><tr><th>Other</th><th>Value</th></tr></table>
            <table class="table table-bordered">
              <tr><th>Select</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
              <tr><td>Selected</td><td>M410003B Platform Software Design 01 Software School</td><td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td></tr>
            </table>
            <a class="btn btn-primary" href="#">Submit</a>
          </form>
        </body></html>
    """.trimIndent()

    private fun replaceHtml(
        selectedOs: Boolean = true,
        selectedTarget: Boolean = false,
        targetRemaining: Int = 2,
    ): String {
        val selectedRows = buildString {
            if (selectedOs) {
                append(
                    """
                    <tr>
                      <td><a class="select-delete-btn" data-pk="selected-1">Delete</a></td>
                      <td>M310005B Operating Systems 01 Software School</td>
                      <td>1</td><td>3</td><td>Required</td><td>Exam</td><td>Teacher A</td><td>Mon 1-2 Room 101</td>
                    </tr>
                    """.trimIndent(),
                )
            }
            if (selectedTarget) {
                append(
                    """
                    <tr>
                      <td><a class="select-delete-btn" data-pk="target-1">Delete</a></td>
                      <td>M410003B Platform Software Design 01 Software School</td>
                      <td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
                    </tr>
                    """.trimIndent(),
                )
            }
        }
        val osAvailable = if (selectedOs) "" else """
            <tr>
              <td><input type="checkbox" name="selects" value="selected-1"></td>
              <td>M310005B Operating Systems 01 Software School</td>
              <td>1</td><td>3</td><td>Required</td><td>Exam</td><td>Teacher A</td><td>Mon 1-2 Room 101</td>
            </tr>
        """.trimIndent()
        val targetAction = if (selectedTarget) {
            "Selected"
        } else {
            """<input type="checkbox" name="selects" value="target-1">"""
        }
        return """
            <html><body>
              <form method="post" action="/course_selection/courseselecttask/submit/">
                <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
                <div id="selected-container">
                  <table class="table table-bordered">
                    <tr><th>Action</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
                    $selectedRows
                  </table>
                </div>
                <table class="table table-bordered"><tr><th>Other</th><th>Value</th></tr></table>
                <table class="table table-bordered">
                  <tr><th>Select</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
                  $osAvailable
                  <tr>
                    <td>$targetAction</td>
                    <td>M410003B Platform Software Design 01 Software School</td>
                    <td>$targetRemaining</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
                  </tr>
                </table>
                <a class="btn btn-primary" href="#">Submit</a>
              </form>
            </body></html>
        """.trimIndent()
    }
}
