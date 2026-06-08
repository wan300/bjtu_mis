# 第三方插件开发者文档

BJTU MIS Android 第三方插件是安装在应用私有目录中的已构建 H5/SPA Web 服务。插件通过公开 GitHub 仓库导入，通过 `bjtu-service.json` 声明元数据、权限和允许执行/联网的 HTTP/HTTPS origin，并在应用内 WebView 的受控沙箱 origin 中调用受控的 `window.BjtuService` API。

本文档中的“第三方插件”和应用代码中的“第三方服务”指同一能力。

## 1. 平台概览

插件不会直接接触校园账号、密码、Cookie、token 或原始 HTTP 客户端。所有校园数据都由 BJTU MIS Android 的受控 Repository 返回，插件只通过桥接 API 请求已经授权的能力。

当前支持：

- 仓库根目录包含 `bjtu-service.json` 和 `dist/`。
- 通过 `https://github.com/{owner}/{repo}` 公开仓库导入。
- 导入前预检 manifest、权限、允许来源、包内容 digest 和文件数量。
- 安装时固定默认分支的 `commitSha`，并按该 commit 下载 zip 包。
- 安装后记录 `dist/` 内容的 SHA-256 digest。
- 安装后的 `dist/` 会通过每服务隔离的虚拟 HTTPS origin 运行，支持 `/assets/...` 绝对路径资源和 SPA history fallback。
- 通过 `window.BjtuService.invoke` 调用受控 API。
- 通过 `app.http_request` 向 `allowed_origins` 中声明的远端 API 发起受控 HTTP/HTTPS 请求。

当前不支持：

- 私有仓库、release asset、仓库子目录安装。
- 应用侧自动源码构建或静默更新。
- Android 端运行 Node、Vite dev server、`npm install` 或开发服务器。
- 原生插件、后台常驻任务、直接读取 App 内部文件。
- 绕过用户确认的作业提交或邮件发送。

## 2. 仓库结构

```text
your-repo/
  bjtu-service.json
  dist/
    index.html
    icon.svg
    assets/
      app.css
      app.js
```

v1 只支持 `https://github.com/{owner}/{repo}` 形式的公开仓库链接。应用读取默认分支，下载 zip 包，并固定安装时的 `commitSha`。应用只会安装 `dist/` 内的静态文件；运行时由 App 映射到每服务隔离的虚拟 HTTPS origin，不直接把 `file://` 作为页面 origin。

再次导入同一 `id` 的服务会作为更新处理。更新后原有授权会被清空，服务进入需要重新确认状态；用户重新同意必须权限后才会启用。

仓库内提供开发起点：

- 基础模板：`third-party-plugins/templates/basic/`
- 离线示例：`third-party-plugins/examples/profile-timetable/`
- 远程来源语义示例：`third-party-plugins/examples/remote-origin-demo/`

发布前建议运行 lint：

```powershell
node tools/third-party-service-lint.cjs third-party-plugins/examples/profile-timetable
```

lint 的 `ERROR` 必须修复；`WARN` 不会阻止通过，但必须人工判断。若 warning 指向真实运行时会加载的远端页面、iframe、接口或资源，要么加入 `allowed_origins`，要么改为本地打包或移除引用；若只是框架错误说明链接、开发占位字符串或不会触发的文本常量，可以保留，但发布说明中应记录原因。

## 3. Manifest

`bjtu-service.json` 使用 snake_case。所有字段都必须存在，权限可为空数组。

```json
{
  "schema_version": 1,
  "id": "bjtu.demo",
  "name": "Demo Service",
  "description": "A demo BJTU MIS service.",
  "version": "1.0.0",
  "entrypoint": "index.html",
  "icon": "icon.svg",
  "author": "Your Name",
  "permissions": {
    "required": ["identity.profile.read"],
    "optional": ["academic.timetable.read"]
  },
  "allowed_origins": ["https://api.example.com"]
}
```

规则：

- `schema_version` 当前必须为 `1`。
- `id` 长度 3-64，以小写字母开头，只能包含小写字母、数字、点、下划线和短横线。
- `name`、`description`、`version`、`author` 不能为空。
- `entrypoint` 和 `icon` 必须是 `dist/` 内相对路径，不能包含 `..`、反斜杠、URL、盘符或绝对路径。
- `permissions.required` 是必须授权的权限。用户未全部同意时服务不能启用。
- `permissions.optional` 是可选权限。用户拒绝后服务仍可打开，但对应接口返回 `permission_denied`。
- `required` 和 `optional` 不能声明重复权限。
- `allowed_origins` 只能填写 HTTP/HTTPS origin，例如 `https://api.example.com` 或 `http://127.0.0.1:8080`，不能带路径、查询参数、片段或用户名。
- `allowed_origins` 是受信任的执行和联网来源。来自这些 origin 的页面或 iframe 可作为插件代码运行，并可调用该插件已授权的接口；`app.http_request` 的 `url` origin 也必须在该列表中。

Schema 文件：

- 仓库文档：`docs/third-party-service-manifest.schema.json`
- 网页下载：`web/assets/schemas/third-party-service-manifest.schema.json`

## 4. 权限

插件必须先在 manifest 中声明权限，再调用对应接口。授权页展示中文说明，不展示裸权限 ID。

| 权限 ID | 能力 |
| --- | --- |
| `identity.profile.read` | 读取姓名、学号、学院、专业、邮箱等个人资料。 |
| `academic.timetable.read` | 读取当前课表和用户自定义课程。 |
| `academic.user_courses.write` | 新增、修改或删除用户手动创建的课程。 |
| `academic.scores.read` | 读取当前学期或指定学期主修成绩。 |
| `academic.history_scores.read` | 读取历年成绩。 |
| `academic.exams.read` | 读取考试时间、地点和考试方式。 |
| `academic.calendar.read` | 读取校历、周次和学期安排。 |
| `academic.progress.read` | 读取培养方案完成情况和学分进度。 |
| `academic.homework.read` | 读取课程作业、截止时间和提交状态。 |
| `academic.homework.submit` | 提交作业内容。每次提交前仍会要求用户确认。 |
| `academic.course_resources.read` | 读取课程资料列表和课程资源元数据。 |
| `mail.folders.read` | 读取邮箱文件夹和未读数量。 |
| `mail.messages.read` | 读取邮件摘要列表。 |
| `mail.message_detail.read` | 读取单封邮件正文、收发件人和附件信息。 |
| `mail.send` | 发送邮件。每次发送前仍会要求用户确认。 |

## 5. JavaScript API

应用内 WebView 会在本服务沙箱 origin 和 `allowed_origins` 页面中注入：

```ts
window.BjtuService.invoke(
  method: string,
  params?: Record<string, unknown>
): Promise<{
  ok: boolean;
  data?: unknown;
  error?: { code: string; message: string };
}>;
```

调用约定：

- `invoke` 返回 Promise。调用方必须检查 `ok`，不要假设接口一定成功。
- 业务读取接口返回 `{ ok: true, data: ModuleEnvelope<T> }`。真正的业务数据在 `response.data.data`，不是 `response.data` 顶层。
- 除 `app.http_request` 的兼容字段 `statusCode` 外，业务接口的请求参数和返回字段都使用 snake_case。
- Kotlin 侧 JSON 配置为 `ignoreUnknownKeys = true`、`explicitNulls = false`、`JsonNamingStrategy.SnakeCase`、`encodeDefaults = true`。可空字段没有值时通常会被省略，而不是返回 `null`。
- 字符串参数会 trim，空字符串按缺失处理。整数和布尔参数必须传 JSON number / boolean，`to`、`cc`、`bcc` 必须传字符串数组。
- 未声明或未授权的权限不会触发真实校园接口访问；高风险接口会先弹出用户确认。

示例：

```js
const profileResult = await window.BjtuService.invoke('identity.get_profile');
if (!profileResult.ok) {
  throw new Error(profileResult.error.message);
}
const profile = profileResult.data.data;
console.log(profile.name, profile.student_id);

const timetableResult = await window.BjtuService.invoke('academic.get_timetable');
const timetable = timetableResult.ok ? timetableResult.data.data.entries : [];

const saved = await window.BjtuService.invoke('academic.save_user_course', {
  course_name: '自习',
  weekday: '周一',
  weekday_index: 0,
  period: '第1-2节',
  period_number: 1,
  start_week: 1,
  end_week: 16,
  duration_type: 'LongTerm',
  location_text: '图书馆'
});

if (!saved.ok) {
  console.warn(saved.error.code, saved.error.message);
}

const remote = await window.BjtuService.invoke('app.http_request', {
  url: 'https://api.example.com/v1/items',
  method: 'GET',
  headers: {
    Accept: 'application/json'
  }
});

await window.BjtuService.invoke('app.close_service');
```

## 6. 方法清单

| 方法 | 权限 | 参数 | 成功时 `data` |
| --- | --- | --- | --- |
| `identity.get_profile` | `identity.profile.read` | 无。 | `ModuleEnvelope<StudentProfileData>` |
| `academic.get_timetable` | `academic.timetable.read` | 无。 | `ModuleEnvelope<TimetableData>` |
| `academic.save_user_course` | `academic.user_courses.write` | `course_name` string、`weekday` string、`weekday_index` int、`period` string、`period_number` int、`start_week` int、`end_week` int 必填；可选 `id` number/string、`time_range` string、`weeks_text` string、`duration_type` `"Temporary" \| "LongTerm"`、`teacher` string、`location_text` string、`remark` string、`color_index` int。 | `{ id: number }` |
| `academic.delete_user_course` | `academic.user_courses.write` | `id` number/string 必填。 | `{ deleted: true }` |
| `academic.get_scores` | `academic.scores.read` | 可选 `term` string、`ctype` string。 | `ModuleEnvelope<ScoreData>` |
| `academic.get_history_scores` | `academic.history_scores.read` | 可选 `term` string。 | `ModuleEnvelope<ScoreData>` |
| `academic.get_exams` | `academic.exams.read` | 可选 `term` string。 | `ModuleEnvelope<ExamData>` |
| `academic.get_calendar` | `academic.calendar.read` | 可选 `month` string。 | `ModuleEnvelope<CalendarData>` |
| `academic.get_academic_progress` | `academic.progress.read` | 无。 | `ModuleEnvelope<AcademicProgressData>` |
| `academic.get_homework` | `academic.homework.read` | 可选 `status` string，默认 `all`。内置过滤值：`all`、`open`、`done`、`expired`、`expired_can_submit`、`expired_closed`；其他值会与上游原始 `item.status` 精确匹配。 | `ModuleEnvelope<HomeworkData>` |
| `academic.submit_homework` | `academic.homework.submit` | `homework_id` int、`course_id` int 必填；可选 `content` string。当前不接受第三方文件列表。 | `HomeworkSubmitResponse` |
| `academic.get_course_resources` | `academic.course_resources.read` | 可选 `term` string、`course_id` string、`folder_id` string，默认 `0`、`search` string、`category_key` string。 | `ModuleEnvelope<CourseResourcesData>` |
| `mail.list_folders` | `mail.folders.read` | 无。 | `ModuleEnvelope<MailFoldersData>` |
| `mail.list_messages` | `mail.messages.read` | 可选 `folder_id` string，默认 `1`、`start` int，默认 `0`、`limit` int，默认 `20`，会限制在 1-100。 | `ModuleEnvelope<MailMessagesData>` |
| `mail.get_message` | `mail.message_detail.read` | `message_id` string 必填；可选 `mboxa` string。 | `ModuleEnvelope<MailMessageDetail>` |
| `mail.send` | `mail.send` | 可选 `compose_id` string、`account` string、`to` string[]、`cc` string[]、`bcc` string[]、`subject` string、`content` string、`body` string 兼容别名、`html_content` string、`is_html` boolean、`save_sent_copy` boolean，默认 `true`、`request_read_receipt` boolean，默认 `false`、`schedule_date` string、`show_one_rcpt` boolean，默认 `false`、`forbid_download` boolean，默认 `false`、`mboxa` string。当前不接受第三方附件列表。 | `MailComposeResponse` |
| `app.http_request` | 无 | `url` string 必填；可选 `method` `"GET" \| "POST" \| "PUT" \| "DELETE"`，默认 `GET`、`data` JSON、`headers` object。 | `HttpBridgeResponse` |
| `app.close_service` | 无 | 无。 | `{}`，随后关闭当前第三方服务并返回服务列表。 |

参数补充：

- `academic.save_user_course` 会把 `weekday_index` 限制在 0-6、`period_number` 至少为 1、`start_week` / `end_week` 至少为 1 并自动排序、`color_index` 限制在 0-7。`duration_type` 缺失或非法时按 `Temporary` 处理。
- `academic.submit_homework` 和 `mail.send` 即使已经授权，每次调用前仍会要求用户确认。用户取消时返回 `user_denied`。
- `mail.send` 的 `to`、`cc`、`bcc` 是数组；传逗号分隔字符串不会被拆分。
- `app.http_request` 的 `headers` 会过滤 `host`、`connection`、`content-length`、`transfer-encoding`、`accept-encoding`、`cookie`、`origin` 等敏感或运行时控制头。

受控 HTTP 请求：

- `app.http_request` 不需要权限 ID，但调用页面必须位于当前服务沙箱 origin 或 `allowed_origins` 中的 origin。
- `url` 只能是 HTTP/HTTPS URL，且其 origin 必须已经写入 `allowed_origins`。
- 非 `GET` 请求默认以 `application/json;charset=UTF-8` 发送 `data`；响应正文会优先解析为 JSON，解析失败则作为字符串返回。
- 响应超过 5 MiB 会返回 `bridge_failed`。
- 成功响应形状为 `{ ok: true, data: { statusCode, data, header } }`。这里的 `statusCode` 保持 WebView/uni-app 兼容命名，不转换为 snake_case。

### 返回数据模型

下面的类型是运行时 JSON 形状，字段已按 snake_case 展示。可空字段在没有值时可能被省略。

```ts
type CoverageLevel = 'verified' | 'provisional';

type ModuleEnvelope<T> = {
  module: string;
  synced_at?: string;
  source_system: string;
  coverage: CoverageLevel;
  source_params: Record<string, unknown>;
  data: T;
};

type TermOption = {
  value: string;
  label: string;
  selected: boolean;
};

type ProfileField = { label: string; value: string };
type ProfileSection = { title: string; fields: ProfileField[] };

type StudentProfileData = {
  name?: string;
  student_id?: string;
  account?: string;
  gender?: string;
  birthday?: string;
  name_pinyin?: string;
  english_name?: string;
  ethnicity?: string;
  political_status?: string;
  nationality?: string;
  is_international_student?: string;
  college?: string;
  major?: string;
  class_name?: string;
  grade?: string;
  education_level?: string;
  has_student_status?: string;
  student_status?: string;
  student_category?: string;
  change_status?: string;
  cultivation_method?: string;
  is_auditor?: string;
  study_language?: string;
  campus?: string;
  phone?: string;
  email?: string;
  avatar_url?: string;
  fields: ProfileField[];
  sections: ProfileSection[];
};

type CourseEntry = {
  weekday: string;
  period: string;
  time_range?: string;
  course_code: string;
  section?: string;
  course_name: string;
  teacher?: string;
  weeks?: string;
  campus?: string;
  building?: string;
  room?: string;
  location_text?: string;
  local_id?: number;
  remark?: string;
  color_index?: number;
  is_user_created: boolean;
};

type TimetableData = {
  days: string[];
  periods: string[];
  entries: CourseEntry[];
  current_term?: string;
  available_terms: TermOption[];
};

type ExamItem = {
  term?: string;
  course_name: string;
  schedule?: string;
  exam_mode?: string;
  remark?: string;
  registration?: string;
  status?: string;
};

type ExamData = {
  current_term?: string;
  available_terms: TermOption[];
  items: ExamItem[];
};

type ScoreItem = {
  term?: string;
  course_name: string;
  credit?: string;
  score?: string;
  bonus_score?: string;
  teacher?: string;
  detail?: string;
  detail_path?: string;
};

type ScoreData = {
  current_term?: string;
  available_terms: TermOption[];
  items: ScoreItem[];
};

type CalendarItem = {
  date: string;
  week?: string;
  note?: string;
};

type CalendarData = {
  month: string;
  current_week?: string;
  current_term?: string;
  available_terms: TermOption[];
  items: CalendarItem[];
};

type CreditSummary = {
  course_count: number;
  passed_course_count: number;
  failed_course_count: number;
  attempted_credits: number;
  passed_credits: number;
  failed_credits: number;
  target_credits?: number;
  completion_rate: number;
};

type CreditBucket = {
  name: string;
  required_credits?: number;
  earned_credits: number;
  pending_credits?: number;
  completion_rate?: number;
  parent?: string;
};

type AcademicProgressCourse = {
  term?: string;
  course_code?: string;
  course_name: string;
  credit?: number;
  exam_date?: string;
  score?: string;
  status: string;
  detail?: string;
  group_info?: string;
  source: string;
};

type AcademicProgressData = {
  current_term?: string;
  summary: CreditSummary;
  buckets: CreditBucket[];
  merged_buckets: CreditBucket[];
  detail_buckets: CreditBucket[];
  courses: AcademicProgressCourse[];
  replace_courses: Record<string, unknown>[];
  fields: ProfileField[];
};

type CourseSummary = {
  course_id: number;
  course_name: string;
  course_code?: string;
  teacher_name?: string;
  teacher_id?: string;
  term?: string;
  xq_code?: string;
  xkh_id?: string;
};

type HomeworkAttachment = {
  attachment_id: string;
  filename: string;
  url?: string;
  size?: string;
};

type HomeworkItem = {
  homework_id?: number;
  course: string;
  course_id: number;
  course_code?: string;
  title: string;
  content_excerpt?: string;
  requirement_text?: string;
  opened_at?: string;
  due_at?: string;
  submitted_at?: string;
  status: string;
  sub_type: number;
  submission_status?: string;
  can_submit: boolean;
  can_submit_explicit: boolean;
  content_type: number;
  is_group: boolean;
  return_num: number;
  attachments: HomeworkAttachment[];
};

type HomeworkData = {
  current_term?: string;
  courses: CourseSummary[];
  items: HomeworkItem[];
};

type HomeworkSubmitResponse = {
  status: string;
  message?: string;
  homework_id: number;
  submitted_at?: string;
  upstream: Record<string, unknown>;
};

type CourseResourceCategory = { key: string; label: string };

type CourseResourceFolder = {
  folder_id: string;
  name: string;
  parent_id?: string;
  category_key: string;
  category_label: string;
};

type CourseResourceItem = {
  resource_id: string;
  rp_id: string;
  res_id?: string;
  name: string;
  extension?: string;
  size?: string;
  play_url?: string;
  res_url?: string;
  uploaded_at?: string;
  teacher_name?: string;
  download_count?: number;
  click_count?: number;
  can_download: boolean;
  folder_id: string;
  category_key: string;
  category_label: string;
};

type CourseResourcesData = {
  current_term?: string;
  courses: CourseSummary[];
  selected_course_id?: number;
  folder_id: string;
  categories: CourseResourceCategory[];
  selected_category_key: string;
  tree: CourseResourceFolder[];
  folders: CourseResourceFolder[];
  resources: CourseResourceItem[];
};

type MailFolder = {
  folder_id: string;
  name: string;
  message_count: number;
  unread_count: number;
  message_size: number;
  unread_size: number;
  system: boolean;
};

type MailMessageSummary = {
  message_id: string;
  folder_id: string;
  subject: string;
  from_text: string;
  to_text: string;
  sender?: string;
  sent_at?: string;
  received_at?: string;
  modified_at?: string;
  size: number;
  read: boolean;
  attached: boolean;
  priority?: number;
  summary?: string;
};

type MailAttachment = {
  attachment_id: string;
  filename: string;
  content_type?: string;
  size: number;
  part: string;
};

type MailMessageDetail = MailMessageSummary & {
  from_list: string[];
  to_list: string[];
  cc_list: string[];
  bcc_list: string[];
  html_content: string;
  headers: Record<string, unknown>;
  attachments: MailAttachment[];
};

type MailFoldersData = { folders: MailFolder[] };

type MailMessagesData = {
  folder_id: string;
  start: number;
  limit: number;
  total: number;
  messages: MailMessageSummary[];
};

type MailComposeResponse = {
  status: string;
  compose_id: string;
  draft_id?: string;
  sent_message_id?: string;
  upstream: Record<string, unknown>;
};

type HttpBridgeResponse = {
  statusCode: number;
  data: unknown;
  header: Record<string, string>;
};
```

## 7. 错误码

| 错误码 | 含义 |
| --- | --- |
| `service_not_enabled` | 服务尚未完成授权或更新后需要重新确认。 |
| `unknown_method` | 接口不存在或方法名拼写错误。 |
| `permission_denied` | 服务未获得该接口对应权限。 |
| `user_denied` | 用户拒绝高风险操作确认。 |
| `api_failed` | 底层 BJTU MIS、教学服务或邮箱接口失败，或业务接口参数缺失、类型不正确。 |
| `bridge_failed` | WebView 桥接请求格式错误、当前页面不在允许执行来源内、HTTP 桥接校验失败、HTTP 响应超过 5 MiB 或运行时异常。 |

## 8. 安全限制

- WebView 通过每服务隔离的虚拟 HTTPS origin 加载已安装插件目录内的资源，例如 `https://<service-sandbox-id>.third-party.bjtu-mis.local/`。该 origin 只映射当前服务安装目录，并支持缺失页面路径回落到入口文件以适配 SPA history 路由。
- WebView 允许加载 manifest 中声明的 HTTP/HTTPS origin；这些 origin 是受信任执行来源，不只是网络请求白名单。
- `app.http_request` 只能请求 manifest 中声明的 HTTP/HTTPS origin，不能绕过 `allowed_origins` 访问其他站点。
- 其他未声明 HTTP/HTTPS origin、content URL 和未声明本地路径会被拦截。
- 禁止通过 manifest 声明带路径、查询参数、片段、用户名或非 HTTP/HTTPS 协议的外联地址。
- 下载包最大 25 MiB，解压后最大 50 MiB，最多 1000 个文件。
- Zip 包和 manifest 路径不能包含路径穿越、绝对路径或反斜杠。
- 插件更新后需要重新确认权限，不会静默继承旧版本授权。
- 导入确认页会展示 commitSha、内容 digest、权限和允许执行/联网来源。

## 9. 发布检查清单

- 仓库为公开 GitHub 仓库，导入链接只使用仓库根地址。
- `bjtu-service.json` 位于仓库根目录，字段完整且通过 schema 校验。
- `dist/` 包含入口文件、图标和所有静态资源。
- 已运行 `node tools/third-party-service-lint.cjs <plugin-root>`。
- 仅声明实际需要的权限，优先把非关键能力放到 `optional`。
- 仔细确认 `allowed_origins`，不要声明不完全信任的第三方页面、iframe 来源或远端 API。
- 所有 API 调用都处理 `ok: false` 和常用错误码。
- 页面在窄屏下可用，表格、按钮、长文本和代码片段不会遮挡内容。
- 不要把真实校园账号、Cookie、token、邮箱内容、验证码或个人隐私写入代码或示例数据。
