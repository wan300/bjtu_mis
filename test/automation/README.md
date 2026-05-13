# 邮件系统接口录制流程

这里存放只用于人工采集和离线分析的自动化脚本。脚本会复用后端配置中的 Playwright 持久化浏览器 profile，但不再由 `backend/app/cli.py` 暴露，避免采集工具混入正常后端功能。

## 1. 启动录制

从仓库根目录执行：

```powershell
.\.venv\Scripts\python.exe test\automation\cli.py record-mail
```

By default, the recorder keeps the currently open non-blank browser page and only attaches network logging.

如果已经知道邮件入口 URL，可以指定起始页：

```powershell
.\.venv\Scripts\python.exe test\automation\cli.py record-mail --start-url "https://mis.bjtu.edu.cn/home/"
```

When `--start-url` is provided, the URL is opened in a new tab so the existing page is not overwritten.

录制文件会写入：

```text
captures/logs/mail_YYYYMMDD_HHMMSS/
├── manifest.json
└── network.jsonl
```

`captures/` 已在 `.gitignore` 中，不要把这些日志提交或分享出去。

## 2. 操作时打标记

录制命令运行后，终端支持输入阶段标记。建议每一步操作前输入一次：

```text
MARK 进入邮箱首页
MARK 收件箱列表
MARK 查看邮件详情
MARK 发送普通邮件
MARK 发送带附件邮件
MARK 删除邮件
MARK 拉黑发件人
MARK 解除拉黑
```

标记会写入 `network.jsonl`，后续分析会按标记分段，方便判断哪个接口对应哪个动作。

## 3. 建议录制的完整功能路径

为了支撑完整邮件系统开发，至少录一次这些成功路径：

- 登录后进入邮件系统入口。
- 收件箱列表：分页、刷新、搜索、未读筛选。
- 邮件详情：打开正文、查看发件人/收件人/时间、附件列表、附件下载。
- 写信/发信：收件人搜索或选择、填写主题正文、发送给测试账号或自己。
- 附件：上传附件、删除已选附件、发送带附件邮件。
- 删除：删除单封、批量删除；如果有回收站，录制恢复和彻底删除。
- 拉黑：添加发件人到黑名单、查看黑名单、移除黑名单。

最好也录一次失败路径，例如收件人为空、附件过大、删除不存在邮件等。失败返回对前端提示和后端异常处理很有价值。

## 4. 结束录制

关闭浏览器，或在终端输入：

```text
q
```

## 5. 分析接口

分析最新一次邮件录制：

```powershell
.\.venv\Scripts\python.exe test\automation\cli.py analyze-mail-capture
```

分析指定录制目录：

```powershell
.\.venv\Scripts\python.exe test\automation\cli.py analyze-mail-capture "d:\code\project\bjtu_web\captures\logs\mail_YYYYMMDD_HHMMSS"
```

输出文件：

```text
captures/logs/mail_YYYYMMDD_HHMMSS/
├── mail_interface_analysis.json
└── mail_interface_analysis.md
```

重点看 `mail_interface_analysis.md`：

- “功能覆盖”确认收信、详情、发信、附件、删除、拉黑、联系人等是否都有候选接口。
- “操作标记分段”把你的手动操作和请求关联起来。
- “候选接口”列出 URL、方法、请求字段、响应结构和样本请求 ID。

如果某个功能显示“未观察”，说明还需要重新录制该操作路径。
