<script setup lang="ts">
import { onHide, onShow } from "@dcloudio/uni-app";
import { useNotificationStore } from "@/store/notification";
import { useUserStore } from "@/store/user";

onShow(() => {
  const user = useUserStore();
  const notification = useNotificationStore();
  if (user.isLogin && !user.isBjtuServiceSession) {
    notification.startPolling();
  } else {
    notification.stopPolling(true);
  }
});

onHide(() => {
  useNotificationStore().stopPolling();
});
</script>
<style>
/* ═══════════════════════════════════════════════════════════════════
   技匠 · 新孟菲斯玻璃拟态 — 全局工具类 (Neo-Memphis Glassmorphism)
   所有页面优先使用这些类，确保全站视觉一致
   ═══════════════════════════════════════════════════════════════════ */

/* ── 页面基底 ── */
page {
  min-height: 100%;
  background: var(--color-bg-main);
  color: var(--color-text);
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

view,
text,
button,
input,
textarea {
  box-sizing: border-box;
  line-height: 1.5;
}

/* ── 按钮重置 ── */
button {
  margin: 0;
  padding: 0;
  border: 0;
  line-height: 1.4;
  background: transparent;
  transition: opacity 0.14s ease, transform 0.14s ease, box-shadow 0.14s ease, background 0.14s ease;
}

button::after {
  border: 0;
}

/* ── 页面外壳 ── */
.page-shell {
  min-height: 100vh;
  padding: 28rpx;
  background: var(--color-bg-main);
}

.page-with-tab {
  padding-top: calc(28rpx + var(--status-bar-height));
  padding-bottom: 160rpx;
}

/* ── Hero 卡片 → 玻璃拟态 + 微彩光晕 ── */
.hero-card {
  position: relative;
  border: var(--stroke);
  border-radius: var(--radius-xl);
  padding: 36rpx;
  color: var(--color-text);
  background:
    linear-gradient(135deg, rgba(90, 154, 252, 0.06), rgba(247, 141, 167, 0.04)),
    var(--color-glass-bg);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  box-shadow: var(--shadow-md);
}

/* 小程序 fallback：不支持 backdrop-filter 时用不透明白底 */
@supports not (backdrop-filter: blur(1px)) {
  .hero-card {
    background: rgba(255, 255, 255, 0.92);
  }
}

/* ── 表面卡片（全站最常用） ── */
.surface-card {
  border: var(--stroke);
  border-radius: var(--radius-lg);
  background: var(--color-card);
  box-shadow: var(--shadow-md);
}

.glass-card {
  border: var(--glass-border);
  border-radius: var(--radius-lg);
  background: var(--color-glass-bg);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  box-shadow: var(--shadow-sm);
}

/* ── 区块标题 ── */
.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 34rpx 4rpx 20rpx;
  color: var(--color-text);
  font-size: 34rpx;
  font-weight: 900;
  letter-spacing: 0;
}

/* ── 主按钮（冰川蓝 + 黑边硬阴影） ── */
.primary-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 92rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  background: var(--color-primary);
  box-shadow: var(--shadow-md);
}

.primary-btn:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: var(--shadow-sm);
}

.primary-btn[disabled],
.ghost-btn[disabled],
button[disabled] {
  opacity: var(--disabled-opacity);
  box-shadow: none;
  transform: none;
}

/* ── 幽灵按钮（白底黑边） ── */
.ghost-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 82rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  color: var(--color-text);
  font-weight: 700;
  background: var(--color-card);
  box-shadow: var(--shadow-sm);
}

.ghost-btn:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: none;
}

/* ── 危险色文字 ── */
.danger-text {
  color: var(--color-accent-3);
}

.muted {
  color: var(--color-text-muted);
}

/* ── 表单字段 ── */
.field {
  margin-bottom: 22rpx;
}

.field-label {
  margin-bottom: 12rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  font-weight: 700;
}

.field-input,
.field-textarea {
  width: 100%;
  height: 80rpx;
  border: var(--stroke);
  border-radius: var(--radius-md);
  padding: 0 24rpx;
  color: var(--color-text);
  font-size: 28rpx;
  line-height: 80rpx;
  background: #ffffff;
  transition: border-color 0.14s ease, box-shadow 0.14s ease;
}

.field-input:focus,
.field-textarea:focus {
  outline: 2px solid var(--color-primary);
  outline-offset: 2rpx;
}

.field-input[disabled],
.field-textarea[disabled] {
  color: var(--color-text-faint);
  background: #f1f4f8;
}

.field-textarea {
  min-height: 190rpx;
  height: auto;
  padding: 24rpx;
  line-height: 1.6;
}

/* ── 玻璃拟态工具类 ── */
.glass-effect {
  background: var(--color-glass-bg);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  border: var(--glass-border);
}

@supports not (backdrop-filter: blur(1px)) {
  .glass-effect {
    background: rgba(255, 255, 255, 0.92);
  }
}

/* ── 孟菲斯硬阴影工具类 ── */
.memphis-shadow {
  border: var(--stroke);
  box-shadow: var(--shadow-md);
}

/* ── 点击压下感工具类 ── */
.click-press:active {
  transform: translate(2rpx, 2rpx);
  box-shadow: var(--shadow-sm);
}

/* ── 加载/骨架屏 ── */
.loading-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  color: var(--color-text-muted);
  font-size: 24rpx;
  font-weight: 700;
}

.loading-dot {
  width: 18rpx;
  height: 18rpx;
  border: 3rpx solid rgba(90, 154, 252, 0.16);
  border-top-color: var(--color-primary);
  border-radius: 999rpx;
  animation: ji-spin 0.8s linear infinite;
}

.skeleton-card {
  overflow: hidden;
  position: relative;
  border: var(--stroke);
  border-radius: var(--radius-lg);
  background: #fff;
  box-shadow: var(--shadow-sm);
}

.skeleton-line,
.skeleton-block {
  overflow: hidden;
  position: relative;
  border-radius: 999rpx;
  background: var(--skeleton-base);
}

.skeleton-block {
  border-radius: var(--radius-md);
}

.skeleton-line::after,
.skeleton-block::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  left: -80%;
  width: 70%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.72), transparent);
  animation: ji-shimmer 1.2s ease-in-out infinite;
}

@keyframes ji-spin {
  to { transform: rotate(360deg); }
}

@keyframes ji-shimmer {
  to { left: 110%; }
}
</style>
