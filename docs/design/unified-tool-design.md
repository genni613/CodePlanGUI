# CodePlanGUI 统一工具协议系统 — 设计文档

> **版本**: v2.0
> **日期**: 2026-04-17
> **状态**: Draft
> **参考**: MiniCode PRD_UNIFIED_TOOL_PROTOCOL.md、claw-code 权限模型调研
> **变更**: v1.0 → v2.0 合并 protocol-design + architecture，优化 7 项设计问题

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
| **安全** | 多层权限策略 + 文件写操作 diff 审批 + 新建文件确认 |
| **可扩展** | 运行时动态加载/卸载工具（MCP server 上下线） |
| **健壮** | 工具执行永不导致 Agent Loop 崩溃 |
| **并发安全** | 同文件写入操作串行化，防止数据竞争 |

### 1.3 工具范围

第一版 6 个内置工具 + MCP 预留：

| 分类 | 工具 | 权限 | 用途 |
|------|------|------|------|
| 命令执行 | `run_command` | 动态分级 | Shell 命令（现有，重构） |
| 文件读取 | `read_file` | READ_ONLY | 读取文件内容（支持按行分块） |
| 目录浏览 | `list_files` | READ_ONLY | 列出目录结构 |
| 文本搜索 | `grep_files` | READ_ONLY | 基于 IntelliJ Search API 的文本搜索 |
| 精确替换 | `edit_file` | WORKSPACE_WRITE | 单处/多处文本替换 + diff 审批 |
| 整文件写入 | `write_file` | WORKSPACE_WRITE | 新建或覆写文件 + 确认/diff 审批 |
| MCP 工具 | `mcp__*__*` | 需审批（默认） | Phase 4 动态加载 |

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
                          ┌────────────▼─────────────┐
                          │   ToolCallDispatcher      │
                          │  权限策略 + 审批挂起 + 调度│
                          └────────────┬─────────────┘
                                       │
                    ┌──────────────────▼──────────────────┐
                    │           ToolRegistry               │
                    │  注册 · 查找 · 校验 · 执行 · 清理   │
                    └──┬─────┬─────┬─────┬─────┬────┬────┘
                       │     │     │     │     │    │
                      Bash  Read  List  Grep Edit Write MCP
                      Exec  File  Files Files File File  (P4)
                       │                         │    │
                       ▼                         ▼    ▼
               ┌──────────────┐          ┌──────────────┐
               │ CommandExec  │          │ FileChange   │
               │ Service      │          │ Review       │
               │ (现有，不改) │          │ (diff 审批)  │
               └──────────────┘          └──────────────┘
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
| M5 | MCP 预留 | 接口定义，Phase 4 实现 |

---

## 3. 核心类型定义（M1）

### 3.1 ToolResult — 统一结果类型

所有工具（内置和 MCP）的返回值遵循同一结构。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ok` | Boolean | 是 | 成功或失败 |
| `output` | String | 是 | 结果文本；失败时存放可读错误描述 |
| `awaitUser` | Boolean | 否 | 若为 true，暂停 Agent Loop 等待用户输入 |
| `backgroundTask` | BackgroundTask? | 否 | 后台任务信息（bash 后台命令） |
| `truncated` | Boolean | 否 | 输出是否被截断 |

与现有 `ExecutionResult` 的映射：

| ExecutionResult | ToolResult |
|-----------------|------------|
| `Success(stdout, stderr, exitCode, durationMs)` | `{ ok: true, output: stdout + stderr }` |
| `Failed(stdout, stderr, exitCode, durationMs)` | `{ ok: false, output: stderr }` |
| `Blocked(reason)` | `{ ok: false, output: reason }` |
| `Denied(reason)` | `{ ok: false, output: reason }` |
| `TimedOut(stdout, timeoutSeconds)` | `{ ok: false, output: timeout info }` |

**关键约束**：`output` 在 `ok=false` 时必须包含可读的错误描述。

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
| `find(name)` | 按名称查找工具 |
| `addTools(specs)` | 去重追加（同名跳过不覆盖） |
| `removeTool(name)` | 移除工具（MCP 下线时调用） |
| `execute(name, input, context)` | 核心执行：查找 → 校验 → 执行 |
| `addDisposer(fn)` | 注册清理函数 |
| `dispose()` | 反序调用所有清理函数 |
| `buildOpenAiTools()` | 转为 OpenAI API tools 参数格式 |

### 4.2 execute — 三层安全防护

**此方法绝对不能抛出未捕获的异常。**

```
输入: toolName, input (JsonObject), context (ToolContext)
  │
  ├─ 第 1 层：工具查找
  │   └─ 未找到 → { ok: false, output: "Unknown tool: xxx" }
  │
  ├─ 第 2 层：参数校验
  │   └─ 必填参数缺失 → { ok: false, output: "Missing required parameter: xxx" }
  │
  ├─ 第 3 层：执行
  │   └─ try/catch 包裹 toolExecutor.execute()
  │       ├─ 正常返回 → 工具自身的 ToolResult
  │       └─ 异常 → { ok: false, output: error.message }
  │
  └─ 保证：调用方永远收到 ToolResult，不会崩溃
```

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
      └─ 通过 CommandExecutionService 执行
```

**设计说明**：IDE 插件不应依赖外部工具。`rg` 在 Windows 上可能不存在，macOS 默认也没装。IntelliJ 的 `FindInProjectUtil` 已有成熟的搜索实现，且可复用 IDE 的文件索引加速。

### 5.5 精确文本替换 — `edit_file`

| 属性 | 值 |
|------|-----|
| 权限 | WORKSPACE_WRITE |
| 底层 | 字符串替换 + IntelliJ WriteAction |
| 审批 | **必须** — 展示 diff，等待用户确认 |

**输入参数**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `path` | String | 是 | — | 文件路径 |
| `search` | String | 是 | — | 要查找的文本 |
| `replace` | String | 是 | — | 替换为的文本 |
| `replaceAll` | Boolean | 否 | false | 是否替换所有出现 |

**行为规则**：

- `search` 在文件中不存在时返回 `ok=false`
- `replaceAll=false` 时，若 `search` 出现多次，返回 `ok=false` 并列出匹配行号（要求 AI 指定更精确的上下文）
- `replaceAll=true` 时使用 `split().join()` 而非正则，避免特殊字符问题
- 所有替换必须经过 diff 审批

**设计说明**：`replaceAll=false` 时如果有多处匹配，静默替换第一处容易导致错误修改。返回匹配数和行号让 AI 提供更精确的上下文，是更安全的做法。

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
  │   ├─ 是 → 展示 unified diff，等待用户确认（与 edit_file 一致）
  │   └─ 否（新建文件）→ 弹出新建文件确认框
  │       ├─ 显示：路径、文件大小、内容预览（前 20 行）
  │       ├─ 用户确认 → WriteAction 写入
  │       └─ 用户拒绝 → ToolResult(ok=false, "User rejected")
  │
  └─ WriteAction 写入 → ToolResult(ok=true)
```

**设计说明**：新建文件不能静默跳过审批。AI 可以在 workspace 任意位置创建文件（如 `.git/hooks/pre-commit`），存在安全风险。新建文件虽然无法生成 diff，但应弹确认框展示路径和内容摘要供用户审核。

---

## 6. 文件写操作并发保护

### 6.1 问题

AI 可能一次返回多个 tool_call，其中两个 `edit_file` 修改同一文件。并发执行会导致后写入的覆盖先写入的，丢失修改。

### 6.2 解决方案：文件级写锁

```kotlin
class FileWriteLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(path) { Mutex() }
        return mutex.withLock(block)
    }
}
```

### 6.3 调度流程

```
ToolCallDispatcher 收到多个 tool_calls
  │
  ├─ 1. 分类：读取类工具（无锁）vs 写入类工具（需文件锁）
  │
  ├─ 2. 读取类工具：并行执行
  │
  ├─ 3. 写入类工具：提取目标路径
  │     ├─ 不同文件 → 可并行执行（各自持各自的锁）
  │     └─ 同文件 → 串行执行（共享同一把锁）
  │
  └─ 4. 全部完成后收集结果，回传 API
```

### 6.4 限制

- 文件锁仅保护同一会话内的并发写入
- 跨会话的并发写入依赖 IDE 自身的文件系统锁（VFS）
- MCP 工具的写入也经过同一套锁机制

---

## 7. ToolCallDispatcher 统一调度（M4）

### 7.1 职责

在 ChatService 和 ToolRegistry 之间，负责：
1. 动态权限解析（bash 命令分级）
2. 权限策略判定（deny / allow / ask）
3. 审批挂起（协程挂起 + Bridge 通知）
4. 文件写锁管理
5. 调度执行（委托 ToolRegistry）

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
  ├─ 4. 动态权限解析
  │     ├─ run_command → BashExecutor.classifyPermission(command)
  │     │   ├─ 只读命令 (cat, ls, grep...) → READ_ONLY
  │     │   ├─ 开发命令 (git, npm, cargo...) → WORKSPACE_WRITE
  │     │   └─ 未知命令 → DANGER_FULL_ACCESS
  │     └─ 其他工具 → 使用注册的 requiredPermission
  │
  ├─ 5. 权限策略判定 authorize()
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
  ├─ 6. 执行
  │     ├─ DENY → ToolResult(ok=false, "Denied by policy")
  │     ├─ ALLOW → registry.execute()
  │     └─ ASK → requestApproval()
  │         ├─ Bridge.notifyApprovalRequest()
  │         ├─ 协程挂起 awaitApproval()（见 7.4）
  │         ├─ 超时/拒绝 → ToolResult(ok=false)
  │         └─ 用户允许 → registry.execute()
  │
  └─ 7. 返回 ToolResult
```

### 7.3 权限策略规则

| 规则类型 | 行为 | 示例 |
|----------|------|------|
| deny_rules | 直接拒绝 | `rm -rf /`、路径穿越 `../../etc/passwd`、workspace 外访问 |
| allow_rules | 放行（受 session mode 约束） | 白名单内命令、READ_ONLY 工具 |
| session mode | 用户配置的全局权限等级 | READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS |
| ask (兜底) | 弹审批框 | 权限升级、非白名单命令 |

### 7.4 审批挂起机制（协程实现）

使用 Kotlin 协程的 `suspendCancellableCoroutine` 替代 `CompletableFuture.get()`，避免阻塞线程：

```kotlin
private val pendingApprovals = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

private suspend fun awaitApproval(requestId: String): Boolean {
    return suspendCancellableCoroutine { cont ->
        pendingApprovals[requestId] = cont

        // 60 秒超时自动拒绝
        val timeoutJob = scope.launch {
            delay(60_000)
            cont.resume(false) { }
            pendingApprovals.remove(requestId)
        }

        cont.invokeOnCancellation {
            timeoutJob.cancel()
            pendingApprovals.remove(requestId)
        }
    }
}

// Vue 回调时恢复协程
fun onApprovalResponse(requestId: String, decision: String) {
    val cont = pendingApprovals.remove(requestId) ?: return
    val approved = decision == "allow"
    cont.resume(approved)
}
```

**优势**：不阻塞线程池，多个审批可同时挂起而不消耗线程资源。

---

## 8. Diff 审批与新建文件确认

### 8.1 设计动机

文件写操作具有不可逆性。用户必须在变更发生前看到并确认。这适用于修改已有文件和创建新文件两种场景。

### 8.2 已有文件 — Diff 审批

```
EditFileExecutor / WriteFileExecutor 修改已有文件
  │
  ├─ 路径安全检查：resolveToolPath(path)
  │   不在 workspace 内 → ToolResult(ok=false)
  │
  ├─ 读取当前文件内容
  │
  ├─ 生成 unified diff：original → newContent
  │   计算增删行数
  │
  ├─ 通过 Bridge 发送 diff 审批请求
  │   事件: file_change_request
  │   Payload: { requestId, path, diff, stats: { added, removed } }
  │
  ├─ 协程挂起 awaitApproval()，等待 Vue 回调
  │
  ├─ 用户拒绝 → ToolResult(ok=false, "User rejected")
  │
  └─ 用户接受 → WriteAction 写入 → ToolResult(ok=true)
```

### 8.3 新建文件 — 确认对话框

```
WriteFileExecutor 创建新文件
  │
  ├─ 路径安全检查：resolveToolPath(path)
  │
  ├─ 文件不存在 → 新建文件确认流程
  │
  ├─ 通过 Bridge 发送新建文件确认请求
  │   事件: file_create_request
  │   Payload: {
  │     requestId,
  │     path,
  │     size: content.length,
  │     preview: content 的前 20 行,
  │     language: 根据扩展名推断的语言标识
  │   }
  │
  ├─ 协程挂起 awaitApproval()，等待 Vue 回调
  │
  ├─ 用户拒绝 → ToolResult(ok=false, "User rejected")
  │
  └─ 用户接受 → WriteAction 写入 → ToolResult(ok=true)
```

### 8.4 Bridge 事件定义

#### Kotlin → Vue

| 事件 | 字段 | 说明 |
|------|------|------|
| `file_change_request` | requestId, path, diff, stats | 修改已有文件的 diff 审批 |
| `file_create_request` | requestId, path, size, preview, language | 新建文件的确认 |

#### Vue → Kotlin

| 事件 | 字段 | 说明 |
|------|------|------|
| `file_change_response` | requestId, decision | `"allow"` 或 `"deny"` |
| `file_create_response` | requestId, decision | `"allow"` 或 `"deny"` |

### 8.5 前端组件

**FileChangeDialog** — Diff 审批：
- 展示 unified diff（增行绿色、删行红色、上下文灰色）
- 显示统计：`+N 行 / -N 行`
- 操作按钮："拒绝" / "接受更改"

**FileCreateConfirmDialog** — 新建文件确认：
- 显示文件路径、大小、语言类型
- 内容预览（前 20 行，带语法高亮）
- 操作按钮："拒绝" / "创建文件"

两个对话框均 60 秒超时自动拒绝。

---

## 9. Bridge 事件体系扩展

### 9.1 现有事件（保留）

| 方向 | 事件 | 说明 |
|------|------|------|
| IDE → Vue | `onApprovalRequest` | 命令审批请求 |
| IDE → Vue | `onExecutionStatus` | 执行状态更新 |
| Vue → IDE | `approvalResponse` | 用户审批决定 |

### 9.2 新增事件

| 方向 | 事件 | 说明 |
|------|------|------|
| IDE → Vue | `onFileChangeRequest` | 文件修改 diff 审批请求 |
| Vue → IDE | `fileChangeResponse` | diff 审批决定 |
| IDE → Vue | `onFileCreateRequest` | 新建文件确认请求 |
| Vue → IDE | `fileCreateResponse` | 新建文件确认决定 |

### 9.3 现有事件扩展

**onApprovalRequest** 增加 `toolName` 参数：

| 参数 | 现有 | 新增 |
|------|------|------|
| `requestId` | ✓ | ✓ |
| `command` | ✓ | → 改名 `toolInput`（通用化） |
| `description` | ✓ | ✓ |
| `toolName` | — | ✓（如 `run_command`、`mcp__xxx`） |

前端根据 `toolName` 渲染不同审批 UI：
- `run_command` → 展示命令 + 描述（现有样式）
- 其他工具 → 展示工具名 + 参数摘要

### 9.4 BridgeHandler.kt 变更

| 方法 | 变更 |
|------|------|
| `notifyApprovalRequest` | 参数增加 `toolName` |
| `notifyFileChangeRequest` | **新增** |
| `notifyFileCreateRequest` | **新增** |
| Bridge JS 模板 | 新增 `onFileChangeRequest` / `fileChangeResponse` / `onFileCreateRequest` / `fileCreateResponse` |

### 9.5 bridge.d.ts 变更

| 接口方法 | 变更 |
|----------|------|
| `onApprovalRequest` | 增加 `toolName` 参数 |
| `onFileChangeRequest` | **新增** |
| `fileChangeResponse` | **新增** |
| `onFileCreateRequest` | **新增** |
| `fileCreateResponse` | **新增** |

---

## 10. MCP 预留接口（M5）

Phase 4 接入 MCP 只需以下步骤，**不修改 ToolCallDispatcher 或 ToolRegistry**：

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

### 10.4 与内置工具的差异

| 维度 | 内置工具 | MCP 工具 |
|------|----------|----------|
| 名称 | `run_command`、`read_file` 等 | `mcp__<server>__<tool>` |
| 参数校验 | Kotlin 层严格校验 | 不做本地校验（schema 动态获取） |
| 执行 | 本地逻辑（VFS/ProcessBuilder） | 远程调用 |
| 注册时机 | 插件启动时 | MCP 连接建立后 |
| 生命周期 | 插件生命周期 | MCP 连接生命周期 |
| 权限 | 精确分级 | 未信任需审批 / 已信任 WORKSPACE_WRITE |

---

## 11. Settings 扩展

### 11.1 新增配置项

在 `SettingsState` 和 `SettingsFormState` 中新增：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `permissionMode` | String | `"WORKSPACE_WRITE"` | 全局权限等级 |
| `trustedMcpServers` | List<String> | `[]` | 已信任的 MCP server 列表 |

### 11.2 Settings UI

在现有 "Command Execution" 区块上方新增权限模式选择：

```
Permission Level     [ Workspace Write ▼ ]

  ● Read Only       — 只允许只读操作和只读命令
  ● Workspace Write — 允许项目内读写操作（默认，推荐）
  ● Full Access     — 允许任意命令执行

Command Execution                           [Toggle: OFF/ON]
─────────────────────────────────────────────────────────────
Allowed Commands (base command prefix matching)
  ┌──────────┬──────────────────────────────────────────┐
  │ cargo    │                                     [✕] │
  │ git      │                                     [✕] │
  │ ...      │                                         │
  └──────────┴──────────────────────────────────────────┘
Execution timeout    [ 30 ] seconds

MCP Server Trust (Phase 4)
  ┌──────────────────┬─────────────┐
  │ Server           │ Trusted     │
  ├──────────────────┼─────────────┤
  │ (连接后自动列出) │             │
  └──────────────────┴─────────────┘
```

---

## 12. ChatService 重构

### 12.1 删除的代码（约 120 行）

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

### 12.2 替换后的代码（约 15 行）

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

### 12.3 不变的部分

- SSE 状态机（STREAMING_TEXT → ACCUMULATING_TOOL_CALL → WAITING_RESULT）
- Token 流式渲染
- Session 持久化
- Context 注入
- System prompt 构建

---

## 13. 现有实现保留与变更总览

### 13.1 保留不动

| 组件 | 原因 |
|------|------|
| `ToolCallAccumulator` | SSE 解析层，与工具协议无关 |
| `SseChunkParser` | 提取 ToolCallDelta，通用 |
| `OkHttpSseClient` | HTTP/SSE 客户端，不涉及工具逻辑 |
| `CommandExecutionService` | BashExecutor/GrepExecutor 包装它，不改内部 |
| `ExecutionResult` | 只新增 `toToolResult()` 扩展方法 |
| `ExecutionCard.tsx` | 展示逻辑通用，扩展支持非命令工具即可 |

### 13.2 重构变更

| 组件 | 变更 |
|------|------|
| `ChatService.kt` | 删除 ~120 行硬编码工具逻辑，替换为 ~15 行 Dispatcher 调用 |
| `BridgeHandler.kt` | approval_request 增加 toolName；新增 file_change_request / file_create_request 事件 |
| `bridge.d.ts` | 更新审批类型；新增文件变更和新建文件事件类型 |
| `ApprovalDialog.tsx` | 扩展支持多种工具类型的审批展示 |

---

## 14. 文件变更清单

### 14.1 新建文件（16 个）

| 文件 | 说明 |
|------|------|
| `execution/ToolResult.kt` | 统一结果类型 |
| `execution/ToolContext.kt` | 工具执行上下文 |
| `execution/ToolSpec.kt` | 工具注册信息 |
| `execution/PermissionMode.kt` | 权限等级枚举 |
| `execution/ToolExecutor.kt` | 执行器接口 |
| `execution/ToolRegistry.kt` | 注册中心 |
| `execution/ToolCallDispatcher.kt` | 统一调度器 |
| `execution/FileChangeReview.kt` | 文件写操作 diff 审批 + 新建文件确认 |
| `execution/FileWriteLock.kt` | 文件级并发写锁 |
| `execution/ToolSpecs.kt` | 6 个工具的 ToolSpec 定义 |
| `execution/executors/BashExecutor.kt` | bash 执行器 + 动态分级 |
| `execution/executors/ReadFileExecutor.kt` | 文件读取（行号分块） |
| `execution/executors/ListFilesExecutor.kt` | 目录列表 |
| `execution/executors/GrepFilesExecutor.kt` | IntelliJ Search API 文本搜索 |
| `execution/executors/EditFileExecutor.kt` | 精确替换 + diff 审批 |
| `execution/executors/WriteFileExecutor.kt` | 整文件写入 + 确认/diff 审批 |

### 14.2 修改文件

| 文件 | 变更 |
|------|------|
| `ChatService.kt` | 删除硬编码工具逻辑，改用 ToolCallDispatcher |
| `ExecutionResult.kt` | 新增 `toToolResult()` 转换方法 |
| `BridgeHandler.kt` | 审批请求增加 toolName；新增 4 个 Bridge 事件 |
| `bridge.d.ts` | 更新审批类型；新增文件变更/新建文件类型 |
| `ApprovalDialog.tsx` | 扩展支持多工具类型审批 |
| `SettingsState` + Settings UI | 新增 permissionMode 字段和 UI |

### 14.3 不动文件

| 文件 | 原因 |
|------|------|
| `CommandExecutionService.kt` | BashExecutor 和 GrepExecutor 包装它，不改内部 |
| `ToolCallAccumulator.kt` | SSE 解析层不变 |
| `ExecutionCard.tsx` | 展示逻辑通用，无需修改 |

---

## 15. 迁移步骤

| 步骤 | 内容 | 依赖 | 涉及文件 |
|------|------|------|----------|
| **Step 1** | 核心类型（纯新增） | 无 | ToolResult、ToolContext、ToolSpec、PermissionMode、ToolExecutor |
| **Step 2** | ToolRegistry + FileWriteLock | Step 1 | ToolRegistry、FileWriteLock |
| **Step 3** | 读取类执行器 | Step 2 | BashExecutor、ReadFileExecutor、ListFilesExecutor、GrepFilesExecutor |
| **Step 4** | 写入类执行器 + 审批机制 | Step 2 | EditFileExecutor、WriteFileExecutor、FileChangeReview、FileWriteLock |
| **Step 5** | ToolCallDispatcher | Step 3, 4 | ToolCallDispatcher |
| **Step 6** | ChatService 重构 | Step 5 | ChatService.kt（重构） |
| **Step 7** | Bridge + Settings + 前端扩展 | Step 5 | BridgeHandler、bridge.d.ts、SettingsState、ApprovalDialog、FileChangeDialog、FileCreateConfirmDialog |
| **Step 8** | 测试 & 清理 | Step 7 | 测试文件、删除废弃代码 |

每个步骤完成后可独立验证。

---

## 16. 验收标准

### 功能验收

- [ ] 6 个内置工具均可在 Chat 中通过 AI 自主调用
- [ ] `run_command` 的三级权限分级正确生效
- [ ] `read_file` 支持按行分块读取，截断时提示模型继续
- [ ] `edit_file` 在 search 文本不存在或多次匹配时返回清晰错误
- [ ] `grep_files` 在无外部 rg 时仍可通过 IntelliJ API 正常搜索
- [ ] 文件修改弹出 diff 审批，用户可接受/拒绝
- [ ] 新建文件弹出确认框（显示路径、大小、内容预览）
- [ ] 同文件并发写入串行执行，不丢失修改
- [ ] Settings 切换 permissionMode 后行为立即变化
- [ ] IDE 重启后 Settings 配置保留

### 安全验收

- [ ] 路径穿越攻击被阻止（`../../etc/passwd`）
- [ ] workspace 外的路径访问被拒绝
- [ ] ToolRegistry.execute 永不抛出未捕获异常
- [ ] 审批超时 60 秒自动拒绝
- [ ] MCP 工具默认需审批，加入信任列表后才自动放行

### 回归验收

- [ ] 现有 `CommandExecutionServiceTest` 全部通过
- [ ] Chat SSE 流式输出不受影响
- [ ] Session 持久化不受影响
- [ ] 前端现有功能（Chat、Settings、Commit 生成）不受影响
- [ ] 工具功能关闭时（`commandExecutionEnabled=false`）行为与现有一致

### MCP 预留验收

- [ ] `ToolExecutor` 接口可被 MCP 工具实现
- [ ] `addTools` 去重机制正确工作
- [ ] `removeTool` 可正确移除工具
- [ ] `dispose` 反序调用所有清理函数

---

## 附录 A：v1.0 → v2.0 变更摘要

| # | 变更 | 原因 |
|---|------|------|
| 1 | `grep_files` 底层改为 IntelliJ FindInProjectUtil 优先 | 外部 `rg` 在 Windows/部分 macOS 上不可用，IDE 插件不应依赖外部工具 |
| 2 | `read_file` 改用行号（`line_number` + `limit` 行数） | 字符偏移对 AI 不直观，行号是行业惯例 |
| 3 | 新增 `FileWriteLock` 文件级并发保护 | 多 tool_call 并发写同文件会丢失数据 |
| 4 | 审批挂起改用协程 `suspendCancellableCoroutine` | `CompletableFuture.get()` 阻塞线程，多审批并发时耗尽线程池 |
| 5 | 新建文件增加确认对话框 | 防止 AI 在任意位置静默创建文件 |
| 6 | MCP 工具默认需审批，新增 `trustedMcpServers` 配置 | 远程工具默认 WORKSPACE_WRITE 过于宽松 |
| 7 | 合并 protocol-design + architecture 为一份文档 | 两份文档 60% 重复，维护成本高 |
