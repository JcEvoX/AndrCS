# ACSIDE 与 LSPilot 静态分析及 AndrCS 替换方案

本文记录针对指定发行 APK 的 DEX、资源和原生库静态分析。实现仅复用可验证的架构思想，不复制反编译源码、密钥或受保护资源。

## 样本

| APK | applicationId / versionName | SHA-256 |
| --- | --- | --- |
| ACSIDE | `com.nullij.androidcodestudio` / `1.0.0-alpha.5.20260708-arm64-v8a` | `afad235174f0c17d03ed2d37b1b46e4a19a657973aaf9d65c4b12da1ba656886` |
| LSPilot | `me.yun.lspilot` / `1.0.8` | `7c97cbd57cfb459324cb1a050eb45d7d07e7ec9ed026d4cc4ccf324af594928c` |

ACSIDE 含 15 个 DEX，LSPilot 含 9 个 DEX。分析使用 JADX 1.5.6、`apkanalyzer`、DEX 字符串交叉引用和 ELF 字符串/符号检查。

## ACSIDE

### Android 构建链

主要调用链为：

`GradleService` → `ToolingApiClient` → rootfs 中的 Java 进程 → Tooling API 服务端 → Gradle。

- `ToolingApiClient` 使用 `ProcessBuilder` 启动独立 Java 进程，设置 rootfs/Gradle 环境、工作目录以及必要的 proot bind 参数。
- 客户端维护 stdin/stdout 请求、独立 stderr 和 stdout 读取线程，并把 `BuildInfo`、`BuildResult`、`LogMessageParams` 和进度事件转交给 `GradleService`。
- API 包含项目初始化、任务枚举、元数据/模块 classpath 查询、执行任务、取消构建和停止 Gradle daemon。
- `GradleService` 负责初始化脚本、同步缓存、任务执行状态、APK 定位、构建前后存储清理和 UI 可观察状态。
- 构建输出在内存中按每页 2,000 行分页；样本中没有发现 `build-out.txt` 持久化逻辑。因此项目内日志是 AndrCS 的补充能力，而不是照搬该实现。

与构建相关的 arm64 原生库包括 `libaapt2.so`、`libproot_shared.so`、`libtermux.so`、`libbusybox.so`、`libacsbox.so`、`libquickjs.so` 和 `libeditor-backend.so`。这些库承担 AAPT2、隔离命令环境、终端和脚本运行等职责；Gradle 编排本身仍在 DEX/JVM 层。

### AI 助手

`com.nullij.androidcodestudio.mcp.agent` 包实现了工具调用型代理：

- `GeminiMcpAgent`、`AnthropicMcpAgent` 和 `OpenAiCompatibleMcpAgent` 统一暴露 `chat`、`clearHistory` 和 `restoreHistory`。
- `AgentEvent` 区分 thinking、tool call、tool result、file written、error 和 final response，并记录 prompt/candidate/total token。
- `WorkspaceTools` 声明 `list_files`、`read_file`、`write_file`、`delete_file`、`create_directory`、`file_info`、`search_files` 以及终端工具。
- 可选的 `WebSearchTool` 增加联网搜索和页面抓取。
- 每个 provider 把工具声明转换成对应协议的 JSON Schema，执行工具后将结果追加到对话历史，再继续模型循环。
- 文件读取设置了 500 KiB 上限，路径先 canonicalize 后检查工作区边界。不过样本使用字符串 `startsWith` 判断，仍可能混淆 `/project` 与 `/project-other`；AndrCS 使用 `Path.startsWith`。

原生库没有发现独立 LLM 推理引擎；模型请求和工具编排均在 DEX 层。

## LSPilot

LSPilot 的 AI 是面向 Android 逆向分析的工具代理，而不是普通聊天窗口。

- 内置 provider 配置包含 OpenAI、Anthropic、Gemini 和 DeepSeek，并支持自定义 base URL、API path、模型列表及自定义 provider。
- OpenAI 兼容实现发送 `Authorization: Bearer` 请求，支持 SSE、`stream_options.include_usage`、reasoning、文本增量、`tool_calls`、自动工具选择和取消。
- Gemini 使用 `x-goog-api-key`；provider 模型列表可从服务端加载。
- 响应统一为 reasoning、text、tool 三种内容块；tool 块保存 call id、tool name、输入、输出和元数据。
- `assets/ai-system-prompt.md` 声明 30+ 个逆向工具，并明确采用“字符串锚点 → 候选缩小 → 方法检查 → Java/Smali 取证”的低成本优先编排。
- 工具覆盖 DEX 类/方法/字段搜索、调用方查询、Java/Smali 反编译、manifest/资源读取、插件读写、网页搜索以及 Termux 命令执行。
- `WebSearchManager` 直接通过 Bing 搜索；模型逻辑位于 DEX。

原生库为 `libmemsearch.so`、`libdexkit.so`、`libluajava.so` 和图形支持库。它们提供内存搜索、DEX 查询及脚本桥接，不负责云模型对话。

## AndrCS 替换/补充设计

本次已落地：

1. `GradleBuildService` 在 UI 是否连接之外持续接收原始输出，并把最新一次构建写入项目的 `.andrcs/logs/build-out.txt`。
2. AI 项目索引排除构建产物、VCS 元数据、常见密钥文件和超大文件，并限制文件数、单文件字符数及总上下文大小。
3. 所有 provider 共用同一份有界项目上下文，避免各自再次遍历整个项目。
4. AI 文件写入使用 canonical `Path` 边界检查；首次启用采用安全默认值，每次修改默认要求用户确认并保留备份。
5. provider API key 存入 `EncryptedSharedPreferences`，首次读取时迁移并删除旧明文值。
6. 请求体、模型响应和生成源码不再写入 Logcat。

后续适合在现有 `AIAgent` 接口上分层补充：

- 定义 provider-neutral 的 message/content/tool-call DTO，由 OpenAI、Anthropic、Gemini 适配器负责协议转换。
- 用集中式 `ToolRegistry` 替换自由格式整文件响应。读取工具默认可用；写入、删除和终端工具必须经过工作区策略及用户确认。
- 为工具声明 JSON Schema，限制参数、输出大小、超时、最大工具轮次和并发重操作数量。
- 将 reasoning、文本增量、工具状态和 token usage 转换成统一事件流，并提供取消传播。
- 对话历史按 token 预算裁剪；项目文件只在模型明确调用读取工具时按需加入，避免每轮发送源码快照。
- 构建服务继续保留 Tooling API 隔离进程，向 AI 暴露只读的任务列表、构建状态和项目内日志摘要；执行构建仍需显式确认。

不建议直接引入 LSPilot 的 Hook、内存读写或任意命令能力。AndrCS 的目标是项目开发，默认权限应严格限制在当前项目，并保持每个高风险动作可见、可取消、可审计。
