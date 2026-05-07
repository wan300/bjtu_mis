<script setup>
import { RouterView, useRoute } from 'vue-router'
import { ref, watch } from 'vue'
import {
  NAlert,
  NDrawer,
  NDrawerContent,
  NLayout,
  NLayoutContent,
  NLayoutSider,
  NSpace,
  useMessage
} from 'naive-ui'
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
import StatusStrip from './StatusStrip.vue'
import { useAlerts, setMessageApi } from '../composables/useAlerts'

const route = useRoute()
const message = useMessage()
setMessageApi(message)

const { alerts } = useAlerts()
const mobileMenuVisible = ref(false)

function openMobileMenu() {
  mobileMenuVisible.value = true
}

watch(
  () => route.fullPath,
  () => {
    mobileMenuVisible.value = false
  }
)
</script>

<template>
  <div class="app-shell">
    <NLayout has-sider class="app-layout">
      <NLayoutSider
        bordered
        collapse-mode="transform"
        :collapsed-width="0"
        :native-scrollbar="false"
        show-trigger="bar"
        class="app-sider"
      >
        <AppSidebar />
      </NLayoutSider>
      <NLayoutContent class="app-content">
        <div class="content-inner">
          <AppHeader @open-menu="openMobileMenu" />
          <StatusStrip />
          <NSpace v-if="alerts.length" vertical class="alert-space">
            <NAlert
              v-for="(item, index) in alerts"
              :key="index"
              :type="item.type === 'error' ? 'error' : item.type === 'success' ? 'success' : item.type === 'warning' ? 'warning' : 'info'"
              :title="item.message"
              closable
              @close="alerts.splice(index, 1)"
            />
          </NSpace>
          <RouterView />
        </div>
      </NLayoutContent>
    </NLayout>

    <NDrawer
      v-model:show="mobileMenuVisible"
      placement="left"
      width="min(82vw, 300px)"
      class="mobile-menu-drawer"
    >
      <NDrawerContent
        :body-content-style="{ padding: '0' }"
        :native-scrollbar="false"
      >
        <AppSidebar />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  overflow-x: hidden;
}

.app-layout {
  min-height: 100vh;
}

.app-sider {
  background: linear-gradient(180deg, rgba(234, 223, 203, 0.95), rgba(245, 239, 228, 0.98));
}

.app-content {
  background: transparent;
}

.content-inner {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px 32px;
  display: grid;
  gap: 20px;
  min-width: 0;
}

.alert-space {
  width: 100%;
}

@media (max-width: 768px) {
  .app-sider {
    display: none;
  }

  .content-inner {
    padding: 14px calc(12px + env(safe-area-inset-right)) 20px calc(12px + env(safe-area-inset-left));
    gap: 14px;
  }
}
</style>
