# ExecPlan: 发布 BJTU MIS 网站 v1.4.0 下载入口

## 1. Purpose and user-visible outcome

更新 `web/` 静态站点的 Android 下载入口，并将完整站点以新 release 目录部署到 `my-server`。完成后，访问 `bjtu.cc` 时：

- 默认版本显示为 `v1.4.0`。
- GitHub 版本显示为 `v1.4.0`，入口指向仓库的最新 Release。
- 在 GitHub 尚未公开 `v1.4.0` APK 时，页面明确显示“等待 GitHub 发布”，不提供会返回 404 或下载旧版本的伪直链。
- 百度网盘入口指向分享 `1bfgaYYnw5P0xoOoVm7jLQg`，文件名为 `app-release.apk`，提取码仍为 `0721`。

成功标准是本地静态检查通过、服务器 release 文件与本地一致、Nginx 配置测试通过，且公网首页返回的新 HTML 包含上述版本和链接。

## 2. Repository context

- 生产静态文件：`web/` 中除 `web/platform/` 以外的内容；该后端目录是在本次历史部署完成后迁入的，后续静态发布必须排除。
- 下载页：`web/index.html`
- Release 动态同步：`web/assets/js/site.js`
- 服务器布局和回滚要求：`deploy/README.md`
- 生产站点当前由 `my-server` 的 Nginx 直接提供，`/var/www/bjtu.cc/current` 指向一个带时间戳的 release 下的 `web/`。
- 初始工作区存在与本任务无关的用户改动：`android/open-webui/static/pyodide/pyodide-lock.json`，不得修改、回退或部署。

## 3. Constraints and non-goals

- 只修改网页下载入口、缓存版本号和本执行计划。
- 不修改 Android 工程、Open WebUI、插件平台、Nginx 配置或服务器 secrets。
- 不上传 APK、Android 源码、构建缓存或仓库其他目录。
- 不删除旧 release；旧 release 是本次部署的回滚点。
- GitHub 公共 API 在部署前仍将 `v1.3.1` 标记为 Latest，且公开侧没有 `v1.4.0` 标签或 Release。网页必须避免用动态 API 把默认显示重新降级到 `v1.3.1`。

## 4. Proposed design

- 在内置 Release 快照中加入 `v1.4.0`，并标记为等待 GitHub 发布。
- GitHub 入口使用稳定地址
  `https://github.com/wan300/bjtu_mis_Android/releases/latest`。
- GitHub API 返回中尚无 `v1.4.0` 时，将内置 `v1.4.0` 与公开 Release 列表合并；GitHub 公开 `v1.4.0` 后，优先使用 API 的真实 Release 数据。
- 更新 HTML 的无 JavaScript 默认值和百度网盘的两个入口。
- 以新时间戳目录上传 `web/` 的静态内容并排除 `web/platform/`，核对文件数和 `index.html`、`site.js` 的 SHA-256，在 Nginx 回环地址预检后原子切换 `current`。

## 5. Validation plan

- `git diff --check -- web`
- 搜索旧百度链接和旧默认版本，确认生产文件中不再残留。
- 使用 Node 解析 `web/assets/js/site.js`，确认 JavaScript 语法有效。
- 比较本地与服务器 release 的文件数、`index.html` 和 `site.js` SHA-256。
- `nginx -t`
- 切换前通过回环地址预检新 release；切换后通过 `https://bjtu.cc/` 检查版本和两个下载 URL。

## 6. Rollback

记录部署前 `current` 的绝对目标。若上传校验、Nginx 检查或公网验证失败，则把 `current` 原子恢复到原目标并重新验证站点。旧 release 不删除，因此内容回滚不依赖重新上传。

## 7. Progress log

- 2026-07-28：确认当前 `current` 指向 `/var/www/bjtu.cc/releases/20260717-162630/web`，共 53 个文件。
- 2026-07-28：确认 GitHub 公共 Latest 仍为 `v1.3.1`；`v1.4.0` Release 和 tag 在公共 API 中均返回 404。
- 2026-07-28：开始更新下载入口，保护既有 `pyodide-lock.json` 改动。
- 2026-07-28：首次候选 release 通过上传和回环预检；公网端点检查确认固定的 `v1.4.0/app-release.apk` 返回 404，决定改用可用的 Latest 发布页并显示等待发布状态。
- 2026-07-28：最终 release `/var/www/bjtu.cc/releases/20260728-021203/web` 上传完成，共 59 个文件；`index.html` 和 `site.js` 哈希与本地一致。
- 2026-07-28：回环预览、`nginx -t`、原子 `current` 切换和公网检查均通过。公网首页与脚本返回 200，百度分享入口返回 302。
- 2026-07-28：GitHub `/releases/latest` 仍重定向到 `v1.3.1`。`TODO: confirm`：在 GitHub 公开 `v1.4.0` Release 和 `app-release.apk`；公开后网页会自动使用 API 返回的真实资产、日期和大小。
- 2026-07-28：按最新分享信息，将首页两个百度网盘入口更新为 `1bfgaYYnw5P0xoOoVm7jLQg`，文件名明确为 `app-release.apk`，提取码保持 `0721`；准备发布新的静态 release。
- 2026-07-28：新 release `/var/www/bjtu.cc/releases/20260728-130615/web` 已发布，共 59 个静态文件且不含 `web/platform/`；线上 `index.html` 和 `site.js` SHA-256 与本地一致。
- 2026-07-28：公网裸域名和 `www` 首页返回 200，HTTP 首页 308 到 HTTPS，插件 API/页面返回 200；新网盘链接出现 2 次、旧链接 0 次、`app-release.apk` 出现 2 次，百度分享返回有效 302。
- 2026-07-28：本次回滚点为 `/var/www/bjtu.cc/releases/20260728-034109/web`。

## 8. Completion checklist

- [x] 网页默认版本和两个下载渠道已更新。
- [x] 本地静态验证通过。
- [x] 新 release 已上传并完成哈希核对。
- [x] `current` 已原子切换，旧目标已记录。
- [x] Nginx 与公网验证通过。
- [x] `git status --short` 已复核，既有用户改动未受影响。
