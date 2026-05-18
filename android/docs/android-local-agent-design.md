# Android Agent Integration

本项目不再保留独立的原生作业 Agent 任务系统。

当前作业协助入口统一接入嵌入式 OpenWebUI：

- Android 作业卡片通过 `NativeAgentHomeworkHandoffStore` 传递作业上下文。
- OpenWebUI chat 消费 handoff，生成待发送草稿，并在请求参数中携带 `agent_workspace_id`。
- OpenWebUI local-first agent loop 根据 `agent_workspace_id` 动态挂载 Android 原生 `agent_*` 工具。
- Android 端只保留 workspace、文档、归档、代码运行等工具能力，不再维护独立的原生任务、模型配置、API Key、Room Agent 表或前台 Agent 服务。

旧原生 Agent 设计已废弃。新增或调整作业协助能力时，应优先修改 OpenWebUI local-first agent 与 `NativeAgentToolsPlugin` 桥接层。
