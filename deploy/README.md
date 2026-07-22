# bjtu.cc 部署

## 插件平台

插件目录使用 `deploy/docker-compose.plugins.yml` 部署 PostgreSQL 16、迁移任务、Fastify API 和独立校验 worker。部署前：

1. 将 `platform/.env.example` 复制到服务器 `/etc/bjtu-plugin-platform.env`，填写 GitHub OAuth、32 字节 AES-GCM 密钥、管理员 GitHub ID 和数据库密码；不要提交该文件。
2. `/etc/bjtu-plugin-postgres-password` 只包含 PostgreSQL 密码，且必须与 `DATABASE_URL` 中的密码一致。
3. 备份 PostgreSQL、插件 artifact 卷和当前 Nginx 配置，再运行 `docker compose -f deploy/docker-compose.plugins.yml up -d --build`。
4. 安装 `deploy/nginx/bjtu.cc.conf` 后先执行 `nginx -t`，确认 `/api/v1/` 只反代至 `127.0.0.1:15020`。

回滚时恢复旧 Nginx 配置并停止插件 Compose 服务；Android 会自动退化到目录缓存与已安装插件。数据库和 artifact 卷应保留以便恢复。

生产站点是仓库 `web/` 下的纯静态文件，由 `my-server` 上的 Nginx 直接提供。Nginx 配置的仓库副本位于 `deploy/nginx/bjtu.cc.conf`。

服务器布局：

- release 内容目录：`/var/www/bjtu.cc/releases/<timestamp>/web`
- 当前版本：`/var/www/bjtu.cc/current`（必须直接指向某个 release 下的 `web/` 目录）
- Nginx 配置：`/etc/nginx/sites-available/bjtu.cc`
- 启用链接：`/etc/nginx/sites-enabled/bjtu.cc`
- 证书：`/etc/letsencrypt/live/bjtu.cc/`

发布新版本时，应先创建 `/var/www/bjtu.cc/releases/<timestamp>/web/`，再把仓库 `web/` 内的完整内容上传到该目录，核对文件数与关键文件 SHA-256，并在回环端口验证。随后原子更新 `current` 链接，使其直接指向新 release 的 `web/` 目录；安装仓库中的 Nginx 配置，执行 `nginx -t`，成功后再执行 `systemctl reload nginx`。

回滚内容版本时，把 `current` 原子指向上一个已验证 release 的 `web/` 目录。只有确认 `127.0.0.1:15000` 已恢复服务后，才可回滚本次架构迁移：恢复 `/etc/nginx/sites-available/bjtu.cc.before-direct-20260717-162630.bak`，然后依次执行 `nginx -t` 和 `systemctl reload nginx`。如果该端口仍未监听，恢复旧配置会使站点再次不可用，不应作为回滚手段。

不要上传 Android 工程、Open WebUI、密钥、本机配置、APK/AAB、构建缓存或其他生成物。
