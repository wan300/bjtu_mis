# BJTU MIS v1

本项目已经按 `采集资产隔离 + FastAPI 后端 + Vue3 前端` 的结构落地。

## 目录

```text
captures/
  recorder/
  logs/
  profile/
backend/
frontend/
runtime/
```

`captures/` 默认不进入版本控制，里面保留录制脚本、原始日志和浏览器 profile。

## 安装

```powershell
d:/code/project/bjtu_web/.venv/Scripts/python.exe -m pip install -r d:/code/project/bjtu_web/requirements.txt
cd d:/code/project/bjtu_web/frontend
npm install
npm run build
```

## 启动后端

```powershell
cd d:/code/project/bjtu_web/backend
d:/code/project/bjtu_web/.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

如果你在 PowerShell 中执行，直接使用下面这一行更稳：

```powershell
Set-Location d:/code/project/bjtu_web/backend
d:/code/project/bjtu_web/.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

## 启动前端开发环境

```powershell
cd d:/code/project/bjtu_web/frontend
npm run dev
```

Vite 已经把 `/api` 代理到 `http://127.0.0.1:8000`。

## 登录与同步

1. 打开前端页面。
2. 点击“打开登录浏览器”。
3. 在新打开的 Chromium 窗口里先完成 MIS 登录（`https://mis.bjtu.edu.cn/home/`）。
4. 登录后通过 MIS 内跳转页（`/module/module/10/`）自动进入 AA 教学支撑平台。
5. 确认可正常打开教务页面（如“本学期课表”）后，回到控制台点击“立即同步”。

后端会把会话导出到 `runtime/session_state.json`，之后同步默认使用 `httpx + cookies`。

## CLI

手动打开登录浏览器：

```powershell
cd d:/code/project/bjtu_web/backend
d:/code/project/bjtu_web/.venv/Scripts/python.exe -m app.cli open-browser
```

手动执行一次同步：

```powershell
cd d:/code/project/bjtu_web/backend
d:/code/project/bjtu_web/.venv/Scripts/python.exe -m app.cli sync-once
```

## Windows 定时任务

建议让任务计划程序执行下面这条命令：

```powershell
Set-Location d:/code/project/bjtu_web/backend
d:/code/project/bjtu_web/.venv/Scripts/python.exe -m app.cli sync-once
```

触发频率可以先按每 2 小时一次配置。
