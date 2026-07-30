# BJTU 插件目录平台

> 本服务服从 constitution 原则 VII 与 `docs/third-party-services.md` 的 Manifest v3 /
> contract_v1 基线。旧 `/api/v1`、`/api/v2` 和历史格式仅用于只读兼容，不得扩展为投稿、
> 更新或运行能力。

Node.js 22 + TypeScript + Fastify 服务，包含同一代码库中的 API 与独立校验 worker。PostgreSQL 16 负责队列与业务数据，不依赖 Redis。

新目录、投稿、更新解析和制品使用 `/api/v3`，只接受 `bjtu-plugin.json` contract_v1
与独立的 `bjtu-marketplace.json`。`/api/v2` 冻结为 P0-A 目录、详情和制品只读接口；
`/api/v1` 仅保留更早 legacy 兼容。

## 本地验证

```powershell
npm ci
npm run typecheck
npm test
npm run test:integration
npm run test:e2e
```

`test:integration` 仅在设置 `TEST_DATABASE_URL` 时执行数据库迁移断言。生产迁移使用 `node dist/src/migrate.js`。

## 安全边界

- OAuth 不请求仓库写 scope，仅接受公开 GitHub 仓库根链接，并使用仓库响应中的 `permissions.push/admin` 证明管理权限。
- OAuth token 只以服务端 AES-256-GCM 密文保存；`TOKEN_ENCRYPTION_KEY_BASE64` 必须解码为 32 字节。
- worker 只下载归档、静态扫描并重新打包 `bjtu-plugin.json`、`bjtu-marketplace.json`
  与 `dist/`，绝不运行仓库脚本或插件代码；P0-A 必须由作者迁移后重新投稿。
- publisher subject 首次发布时固定为 GitHub owner 数值 ID；GitHub repository 数值 ID 用于识别改名、重建与 owner 转移。
- owner 转移会冻结更新，管理员批准后只更新 owner/repository 绑定，原 publisher subject 保持不变。
- 单个源包限制 25 MiB、解压限制 50 MiB/1000 文件，并拒绝路径穿越、绝对路径、符号链接和设备条目。
- 自动校验通过仍统一显示“未人工审核”。平台从不接收或保存 Android 用户的插件配置值。

环境变量示例见 `.env.example`；本后端与网站静态前端统一位于 `web/`，生产编排与回滚说明见仓库根目录的 `deploy/docker-compose.plugins.yml` 和 `deploy/README.md`。
