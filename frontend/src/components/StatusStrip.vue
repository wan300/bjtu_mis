<script setup>
import { NCard, NGrid, NGi, NStatistic } from 'naive-ui'
import { useSession } from '../composables/useSession'
import { useSync } from '../composables/useSync'
import { computed } from 'vue'

const { sessionStatus } = useSession()
const { syncStatus } = useSync()

const finishTime = computed(() => syncStatus.finished_at || syncStatus.started_at || '尚未运行')
</script>

<template>
  <NGrid cols="1 s:2 m:3" :x-gap="16" :y-gap="12" responsive="screen">
    <NGi>
      <NCard size="small" :bordered="true" class="status-card">
        <NStatistic label="会话状态" :value="sessionStatus.state">
          <template #suffix>
            <span class="stat-detail">{{ sessionStatus.detail }}</span>
          </template>
        </NStatistic>
      </NCard>
    </NGi>
    <NGi>
      <NCard size="small" :bordered="true" class="status-card">
        <NStatistic label="最近同步" :value="syncStatus.status">
          <template #suffix>
            <span class="stat-detail">{{ finishTime }}</span>
          </template>
        </NStatistic>
      </NCard>
    </NGi>
    <NGi>
      <NCard size="small" :bordered="true" class="status-card">
        <NStatistic label="最近错误" :value="syncStatus.error_text ? '需要关注' : '无'">
          <template #suffix>
            <span class="stat-detail">{{ syncStatus.error_text || '最近一次同步没有记录错误。' }}</span>
          </template>
        </NStatistic>
      </NCard>
    </NGi>
  </NGrid>
</template>

<style scoped>
.stat-detail {
  font-size: 13px;
  color: #776b5d;
  display: block;
  margin-top: 4px;
}
</style>
