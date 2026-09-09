# v1.4.2 发布验证记录

日期：2026-09-09。应用源码提交 `947cf078fe9e163ac25a0de1b7b0e5c6da00ad07`；CI 修复提交 `2d7f6f989b1d17ca9a32b43ff7d284366d38d2fa`（仅构建上下文、测试本地化和模拟器镜像）。

## 正式制品

- Android Studio 签名构建于北京时间 11:42 完成，`BUILD SUCCESSFUL`。
- 文件：`BJTU-MIS-v1.4.2.apk`，151,663,841 字节；包名 `cn.edu.bjtu.mis`，版本 `v1.4.2` / code 9，arm64-v8a，minSdk 26 / targetSdk 35。
- APK SHA-256：`6d01b66df07b187cc2474bbd89d9981de914e521cff37b1f0797f401fd27e839`。
- apksigner 验签通过；证书 SHA-256 `6808eb315b7619f375ed7a58abe24bd53125426203ceaf755d71290a46cf7af8` 与 v1.4.1 一致。
- R8 mapping 与实际 DEX 核验：管理页停止动作先调用 `PluginSessionKeepAlive.revoke`，包含本次审查修复。
- [GitHub Release](https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.4.2) 已公开；远端资产 digest 与上述 SHA-256 一致，实际下载请求返回 206。
- [百度网盘](https://pan.baidu.com/s/1KtVRietmqbKRy4b7VSh6Hg?pwd=0721)，提取码 `0721`。客户端上传同一制品，未登录浏览器验证文件名、144.6M 大小及“永久有效”；未从网盘重新下载进行独立摘要复核。

## 验证结果与限制

- Android `test assembleDebug :app:assembleDebugAndroidTest` 通过；正式签名包在华为 API 31 真机从 v1.4.1 覆盖升级成功，启动成功，首页中文与同步状态正常，未发现本应用崩溃记录。之后 ADB 离线，未继续扩展真机操作场景。
- Open WebUI Vitest：11 个文件、96 个测试通过；未以存在既有基线诊断的 Svelte 类型检查作为本次通过项。
- 插件工具链生成、类型检查、测试、模板与确定性打包通过。首次本地浏览器测试超时，独立重跑通过，远端 CI 同样通过。
- 平台类型、单元及 e2e 检查通过；本地 PostgreSQL 测试因缺少数据库跳过，远端真实 PostgreSQL migration/constraint 测试及生产镜像检查通过。
- [阻塞 CI](https://github.com/wan300/bjtu_mis_Android/actions/runs/34308308275) 全部作业通过。API 26 的 7 项高级 WebView 场景因镜像组件不支持安全传输而跳过；其功能门控验证通过。API 35 无跳过。未将跳过项或未覆盖的真实系统操作视为已验证。
- Manifest 模板/示例 lint 通过，docs/web schema 字节一致，相关源码和静态文本 UTF-8 检查无替换字符。
- [Issue #1](https://github.com/wan300/bjtu_mis_Android/issues/1)：依据模型选择器/菜单层级、删除确认交互与 Coremail 内嵌图片修复及回归测试，已说明并关闭为 completed。

## 官网部署与回滚

- 更新 `web/index.html`、`web/assets/js/site.js` 中的正式 APK、版本日期、大小、永久分享和简要更新说明；保留 GitHub API 不可用时的内置制品快照。
- 本地浏览器检查中文、布局与下载信息正常，常规加载无控制台错误；JavaScript 语法检查和 `git diff --check` 通过。
- 通过 `ssh my-server` 上传 60 个静态文件，排除 `web/platform/`，逐文件 SHA-256 校验通过。首次清单校验因 Windows CRLF 行尾失败，改为 LF 后全部通过，失败期间未切换线上目录。
- 当前目录：`/var/www/bjtu.cc/releases/20260909-120000/web`；`current` 已原子切换，`nginx -t` 和 reload 成功。
- 公网裸域名、www、脚本和开发者文档返回 200；HTTP 返回 308，`/platform/.env` 返回 404。线上 HTML/JS 摘要与本地一致，两个网盘链接正确，无中文替换字符。
- 回滚点：`/var/www/bjtu.cc/releases/20260812-020437/web`。如需回滚，先验证旧目录，创建临时软链接指向该目录，再通过 `mv -Tf` 原子替换 `current`；执行 `nginx -t`、reload 并复核公网。旧目录保留，数据库和插件服务未改动。

APK、签名密钥、mapping、设备截图与临时校验文件均未加入 Git；原有 `.codex/` 和 `tmp_media3_ui/` 未修改。
