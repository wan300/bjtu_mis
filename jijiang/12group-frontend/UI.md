# 「技匠」新孟菲斯玻璃拟态 — 最终设计系统 (V3.0)

> **状态**：✅ 全站实施完成 | **覆盖**：~50 个文件（全局令牌 + 8 组件 + 6 核心页 + 30+ 页面 + 管理后台）
> **最后更新**：2026-06-04

---

## 一、设计令牌 (Design Tokens)

定义于 `12group-frontend/src/styles/global.css`。

### 冰淇淋拼色系统

| 令牌 | 值 | 用途 |
|---|---|---|
| `--color-primary` | `#5A9AFC` | 冰川蓝 — 品牌主色、按钮、认证 |
| `--color-primary-soft` | `rgba(90,154,252,0.10)` | 冰川蓝淡化 — 标签背景 |
| `--color-accent-1` | `#74D6C1` | 薄荷绿 — 成功状态、已完成徽章 |
| `--color-accent-2` | `#F9E58A` | 奶油黄 — 待处理、警告、学业分类 |
| `--color-accent-3` | `#F78DA7` | 珊瑚粉 — 退款、红点、求职分类 |
| `--color-dark` | `#212529` | 墨黑 — **全站外边框 + 主标题** |
| `--color-bg-main` | `#FBFBFF` | 极光白 — 页面背景 |
| `--color-glass-bg` | `rgba(255,255,255,0.65)` | 玻璃拟态基底 |
| `--color-card` | `#FFFFFF` | 卡片白 |
| `--color-card-muted` | `#FAFBFD` | 卡片次级背景 |

### 语义色

| 令牌 | 值 | 场景 |
|---|---|---|
| `--color-success` | `var(--color-accent-1)` | 成功 |
| `--color-warning` | `#946018` | 警告文字 |
| `--color-danger` | `var(--color-accent-3)` | 危险 |
| `--color-price` | `#0F766E` | 价格深碧绿 |

### 几何

```css
--stroke:        2px solid var(--color-dark);   /* 全站统一边框 */
--radius-sm:     8rpx;    /* 标签、徽章 */
--radius-md:     16rpx;   /* 按钮、输入框 */
--radius-lg:     24rpx;   /* 大卡片 */
--radius-xl:     32rpx;   /* Hero区 */
```

### 阴影

```css
--shadow-sm:  4rpx 4rpx 0px 0px var(--color-dark);
--shadow-md:  6rpx 6rpx 0px 0px var(--color-dark);
--shadow-lg:  8rpx 8rpx 0px 0px var(--color-dark);
```

### 玻璃拟态

```css
--glass-blur:  12px;
--glass-border: 1.5px solid rgba(255,255,255,0.4);
```

---

## 二、全局工具类

定义于 `App.vue`。所有页面优先使用这些类。

| 类名 | 说明 |
|---|---|
| `.page-shell` | 页面外壳：极光白底 + 28rpx padding |
| `.hero-card` | 玻璃拟态卡片：半透明白底 + 微彩光晕 + blur + 黑边硬阴影 |
| `.surface-card` | 标准卡片：白底 + 2px黑边 + 6rpx硬阴影 |
| `.primary-btn` | 主按钮：冰川蓝底 + 黑边 + 硬阴影 + 16rpx圆角 |
| `.ghost-btn` | 幽灵按钮：白底 + 黑边 + 硬阴影 |
| `.field-input` / `.field-textarea` | 表单：2px黑边 + focus:outline |
| `.glass-effect` | 纯玻璃拟态工具类 |

### 核心铁律

1. 卡片/按钮/输入框 → `border: var(--stroke)` (2px solid #212529)
2. 阴影 → `var(--shadow-md)` (6rpx 6rpx 0 0 #212529)，禁止模糊阴影
3. 点击态 → `transform: translate(2rpx, 2rpx); box-shadow: 缩至var(--shadow-sm)`
4. 内部小标签 → **禁止黑边框**，用纯色背景（浅蓝/浅绿/浅黄）
5. 价格 → 淡薄荷底 `#E8F8F5` + 深碧绿字 `#0F766E`
6. 圆角 → 统一用 `--radius-sm/md/lg`，非必要不用 999rpx

---

## 三、组件规范

| 组件 | 关键特征 |
|---|---|
| `ji-status-pill` | 5色映射：warn=奶油黄、active=冰川蓝白字、done=薄荷绿、danger=珊瑚粉白字、idle=灰白；方形8rpx |
| `ji-service-card` | 白底黑边硬阴影、封面overflow:hidden+黑边、价格淡薄荷底深绿字、meta标签浅蓝底无边框 |
| `ji-order-card` | 同上模式、价格淡薄荷底深绿字 |
| `ji-review-card` | 黑边硬阴影、左色条(绿/黄/粉)、头像蓝底黑边、星星奶油黄、标签浅蓝底无边框 |
| `ji-review-list` | 热门标签浅蓝底无边框、筛选chip active冰川蓝 |
| `ji-review-summary` | 黑边硬阴影、分布条奶油黄 |
| `ji-empty` | 图标黑边方角 |
| `ji-tab-bar` | 玻璃拟态底栏 + 硬阴影、未选中色 `#4A5568` |

---

## 四、核心页面

### 买家主页 `home/index`
- Hero区：玻璃拟态 + 装饰气泡(蓝/粉blur60px) + 半透明白底
- 校区徽章：冰川蓝底 + 旋转-1deg + 黑边硬阴影
- 搜索框：⊙粗体图标 + 右侧搜按钮全高贴合（去独立边框）
- 分类金刚区：四色轮转(黄/蓝/绿/粉) + Unicode几何符号(◆◷◫⬡等) 44rpx
- 卡片流：交替 ±0.5deg 旋转，标题 word-break:keep-all 防孤字

### 服务详情 `service/detail`
- 顶部大图：大圆角倒切 + 墨黑边框
- 价格：淡薄荷底深绿字 46rpx
- 底部栏：双按钮grid（冰川蓝 + 白底）

### 实名认证 `user/verify`
- Hero：玻璃拟态卡片
- OCR上传框：3px dashed #212529 粗黑虚线框
- 上传成功：奶油黄便利贴 + 右上折角

### 订单详情 `order/detail`
- 状态看板：status动态背景色(10黄/20蓝/30绿/40绿/50白/60+粉)
- 时间线：3rpx粗线 + 黑边圆点
- 操作按钮：双按钮并排flex

### 支付结果 `pay-result`
- 状态图标：方角硬阴影(成功薄荷绿/待付奶油黄/关闭珊瑚粉)
- 二维码框：黑边硬阴影

### 卖家工作台 `seller-desk`
- Hero：玻璃拟态
- 资产看板：左2列宽薄荷绿 + 右斑马斜线(repeating-linear-gradient)
- 信誉分：大号墨黑42rpx
- 快捷按钮：黑边方角硬阴影

### 发现页 `discover/index`
- 热门方向：去黑边 → 四色马卡龙背景(黄/蓝/绿/粉) + 硬阴影
- 灵感卡片：对角斜切渐变 + 十字"+"装饰

### 聊天页 `chat/detail`
- 发送按钮：微信绿 `#07C160` + 白字 + 黑边硬阴影
- 输入栏：白底!important + z-index:9999 + border-top:2px
- 头像：1.5px solid #1A1A1A 精细黑边
- 消息底部：40rpx留白防挤压

### 个人中心 `mine/index`
- 菜单分隔线：1px solid #E2E8F0 细线（非粗黑线）
- 菜单图标：48rpx 彩色圆形孟菲斯徽章(2px黑边+硬阴影+冰淇淋色底+白色几何符号28rpx)
- 统计卡：2px dashed #CBD5E1 虚线垂直分隔

---

## 五、受影响的文件

### 全局基础 (3)
`global.css` `App.vue` `uni.scss`

### 共享组件 (8)
`ji-empty` `ji-order-card` `ji-review-card` `ji-review-list` `ji-review-summary` `ji-service-card` `ji-status-pill` `ji-tab-bar`

### 核心页面 (6)
`home/index` `service/detail` `user/verify` `order/detail` `order/pay-result` `seller-desk/index`

### 功能页面 (30+)
`login` `mine` `message` `discover` `chat/detail` `service/search` `service/publish` `order/list` `order/create` `order/refund` `review/submit` `review/list` `demand/list` `demand/detail` `seller/income` `seller/withdraw` `seller/deposit` `seller/deposit-result` `user/profile` `user/my-credit` `user/my-service` `user/logout` `report/submit` `common/webview` `seller-order` `seller-service` `admin/*`(5)

### 管理后台 (1)
`jijiang-admin/src/styles.css`

---

## 六、新页面开发规范

1. **颜色**：只用 `var(--color-*)`，禁止硬编码色值
2. **边框**：`border: var(--stroke)`
3. **阴影**：`box-shadow: var(--shadow-md)`
4. **圆角**：`border-radius: var(--radius-md)`
5. **按钮**：`.primary-btn` / `.ghost-btn`
6. **卡片**：`.surface-card`
7. **Hero区**：`.hero-card`（自动玻璃拟态+微彩光晕）
8. **点击态**：`translate(2rpx,2rpx)` + shadow 缩小
9. **小标签**：无黑边 + 纯色背景
10. **价格**：`#E8F8F5` 底 + `#0F766E` 字
