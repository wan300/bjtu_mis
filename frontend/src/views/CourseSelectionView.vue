<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NH2,
  NInput,
  NInputNumber,
  NModal,
  NP,
  NSelect,
  NSpace,
  NTable,
  NTag
} from 'naive-ui'
import { api } from '../api'

const loading = ref(false)
const running = ref(false)
const stopRequested = ref(false)
const error = ref('')
const payload = ref(null)
const selectedKeys = ref([])
const doneKeys = ref([])
const doneReplaceRuleIds = ref([])
const replaceRules = ref([])
const replaceTargetKey = ref(null)
const replaceDropKey = ref(null)
const logs = ref([])
const retryInterval = ref(2)
const maxRetries = ref(100)
const pendingCaptcha = ref(null)
const captchaText = ref('')
let captchaResolver = null

const availableCourses = computed(() => payload.value?.data?.available_courses || [])
const selectedCourses = computed(() => payload.value?.data?.selected_courses || [])
const chosenCourses = computed(() => availableCourses.value.filter(course => selectedKeys.value.includes(course.key)))
const replaceTargetOptions = computed(() => availableCourses.value.map(course => ({
  label: `${course.course_name}${course.remaining_text != null || course.remaining != null ? ` / 余量 ${course.remaining_text ?? course.remaining}` : ''}`,
  value: course.key
})))
const replaceDropOptions = computed(() => selectedCourses.value.map(course => ({
  label: course.course_name,
  value: course.key
})))
const hasRunnableWork = computed(() => chosenCourses.value.length > 0 || replaceRules.value.length > 0)

function log(message) {
  const time = new Date().toLocaleTimeString()
  logs.value = [`[${time}] ${message}`, ...logs.value].slice(0, 120)
}

function sleep(seconds) {
  return new Promise(resolve => window.setTimeout(resolve, Math.max(0, seconds) * 1000))
}

async function loadCourses() {
  loading.value = true
  error.value = ''
  try {
    payload.value = await api.getCourseSelection()
  } catch (err) {
    error.value = err.message || '课程列表加载失败'
  } finally {
    loading.value = false
  }
}

function toggleCourse(key, checked) {
  selectedKeys.value = checked
    ? Array.from(new Set([...selectedKeys.value, key]))
    : selectedKeys.value.filter(item => item !== key)
}

function addReplaceRule() {
  const target = availableCourses.value.find(course => course.key === replaceTargetKey.value)
  const drop = selectedCourses.value.find(course => course.key === replaceDropKey.value)
  if (!target || !drop) return
  const id = `${target.key}->${drop.key}`
  if (replaceRules.value.some(rule => rule.id === id)) return
  replaceRules.value = [
    ...replaceRules.value,
    {
      id,
      target_key: target.key,
      target_name: target.course_name,
      drop_key: drop.key,
      drop_name: drop.course_name
    }
  ]
}

function removeReplaceRule(id) {
  replaceRules.value = replaceRules.value.filter(rule => rule.id !== id)
  doneReplaceRuleIds.value = doneReplaceRuleIds.value.filter(item => item !== id)
}

function clearReplaceRules() {
  replaceRules.value = []
  doneReplaceRuleIds.value = []
}

function waitForCaptcha(challenge) {
  pendingCaptcha.value = challenge
  captchaText.value = ''
  return new Promise(resolve => {
    captchaResolver = resolve
  })
}

async function handleCaptchaSubmit() {
  if (!pendingCaptcha.value || !captchaText.value.trim()) return
  const challengeId = pendingCaptcha.value.challenge_id
  const text = captchaText.value.trim()
  try {
    const result = await api.submitCourseSelectionCaptcha({
      captcha_challenge_id: challengeId,
      captcha: text
    })
    pendingCaptcha.value = null
    captchaText.value = ''
    captchaResolver?.(result)
    captchaResolver = null
  } catch (err) {
    error.value = err.message || '验证码提交失败'
  }
}

function handleCaptchaCancel() {
  pendingCaptcha.value = null
  captchaText.value = ''
  captchaResolver?.({ status: 'cancelled', message: '已取消验证码输入' })
  captchaResolver = null
}

async function handleAttemptResult(course, result) {
  log(`${course.course_name}: ${result.message || result.status}`)
  if (['success', 'already_selected'].includes(result.status)) {
    doneKeys.value = Array.from(new Set([...doneKeys.value, course.key]))
    return true
  }
  if (result.status === 'captcha_required' && result.captcha_challenge) {
    const captchaResult = await waitForCaptcha(result.captcha_challenge)
    return handleAttemptResult(course, captchaResult)
  }
  return false
}

async function rollbackDroppedCourse(rule) {
  try {
    const rollback = await api.selectCourse({ course_key: rule.drop_key, course_name: rule.drop_name })
    log(`${rule.drop_name}: 回滚结果 ${rollback.message || rollback.status}`)
    return ['success', 'already_selected'].includes(rollback.status)
  } catch (err) {
    log(`${rule.drop_name}: 回滚请求失败 ${err.message || '请求失败'}`)
    return false
  }
}

async function handleReplaceResult(rule, result) {
  log(`换课 ${rule.drop_name} -> ${rule.target_name}: ${result.message || result.status}`)
  if (['replace_success', 'target_already_selected'].includes(result.status)) {
    doneReplaceRuleIds.value = Array.from(new Set([...doneReplaceRuleIds.value, rule.id]))
    doneKeys.value = Array.from(new Set([...doneKeys.value, rule.target_key]))
    return true
  }
  if (result.status === 'captcha_required' && result.captcha_challenge) {
    const captchaResult = await waitForCaptcha(result.captcha_challenge)
    if (['success', 'already_selected'].includes(captchaResult.status)) {
      log(`换课 ${rule.drop_name} -> ${rule.target_name}: 验证码后选课成功`)
      doneReplaceRuleIds.value = Array.from(new Set([...doneReplaceRuleIds.value, rule.id]))
      doneKeys.value = Array.from(new Set([...doneKeys.value, rule.target_key]))
      return true
    }
    log(`换课 ${rule.drop_name} -> ${rule.target_name}: ${captchaResult.message || captchaResult.status}`)
    await rollbackDroppedCourse(rule)
  }
  return false
}

async function startSelecting() {
  if (!hasRunnableWork.value || running.value) return
  running.value = true
  stopRequested.value = false
  doneKeys.value = []
  doneReplaceRuleIds.value = []
  logs.value = []
  const selectedSnapshot = [...chosenCourses.value]
  const replaceSnapshot = [...replaceRules.value]
  const rounds = maxRetries.value || 1
  log(`开始抢课：普通 ${selectedSnapshot.length} 门，换课 ${replaceSnapshot.length} 条，最多 ${rounds} 轮。`)

  for (let round = 1; round <= rounds; round += 1) {
    if (stopRequested.value) break
    log(`第 ${round} 轮尝试`)
    let hadPendingWork = false

    for (const rule of replaceSnapshot) {
      if (stopRequested.value) break
      if (doneReplaceRuleIds.value.includes(rule.id)) continue
      hadPendingWork = true
      try {
        const result = await api.replaceCourse({
          target_course_key: rule.target_key,
          target_course_name: rule.target_name,
          drop_course_key: rule.drop_key,
          drop_course_name: rule.drop_name
        })
        await handleReplaceResult(rule, result)
      } catch (err) {
        log(`换课 ${rule.drop_name} -> ${rule.target_name}: ${err.message || '请求失败'}`)
      }
    }

    for (const course of selectedSnapshot) {
      if (stopRequested.value) break
      if (doneKeys.value.includes(course.key)) continue
      hadPendingWork = true
      try {
        const result = await api.selectCourse({ course_key: course.key, course_name: course.course_name })
        await handleAttemptResult(course, result)
      } catch (err) {
        log(`${course.course_name}: ${err.message || '请求失败'}`)
      }
    }

    const selectedDone = selectedSnapshot.every(course => doneKeys.value.includes(course.key))
    const replaceDone = replaceSnapshot.every(rule => doneReplaceRuleIds.value.includes(rule.id))
    if (selectedDone && replaceDone) {
      log('全部目标课程和换课规则已完成。')
      break
    }
    if (hadPendingWork && round < rounds && !stopRequested.value) {
      await sleep(retryInterval.value || 0)
    }
  }

  running.value = false
  stopRequested.value = false
  await loadCourses()
}

function stopSelecting() {
  stopRequested.value = true
  log('正在停止，当前请求完成后退出。')
}

onMounted(loadCourses)
</script>

<template>
  <div class="module-page">
    <div class="section-head">
      <div>
        <NH2>抢课</NH2>
        <NP class="desc">从 AA 选课页读取可选课程，支持普通多门抢课和高级换课规则。</NP>
      </div>
      <NSpace>
        <NTag v-if="payload?.data?.can_submit" type="success" round>可提交</NTag>
        <NTag v-else type="warning" round>需检查入口</NTag>
        <NButton :loading="loading" @click="loadCourses">刷新列表</NButton>
      </NSpace>
    </div>

    <NAlert v-if="error" type="error" :title="error" />
    <NAlert v-if="payload?.data?.submit_error" type="warning" :title="payload.data.submit_error" />

    <NCard>
      <div class="control-row">
        <NInputNumber v-model:value="retryInterval" :min="0.2" :step="0.5" placeholder="间隔秒" />
        <NInputNumber v-model:value="maxRetries" :min="1" :step="1" placeholder="最大轮数" />
        <NButton type="primary" :disabled="!hasRunnableWork || running || !payload?.data?.can_submit" @click="startSelecting">
          {{ running ? '抢课中' : '开始抢课' }}
        </NButton>
        <NButton :disabled="!running" @click="stopSelecting">停止</NButton>
      </div>
    </NCard>

    <NCard title="高级换课规则">
      <div class="replace-controls">
        <NSelect
          v-model:value="replaceTargetKey"
          :options="replaceTargetOptions"
          :disabled="running"
          filterable
          clearable
          placeholder="目标课程 A"
        />
        <NSelect
          v-model:value="replaceDropKey"
          :options="replaceDropOptions"
          :disabled="running"
          filterable
          clearable
          placeholder="要退课程 B"
        />
        <NButton :disabled="running || !replaceTargetKey || !replaceDropKey" @click="addReplaceRule">添加规则</NButton>
        <NButton :disabled="running || !replaceRules.length" @click="clearReplaceRules">清空</NButton>
      </div>
      <div class="table-wrap replace-table">
        <NTable size="small" :bordered="true">
          <thead>
            <tr>
              <th>状态</th>
              <th>目标课程 A</th>
              <th>退课 B</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in replaceRules" :key="rule.id" :class="{ done: doneReplaceRuleIds.includes(rule.id) }">
              <td>{{ doneReplaceRuleIds.includes(rule.id) ? '已完成' : '待执行' }}</td>
              <td>{{ rule.target_name }}</td>
              <td>{{ rule.drop_name }}</td>
              <td>
                <NButton size="small" :disabled="running" @click="removeReplaceRule(rule.id)">删除</NButton>
              </td>
            </tr>
            <tr v-if="!replaceRules.length">
              <td colspan="4" class="empty-row">尚未添加换课规则。</td>
            </tr>
          </tbody>
        </NTable>
      </div>
    </NCard>

    <NCard title="可选课程">
      <div class="table-wrap">
        <NTable size="small" :bordered="true">
          <thead>
            <tr>
              <th>选择</th>
              <th>状态</th>
              <th>课程</th>
              <th>余量</th>
              <th>学分</th>
              <th>教师</th>
              <th>时间地点</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="course in availableCourses" :key="course.key" :class="{ done: doneKeys.includes(course.key) }">
              <td>
                <NCheckbox
                  :checked="selectedKeys.includes(course.key)"
                  :disabled="running || doneKeys.includes(course.key)"
                  @update:checked="checked => toggleCourse(course.key, checked)"
                />
              </td>
              <td>{{ doneKeys.includes(course.key) ? '已完成' : course.status }}</td>
              <td>{{ course.course_name }}</td>
              <td>{{ course.remaining_text ?? course.remaining ?? '-' }}</td>
              <td>{{ course.credit || '-' }}</td>
              <td>{{ course.teacher || '-' }}</td>
              <td>{{ course.time_location || '-' }}</td>
            </tr>
            <tr v-if="!availableCourses.length">
              <td colspan="7" class="empty-row">当前没有读取到可选课程。</td>
            </tr>
          </tbody>
        </NTable>
      </div>
    </NCard>

    <NCard title="已选课程">
      <NSpace v-if="selectedCourses.length">
        <NTag v-for="course in selectedCourses" :key="course.key" type="success">
          {{ course.course_name }}
        </NTag>
      </NSpace>
      <NP v-else class="desc">当前没有读取到已选课程。</NP>
    </NCard>

    <NCard title="运行日志">
      <div class="log-list">
        <div v-for="line in logs" :key="line">{{ line }}</div>
      </div>
    </NCard>

    <NModal :show="!!pendingCaptcha" preset="card" title="输入验证码" class="captcha-modal" @mask-click="handleCaptchaCancel">
      <NP v-if="pendingCaptcha?.prompt">{{ pendingCaptcha.prompt }}</NP>
      <img v-if="pendingCaptcha?.image_data_url" class="captcha-image" :src="pendingCaptcha.image_data_url" alt="验证码" />
      <NInput v-model:value="captchaText" placeholder="请输入验证码" @keyup.enter="handleCaptchaSubmit" />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="handleCaptchaCancel">取消</NButton>
          <NButton type="primary" :disabled="!captchaText.trim()" @click="handleCaptchaSubmit">提交</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.module-page { display: grid; gap: 18px; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.section-head h2 { margin: 0; }
.desc { margin: 6px 0 0; color: #776b5d; }
.control-row { display: grid; grid-template-columns: 160px 160px auto auto; gap: 12px; align-items: center; justify-content: start; }
.replace-controls { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) auto auto; gap: 12px; align-items: center; }
.replace-table { margin-top: 14px; }
.table-wrap { overflow-x: auto; }
.table-wrap :deep(th),
.table-wrap :deep(td) { white-space: nowrap; }
.done { background: rgba(47, 125, 90, 0.08); }
.empty-row { text-align: center; color: #776b5d; padding: 28px !important; }
.log-list { display: grid; gap: 6px; max-height: 260px; overflow: auto; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; }
.captcha-modal { width: min(420px, calc(100vw - 32px)); }
.captcha-image { display: block; max-width: 100%; margin: 8px 0 14px; border: 1px solid rgba(0, 0, 0, 0.12); border-radius: 6px; }

@media (max-width: 760px) {
  .section-head { display: grid; }
  .control-row,
  .replace-controls { grid-template-columns: 1fr; }
}
</style>
