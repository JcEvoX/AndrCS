# AI Agent 能力升级方案

本文件记录对 AndrCS AI 助手能力的对照分析与改进方案。所有结论基于对 ACSIDE
v1.0.0-alpha.5.20260708 (`afad2351...`) 和 LSPilot 1.0.8 (`7c97cbd5...`) 两个
APK 的静态反编译结果,只复用架构思想,不复制反编译源码、密钥或受保护资源。对照样本
SHA-256 与 `docs/zh-cn/APK_REVERSE_ENGINEERING.md` 中记录一致。

## 一、对照现状

| 维度 | AndrCS (改造前) | ACSIDE (反编译参考) | LSPilot (反编译参考) |
|---|---|---|---|
| 接口签名 | `generateCode(): Result<String>` 同步阻塞 | `chat(): Flow<AgentEvent>` 流式事件 | SSE 流 + `Function1` 回调 |
| 事件类型 | 7 个 callback 字符串方法 | sealed `AgentEvent` 6 子类 | sealed `a` 3 子类 + 内部 `aj.a/b/c` |
| HTTP | `HttpURLConnection` 同步 | `HttpURLConnection` 同步 | OkHttp + `stream: true` 真 SSE |
| 工具调用 | 解析 `"FILE_TO_MODIFY:"` 字符串 | 原生 `tool_calls` JSON + ReAct 循环 | 原生 `tool_calls` 流式增量 |
| Token 追踪 | 无 | `FinalResponse(total/prompt/candidate)` | `stream_options.include_usage` + `cached_tokens` |
| `reasoning_content` | 不支持 | 已提取(反编译元数据可见) | 显式字段(DeepSeek R1/Claude/GLM-4.5) |
| 工具集 | 4 个(list/read/search/write) | 9 个 | 30+ 个(按成本分级) |
| 路径边界 | `Path.startsWith` ✓ | `canonicalPath.startsWith` ✓ | (Xposed 上下文不同) |
| 敏感文件过滤 | `.env`、`local.properties`、`keystore.properties` ✓ | 无 | 无 |
| 取消 | 无 | 无 | `AtomicBoolean` + `Call.cancel()` ✓ |
| 错误恢复 | ✓ provider 自动切换 + 重试 + undo(强) | 无 | 无 |
| HTML 错误识别 | 无 | 无 | ✓ 登录页/网关页检测 |

## 二、改进方案

按优先级分批落地,**核心原则是纯新增、不破坏现有 `generateCode()`**,通过双接口过渡。

### P0 — 高价值低成本

#### P0-1: `AgentEvent` sealed class
- **目标**: 让 UI 区分 Thinking / ToolCall / ToolResult / FileWritten / FinalResponse /
  Error,而非字符串消息。
- **来源**: ACSIDE `mcp/agent/AgentEvent.kt`。
- **改造**: 新建
  [core/app/.../artificial/agents/AgentEvent.kt](file:///workspace/AndrCS/core/app/src/main/java/com/tom/rv2ide/artificial/agents/AgentEvent.kt)。

#### P0-2: 原生 `tool_calls` 支持
- **目标**: 替换脆弱的 `"FILE_TO_MODIFY:"` 字符串协议,改用 OpenAI 标准 `tool_calls`
  JSON 数组 + ReAct 循环(模型 → 工具调用 → 结果回填 → 模型继续)。
- **来源**: ACSIDE `OpenAiCompatibleMcpAgent` + `WorkspaceTools`。
- **改造**:
  - `AIAgent` 接口新增 `chat(prompt, tools): Flow<AgentEvent>`,保留 `generateCode()`。
  - 新建 `ToolSchemaJson.kt` 把 `ToolDefinition` 转 OpenAI `tools` JSON。
  - `OpenAI.kt` 实现 `chat()`,旧 `generateCode()` 委托给新实现以减少重复。
  - `AIAgentManager` 新增 `executeChat()`,把 `Flow<AgentEvent>` 转回现有 callback,
    保持 UI 不动。

#### P0-3: 扩展工具集
- **目标**: 从 4 个增到 7 个核心工具。
- **来源**: ACSIDE `WorkspaceTools`(9 个,本文档去掉 `run_command`、
  `run_command_in_dir` 因涉及 shell 注入面;Android 上下文不需要 `web_search`)。
- **新增**:
  - `delete_file` — 删除文件,需要确认。
  - `create_directory` — 创建目录。
  - `file_info` — 查询文件大小/是否存在/修改时间。

### P1 — 高价值中成本(后续)

| 项 | 来源 | 说明 |
|---|---|---|
| SSE 真流式 | LSPilot `wqe.r()` OkHttp + 行扫描 | 当前 HttpURLConnection 同步读取 |
| `reasoning_content` 提取 | LSPilot 显式字段 | 让 DeepSeek R1 / Claude thinking / GLM-4.5 思考过程可见 |
| Token 用量追踪 | ACSIDE `FinalResponse` + LSPilot `cached_tokens` | 用于历史裁剪和成本显示 |
| 请求取消 | LSPilot `AtomicBoolean` + `Call.cancel()` | 长时操作可中断 |
| HTML 错误识别 | LSPilot `wqe.q()` | 网关/登录页检测 |

### P2 — 锦上添花

- AI ↔ 构建服务只读桥接:让 AI 能查询任务列表、构建状态、
  `.andrcs/logs/build-out.txt` 摘要,修构建错误。
- 工具成本分级系统提示(借鉴 LSPilot `ai-system-prompt.md` 设计哲学,
  转译到 IDE 场景)。

## 三、日志路径(已完成)

[ProjectBuildLog.kt:108](file:///workspace/AndrCS/core/app/src/main/java/com/tom/rv2ide/services/builder/ProjectBuildLog.kt#L108)
已写入 `<项目根目录>/.andrcs/logs/build-out.txt`,通过 `projectRoot()` 从
`ProjectManagerImpl` 动态获取项目根目录,**已脱离 `/data` 私有路径**。本文件无需再改。

## 四、参考样本

| 项目 | 包名 | SHA-256 | DEX 数 |
|---|---|---|---|
| ACSIDE | `com.nullij.androidcodestudio` | `afad235174f0c17d03ed2d37b1b46e4a19a657973aaf9d65c4b12da1ba656886` | 15 |
| LSPilot | `me.yun.lspilot` | `7c97cbd57cfb459324cb1a050eb45d7d07e7ec9ed026d4cc4ccf324af594928c` | 9 |

反编译产物保留在 `/workspace/re/` 下,供后续 P1 阶段参考,不入库。
