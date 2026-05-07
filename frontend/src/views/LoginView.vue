<script setup>
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NForm, NFormItem, NInput, NH1, NP, NSpace, NDivider } from 'naive-ui'
import { useSession } from '../composables/useSession'
import { useSync } from '../composables/useSync'

const router = useRouter()
const route = useRoute()
const { loginForm, captchaState, busy, isSessionReady, loadCaptcha, submitInlineLogin, openBrowser, loadSessionStatus } = useSession()
const { refreshAll } = useSync()

function getPostLoginRedirect() {
  const redirect = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  if (!redirect || typeof redirect !== 'string') return '/overview'
  if (!redirect.startsWith('/') || redirect.startsWith('/login')) return '/overview'
  return redirect
}

async function handleLogin() {
  const result = await submitInlineLogin()
  if (result.success) {
    await refreshAll()
    router.push(getPostLoginRedirect())
  }
}

async function handleOpenBrowser() {
  await openBrowser()
}

onMounted(async () => {
  await loadSessionStatus({ redirectOnInvalid: false })
  if (isSessionReady.value) {
    router.replace(getPostLoginRedirect())
    return
  }
  if (!captchaState.imageDataUrl) {
    await loadCaptcha(false)
  }
})
</script>

<template>
  <div class="login-page">
    <NCard class="login-card" :bordered="true">
      <div class="login-header">
        <NH1 class="login-title">BJTU MIS</NH1>
        <NP class="login-subtitle">校园信息采集控制台 · 统一登录</NP>
      </div>

      <NDivider />

      <NForm label-placement="top" class="login-form">
        <NFormItem label="学号 / 工号">
          <NInput
            v-model:value="loginForm.loginname"
            placeholder="请输入学号或工号"
            size="large"
            :disabled="busy.login"
          />
        </NFormItem>

        <NFormItem label="密码">
          <NInput
            v-model:value="loginForm.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :disabled="busy.login"
            show-password-on="click"
            @keyup.enter="handleLogin"
          />
        </NFormItem>

        <NFormItem label="验证码">
          <div class="captcha-row">
            <NInput
              v-model:value="loginForm.captcha"
              placeholder="请输入验证码"
              size="large"
              :disabled="busy.login"
              maxlength="8"
              @keyup.enter="handleLogin"
            />
            <img
              v-if="captchaState.imageDataUrl"
              :src="captchaState.imageDataUrl"
              alt="验证码"
              class="captcha-image"
              @click="loadCaptcha(false)"
              title="点击刷新验证码"
            />
            <div v-else class="captcha-placeholder" @click="loadCaptcha(false)" title="点击加载验证码">
              点击加载
            </div>
          </div>
          <NP v-if="captchaState.fetchedAt" class="captcha-time">
            验证码更新时间：{{ captchaState.fetchedAt }}
          </NP>
        </NFormItem>
      </NForm>

      <NSpace vertical :size="12" class="login-actions">
        <NButton
          type="primary"
          size="large"
          block
          :loading="busy.login"
          :disabled="busy.captcha"
          @click="handleLogin"
        >
          {{ busy.login ? '登录中...' : '提交登录' }}
        </NButton>
        <NButton
          size="large"
          block
          :disabled="busy.login || busy.captcha"
          @click="handleOpenBrowser"
        >
          备用：打开浏览器登录
        </NButton>
      </NSpace>
    </NCard>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: radial-gradient(circle at top left, rgba(255, 245, 223, 0.9), transparent 35%),
              linear-gradient(135deg, #f5efe4, #eadfcb);
}

.login-card {
  width: 100%;
  max-width: 440px;
  border-radius: 22px;
  box-shadow: 0 18px 48px rgba(88, 61, 20, 0.15);
}

.login-header {
  text-align: center;
}

.login-title {
  margin: 0;
  font-size: 32px;
  font-weight: 800;
  color: #b34f1f;
  letter-spacing: 0.05em;
}

.login-subtitle {
  margin: 8px 0 0;
  color: #776b5d;
  font-size: 14px;
}

.login-form {
  margin-top: 4px;
}

.captcha-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.captcha-row > :first-child {
  flex: 1;
}

.captcha-image {
  width: 150px;
  height: 48px;
  border-radius: 12px;
  border: 1px solid rgba(78, 62, 43, 0.15);
  cursor: pointer;
  object-fit: cover;
}

.captcha-placeholder {
  width: 150px;
  height: 48px;
  border-radius: 12px;
  border: 1px solid rgba(78, 62, 43, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #776b5d;
  font-size: 13px;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.5);
}

.captcha-placeholder:hover {
  background: rgba(179, 79, 31, 0.08);
}

.captcha-time {
  margin: 4px 0 0;
  font-size: 12px;
  color: #776b5d;
}

.login-actions {
  margin-top: 8px;
}

@media (max-width: 480px) {
  .login-page {
    align-items: flex-start;
    padding: 16px calc(12px + env(safe-area-inset-right)) 20px calc(12px + env(safe-area-inset-left));
  }

  .login-card {
    border-radius: 16px;
  }

  .login-title {
    font-size: 28px;
  }

  .login-subtitle {
    font-size: 13px;
    line-height: 1.5;
  }

  .captcha-row {
    flex-direction: column;
    gap: 10px;
  }

  .captcha-row > :first-child {
    width: 100%;
  }

  .captcha-image,
  .captcha-placeholder {
    width: 160px;
    max-width: 100%;
  }
}
</style>
