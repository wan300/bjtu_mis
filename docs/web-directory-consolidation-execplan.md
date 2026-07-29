# ExecPlan：将网站前后端统一整理到 web 目录

## 目标

在不影响已经上线的生产 release、不覆盖现有 Android/UI 改动的前提下，把本地插件平台后端从仓库根目录 `platform/` 移动到 `web/platform/`，使网站静态前端与网站后端源码统一保存在 `web/` 下。

## 范围

- 移动 `platform/` 到 `web/platform/`，包括源码、SQL migration、测试、Dockerfile、npm 配置和本地已安装但被忽略的依赖。
- 更新 `.gitignore`、`.dockerignore`、Compose、Dockerfile、README、规格与部署记录中的路径。
- 保留根目录 `deploy/`：它是服务器编排与 Nginx 基础设施配置，不属于前端或后端应用源码。
- 静态站点发布必须排除 `web/platform/`，Nginx 对 `/platform/` 显式返回 404，防止后端源码误入公开站点。
- 不修改或回退 Android、Open WebUI、Apple UI 重构和现有网页内容改动。
- 不移动或重启服务器上的 `/opt/bjtu-plugin-platform/releases/20260728-025807`；线上容器继续使用不可变 release。

## 执行步骤

1. 记录工作区状态以及全部 `platform/` 路径引用。
2. 将 `platform/` 原子地移动为 `web/platform/`，确认旧路径消失、新路径完整存在。
3. 修正 Docker build context 内的复制路径和 Compose 的 Dockerfile 路径。
4. 修正忽略规则，确保 `web/platform` 源码和测试可跟踪，同时继续忽略 `node_modules`、`dist`、`.data` 与 `.env`。
5. 更新项目 README、部署说明、规格和已有部署计划中的本地路径。
6. 在新路径运行 typecheck、单元测试、integration、e2e、Docker Compose 配置校验与 Docker 构建验证。
7. 用 `git status` 和 `git diff --check` 确认只产生目录整理及既有用户改动。

## 验证

- `Set-Location web/platform; npm run typecheck`
- `Set-Location web/platform; npm test`
- `Set-Location web/platform; npm run test:integration`
- `Set-Location web/platform; npm run test:e2e`
- `docker compose -f deploy/docker-compose.plugins.yml config --quiet`
- `docker build -f web/platform/Dockerfile .`
- `git diff --check`

## 回滚

- 本地验证失败时，将 `web/platform/` 移回 `platform/`，并恢复本次涉及的路径配置文件。
- 不执行 `docker compose down -v`，不删除生产数据库或 artifact 卷。
- 服务器 release 与 Nginx 配置不在本次本地整理范围内，因此无需线上切换或停机回滚。

## 进度

- [x] 已盘点现有路径引用和工作区改动。
- [x] 后端目录已移动到 `web/platform/`。
- [x] 忽略规则、构建配置和文档路径已更新。
- [x] 新路径下的 typecheck、单元测试、e2e、前端脚本与 Compose 配置验证已通过；integration 命令通过但因未设置 `TEST_DATABASE_URL` 跳过 2 个数据库用例。
- [ ] Docker 镜像构建已验证；当前 Docker Desktop Linux Engine 未运行，待可用环境复核。
- [x] 最终工作区检查已完成：旧目录的 24 个受跟踪文件均存在于新目录，旧构建路径引用为 0，`git diff --check` 通过。
