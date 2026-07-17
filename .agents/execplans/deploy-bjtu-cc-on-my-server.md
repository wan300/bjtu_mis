# ExecPlan: 将 bjtu.cc 静态站点迁移到 my-server

## 目标与成功标准

把仓库 `web/` 中的静态网页直接部署到 `my-server`，让 `https://bjtu.cc/` 和 `https://www.bjtu.cc/` 不再依赖 B 电脑或 FRP 的 `127.0.0.1:15000`。成功时首页、CSS、JavaScript、图片、模块页和开发者文档均返回正确内容，HTTPS 证书继续有效。

## 已确认事实

- 2026-07-17：工作区在操作前为干净状态。
- `bjtu.cc` 的 A 记录解析到 `my-server` 的 `39.102.114.72`。
- `my-server` 是 Ubuntu 22.04，Nginx 正在监听 80/443。
- `/etc/nginx/sites-enabled/bjtu.cc` 当前把 HTTP/HTTPS 请求代理到 `127.0.0.1:15000`。
- `127.0.0.1:15000` 当前未监听，现有站点链路不可用。
- Let's Encrypt 证书已位于 `/etc/letsencrypt/live/bjtu.cc/`。
- 仓库 `web/` 是约 10 MB 的纯静态站点，没有必须由本站后端处理的 API。

## 范围与约束

- 仅部署 `web/`，不部署 Android 工程、Open WebUI、密钥、本机配置或构建缓存。
- 不修改 DNS、证书内容、FRP 服务或 B 电脑。
- 使用版本化 release 目录和 `current` 符号链接，避免半发布状态并支持快速回滚。
- 保留 Nginx 原配置的时间戳备份；切换前必须通过 `nginx -t`。
- HTTP 保持当前可访问行为；HTTPS 沿用现有证书。

## 实施步骤

1. 在本机为 `web/` 生成文件清单和哈希，确认发布范围。
2. 在 `my-server:/var/www/bjtu.cc/releases/<timestamp>/web/` 创建新 release 内容目录，并把仓库 `web/` 下的内容上传到该目录。
3. 以只绑定 `127.0.0.1` 的临时 Nginx 配置验证首页、静态资源、模块页、404 和内容哈希。
4. 备份 `/etc/nginx/sites-available/bjtu.cc`，把站点从 `proxy_pass` 改为 `root /var/www/bjtu.cc/current` + `try_files`，保留 ACME、日志、TLS 与禁缓存头。
5. 执行 `nginx -t`，通过后 reload；从服务器和公网域名验证 HTTP/HTTPS。
6. 检查 Nginx 错误日志、Git 状态和差异，记录结果。

## 验证

- `sha256sum`：服务器首页和本地 `web/index.html` 一致。
- `curl`：`/`、`/assets/css/styles.css`、`/assets/js/site.js`、`/modules/timetable.html`、`/developers.html` 返回 200。
- `curl`：不存在路径返回 404。
- `curl https://bjtu.cc/`：证书校验成功并返回 200。
- `nginx -t`：配置语法成功。
- Nginx error log 中没有本次切换造成的新错误。

## 风险与回滚

- 配置错误：只有 `nginx -t` 通过才 reload。
- 内容缺失：切换前在本机回环临时端口验证，失败则不切换。
- 切换后异常：优先保留并继续使用已验证的当前静态 release；只有确认 `127.0.0.1:15000` 已恢复监听后，才可恢复带时间戳的 Nginx 备份并执行 `nginx -t && systemctl reload nginx`。
- 发布内容异常：把 `/var/www/bjtu.cc/current` 原子指向上一个已验证 release 的 `web/` 目录后 reload；首次迁移没有上一静态 release 时，保留并修复当前 release，不回退到已知不可用的代理链路。

## 进度日志

- 2026-07-17 16:26：完成拓扑、DNS、证书、端口和仓库静态资源调查；创建计划。
- 2026-07-17 16:28：上传 53 个静态文件到 release `20260717-162630`；关键文件 SHA-256 与仓库一致。
- 2026-07-17 16:29：回环预验证通过：首页、CSS、JavaScript、模块页和开发者文档为 200，缺失路径为 404。
- 2026-07-17 16:30：备份原 Nginx 配置，原子切换 `current` 链接，`nginx -t` 和 reload 成功。
- 2026-07-17 16:31：服务器本机 Host/SNI 验证和外部直连验证通过；HTTPS 证书校验成功，首页哈希一致，切换后未发现新错误。
- 2026-07-17：评审时确认 `current` 实际指向 release 下的 `web/` 目录；修正发布路径描述，并将旧代理配置回滚改为需要上游端口已恢复的有条件操作。

## 完成状态

- [x] 发布内容完整且哈希匹配。
- [x] Nginx 不再为 bjtu.cc 使用 `proxy_pass` 或 `127.0.0.1:15000`。
- [x] HTTP、HTTPS、静态资源、404 与证书验证通过。
- [x] 原配置和回滚路径已保留。

## TODO: confirm

- B 电脑 SSH 公钥认证当前失败，无法读取其站点目录；由于 `my-server:15000` 已不可用，本次以当前干净仓库的 `web/` 为发布源。
