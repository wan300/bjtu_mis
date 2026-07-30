# ExecPlan：完整部署插件大厅前后端

> 历史记录：本计划记录 2026-07-28 的 P0-A `/api/v2` 上线过程。contract_v1
> 的当前部署与回滚要求以
> `docs/plugin-runtime-contract-p0-p1-execplan.md` 和 `deploy/README.md` 为准；
> 生产 Nginx 还必须代理 `/api/v3/`。

## 目标

修复 `https://bjtu.cc/plugins/index.html` 的 API 404，完整部署静态前端、PostgreSQL、数据库迁移、Fastify API、校验 Worker、GitHub OAuth 和 Nginx `/api/v1/` 代理。

完成后：

- `GET /api/v2/plugins` 返回 200 和兼容的 Manifest v3 插件列表 JSON。
- `GET /api/v1/auth/me` 创建安全会话 Cookie。
- GitHub OAuth 登录、投稿、校验任务、插件管理和管理员接口具备生产配置。
- API 仅绑定服务器回环地址 `127.0.0.1:15020`，公网只通过 HTTPS Nginx 访问。
- GitHub 用户 `wan300`（数字 ID `143626063`）是平台管理员。

## 本地与生产布局

- 静态前端：`web/`，发布时必须排除 `web/platform/`。
- 插件大厅请求逻辑：`web/assets/js/plugins.js`。
- 平台后端：`web/platform/`。
- 数据库迁移：`web/platform/src/migrations/`。
- Compose：`deploy/docker-compose.plugins.yml`。
- Nginx：`deploy/nginx/bjtu.cc.conf`。
- 生产静态站点：`/var/www/bjtu.cc/current`。
- 生产平台：`/opt/bjtu-plugin-platform/current`。
- 生产环境配置：`/etc/bjtu-plugin-platform.env`，权限 `0600 root:root`。
- PostgreSQL 密码文件：`/etc/bjtu-plugin-postgres-password`，权限 `0600 root:root`。

## 安全约束

- OAuth Client Secret、数据库密码和 token 加密密钥不得写入仓库、日志或公开输出。
- PostgreSQL 和 API 不开放公网端口。
- PostgreSQL 与 artifact 数据卷在回滚时保留，禁止使用 `docker compose down -v`。
- 静态站点 release 禁止包含 `web/platform/`；Nginx 对 `/platform/` 显式返回 404。
- 保留旧 release、旧 Nginx 配置和 OAuth 更新前的环境文件备份。

## 验证

本地：

- `Set-Location web/platform; npm run typecheck`
- `Set-Location web/platform; npm test`
- `Set-Location web/platform; npm run test:integration`
- `Set-Location web/platform; npm run test:e2e`
- `node --check web/assets/js/plugins.js`
- `git diff --check`

生产：

- PostgreSQL health 为 healthy。
- migration 退出码为 0。
- `GET http://127.0.0.1:15020/health` 返回 200。
- `GET https://bjtu.cc/api/v2/plugins` 返回 200。
- `GET https://bjtu.cc/api/v1/auth/me` 返回会话 JSON，并设置 `HttpOnly; Secure; SameSite=Lax` Cookie。
- `GET https://bjtu.cc/api/v1/auth/github/start` 返回 302，Client ID 和 callback URL 正确。
- `GET https://bjtu.cc/platform/package.json` 返回 404。
- `nginx -t` 通过且服务状态为 active。

## 回滚

- 平台回滚：把 `/opt/bjtu-plugin-platform/current` 指回上一 release，使用相同数据卷重建 API/Worker。
- Nginx 回滚：恢复时间戳备份，执行 `nginx -t` 后 reload。
- 静态前端回滚：把 `/var/www/bjtu.cc/current` 原子指回上一 release。
- OAuth 回滚：恢复 `/etc/bjtu-plugin-platform.env.before-oauth-20260728-032748.bak` 后重建 API/Worker。
- 不自动删除 release、数据库卷或 artifact 卷。

## 完成记录

- [x] 本地 typecheck、9 项单元测试和 e2e 通过。
- [x] integration 命令通过；未设置本地 `TEST_DATABASE_URL` 时按设计跳过 2 个数据库用例。
- [x] 生产 release `/opt/bjtu-plugin-platform/releases/20260728-025807` 已上传并核对。
- [x] PostgreSQL、migration、API 和 Worker 正常。
- [x] Nginx `/api/v1/` 代理已安装并验证。
- [x] 静态前端 release 已发布，插件大厅 API 不再返回 404。
- [x] 生产 OAuth 凭据已通过受限临时文件安装，临时文件已删除。
- [x] API/Worker 已用真实 OAuth 配置重建。
- [x] OAuth start 返回 302，Client ID 与 callback 参数验证通过。
- [x] `wan300` 的数字 ID `143626063` 已配置为管理员。
- [x] `/platform/` 静态访问保护已上线并返回 404。
- [x] 回滚点已记录。

## 剩余人工验收

首次使用时由 `wan300` 在浏览器中打开插件管理页，点击 GitHub 登录并确认授权；成功回跳后，`/api/v1/auth/me` 应返回 `authenticated: true`、`login: "wan300"` 和 `admin: true`。

## 2026-07-28 HTTP 插件大厅 404 事件

### 现象与根因

- `https://bjtu.cc/api/v2/plugins?` 与 `https://www.bjtu.cc/api/v2/plugins?` 返回 200。
- `http://bjtu.cc/api/v2/plugins?` 与 `http://www.bjtu.cc/api/v2/plugins?` 返回 404。
- HTTP 插件大厅页面本身返回 200，因此页面脚本保持 HTTP 同源请求 API，落入没有 `/api/v1/` 代理的 HTTP Nginx server。
- 插件大厅在无筛选条件时额外生成了末尾空查询串 `?`；它不是 HTTPS 404 的原因，但会一并清理。

### 修复与回滚

1. 保留 `/.well-known/acme-challenge/`，将 HTTP `location /` 改为 308 永久跳转至相同 host、URI 和 query 的 HTTPS 地址。
2. 前端只在筛选参数非空时附加 `?`。
3. 创建新的静态 release，排除 `web/platform/`，核对文件数与关键 SHA-256 后原子切换。
4. 安装 Nginx 配置前保存时间戳备份，`nginx -t` 通过后 reload。
5. 若公网或浏览器测试失败，恢复 Nginx 备份与旧静态 release 指针。

### 验证矩阵

- HTTP 裸域名与 `www` 的插件页面、API 均返回 308 且目标为 HTTPS。
- HTTPS 裸域名与 `www` 的插件页面、`/api/v2/plugins`、`/api/v2/plugins?` 均返回 200。
- 真实浏览器进入 HTTP 插件大厅后最终 URL 为 HTTPS，插件目录显示“暂无符合条件的插件”，没有请求失败文本。
- 浏览器网络中 `/api/v2/plugins` 为 200，控制台没有 404、脚本错误或 mixed-content 错误。
- `/favicon.ico` 由现有站点图标提供并返回 200，避免浏览器控制台出现与插件功能无关的资源 404。
- API、Worker、PostgreSQL 保持健康，Nginx 错误日志没有新的相关错误。

### 完成记录

- [x] 本地 `plugins.js` 与 `site.js` 语法检查通过，`git diff --check` 通过。
- [x] HTTP 裸域名和 `www` 的页面/API 均返回 308，目标保持原 host、path 和 query 并切换为 HTTPS。
- [x] HTTPS 裸域名和 `www` 的页面、无查询 API、空查询 API 均返回 200。
- [x] 新静态 release `/var/www/bjtu.cc/releases/20260728-034109/web` 已发布，包含 59 个文件且不存在 `platform/`。
- [x] 线上 `plugins.js` SHA-256 与本地一致。
- [x] Playwright 从 HTTP 打开插件大厅后最终位于 HTTPS；初始目录请求 200，筛选请求 200，页面状态正常。
- [x] 插件大厅与“我的插件”页的干净浏览器会话均为 0 console errors、0 warnings。
- [x] `/api/v1/auth/me` 返回 200，未登录页面正确显示 GitHub 登录入口。
- [x] `/favicon.ico` 返回 200，`/platform/package.json` 返回 404。
- [x] 后端 typecheck、9 项单元测试和 e2e 通过；integration 命令通过但本地未设置 `TEST_DATABASE_URL`，2 项数据库用例按设计跳过。
- [x] 生产 PostgreSQL 的 8 张预期业务表均存在；PostgreSQL healthy，migration 为 Exited (0)，API 与 Worker 为 Up。
- [x] 最近 10 条 `/api/v2/plugins` HTTPS 请求全部为 200，最近 200 条错误日志没有代理连接失败。
- [x] Nginx 配置测试通过，服务状态为 active。

回滚点：

- Nginx：`/etc/nginx/sites-available/bjtu.cc.before-http-redirect-20260728-034109.bak`
- 旧静态 release：`/var/www/bjtu.cc/releases/20260728-025807/web`
