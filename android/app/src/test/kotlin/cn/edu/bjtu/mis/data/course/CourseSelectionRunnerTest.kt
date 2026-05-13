package cn.edu.bjtu.mis.data.course

import cn.edu.bjtu.mis.model.CourseSelectionAttemptResult
import cn.edu.bjtu.mis.model.CourseSelectionCaptchaChallenge
import cn.edu.bjtu.mis.model.CourseSelectionReplaceRule
import cn.edu.bjtu.mis.model.CourseSelectionRunConfig
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseSelectionRunnerTest {
    @Test
    fun marksCourseDoneAfterSuccess() = runBlocking {
        val target = CourseSelectionTarget("M410003B_01", "Platform Software")
        val runner = CourseSelectionRunner(
            selectCourse = { CourseSelectionAttemptResult(status = "success", message = "ok") },
            replaceCourse = { CourseSelectionAttemptResult(status = "replace_success", message = "ok") },
            submitCaptchaAnswer = { _, _ -> CourseSelectionAttemptResult(status = "failed") },
        )

        assertTrue(runner.start(CourseSelectionRunConfig(listOf(target), retryIntervalMillis = 0, maxRounds = 1)))
        waitUntil { target.key in runner.state.value.doneKeys && !runner.state.value.running }

        assertTrue(target.key in runner.state.value.doneKeys)
        assertTrue(runner.state.value.completed)
    }

    @Test
    fun waitsForCaptchaAndContinuesAfterSubmit() = runBlocking {
        val target = CourseSelectionTarget("M410003B_01", "Platform Software")
        val challenge = CourseSelectionCaptchaChallenge(
            challengeId = "captcha-1",
            imageDataUrl = "data:image/png;base64,cG5n",
            fetchedAt = "2026-05-09T00:00:00Z",
        )
        val runner = CourseSelectionRunner(
            selectCourse = { CourseSelectionAttemptResult(status = "captcha_required", captchaChallenge = challenge) },
            replaceCourse = { CourseSelectionAttemptResult(status = "replace_success") },
            submitCaptchaAnswer = { challengeId, captcha ->
                assertEquals("captcha-1", challengeId)
                assertEquals("1234", captcha)
                CourseSelectionAttemptResult(status = "success", message = "ok")
            },
        )

        runner.start(CourseSelectionRunConfig(listOf(target), retryIntervalMillis = 0, maxRounds = 1))
        waitUntil { runner.state.value.awaitingCaptcha != null }
        assertEquals(challenge.challengeId, runner.state.value.awaitingCaptcha?.challengeId)

        assertTrue(runner.submitCaptcha("1234"))
        waitUntil { !runner.state.value.running }

        assertTrue(target.key in runner.state.value.doneKeys)
        assertNull(runner.state.value.awaitingCaptcha)
    }

    @Test
    fun stopClearsPendingCaptcha() = runBlocking {
        val target = CourseSelectionTarget("M410003B_01", "Platform Software")
        val challenge = CourseSelectionCaptchaChallenge(
            challengeId = "captcha-1",
            imageDataUrl = "data:image/png;base64,cG5n",
            fetchedAt = "2026-05-09T00:00:00Z",
        )
        val runner = CourseSelectionRunner(
            selectCourse = { CourseSelectionAttemptResult(status = "captcha_required", captchaChallenge = challenge) },
            replaceCourse = { CourseSelectionAttemptResult(status = "replace_success") },
            submitCaptchaAnswer = { _, _ -> CourseSelectionAttemptResult(status = "success") },
        )

        runner.start(CourseSelectionRunConfig(listOf(target), retryIntervalMillis = 0, maxRounds = 10))
        waitUntil { runner.state.value.awaitingCaptcha != null }

        runner.stop()
        waitUntil { !runner.state.value.running }

        assertFalse(target.key in runner.state.value.doneKeys)
        assertNull(runner.state.value.awaitingCaptcha)
        assertFalse(runner.state.value.stopping)
    }

    @Test
    fun replaceRulesRunBeforeNormalTargetsAndMarkDone() = runBlocking {
        val target = CourseSelectionTarget("M410003B_01", "Platform Software")
        val drop = CourseSelectionTarget("M310005B_01", "Operating Systems")
        val rule = CourseSelectionReplaceRule("M410003B_01->M310005B_01", target, drop)
        val calls = mutableListOf<String>()
        val runner = CourseSelectionRunner(
            selectCourse = {
                calls += "select:${it.key}"
                CourseSelectionAttemptResult(status = "success")
            },
            replaceCourse = {
                calls += "replace:${it.target.key}:${it.drop.key}"
                CourseSelectionAttemptResult(status = "replace_success")
            },
            submitCaptchaAnswer = { _, _ -> CourseSelectionAttemptResult(status = "failed") },
        )

        assertTrue(
            runner.start(
                CourseSelectionRunConfig(
                    targets = listOf(target),
                    replaceRules = listOf(rule),
                    retryIntervalMillis = 0,
                    maxRounds = 1,
                ),
            ),
        )
        waitUntil { rule.id in runner.state.value.doneReplaceRuleIds && target.key in runner.state.value.doneKeys }

        assertEquals(listOf("replace:${target.key}:${drop.key}"), calls)
        assertTrue(runner.state.value.completed)
    }

    private suspend fun waitUntil(timeoutMs: Long = 1_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("Condition was not met within ${timeoutMs}ms")
    }
}
