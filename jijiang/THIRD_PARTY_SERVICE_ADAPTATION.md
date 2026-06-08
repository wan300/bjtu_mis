# BJTU MIS 第三方服务适配说明

本目录已经整理为 BJTU MIS Android 第三方服务导入包，只保留前端源码、第三方服务协议文件和已构建的静态资源。后端、支付服务、数据库、模型、Docker/Nginx、微信小程序发布配置和运行态数据均不属于 Android 端嵌入范围，已经从本副本移除。

## 保留内容

- `bjtu-service.json`：第三方服务 manifest，声明入口、图标、权限和允许外联域名。
- `dist/`：提交给 BJTU MIS App 导入器识别的 H5 静态包，入口为 `dist/index.html`。
- `12group-frontend/`：技匠前端源码与 H5 构建配置。
- `12group-frontend/scripts/prepare-bjtu-service.cjs`：把 H5 构建产物同步到仓库根 `dist/`。
- `12group-frontend/src/api/bjtu-service.ts`：封装 `window.BjtuService.invoke(method, params)`。

## 构建

```powershell
Set-Location jijiang\12group-frontend
npm ci
npm run build:bjtu-service
```

`build:bjtu-service` 会执行 H5 构建，并把 `12group-frontend/dist/build/h5` 同步到仓库根 `dist/`。发布为第三方服务仓库时，根目录必须同时包含 `bjtu-service.json` 和 `dist/`。

## 登录与退出边界

嵌入 BJTU MIS Android 后，技匠前端不再提供微信登录、MIS 登录或退出登录。启动时会通过 BJTU App Bridge 调用 `identity.get_profile`，把用户身份映射为本地前端登录态。

第三方服务内只提供“退出第三方服务”，调用 `app.close_service` 返回 BJTU MIS 服务列表，不清除 BJTU App 登录态，也不退出校园统一身份认证。

## 权限与外联

当前 manifest 只申请：

- `identity.profile.read`：读取 BJTU App 授权后的用户身份信息。

`app.close_service` 不需要权限。前端运行中如果访问 `bjtu-service.json.allowed_origins` 之外的 HTTP/HTTPS origin，会被 BJTU MIS WebView 拦截。当前保留的外联 origin 用于技匠 API、支付跳转/回调和公共资源加载：

- `http://47.95.238.140:8080`
- `https://api.jijiang.com`
- `https://pay.jijiang.com`
- `https://api.xunhupay.com`
- `https://jijiang-1325125602.cos.ap-beijing.myqcloud.com`

如果生产域名变化，需要同步修改：

- `bjtu-service.json.allowed_origins`
- `12group-frontend/src/utils/env.ts` 中 `bjtuServiceDefaultApiBase`，或在本地构建环境中覆盖 `VITE_API_BASE`

不要提交 `.env.*` 文件；本仓库只提交 `.env.example` 作为本地开发模板。

当前 `node tools/third-party-service-lint.cjs jijiang` 可通过，但会提示若干 `WARN`：

- `https://cdn.dcloud.net.cn` 来自 uni-app H5 运行时 CSS 的阴影图片引用，当前不作为受信任执行来源加入 `allowed_origins`。
- `https://vuejs.org` 来自 Vue 运行时错误说明字符串，不是正常业务网络请求。
- `https://mock-cos` 是开发占位上传 URL，前端代码会识别后走本地开发分支，不作为生产外联来源。

如果后续这些字符串变成真实运行时资源或接口调用，需要重新评估并同步 `bjtu-service.json.allowed_origins`，或改成本地打包资源。

## 不包含内容

- 不包含 jijiang 后端、管理后台、数据库脚本、支付服务、模型文件或服务器部署配置。
- 不在 Android 设备端执行 npm/build。
- 不暴露 BJTU Cookie、token、原始 HTTP 客户端或校园账号密码。
- 不支持未写入 manifest 的运行时权限申请或未声明外联域名。
