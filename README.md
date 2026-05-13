# BJTU MIS

BJTU MIS 是一个面向北京交通大学 MIS / 教学服务系统的本地信息采集与查看项目。仓库目前同时保留两套实现：

- `backend/` + `frontend/`：本地 Web 版。FastAPI 负责登录、会话维护、数据采集、缓存和文件下载代理，Vue 3 控制台负责展示与操作。
- `android/`：原生 Android 迁移版。应用直接访问 BJTU CAS、MIS、AA 和 VE 系统，不依赖本地 HTTP 后端，使用本地加密 Cookie 和 Room 快照支持离线查看。

项目只在本机保存登录态和同步数据，`captures/`、`runtime/`、`.venv/`、`frontend/dist/`、`frontend/node_modules/`、Android 构建产物等已在 `.gitignore` 中排除。

## 功能范围

当前已实现的数据模块包括：

- 统一认证登录与会话校验
- 个人信息
- 学业进度、培养计划完成情况
- 本学期课表
- 考务安排
- 主修成绩、历史成绩
- 学年日历
- 作业列表与作业提交
- 课程资源列表与下载
- 空教室查询
- 同步状态、模块快照与失败降级结果

数据来源主要分为：

- `AA` 教学支撑平台：课表、考务、成绩、学业进度、空教室、部分学籍信息
- `VE` 智慧课程平台：教学日历、作业、课程资源
- `MIS / CAS`：统一认证与平台跳转

## 目录结构

```text
.
├── backend/        # FastAPI 后端、采集 Provider、HTML/JSON 解析器、接口测试
├── frontend/       # Vue 3 + Vite + Naive UI 控制台
├── android/        # Kotlin + Jetpack Compose 原生 Android 应用
├── captures/       # Playwright 持久化浏览器 profile 和日志，本地生成，不进 Git
├── runtime/        # SQLite、会话状态和锁文件，本地生成，不进 Git
├── requirements.txt
└── README.md
```

## 环境要求

Web 版：

- Python 3.11+ 建议
- Node.js 20+ 建议
- Chromium 由 Playwright 安装

Android 版：

- Android Studio
- JDK 17
- Android SDK，项目 `compileSdk = 35`、`minSdk = 26`

## Web 版启动

在仓库根目录创建虚拟环境并安装依赖：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m playwright install chromium
```

安装前端依赖：

```powershell
Set-Location frontend
npm install
Set-Location ..
```

启动后端：

```powershell
Set-Location backend
..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

另开一个终端启动前端开发服务器：

```powershell
Set-Location d:\code\project\bjtu_web\frontend
npm run dev
```

访问：

- 前端控制台：`http://127.0.0.1:5173`
- 后端健康检查：`http://127.0.0.1:8000/health`
- API 文档：`http://127.0.0.1:8000/docs`

Vite 已将 `/api`、`/health`、`/favicon.ico` 代理到 `http://127.0.0.1:8000`。如需改后端地址，可设置 `VITE_API_PROXY_TARGET`。

## 登录与同步

Web 版有两种登录方式：

1. 推荐：打开前端 `/login` 页面，输入统一认证账号、密码和验证码。后端会通过 CAS 登录，并补建 AA / VE 会话。
2. 备用：在登录页点击“打开浏览器登录”，或执行 CLI 打开 Playwright 持久化浏览器，手动完成登录。

备用 CLI：

```powershell
Set-Location d:\code\project\bjtu_web\backend
d:\code\project\bjtu_web\.venv\Scripts\python.exe -m app.cli open-browser
```

手动执行一次同步：

```powershell
Set-Location d:\code\project\bjtu_web\backend
d:\code\project\bjtu_web\.venv\Scripts\python.exe -m app.cli sync-once
```

登录态默认保存到 `runtime/session_state.json`，同步快照默认保存到 `runtime/bjtu_mis.sqlite3`。这些文件包含敏感会话信息，只应保存在本机。

## Web 版生产运行

构建前端：

```powershell
Set-Location d:\code\project\bjtu_web\frontend
npm run build
```

构建完成后，后端会在根路径自动托管 `frontend/dist/index.html` 和静态资源：

```powershell
Set-Location d:\code\project\bjtu_web\backend
d:\code\project\bjtu_web\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

然后访问 `http://127.0.0.1:8000`。

## Android 版

Android 模块是原生迁移目标，不需要启动 PC 端后端：

1. 用 Android Studio 打开 `android/`。
2. 等待 Gradle 同步完成。
3. 运行 `app` target。

命令行构建与测试：

```powershell
Set-Location d:\code\project\bjtu_web\android
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

Android 端当前实现：

- Kotlin、Jetpack Compose、Material 3
- OkHttp + Jsoup + kotlinx.serialization
- Room 本地快照数据库
- DataStore / 本地安全存储保存 Cookie
- WorkManager 每 2 小时周期同步
- 自定义 BJTU DNS fallback
- FileProvider 打开课程资源下载文件
- 作业文字内容与附件提交

## 测试

后端测试：

```powershell
Set-Location d:\code\project\bjtu_web
.\.venv\Scripts\python.exe -m pytest backend\tests
```

前端构建检查：

```powershell
Set-Location d:\code\project\bjtu_web\frontend
npm run build
```

Android 单元测试：

```powershell
Set-Location d:\code\project\bjtu_web\android
.\gradlew.bat test
```

## 常用后端接口

- `GET /health`：健康检查
- `GET /api/session/status`：会话状态
- `GET /api/session/captcha`：获取统一认证验证码
- `POST /api/session/login-inline`：网页内登录
- `POST /api/session/open-browser`：启动备用登录浏览器
- `POST /api/sync/run`：立即同步
- `GET /api/sync/status`：同步状态
- `GET /api/modules/profile`
- `GET /api/modules/academic-progress`
- `GET /api/modules/history-scores`
- `GET /api/modules/timetable`
- `GET /api/modules/exams`
- `GET /api/modules/scores`
- `GET /api/modules/calendar`
- `GET /api/modules/homework`
- `POST /api/modules/homework/{homework_id}/submit`
- `GET /api/modules/course-resources`
- `GET /api/modules/course-resources/download/{rp_id}`
- `GET /api/modules/empty-rooms`

## 配置项

后端可通过环境变量覆盖默认路径和请求参数：

| 变量 | 默认值 |
| --- | --- |
| `BJTU_MIS_ROOT_DIR` | 仓库根目录 |
| `BJTU_MIS_CAPTURES_DIR` | `captures/` |
| `BJTU_MIS_PROFILE_DIR` | `captures/profile/default` |
| `BJTU_MIS_RUNTIME_DIR` | `runtime/` |
| `BJTU_MIS_DB_PATH` | `runtime/bjtu_mis.sqlite3` |
| `BJTU_MIS_SESSION_STATE_PATH` | `runtime/session_state.json` |
| `BJTU_MIS_FRONTEND_DIST` | `frontend/dist` |
| `BJTU_MIS_PYTHON` | 当前 Python 解释器 |
| `BJTU_MIS_HOME_URL` | `https://mis.bjtu.edu.cn/home/` |
| `BJTU_MIS_USER_AGENT` | 内置桌面 Chrome UA |
| `BJTU_MIS_REQUEST_TIMEOUT` | `30` 秒 |

## 定时同步

Web 版可以用 Windows 任务计划程序定期执行：

```powershell
Set-Location d:\code\project\bjtu_web\backend
d:\code\project\bjtu_web\.venv\Scripts\python.exe -m app.cli sync-once
```

建议先按每 2 小时一次配置。Android 版已经通过 WorkManager 按 2 小时间隔注册周期同步。

## 注意事项

- 本项目依赖学校系统页面结构和接口行为，若上游系统调整，解析器和 Provider 需要同步维护。
- `runtime/session_state.json`、`runtime/bjtu_mis.sqlite3`、`captures/profile/` 可能包含 Cookie 和个人数据，不要提交或共享。
- VE 课程平台存在明文 HTTP / IP 访问场景，Android 清单中已开启必要的 cleartext 配置。
- 同步失败时，部分模块会回退到已有快照或返回 `coverage = provisional` 的临时结果。
