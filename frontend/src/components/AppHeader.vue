<script setup>
import { NButton, NH1, NIcon, NP } from 'naive-ui'
import { MenuOutline } from '@vicons/ionicons5'
import { useSync } from '../composables/useSync'

const emit = defineEmits(['open-menu'])
const { busy, runSync } = useSync()
</script>

<template>
  <div class="app-header">
    <div class="header-main">
      <NButton
        quaternary
        circle
        size="large"
        class="mobile-menu-button"
        aria-label="打开导航菜单"
        @click="emit('open-menu')"
      >
        <template #icon>
          <NIcon><MenuOutline /></NIcon>
        </template>
      </NButton>
      <div class="header-text">
        <NH1 class="header-title">
          <span class="title-prefix">BJTU MIS</span>
          <span class="title-divider">·</span>
          <span class="title-main">本页登录复用会话的采集与展示控制台</span>
        </NH1>
        <NP class="header-desc">
          在当前页面输入账号、密码与验证码即可完成 MIS 登录，无需跳转登录页。
          学籍信息、学业进度、历史成绩、课表与课程平台数据会统一同步到本地快照。
        </NP>
      </div>
    </div>
    <div class="header-actions">
      <NButton
        type="primary"
        size="large"
        :loading="busy.sync"
        @click="runSync"
      >
        {{ busy.sync ? '同步中...' : '立即同步' }}
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.app-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  padding: 28px;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: linear-gradient(135deg, rgba(255, 250, 241, 0.96), rgba(255, 239, 214, 0.85));
  box-shadow: 0 18px 48px rgba(88, 61, 20, 0.12);
}

.header-main {
  flex: 1;
  min-width: 0;
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.mobile-menu-button {
  display: none;
  flex-shrink: 0;
}

.header-text {
  flex: 1;
  min-width: 0;
}

.header-title {
  margin: 0;
  font-size: 26px;
  line-height: 1.3;
}

.title-prefix {
  color: #b34f1f;
  font-size: 14px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  display: block;
}

.title-divider {
  display: none;
}

.title-main {
  font-size: 26px;
  color: #2c261f;
}

.header-desc {
  margin: 12px 0 0;
  max-width: 680px;
  color: #776b5d;
  line-height: 1.6;
  font-size: 14px;
}

.header-actions {
  flex-shrink: 0;
}

@media (max-width: 960px) {
  .app-header {
    flex-direction: column;
  }

  .header-main,
  .header-actions {
    width: 100%;
  }

  .header-actions > * {
    width: 100%;
  }
}

@media (max-width: 768px) {
  .app-header {
    gap: 16px;
    padding: 18px;
    border-radius: 16px;
  }

  .header-main {
    gap: 12px;
  }

  .mobile-menu-button {
    display: inline-flex;
    margin-top: 2px;
  }

  .header-title {
    font-size: 22px;
    line-height: 1.25;
  }

  .title-prefix {
    font-size: 12px;
    letter-spacing: 0.14em;
  }

  .title-main {
    font-size: 22px;
  }

  .header-desc {
    margin-top: 8px;
    font-size: 13px;
    line-height: 1.55;
  }
}

@media (max-width: 430px) {
  .title-main {
    font-size: 20px;
  }
}
</style>
