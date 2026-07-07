package cn.edu.bjtu.mis.data.course

import cn.edu.bjtu.mis.model.CourseSelectionAttemptResult
import cn.edu.bjtu.mis.model.CourseSelectionReplaceRule
import cn.edu.bjtu.mis.model.CourseSelectionRunConfig
import cn.edu.bjtu.mis.model.CourseSelectionRunState
import cn.edu.bjtu.mis.model.CourseSelectionSuccessAlert
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CourseSelectionRunner(
    private val selectCourse: suspend (CourseSelectionTarget) -> CourseSelectionAttemptResult,
    private val selectCourses: suspend (List<CourseSelectionTarget>) -> CourseSelectionAttemptResult,
    private val replaceCourse: suspend (CourseSelectionReplaceRule) -> CourseSelectionAttemptResult,
    private val submitCaptchaAnswer: suspend (challengeId: String, captcha: String) -> CourseSelectionAttemptResult,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(CourseSelectionRunState())
    val state: StateFlow<CourseSelectionRunState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var captchaWaiter: CompletableDeferred<CourseSelectionAttemptResult>? = null
    private var successAlertSequence = 0L
    @Volatile
    private var stopRequested = false

    fun start(config: CourseSelectionRunConfig): Boolean {
        val normalized = config.copy(
            targets = config.targets.filter { it.key.isNotBlank() }.distinctBy { it.key },
            replaceRules = config.replaceRules
                .filter { it.target.key.isNotBlank() && it.drop.key.isNotBlank() }
                .distinctBy { it.id },
            retryIntervalMillis = config.retryIntervalMillis.coerceAtLeast(0L),
            maxRounds = config.maxRounds.coerceAtLeast(1),
        )
        if ((normalized.targets.isEmpty() && normalized.replaceRules.isEmpty()) || _state.value.running) return false

        stopRequested = false
        runJob = scope.launch {
            runSelection(normalized)
        }
        return true
    }

    fun stop() {
        stopRequested = true
        val waiter = captchaWaiter
        if (waiter != null && !waiter.isCompleted) {
            waiter.complete(CourseSelectionAttemptResult(status = "cancelled", message = "已停止抢课。"))
        }
        if (_state.value.running) {
            log("正在停止，当前请求完成后退出。")
            _state.update { it.copy(stopping = true) }
        }
    }

    fun submitCaptcha(captcha: String): Boolean {
        val challenge = _state.value.awaitingCaptcha ?: return false
        val waiter = captchaWaiter ?: return false
        if (waiter.isCompleted || _state.value.captchaSubmitting) return false

        _state.update { it.copy(captchaSubmitting = true, captchaError = null) }
        scope.launch {
            runCatching { submitCaptchaAnswer(challenge.challengeId, captcha) }
                .onSuccess { result ->
                    _state.update { it.copy(captchaSubmitting = false) }
                    waiter.complete(result)
                }
                .onFailure { error ->
                    val message = error.message ?: "验证码提交失败"
                    log("验证码提交失败：$message")
                    _state.update { it.copy(captchaSubmitting = false, captchaError = message) }
                }
        }
        return true
    }

    fun cancelCaptcha(): Boolean {
        val waiter = captchaWaiter ?: return false
        if (waiter.isCompleted) return false
        waiter.complete(CourseSelectionAttemptResult(status = "cancelled", message = "已取消验证码输入。"))
        return true
    }

    private suspend fun runSelection(config: CourseSelectionRunConfig) {
        _state.value = CourseSelectionRunState(running = true)
        log("开始抢课：普通 ${config.targets.size} 门，换课 ${config.replaceRules.size} 条，最多 ${config.maxRounds} 轮。")
        try {
            for (round in 1..config.maxRounds) {
                if (stopRequested) break
                log("第 $round 轮尝试")
                for (rule in config.replaceRules) {
                    if (stopRequested) break
                    if (rule.id in _state.value.doneReplaceRuleIds) continue
                    runCatching { replaceCourse(rule) }
                        .onSuccess { handleReplaceResult(rule, it) }
                        .onFailure { error ->
                            log("换课 ${rule.drop.courseName} -> ${rule.target.courseName}: ${error.message ?: "请求失败"}")
                        }
                }
                val pendingTargets = config.targets.filter { it.key !in _state.value.doneKeys }
                if (!stopRequested && pendingTargets.isNotEmpty()) {
                    runCatching {
                        if (pendingTargets.size == 1) {
                            selectCourse(pendingTargets.single())
                        } else {
                            selectCourses(pendingTargets)
                        }
                    }.onSuccess { result ->
                        if (pendingTargets.size == 1) {
                            handleResult(pendingTargets.single(), result)
                        } else {
                            handleTargetsResultOrFallback(pendingTargets, result)
                        }
                    }.onFailure { error ->
                        log("批量抢课: ${error.message ?: "请求失败"}")
                    }
                }
                val replaceDone = config.replaceRules.all { it.id in _state.value.doneReplaceRuleIds }
                val targetsDone = config.targets.all { it.key in _state.value.doneKeys }
                if (replaceDone && targetsDone) {
                    log("全部目标课程和换课规则已完成。")
                    break
                }
                if (!stopRequested && round < config.maxRounds) {
                    delay(config.retryIntervalMillis)
                }
            }
        } catch (error: Throwable) {
            if (!stopRequested) {
                val message = error.message ?: "抢课任务异常退出"
                log(message)
                _state.update { it.copy(error = message) }
            }
        } finally {
            captchaWaiter = null
            _state.update {
                it.copy(
                    running = false,
                    stopping = false,
                    awaitingCaptcha = null,
                    awaitingCaptchaCourse = null,
                    captchaSubmitting = false,
                    completed = !stopRequested && it.error == null,
                )
            }
            stopRequested = false
            runJob = null
        }
    }

    private suspend fun handleResult(target: CourseSelectionTarget, result: CourseSelectionAttemptResult): Boolean {
        log("${target.courseName}: ${result.message ?: result.status}")
        val finalResult = resolveCaptcha(target, result) ?: return false
        if (finalResult.status in selectionSuccessStatuses) {
            val successAlert = if (finalResult.status == "success") {
                nextSuccessAlert(target, finalResult)
            } else {
                null
            }
            _state.update {
                it.copy(
                    doneKeys = it.doneKeys + target.key,
                    successAlerts = if (successAlert == null) it.successAlerts else it.successAlerts + successAlert,
                )
            }
            return true
        }
        return false
    }

    private suspend fun handleTargetsResultOrFallback(
        targets: List<CourseSelectionTarget>,
        result: CourseSelectionAttemptResult,
    ): Boolean {
        var handled = handleTargetsResult(targets, result)
        if (result.status !in batchFallbackStatuses || stopRequested) return handled

        val remainingTargets = targets.filter { it.key !in _state.value.doneKeys }
        if (remainingTargets.isEmpty()) return handled

        log("批量抢课入口不可用，改为逐门尝试。")
        for (target in remainingTargets) {
            if (stopRequested) break
            runCatching { selectCourse(target) }
                .onSuccess { singleResult ->
                    handled = handleResult(target, singleResult) || handled
                }
                .onFailure { error ->
                    log("${target.courseName}: ${error.message ?: "请求失败"}")
                }
        }
        return handled
    }

    private suspend fun handleTargetsResult(
        targets: List<CourseSelectionTarget>,
        result: CourseSelectionAttemptResult,
    ): Boolean {
        val label = "批量抢课 ${targets.size} 门"
        log("$label: ${result.message ?: result.status}")
        val displayTarget = CourseSelectionTarget(
            key = "batch:${targets.joinToString(",") { it.key }}",
            courseName = label,
        )
        val finalResult = resolveCaptcha(displayTarget, result) ?: return false
        val completedKeys = finalResult.completedCourseKeys
            .ifEmpty {
                if (finalResult.status in selectionSuccessStatuses) targets.map { it.key } else emptyList()
            }
            .toSet()
        val completedTargets = targets.filter { it.key in completedKeys }
        if (completedTargets.isNotEmpty()) {
            val alerts = if (finalResult.status == "success") {
                completedTargets.map { target -> nextSuccessAlert(target, finalResult.copy(course = null)) }
            } else {
                emptyList()
            }
            _state.update {
                it.copy(
                    doneKeys = it.doneKeys + completedTargets.map { target -> target.key },
                    successAlerts = it.successAlerts + alerts,
                )
            }
        }
        if (finalResult.status in selectionSuccessStatuses) {
            return completedTargets.isNotEmpty()
        }
        return false
    }

    private suspend fun handleReplaceResult(rule: CourseSelectionReplaceRule, result: CourseSelectionAttemptResult): Boolean {
        log("换课 ${rule.drop.courseName} -> ${rule.target.courseName}: ${result.message ?: result.status}")
        val finalResult = resolveCaptcha(rule.target, result) ?: return false
        if (finalResult.status in replaceSuccessStatuses || finalResult.status in selectionSuccessStatuses) {
            val successAlert = if (finalResult.status in realSuccessStatuses) {
                nextSuccessAlert(rule.target, finalResult, rule.id)
            } else {
                null
            }
            _state.update {
                it.copy(
                    doneReplaceRuleIds = it.doneReplaceRuleIds + rule.id,
                    doneKeys = it.doneKeys + rule.target.key,
                    successAlerts = if (successAlert == null) it.successAlerts else it.successAlerts + successAlert,
                )
            }
            return true
        }
        if (result.status == "captcha_required") {
            rollbackDroppedCourse(rule)
        }
        return false
    }

    private suspend fun rollbackDroppedCourse(rule: CourseSelectionReplaceRule) {
        runCatching { selectCourse(rule.drop) }
            .onSuccess { rollback ->
                log("${rule.drop.courseName}: 回滚结果 ${rollback.message ?: rollback.status}")
            }
            .onFailure { error ->
                log("${rule.drop.courseName}: 回滚请求失败 ${error.message ?: "请求失败"}")
            }
    }

    private suspend fun resolveCaptcha(
        target: CourseSelectionTarget,
        initialResult: CourseSelectionAttemptResult,
    ): CourseSelectionAttemptResult? {
        var current = initialResult
        val completedKeys = current.completedCourseKeys.toMutableSet()
        while (current.status == "captcha_required" && current.captchaChallenge != null && !stopRequested) {
            val waiter = CompletableDeferred<CourseSelectionAttemptResult>()
            captchaWaiter = waiter
            _state.update {
                it.copy(
                    awaitingCaptcha = current.captchaChallenge,
                    awaitingCaptchaCourse = target,
                    captchaSubmitting = false,
                    captchaError = null,
                )
            }
            current = waiter.await()
            completedKeys += current.completedCourseKeys
            captchaWaiter = null
            _state.update {
                it.copy(
                    awaitingCaptcha = null,
                    awaitingCaptchaCourse = null,
                    captchaSubmitting = false,
                )
            }
            if (stopRequested) return null
            log("${target.courseName}: ${current.message ?: current.status}")
        }
        if (completedKeys.isNotEmpty()) {
            current = current.copy(completedCourseKeys = completedKeys.toList())
        }
        return current
    }

    private fun log(message: String) {
        _state.update { it.copy(logs = (listOf(message) + it.logs).take(120)) }
    }

    private fun nextSuccessAlert(
        target: CourseSelectionTarget,
        result: CourseSelectionAttemptResult,
        replaceRuleId: String? = null,
    ): CourseSelectionSuccessAlert =
        CourseSelectionSuccessAlert(
            eventId = ++successAlertSequence,
            courseKey = target.key,
            courseName = result.course?.courseName?.takeIf { it.isNotBlank() } ?: target.courseName,
            message = result.message?.takeIf { it.isNotBlank() } ?: "选课成功。",
            replaceRuleId = replaceRuleId,
        )

    private companion object {
        val selectionSuccessStatuses = setOf("success", "already_selected")
        val replaceSuccessStatuses = setOf("replace_success", "target_already_selected")
        val realSuccessStatuses = setOf("success", "replace_success")
        val batchFallbackStatuses = setOf("not_found", "unparseable")
    }
}
