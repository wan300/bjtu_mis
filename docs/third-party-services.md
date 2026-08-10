# BJTU MIS Web 插件 Manifest v3 / contract_v1

本文件描述当前唯一可安装、更新和运行的第三方插件契约。Capability 的权威机器可读
来源是
[`plugin-tooling/contracts/capability-contracts.json`](../plugin-tooling/contracts/capability-contracts.json)；
Manifest、marketplace schema、TypeScript 类型、Kotlin descriptor/router/validator 和
[Capability API 参考](generated/plugin-capability-api.md)均由它确定性生成。

旧 Manifest v1/v2 与 P0-A v3（`bjtu-service.json`）不会获得桥或网络，只能在救援入口
导出数据。P0-A 仅能在 publisher subject、plugin ID 和数据 schema 兼容时原位升级为
contract_v1；回滚到 P0-A 也只会恢复救援状态。

## 1. 快速开始

仓库内工具链包含 `@bjtu-mis/plugin-sdk`、`@bjtu-mis/plugin-cli` 和
`create-bjtu-plugin`，本轮不发布到 npm。开发仓库中的构建方式：

```powershell
Set-Location plugin-tooling
npm ci
npm run build
node packages/create-bjtu-plugin/dist/index.js my-plugin --id io.example.demo
Set-Location my-plugin
npm install
npm run dev
```

生成模板是 Vanilla TypeScript + Vite。浏览器开发模式使用 Mock Host；安装 debug APK
并配置 `adb` 后，可在插件目录运行：

```powershell
bjtu dev --android
```

该命令只向 debug 包的显式 receiver 开启当前插件的联调 transport，配置
`adb reverse`，并在退出时撤销。HTTP 与 HMR WebSocket 都经稳定插件 origin 转发到
loopback；release source set 不包含 receiver 或 transport。`bjtu-plugin.dev.json`
只允许保存 Mock/HMR 配置，禁止进入发布包。

## 2. 包结构

```text
bjtu-plugin.json
bjtu-marketplace.json       # 大厅投稿必需；GitHub 直链安装可省略
bjtu-plugin.dev.json        # 仅本地开发；不得打包
dist/
  index.html
  icon.svg
  assets/
```

`entrypoint`、`icon`、migration entrypoint 和截图路径都相对于 `dist/`。发布包只包含
`bjtu-plugin.json`、可选的 `bjtu-marketplace.json` 和 `dist/`。

## 3. 精简 Manifest

`bjtu-plugin.json` 示例：

```json
{
  "schema_version": 3,
  "id": "io.example.demo",
  "name": "Demo",
  "version": "1.0.0",
  "entrypoint": "index.html",
  "icon": "icon.svg",
  "capabilities": {
    "required": ["runtime.lifecycle@1", "network.request@1"],
    "optional": ["storage.blob@1"]
  },
  "origins": {
    "connect": ["https://api.example.com"],
    "media": ["https://cdn.example.com"]
  },
  "data_schema_version": 1
}
```

规则：

- `runtime.lifecycle@1` 必须是 required Capability。
- optional Capability 首次安装默认关闭，用户授权后才可用。
- 空 `origins`、`configuration` 和 `capabilities.optional` 必须省略。
- `origins` 按 `connect`、`media`、`frame`、`navigation` 分离；公开包只接受标准 HTTPS
  origin，不接受路径、通配符、校园域名、私网、回环或链路本地地址。
- `origins.frame` 需要 `remote.frame@1`。远程 frame 永远无原生桥。
- 使用 `storage.kv@2` 或 `storage.blob@1` 时必须声明正整数
  `data_schema_version`；版本增加时必须提供 `migration_entrypoint`。
- `permissions`、`runtime_version`、`min_runtime_version`、`bridge_origins`、内嵌
  marketplace 信息及 P0-A 的平铺 capability/origin 字段全部禁止。

完整结构见
[`third-party-service-manifest.schema.json`](third-party-service-manifest.schema.json)。

## 4. Marketplace 元数据

`bjtu-marketplace.json` 示例：

```json
{
  "description": "A small demonstration plugin.",
  "author": "Example Team",
  "category": "other",
  "tags": ["demo"],
  "license": "MIT",
  "screenshots": [
    { "src": "screenshots/home.webp", "alt": "Home screen" }
  ]
}
```

大厅投稿必须提供描述、作者、分类和标签；schema 位于
[`bjtu-marketplace.schema.json`](bjtu-marketplace.schema.json)。

## 5. Capability 与 SDK

稳定 Capability 覆盖 lifecycle、configuration、remote frame、外部导航、身份、教务、
邮件读取和只读 `campus.request@1`。beta Capability 包括：

- `network.request@1`
- `storage.kv@2`
- `storage.blob@1`
- `cache.resource@1`
- `academic.userCourses.command@1`
- `academic.homework.submit@1`
- `mail.send@1`

精确方法、请求/响应 schema、权限、确认、幂等、配额、超时、错误和平台支持范围见
[生成的 API 参考](generated/plugin-capability-api.md)。

插件只使用 SDK 命名空间，不直接访问 transport：

```ts
import { BjtuPluginError, createBjtuPluginSdk } from '@bjtu-mis/plugin-sdk';

const bjtu = createBjtuPluginSdk();
const host = await bjtu.runtime.handshake();
await bjtu.runtime.ready();

const profile = await bjtu.campus.getProfile();
console.log(profile.data, profile.meta.syncedAt, profile.meta.fromCache);

const controller = new AbortController();
const response = await bjtu.network.request(
  { url: 'https://api.example.com/data', method: 'GET' },
  {
    signal: controller.signal,
    onProgress: ({ loaded, total }) => console.log(loaded, total)
  }
);

try {
  await bjtu.storage.kv.set('theme', 'dark');
} catch (error) {
  if (error instanceof BjtuPluginError) console.error(error.code, error.message);
}
```

SDK 公开 `runtime`、`configuration`、`network`、`storage.kv`、`storage.blob`、
`cache`、`navigation`、`campus` 和 `mail`。常用身份、教务和邮件方法的 `data`
结构由 Registry 生成具体 TypeScript 类型；仅通用 `campus.request@1` 的业务载荷
保持 `unknown`。所有校园读取统一返回：

```ts
{
  data: T,
  meta: {
    syncedAt: string,
    source: 'cache' | 'network' | 'mixed',
    coverage: 'complete' | 'partial' | 'unknown',
    fromCache: boolean
  }
}
```

迁移页面只能使用 `createBjtuPluginMigrationSdk()`，其表面仅包含影子 KV 和显式
`commit()`；网络、校园读取和 Command Capability 不可用。

## 6. Protocol v2 与桥边界

SDK 内部 protocol v2 请求为：

```json
{
  "protocolVersion": 2,
  "requestId": "request-id",
  "capability": "identity.profile@1",
  "method": "getProfile",
  "params": {}
}
```

响应使用统一成功/错误 envelope；事件包含 `eventId`、`capability`、`event` 和可选
`requestId`。lifecycle 事件由契约固定为 `resume`、`pause`、`theme`、`resize`、
`network` 与 `back`；`back` listener 返回 `true`（也可返回
`Promise<true>`）才表示已消费。宿主只在没有确认消费时回退 WebView 历史或关闭页面，
不会再并行派发 DOM back 事件。SDK 把宿主错误转换为 `BjtuPluginError`，并通过
`AbortSignal` 发送取消。
底层对象不属于公共 API，宿主也不再注入 `window.BjtuService`。

原生桥只注入由不可变 publisher subject 与 plugin ID 决定的稳定本地 HTTPS origin，
且仅接受该 origin 的 main-frame 消息。`bridge_origins` 是宿主固定为 self 的不变量，
不是作者声明。远程 frame、popup 和导航目标永远无法获得桥。

二进制写入必须先完成 `runtime.lifecycle@1#handshake`。宿主返回
`binaryTransports` 与可选 `preferredBinaryTransport`：支持
`WEB_MESSAGE_ARRAY_BUFFER` 的 WebView 同时提供 `arraybuffer` 和
`base64url-chunks-v1` 并首选前者；缺少 ArrayBuffer feature 但仍具备核心安全桥能力时，
只提供 Base64URL 兼容模式。只有缺少 `DOCUMENT_START_SCRIPT` 或
`WEB_MESSAGE_LISTENER` 才彻底拒绝运行。

私有二进制声明固定为 `{ transport, size, chunks, sha256 }`。ArrayBuffer 使用
256 KiB 原始分片；兼容模式使用 RFC 4648 URL-safe、无 `=` 填充的 48 KiB 原始
分片，并等待逐片 ACK 后再发送下一片。宿主逐片严格解码、更新 SHA-256 并写入
app-private 临时文件，不在内存聚合完整编码或完整二进制。Blob/Cache 上传继续受
item/plugin/global 配额、60 秒 deadline、最多四并发、64 MiB 磁盘安全余量和取消/
异常/关闭/下次启动清理约束。旧 SDK 的非二进制调用仍可使用；缺少 transport 或
SHA-256 的旧 Blob/Cache 写入会被拒绝并提示升级到 SDK 0.2.0。

网络图片及其他下载响应继续走
`network.request resource → native resource handle → cache.promote`，不得绕经
JavaScript/Base64。兼容分片仅用于插件生成的 Blob、元数据备份和少量必须由 JS
上传的数据。

每个生成的 capability deadline 同时由 SDK 与 Android 宿主执行。插件可在
`InvokeOptions.timeoutMs` 中缩短 deadline，但不能超过契约上限；超出 deadline
返回 `request_timeout`，上游网络栈自身超时仍返回 `network_timeout`。

## 7. 网络、存储与资源

`network.request@1` 使用无 Cookie、无宿主认证器的独立 OkHttp 客户端。它支持
GET、HEAD、POST、PUT、PATCH、DELETE，以及 JSON、文本、FormData 和 Blob handle
请求体。初始请求、每次 DNS 解析和每次重定向都会重新执行 SSRF 检查；`Host`、
`Cookie`、`Content-Length` 等传输层 header 被拒绝。

- 默认超时 15 秒，上限 60 秒；最多 5 次手动重定向。
- 每插件并发 4，每 origin 并发 2。
- JSON/文本内联响应上限 1 MiB；更大或二进制响应返回资源 handle。
- 浏览器 `fetch` 可继续访问 Manifest 已声明的 connect origin，但不会获得宿主会话。

`storage.kv@2` 的限制为每插件 10 MiB、单值 256 KiB、最多 1024 keys。它提供
batch、revision/CAS、声明式原子 transaction、watch，以及通过 Blob handle 完成的
导入/导出。旧 KV 文件可由同 publisher+plugin 的受控升级直接读取。

`contract_v1` WebView 关闭 DOM Storage，并在 document-start 阶段以不可重新配置的
guard 禁用 `localStorage`、`sessionStorage`、IndexedDB、Cache Storage、Cookie、
Worker/Service Worker 和浏览器文件存储；CSP 同时设置 `worker-src 'none'`。guard
无法完整安装时不会暴露 protocol v2 桥。插件必须使用 SDK 的 KV/Blob/Cache；只有
无桥、无网络的 legacy 救援页保留 DOM Storage 读取能力，以便用户导出旧数据。CI
在 API 26/35 运行真实 JavaScript probe，避免只断言 WebSettings 配置值。

Blob 不可变且内容寻址；Cache 可淘汰并使用 LRU，pin 只阻止自动淘汰。两者按
publisher+plugin 隔离，使用 AES-GCM 分块加密和原子索引：

- Blob：每插件 256 MiB，单项 64 MiB。
- Cache：每插件 512 MiB，全局 1 GiB，单项 250 MiB。
- 写入还必须保留设备安全剩余空间。

资源通过稳定 origin 的 `/__bjtu/resources/<handle>` 提供 GET、HEAD 和 Range，支持
离线读取。零字节 Blob/Cache 是合法资源（对其发起 Range 返回 416）。网络返回的临时
Cache handle 可通过 `cache.promote(handle, key)` 绑定为业务 key，也可通过
`cache.deleteHandle(handle)` 显式释放，避免只能等待 LRU。插件更新保留上一版本包、
KV 快照和 Blob 影子索引；失败时原子恢复。

## 8. Command Capability

所有会改变校园或宿主状态的调用都是 Command Capability。每次调用都必须经过用户
确认并携带 idempotency key。宿主按“key + 请求摘要”处理：

- 相同 key 和摘要返回原回执，不重复执行。
- 相同 key、不同摘要返回 `idempotency_conflict`。
- 加密回执不保存请求正文，保留 7 天，每插件最多 1024 条。

邮件发送和作业提交不会静默执行。

## 9. 错误

统一错误至少包括：

`permission_denied`、`capability_unavailable`、`invalid_request`、
`origin_denied`、`network_timeout`、`request_timeout`、`http_error`、`quota_exceeded`、
`resource_too_large`、`migration_failed`、`user_cancelled` 和
`idempotency_conflict`。

## 10. 校验、打包与发布

```powershell
bjtu lint --source .
bjtu lint --marketplace .
bjtu test .
bjtu inspect .
bjtu doctor .
bjtu pack .
```

`bjtu test` 会启动本机 Chrome/Chromium/Edge 的 headless 实例，在受控 protocol v2
Mock Host 中实际加载发布入口，并捕获脚本错误与未处理 Promise rejection。
`bjtu pack` 生成确定性 ZIP，拒绝 `bjtu-plugin.dev.json`，并与平台一致地执行
25 MiB 压缩包、50 MiB 解压内容、1000 文件及 1 MiB 图标限制。迁移 P0-A 源仓库可先
运行 `bjtu migrate`，再人工复核 Capability、origin、数据 migration 和 marketplace
信息；旧插件不会被平台自动重发。

新目录、投稿、更新解析和制品使用 `/api/v3`。平台保存 `contractProfile`、派生
`runtimeFloor`、Capability 声明和独立 marketplace 元数据。`/api/v2` 只保留旧
P0-A 目录、详情与制品读取，所有写入口冻结。

仓库内完整验证：

```powershell
Set-Location plugin-tooling
npm run generate:check
npm run typecheck
npm test
npm run pack:check

Set-Location ..\web\platform
npm run typecheck
npm test
npm run test:integration
npm run test:e2e
```

## GitHub README 预览

Android 插件大厅和 GitHub 直链导入会在安装预检完成后提供 README 预览。预览请求固定到
预检使用的 `owner/repo@commit`，单次 README 不超过 1 MiB，并使用无 Cookie、无认证、禁止
重定向的只读客户端；成功内容只保存在本次应用进程的有界内存缓存中，不写入 Room 或文件。

README Markdown 在宿主侧清洗后以独立浮窗 WebView 展示。该 WebView 禁用 JavaScript、DOM
存储、文件访问和混合内容，不注入插件桥，也不加载插件资源。仅允许 HTTPS GitHub/GitHub
User Content/GitHub Assets 图片，其他资源会被移除；README 链接只在用户点击时交给系统浏览器。
README 缺失或读取失败不会阻止用户继续确认或取消安装。
