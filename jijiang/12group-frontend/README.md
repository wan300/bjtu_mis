# 12group-frontend

技匠前端源码。当前副本只用于构建 BJTU MIS Android 第三方服务的 H5 静态包，不包含后端、服务器、数据库、Docker/Nginx 或微信小程序发布配置。

## 本地 H5 开发

```bash
npm ci
npm run dev:h5
```

本地开发时可复制 `.env.example` 为 `.env`。`VITE_API_BASE` 留空时请求走 Vite 代理，代理目标由 `VITE_API_PROXY_TARGET` 指定。
`build:bjtu-service` 在未显式设置 `VITE_API_BASE` 时会使用 `src/utils/env.ts` 中的 `bjtuServiceDefaultApiBase`，不要提交 `.env.*` 文件。

## 第三方服务构建

```bash
npm run build:bjtu-service
```

该命令会构建 H5，并把产物同步到仓库根目录 `../dist/`。Android 导入器只读取仓库根目录的 `bjtu-service.json` 和 `dist/`。

## BJTU App Bridge

嵌入 BJTU MIS Android 后，前端通过 `src/api/bjtu-service.ts` 调用：

```ts
window.BjtuService.invoke(method, params)
```

当前使用的能力：

- `identity.get_profile`：读取用户身份并生成前端本地登录态；成功响应是 `ModuleEnvelope<StudentProfileData>`，用户字段位于 `response.data.data`，字段名为 snake_case。
- `app.close_service`：退出第三方服务并返回 BJTU MIS 服务列表。

前端不提供微信登录、MIS 登录或退出登录。退出入口只关闭第三方服务，不清除 BJTU App 的登录状态。
