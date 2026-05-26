(function () {
  const modules = [
    {
      slug: "timetable",
      key: "timetable",
      title: "课表",
      subtitle: "本周课程、上课时间与教室位置集中查看",
      category: "教学学习",
      icon: "icon_timetable.png",
      accent: "#18b7d8",
      summary:
        "课表模块把当前学期课程、节次、周次、地点与教师信息整理成移动端易读视图，并支持已同步数据的离线查看。",
      scenario:
        "适合每天出门前确认下一节课、切换周次查看调课安排，或在校园服务临时不稳定时继续查看已缓存课程。",
      abilities: [
        "展示本周课程、节次、时间范围、地点与任课教师",
        "按当前教学周突出正在生效的课程",
        "支持本地自定义课程，用于临时课程或个人安排",
        "在课程详情中关联匹配的作业与课程资源",
        "提供 Android 桌面小组件，快速查看今天与明天课程"
      ]
    },
    {
      slug: "homework",
      key: "homework",
      title: "作业",
      subtitle: "截止时间、提交状态、附件与 Agent 协助",
      category: "教学学习",
      icon: "icon_homework.png",
      accent: "#2aa876",
      summary:
        "作业模块集中展示课程平台返回的作业要求、截止时间、状态和附件，并能把作业上下文交给内置 Agent。",
      scenario:
        "适合跟进近期待完成事项、整理作业附件，或在资料较多时让 Agent 基于要求和附件生成分析草稿。",
      abilities: [
        "显示作业标题、课程、开放时间、截止时间和提交状态",
        "自动下载并整理作业附件，方便后续查阅",
        "把作业要求和附件导入 Agent 工作区",
        "支持附件预览、读取、解压和结果打包工作流",
        "保留已同步快照，离线时仍可查看关键作业信息"
      ]
    },
    {
      slug: "course-resources",
      key: "course_resources",
      title: "课程资源",
      subtitle: "课件、资料目录与文件下载",
      category: "教学学习",
      icon: "icon_course_resources.png",
      accent: "#7c58c2",
      summary:
        "课程资源模块聚合教学平台中的资料目录和资源文件，移动端可快速查看课件、文档和教师上传资料。",
      scenario:
        "适合课前下载课件、课后补看资料，或把课程文件与作业处理流程连起来。",
      abilities: [
        "按课程和目录展示资源文件",
        "显示资源类型、大小、上传时间、下载次数和教师信息",
        "支持可下载资源的下载入口",
        "支持文档预览流程，减少在多个系统间切换",
        "可从课表详情进入匹配课程资源"
      ]
    },
    {
      slug: "course-replay",
      key: "course_replay",
      title: "课程回放",
      subtitle: "课堂录像与回看入口",
      category: "教学学习",
      icon: "icon_course_replay.png",
      accent: "#ff8a00",
      summary:
        "课程回放模块面向需要复习课堂内容的场景，整理可用的课堂回看信息和播放入口。",
      scenario:
        "适合补看缺席课程、复盘重点章节，或在考试前按课程回到对应课堂记录。",
      abilities: [
        "展示可回看的课程记录",
        "保留课程名称、时间等上下文信息",
        "通过应用内网络能力访问回放资源",
        "与课程资源、课表形成学习资料链路",
        "适配移动端查看和检索"
      ]
    },
    {
      slug: "course-selection",
      key: "course_selection",
      title: "抢课",
      subtitle: "课程选择流程的移动端辅助",
      category: "教学学习",
      icon: "icon_course_selection.png",
      accent: "#0e9d9d",
      summary:
        "抢课模块围绕选课系统的移动端使用做了整理，帮助在手机上查看候选课程并执行课程选择相关操作。",
      scenario:
        "适合选课阶段临时查看课程、跟进可选状态，减少必须打开电脑处理的次数。",
      abilities: [
        "接入学校选课相关服务",
        "展示课程选择所需的关键信息",
        "保留当前会话与本地状态",
        "在移动端完成常见课程选择流程",
        "与课表模块衔接，便于确认选课结果"
      ]
    },
    {
      slug: "academic-progress",
      key: "academic_progress",
      title: "学业进度",
      subtitle: "培养方案完成情况与学分进展",
      category: "成绩考务",
      icon: "icon_academic_progress.png",
      accent: "#0b74f6",
      summary:
        "学业进度模块把培养方案、课程类别和完成状态整理成可浏览信息，帮助快速判断当前学习进展。",
      scenario:
        "适合选课前确认培养要求、毕业前核对学分完成情况，或定期检查缺口。",
      abilities: [
        "查看培养方案相关信息",
        "展示课程类别、完成状态和进度摘要",
        "与成绩模块形成学业完成情况参考",
        "使用本地快照降低重复登录和等待",
        "移动端按信息块组织长列表"
      ]
    },
    {
      slug: "history-scores",
      key: "history_scores",
      title: "历史成绩",
      subtitle: "历年课程成绩集中回看",
      category: "成绩考务",
      icon: "icon_history_scores.png",
      accent: "#6e62d6",
      summary:
        "历史成绩模块用于查看过往学期成绩记录，辅助复盘课程表现和准备材料。",
      scenario:
        "适合申请、评奖或自查时快速翻阅历年课程成绩，不必每次打开完整教务系统。",
      abilities: [
        "按历史学期查看成绩",
        "支持主要成绩字段的移动端呈现",
        "与主修成绩模块区分当前和历史记录",
        "保留最近同步结果用于离线查看",
        "减少长表格在手机上的阅读压力"
      ]
    },
    {
      slug: "scores",
      key: "scores",
      title: "主修成绩",
      subtitle: "本学期与主修课程成绩",
      category: "成绩考务",
      icon: "icon_scores.png",
      accent: "#e46b2d",
      summary:
        "主修成绩模块聚焦当前主修相关成绩信息，便于在手机上快速查看最新结果。",
      scenario:
        "适合成绩公布后快速核对课程、成绩和明细，不必在多个页面之间反复切换。",
      abilities: [
        "查看本学期主修成绩",
        "呈现课程名称、成绩和相关明细",
        "支持成绩详情表格的移动端阅读",
        "与历史成绩模块互补",
        "已同步内容可离线查阅"
      ]
    },
    {
      slug: "exams",
      key: "exams",
      title: "考务",
      subtitle: "考试安排、地点与状态提醒",
      category: "成绩考务",
      icon: "icon_exams.png",
      accent: "#d64b6b",
      summary:
        "考务模块集中展示考试课程、时间、地点、方式和状态，降低错过考试安排的风险。",
      scenario:
        "适合期末周前统一确认考试日期、教室和考试形式，也适合在主页待办中快速跳转。",
      abilities: [
        "展示考试课程和考试时间",
        "整理考试地点、方式和状态",
        "与主页待办联动显示近期考试",
        "支持从总览进入详细考务页",
        "保留同步快照，便于临时无网络时核对"
      ]
    },
    {
      slug: "calendar",
      key: "calendar",
      title: "学年日历",
      subtitle: "校历、教学周与近期安排",
      category: "信息工具",
      icon: "icon_calendar.png",
      accent: "#7c58c2",
      summary:
        "学年日历模块把当前学期、教学周、日期和校历事项整理到移动端，辅助安排学习节奏。",
      scenario:
        "适合查看当前第几教学周、近期校历节点，以及配合课表和考试安排制定计划。",
      abilities: [
        "显示当前学期和教学周",
        "按月份组织校历事项",
        "在主页展示近期日程摘要",
        "与课程、作业和考务形成时间线",
        "适配移动端快速扫读"
      ]
    },
    {
      slug: "mail",
      key: "mail",
      title: "邮箱",
      subtitle: "Coremail 文件夹、邮件与联系人",
      category: "信息工具",
      icon: "icon_mail.png",
      accent: "#2f8dd8",
      summary:
        "邮箱模块接入校园 Coremail，支持查看文件夹、近期邮件、邮件详情、联系人和草稿/发送流程。",
      scenario:
        "适合在手机上处理学校通知、课程邮件和简单回复，并在发送前保留用户确认。",
      abilities: [
        "查看 Coremail 文件夹和近期邮件列表",
        "阅读邮件详情并标记已读",
        "搜索联系人并保存草稿",
        "发送邮件前要求用户确认",
        "可被 Agent 邮件工具按需调用"
      ]
    },
    {
      slug: "zhixing",
      key: "zhixing",
      title: "知行",
      subtitle: "校园讨论与信息入口",
      category: "信息工具",
      icon: "icon_zhixing.png",
      accent: "#4a8b57",
      summary:
        "知行模块为校园讨论和信息浏览提供移动端入口，减少在校内服务之间跳转的成本。",
      scenario:
        "适合浏览校园社区信息、跟进讨论内容，或从统一服务入口进入相关校园资源。",
      abilities: [
        "接入知行相关校园服务",
        "提供统一移动端入口",
        "复用应用会话和网络能力",
        "与其它校园信息工具并列组织",
        "保留简洁的手机阅读体验"
      ]
    },
    {
      slug: "employment-consultation",
      key: "employment_consultation",
      title: "就业咨询",
      subtitle: "就业指导与咨询预约信息",
      category: "信息工具",
      icon: "icon_employment_consultation.png",
      accent: "#e0673d",
      summary:
        "就业咨询模块整理就业指导相关服务，让咨询信息和预约入口在移动端更容易找到。",
      scenario:
        "适合准备实习、求职或升学材料时查看学校提供的就业咨询资源。",
      abilities: [
        "接入就业咨询相关服务",
        "展示咨询入口和预约信息",
        "归入校园信息工具统一导航",
        "减少从移动浏览器反复登录的成本",
        "保留与学生身份相关的服务上下文"
      ]
    },
    {
      slug: "homework-agent",
      key: "openwebui_agent",
      title: "作业助手",
      subtitle: "嵌入式 Open WebUI Agent 工作区",
      category: "智能助手",
      icon: "icon_homework_agent.png",
      accent: "#5a6fe8",
      summary:
        "作业助手把 Open WebUI 嵌入 Android 应用，并通过原生工具连接作业附件、文档处理、归档和邮件等能力。",
      scenario:
        "适合作业要求复杂、附件较多或需要先整理资料再输出草稿的场景。",
      abilities: [
        "从作业模块接收一次性 handoff 上下文",
        "挂载作业附件工作区并支持文件读取",
        "支持常见压缩包解压、文档抽取和结果打包",
        "可调用邮件工具，但发送前必须确认",
        "不会自动提交课程平台作业"
      ]
    },
    {
      slug: "empty-rooms",
      key: "empty_rooms",
      title: "空教室",
      subtitle: "教室余量与时段查询",
      category: "信息工具",
      icon: "icon_empty_rooms.png",
      accent: "#00a6a6",
      summary:
        "空教室模块用于查看教学楼和时段的教室余量，帮助临时自习、讨论或排练找空间。",
      scenario:
        "适合课间临时找教室、自习前查看可用空间，或按时段对比不同教学楼余量。",
      abilities: [
        "查询指定日期和时段的教室余量",
        "按教学楼、节次等信息组织结果",
        "移动端展示可快速扫读的空闲状态",
        "与校历和课表一起辅助安排时间",
        "保留最近同步结果作为参考"
      ]
    },
    {
      slug: "profile",
      key: "profile",
      title: "我的信息",
      subtitle: "个人资料、培养信息与主题设置",
      category: "账户资料",
      icon: "icon_profile.png",
      accent: "#58c7b4",
      summary:
        "我的信息模块集中展示个人资料、培养信息和应用设置，是账号状态与身份信息的入口。",
      scenario:
        "适合核对姓名、学号、学院、专业等基础信息，或调整应用主题并管理登录状态。",
      abilities: [
        "查看人员信息和培养信息",
        "展示账号相关的基础资料字段",
        "支持应用主题切换",
        "提供退出登录入口",
        "与学业进度、成绩和课表形成个人学习档案"
      ]
    }
  ];

  window.BJTU_MODULES = modules;
})();
