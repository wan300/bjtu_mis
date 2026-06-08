<template>
  <text class="ji-status-pill" :class="`ji-tone-${tone}`">{{ text }}</text>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    text: string;
    tone?: "idle" | "warn" | "active" | "done" | "danger";
  }>(),
  { tone: "active" },
);
</script>

<style>
.ji-status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 112rpx;
  height: 46rpx;
  border: var(--stroke);
  border-radius: var(--radius-sm);
  padding: 0 18rpx;
  font-size: 22rpx;
  font-weight: 900;
  white-space: nowrap;
}

/* idle → 灰白底 + 黑边 */
.ji-tone-idle {
  color: var(--color-tag-neutral-text);
  background: var(--color-tag-neutral-bg);
}

/* warn → 奶油黄 + 黑边 */
.ji-tone-warn {
  color: var(--color-tag-warning-text);
  background: var(--color-accent-2);
}

/* active → 冰川蓝 + 黑边 + 白字 */
.ji-tone-active {
  color: #fff;
  background: var(--color-primary);
}

/* done → 薄荷绿 + 黑边 */
.ji-tone-done {
  color: var(--color-tag-success-text);
  background: var(--color-accent-1);
}

/* danger → 珊瑚粉 + 黑边 + 白字 */
.ji-tone-danger {
  color: #fff;
  background: var(--color-accent-3);
}
</style>
