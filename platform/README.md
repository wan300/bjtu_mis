# BJTU 插件目录平台

Node.js 22 + TypeScript + Fastify 服务，包含同一代码库中的 API 与独立校验 worker。PostgreSQL 16 负责队列与业务数据，不依赖 Redis。

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
- worker 只下载归档、静态扫描并重新打包 `bjtu-service.json` 与 `dist/`，绝不运行仓库脚本或插件代码。
- 单个源包限制 25 MiB、解压限制 50 MiB/1000 文件，并拒绝路径穿越、绝对路径、符号链接和设备条目。
- 自动校验通过仍统一显示“未人工审核”。平台从不接收或保存 Android 用户的插件配置值。

环境变量示例见 `.env.example`；生产编排与回滚说明见 `deploy/docker-compose.plugins.yml` 和 `deploy/README.md`。
