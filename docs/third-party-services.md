# BJTU MIS 第三方插件 Manifest v3

> **权威安全基线**：本文与
> `docs/third-party-service-manifest.schema.json`、constitution 原则 VII 共同定义当前插件
> 安全策略，并覆盖此前所有 Manifest v1/v2、聚合 origin、凭据读取桥和通用原生 HTTP
> 桥设计。旧 spec、plan、API 或迁移说明仅用于历史追踪和无桥数据救援，不具备重新启用
> 旧策略的规范效力。

BJTU MIS Android 的第三方插件是安装在应用私有目录中的静态 H5/SPA 包。Manifest v3
把插件身份、WebView 信任边界、runtime 能力、数据迁移和校园会话访问拆成独立契约。

新客户端只安装、更新和运行 Manifest v3。v1/v2 插件会标记为
`legacy_disabled`；旧包和旧 WebStorage 不会自动删除，用户可从无桥、无网络的只读救援
入口查看数据或明确删除插件。

## 快速开始

仓库根目录必须包含：

```text
bjtu-service.json
dist/
  index.html
  icon.svg
```

复制 `third-party-plugins/templates/basic/` 后运行：

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/templates/basic
```

公开投稿只接受 GitHub 公开仓库和 Manifest v3。平台固定解析仓库当前 commit，计算源归档
SHA-256 与 `dist/` 内容 digest，并记录 GitHub owner 的不可变数值 ID。

## Manifest

最小示例：

```json
{
  "schema_version": 3,
  "runtime_version": 1,
  "min_runtime_version": 1,
  "required_capabilities": ["runtime.lifecycle.v1"],
  "optional_capabilities": ["storage.kv.v1"],
  "data_schema_version": 1,
  "id": "bjtu.example.demo",
  "name": "Demo",
  "description": "Manifest v3 example.",
  "version": "1.0.0",
  "entrypoint": "index.html",
  "icon": "icon.svg",
  "author": "Example",
  "permissions": {
    "required": [],
    "optional": []
  },
  "connect_origins": [],
  "media_origins": [],
  "frame_origins": [],
  "navigation_origins": [],
  "bridge_origins": ["self"],
  "marketplace": {
    "category": "other",
    "tags": ["example"],
    "license": "MIT"
  },
  "configuration": []
}
```

`migration_entrypoint` 是可选的 `dist/` 内相对路径。提高
`data_schema_version` 时必须提供它；版本不得降低。

### Runtime capability

| capability | 用途 |
| --- | --- |
| `runtime.lifecycle.v1` | Runtime 信息、ready 握手和环境生命周期事件。 |
| `storage.kv.v1` | 加密、配额化、可迁移的 `app.storage`。 |
| `campus.request.v1` | 通过宿主会话访问校园只读 registry。 |
| `remote.frame.v1` | 加载声明过的受限远程 iframe。 |

必需 capability 不受 runtime 支持时插件不兼容；可选 capability 应在调用前通过
`app.has_capability` 检测。

### 四类远程 origin

Manifest v3 不接受旧的聚合来源字段。每个列表只接受标准 origin，不得携带路径、查询、
片段、用户名或密码。

| 字段 | 作用 |
| --- | --- |
| `connect_origins` | 页面 `fetch`、XHR 等连接目标。 |
| `media_origins` | 图片、音视频等媒体资源目标。 |
| `frame_origins` | 远程 iframe 目标；还需 `remote.frame.v1`。 |
| `navigation_origins` | 用户手势触发的系统浏览器或 Custom Tab 跳转。 |
| `bridge_origins` | 必须严格等于 `["self"]`。 |

公共制品只允许 HTTPS。开发者模式可为 localhost 使用 HTTP。普通 connect/media/frame
来源禁止校园域名、私网、回环、链路本地和本地域名；校园访问必须走 `campus.request`。

远程 iframe 必须声明：

```html
<iframe
  src="https://example.com/embed"
  sandbox="allow-scripts allow-forms allow-same-origin"
></iframe>
```

宿主会在 document-start 再次收紧 sandbox。远程 frame 不获得原生桥、第三方 Cookie、
顶层导航、下载、弹窗或多窗口能力。

## 发布者身份与稳定 origin

平台首次发布时固定：

```text
publisher_subject_id = github-owner:<GitHub numeric owner id>
```

插件稳定 sandbox host 只由插件 ID 和 publisher subject 的 SHA-256 截断值生成，不包含
commit。仓库改名不会改变主体或 WebStorage origin。仓库转移 owner 会冻结更新；管理员
批准后只更新当前 owner 绑定，原 publisher subject 保持不变。高级直链安装检测到 owner
变化时拒绝原位更新。

插件大厅 `/api/v2` 会展示 runtime、capability、四类 origin、publisher identity、
验证级别和兼容状态。旧 `/api/v1` 仅保留迁移期只读目录能力。

## Bridge protocol

桥只在稳定本地 origin、main frame、精确 source origin 匹配时存在。客户端必须同时支持
WebView `DOCUMENT_START_SCRIPT` 与 `WEB_MESSAGE_LISTENER`；缺一即显示兼容错误，不使用
`onPageFinished` 注入回退。

线上的每个请求统一为：

```json
{
  "protocol_version": 1,
  "request_id": "unique-request-id",
  "method": "app.get_runtime_info",
  "params": {}
}
```

插件通常使用 document-start 注入的包装器：

```js
const runtime = await window.BjtuService.invoke('app.get_runtime_info', {});
await window.BjtuService.invoke('app.ready', {});
```

成功响应包含 `protocol_version`、`request_id`、`ok: true` 和 `data`。失败响应包含：

```json
{
  "protocol_version": 1,
  "request_id": "unique-request-id",
  "ok": false,
  "error": {
    "code": "permission_denied",
    "message": "Safe user-facing message",
    "request_id": "unique-request-id",
    "retryable": false,
    "http_status": 403,
    "details": {}
  }
}
```

`http_status` 与 `details` 可省略，`details` 不包含凭据、Cookie、token 或个人通信数据。

### Runtime 方法

| 方法 | 参数 | 结果 |
| --- | --- | --- |
| `app.get_runtime_info` | `{}` | runtime/protocol/schema/data schema、publisher subject、支持的 capabilities。 |
| `app.has_capability` | `{ capability }` | `declared`、`supported`、`available`。 |
| `app.ready` | `{}` | `{ ready: true }`。 |
| `app.get_configuration` | `{ key }` | 读取插件已声明且用户已保存的配置；需要 `app.configuration.read`。 |
| `app.storage.get` | `{ key }` | JSON 值或 `null`。 |
| `app.storage.set` | `{ key, value }` | 当前使用量。 |
| `app.storage.remove` | `{ key }` | 是否删除。 |
| `app.storage.keys` | `{}` | 排序后的 key 列表。 |
| `app.storage.usage` | `{}` | byte/key 使用量与上限。 |
| `app.storage.clear` | `{}` | `{ cleared: true }`。 |
| `campus.request` | 见下节 | 受控校园响应。 |

插件仍可使用既有细粒度领域 API，例如 `identity.get_profile`、
`academic.get_timetable`、`academic.get_scores`、`academic.get_exams`、
`academic.get_homework`、`academic.get_course_resources` 和只读邮件接口。写操作继续使用
领域 API；`academic.submit_homework` 与 `mail.send` 每次都需要宿主确认，Agent 不会自动
提交作业。

Runtime 不提供读取明文登录凭据的 API，也不提供通用原生 HTTP bridge。第三方公网访问
使用受 CSP 与 `connect_origins` 约束的浏览器 `fetch`。

`app.get_configuration` 仅能读取当前插件 Manifest 已声明的 key，配置值保存在设备加密
存储中，并受同一稳定本地 main-frame/source-origin 桥接边界约束；远程 frame 不得读取。

## `app.storage`

`app.storage` 按 publisher subject + plugin ID 隔离，值为 JSON：

- 总配额 10 MiB；
- 单项最大 256 KiB；
- 最多 1024 keys；
- Android Keystore AES-GCM 加密；
- Mutex 串行化同一 namespace；
- 临时文件、`fsync` 和原子替换。

重要数据应写入 `app.storage`。LocalStorage/IndexedDB 会在稳定 origin 下跨 commit
保留，但不进入数据回滚快照。

提高 `data_schema_version` 时，migration entrypoint 在独立临时 origin 的隐藏
WebView 中运行。它无网络、无校园权限，只能操作影子 KV，并必须在 30 秒内调用：

```js
await window.BjtuService.invoke('migration.commit', {});
```

成功提交后才切换包与数据；失败或超时会丢弃影子数据并恢复旧 KV。插件回滚会恢复上一
包、Manifest、授权状态和 KV 快照。当前不提供外部文件导出；可移植 SAF 导入导出属于
后续版本。

## `campus.request`

请求格式：

```js
const response = await window.BjtuService.invoke('campus.request', {
  service_id: 'aa',
  method: 'GET',
  path: '/examine/examplanstudent/stulist/',
  query: { term: '2026-2027-1' },
  accept: 'application/json'
});
```

约束：

- 仅 `GET`/`HEAD`；
- 固定 15 秒超时；
- 5 MiB 流式响应上限；
- 只接受 registry 允许的 path、query key 和对应细粒度权限；
- 宿主复用 `SessionManager`，但不会把 Cookie 或认证头暴露给插件；
- 剥离 `Cookie`、`Set-Cookie`、认证头和不安全响应头；
- 重定向越出当前注册服务立即失败。

首批 registry：

| service | 范围 |
| --- | --- |
| `mis` | 仅 `/home/`，需要 `identity.profile.read`。 |
| `aa` | 已验证的课表、考试、成绩、学籍和学业进度只读路径。 |
| `ve` | 已验证的课程、作业列表、资源、回放和用户信息 path+method 组合。 |

Coremail、知行、就业和全部写操作不在 registry 中。

## 生命周期与环境

window 事件名为 `pause`、`resume`、`back`、`theme`、`resize`、`network`；为兼容包装器，
宿主同时发送 `bjtu:<name>`。事件 `detail` 提供：

- viewport 宽高、density、安全区和 IME 高度；
- 方向与 font scale；
- light/dark 主题、减少动态、高对比度；
- online、validated、metered 与网络 transport。

`back` 可取消：

```js
window.addEventListener('back', (event) => {
  if (closeDialogIfOpen()) event.preventDefault();
});
```

150 ms 内未消费时，宿主回退 Web 历史；没有历史则关闭插件。

## WebView 安全基线

- `MIXED_CONTENT_NEVER_ALLOW`；
- 关闭第三方 Cookie、文件访问、content 访问、多窗口和下载；
- 每个本地响应设置 CSP、Permissions-Policy、`nosniff` 和 Referrer-Policy；
- CSP 分别绑定 connect/media/frame；
- WebViewClient 阻止任何远程 main-frame 导航；
- `navigation_origins` 只允许用户手势打开外部浏览器；
- bridge 消息必须来自 main frame 和精确稳定 origin。

## 更新、迁移、回滚与删除

- 更新保留仍被声明且已授予的权限；
- 删除的权限自动撤销；
- 新增权限与 origin 仅展示差异；
- 用户拒绝新增 required 权限时不切换版本；
- 始终保留上一版本包和 KV 快照；
- 删除插件会清理 KV、配置、快照、包和稳定 origin WebStorage；
- 物理清理失败会在 Room 中留下 tombstone，并在下次启动幂等重试；
- legacy 救援数据只在用户明确删除插件时清理。

## 发布检查清单

- Manifest 为 v3，版本使用 SemVer，`bridge_origins` 严格为 `["self"]`。
- `dist/` 自包含所有可执行脚本；不加载远程 JavaScript。
- origin 按用途最小声明，公开制品只使用 HTTPS。
- 远程 iframe 使用规定 sandbox 且声明 `remote.frame.v1`。
- 重要数据使用 `app.storage`，schema 提升包含可重复执行的 migration。
- 所有 bridge 调用处理结构化错误和 `retryable`。
- 未写入真实校园账号、密码、Cookie、token、邮件、验证码或个人信息。
- 已运行共享 lint、平台测试和 Android 对应测试。
