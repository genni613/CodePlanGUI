# 工具执行可视化 — 折叠式步骤条设计

> **版本**: v1.0
> **日期**: 2026-04-21
> **状态**: Draft
> **依赖**: unified-tool-design.md v3.3

---

## 1. 背景与目标

### 1.1 现状问题

当前 ExecutionCard 组件为每个工具调用创建独立气泡，存在以下问题：

| 问题 | 影响 |
|------|------|
| 每个 `read_file` 都生成一个完整卡片 | 高频只读操作导致聊天区被卡片淹没 |
| 卡片包含 stdout/stderr 分栏、展开收起 | 信息密度高，打断用户阅读 AI 回复的节奏 |
| 卡片是独立消息气泡 | 与 assistant 回复割裂，不自然 |
| 所有工具共享同一套 UI | `read_file` 和 `run_command` 的信息需求差异大，但展示一样重 |

### 1.2 设计目标

| 目标 | 描述 |
|------|------|
| **轻量默认** | 默认折叠为一行，不抢注意力，不影响用户阅读 AI 回复 |
| **按需展开** | 用户点击可查看完整输出/日志，再点收起 |
| **嵌入消息流** | 工具步骤嵌入 assistant 消息内部，不是独立气泡 |
| **区分工具类型** | 读操作极简，写操作带变更摘要，命令操作可看日志 |
| **执行感知** | 执行中有微妙动效（旋转图标），完成后静默折叠 |

### 1.3 参考产品

| 产品 | 做法 |
|------|------|
| Claude Code (CLI) | 工具调用显示为 collapsible section，`▸` 折叠 / `▾` 展开 |
| Cursor | 工具调用显示为 chat 内的 inline block，点击展开 |
| Windsurf | 工具步骤嵌入 assistant 消息，折叠态只显示工具名+状态 |

---

## 2. 交互设计

### 2.1 三态模型

每个工具调用经历三个视觉状态：

```
[1] 执行中          [2] 完成后（折叠）      [3] 展开查看
                                                      
⟳ read_file         ✓ read_file            ▾ read_file
  Main.kt...          Main.kt    8ms         Main.kt    8ms
                                         ┌──────────────────────┐
                                         │ FILE: Main.kt        │
                                         │ LINES: 1-20          │
                                         │   1→fun main() {     │
                                         │   2→    println()    │
                                         └──────────────────────┘
```

| 状态 | 视觉 | 交互 |
|------|------|------|
| **Running** | 图标旋转 + 工具名 + 目标摘要 + `...` | 不可点击展开 |
| **Completed（折叠）** | 图标 ✓ + 工具名 + 目标摘要 + 耗时 | 点击展开 |
| **Completed（展开）** | 同上 + 输出内容区 | 点击收起 |

### 2.2 工具类型区分

不同工具类型使用不同的图标和摘要策略：

| 工具类型 | 图标 | 折叠态摘要 | 展开内容 |
|----------|------|-----------|---------|
| `read_file` | 📖 | `read_file {filename}` | 文件内容（带行号） |
| `list_files` | 📂 | `list_files {dirname}` | 目录列表 |
| `grep_files` | 🔍 | `grep_files "{pattern}"` | 匹配行列表 |
| `edit_file` | ✏️ | `edit_file {filename} (+N/-M)` | 替换前后 diff + 匹配信息 |
| `write_file` | 📝 | `write_file {filename}` (新建/覆盖) | 文件内容摘要 |
| `run_command` | ⌨️ | `run_command {command摘要}` | stdout + stderr + exit code |

### 2.3 完整消息流示例

```
┌─ Assistant ──────────────────────────────────────────┐
│                                                       │
│  让我看看项目结构和关键文件。                            │
│                                                       │
│  ┌─ Tool Steps ──────────────────────────────────┐    │
│  │ ⟳ list_files .              ...              │    │ ← 执行中
│  │ ✓ read_file build.gradle    8ms         [▸]  │    │ ← 折叠
│  │ ✓ read_file Main.kt        12ms         [▸]  │    │
│  │ ✓ grep_files "fun main"     3ms         [▸]  │    │
│  └───────────────────────────────────────────────┘    │
│                                                       │
│  基于项目结构分析如下：                                  │
│  这是一个 Kotlin 项目，入口在 Main.kt...                │
│                                                       │
└───────────────────────────────────────────────────────┘
```

点击展开 `read_file Main.kt` 后：

```
│  ┌─ Tool Steps ──────────────────────────────────┐    │
│  │ ⟳ list_files .              ...              │    │
│  │ ✓ read_file build.gradle    8ms         [▸]  │    │
│  │ ▾ read_file Main.kt        12ms         [▾]  │    │ ← 展开
│  │ ┌────────────────────────────────────────┐    │    │
│  │ │ FILE: Main.kt  LINES: 1-10/85          │    │    │
│  │ │   1→package com.example                │    │    │
│  │ │   2→                                   │    │    │
│  │ │   3→fun main() {                       │    │    │
│  │ │   4→    println("Hello")               │    │    │
│  │ │   5→}                                  │    │    │
│  │ └────────────────────────────────────────┘    │    │
│  │ ✓ grep_files "fun main"     3ms         [▸]  │    │
│  └───────────────────────────────────────────────┘    │
```

### 2.4 与文件变更通知的配合

写入类工具完成后，额外发送 `file_change_auto` 事件，前端在步骤条下方展示内联提示：

```
│  ✓ ✏️ edit_file ChatService.kt (+12/-3)    [▸]  │
│  ✓ 📝 write_file HelloService.kt (新建)    [▸]  │
│                                                   │
│  📝 ChatService.kt (+12/-3)    [Open in Editor]  │ ← 内联变更提示
│  📝 HelloService.kt (新建)     [Open in Editor]  │
```

---

## 3. 组件设计

### 3.1 组件树

```
MessageBubble (assistant)
  ├── MarkdownContent (AI 回复文本)
  ├── ToolStepsBar (工具步骤容器)
  │   ├── ToolStepRow (单个工具步骤)
  │   │   ├── StatusIcon (⟳ / ✓ / ✗)
  │   │   ├── ToolIcon (📖 / ✏️ / ⌨️ ...)
  │   │   ├── Summary (工具名 + 目标摘要)
  │   │   ├── Duration (耗时)
  │   │   └── ExpandToggle ([▸] / [▾])
  │   └── ToolStepOutput (展开后的输出内容，条件渲染)
  └── FileChangeInline[] (写入类工具的变更提示)
```

### 3.2 ToolStepRow Props

```typescript
interface ToolStepRowProps {
  // 基本信息
  toolName: string          // "read_file" | "edit_file" | ...
  targetSummary: string     // "Main.kt" | "grep 'fun main'" | "git status"
  status: 'running' | 'completed' | 'failed'
  durationMs?: number       // 执行耗时（完成后有值）
  
  // 展开内容
  output?: string           // 工具完整输出
  diffStats?: {             // 写入类工具有值
    added: number
    removed: number
  }
  
  // 交互状态
  expanded: boolean
  onToggleExpand: () => void
}
```

### 3.3 ToolStepsBar Props

```typescript
interface ToolStepsBarProps {
  steps: ToolStepInfo[]
}

interface ToolStepInfo {
  id: string                // requestId
  toolName: string
  targetSummary: string
  status: 'running' | 'completed' | 'failed'
  durationMs?: number
  output?: string
  diffStats?: { added: number; removed: number }
  expanded?: boolean
}
```

---

## 4. 数据模型

### 4.1 Message 类型扩展

在现有 `Message` 接口中新增 `toolSteps` 字段：

```typescript
interface Message {
  id: string
  role: 'user' | 'assistant' | 'execution'
  content: string
  isStreaming?: boolean
  execution?: ExecutionCardData        // 旧：兼容
  toolSteps?: ToolStepInfo[]           // 新：折叠式步骤条
}
```

### 4.2 与现有 AppState 的关系

```typescript
// 工具步骤绑定在 assistant message 上，不是独立状态
// 多个 tool_call 的结果汇总到一个 message.toolSteps 数组
interface AppState {
  // ...
  messages: Message[]
  // 不需要单独的 toolSteps 状态 — 它是 message 的一部分
}
```

### 4.3 后端事件协议

Dispatcher 在工具执行过程中发送以下事件（复用现有 `onEvent` 通道）：

| 事件 type | Payload | 时机 |
|-----------|---------|------|
| `tool_step_start` | `{ msgId, requestId, toolName, summary }` | 工具开始执行 |
| `tool_step_end` | `{ msgId, requestId, status, output, durationMs, diffStats? }` | 工具执行完成 |

**与现有事件的关系**：
- `tool_step_start/end` 替代（统一路径下的）`execution_card` + `execution_status`
- 旧路径（`unifiedToolsEnabled=false`）继续使用 `execution_card` + `execution_status`
- `approval_request` 事件不变 — 审批仍是独立弹框

---

## 5. 事件流

### 5.1 完整时序

```
[1] AI 返回 finish_reason: "tool_calls"
      │
      ▼
[2] ChatService: handleToolCallCompleteUnified()
      ├─ 创建 assistant message（content = responseBuffer, toolSteps = []）
      ├─ 发送 tool_step_start 给每个工具
      │
      ▼
[3] ToolCallDispatcher.dispatchAll()
      ├─ dispatch(tool_1)
      │   ├─ Bridge: tool_step_start { msgId, req1, "read_file", "Main.kt" }
      │   ├─ executor.execute()
      │   └─ Bridge: tool_step_end { msgId, req1, ok, output, 8ms }
      │
      ├─ dispatch(tool_2) (并发)
      │   ├─ Bridge: tool_step_start { msgId, req2, "grep_files", "'main'" }
      │   ├─ executor.execute()
      │   └─ Bridge: tool_step_end { msgId, req2, ok, output, 3ms }
      │
      ▼
[4] 全部完成后 → sendMessageInternal() 开始下一轮
```

### 5.2 前端 eventReducer 处理

```typescript
case 'tool_step_start':
  return {
    ...state,
    messages: state.messages.map(m => {
      if (m.id !== payload.msgId) return m
      return {
        ...m,
        toolSteps: [
          ...(m.toolSteps || []),
          {
            id: payload.requestId,
            toolName: payload.toolName,
            targetSummary: payload.summary,
            status: 'running',
          }
        ]
      }
    })
  }

case 'tool_step_end':
  return {
    ...state,
    messages: state.messages.map(m => {
      if (m.id !== payload.msgId) return m
      return {
        ...m,
        toolSteps: (m.toolSteps || []).map(step =>
          step.id === payload.requestId
            ? {
                ...step,
                status: payload.status ? 'completed' : 'failed',
                output: payload.output,
                durationMs: payload.durationMs,
                diffStats: payload.diffStats,
              }
            : step
        )
      }
    })
  }
```

---

## 6. 视觉规范

### 6.1 折叠态

```
┌─────────────────────────────────────────────┐
│ ✓ 📖 read_file Main.kt              8ms  ▸ │  ← 整行可点击
└─────────────────────────────────────────────┘
```

- 高度：单行（约 28px）
- 背景：`rgba(255,255,255,0.04)`（dark mode），与 assistant 消息有微弱区分
- 圆角：4px
- 字号：12px（比正文小一号）
- ✓ 图标颜色：绿色（`#52c41a`），✗ 红色（`#ff4d4f`）
- 耗时：右对齐，灰色（`#888`）
- ▸ 箭头：右对齐，hover 时高亮

### 6.2 展开态

```
┌─────────────────────────────────────────────┐
│ ✓ 📖 read_file Main.kt              8ms  ▾ │  ← 点击收起
├─────────────────────────────────────────────┤
│ FILE: Main.kt                               │
│ LINES: 1-20/85                              │
│   1→package com.example                     │
│   2→                                        │
│   3→fun main() {                            │
│   4→    println("Hello")                    │
│   5→}                                       │
└─────────────────────────────────────────────┘
```

- 展开内容区：单色背景（`rgba(0,0,0,0.15)` dark mode）
- 等宽字体显示输出内容
- 最大高度：300px，超出则滚动
- 输出内容截断到 2000 字符，超出显示 "... truncated，点击查看完整输出"

### 6.3 执行中动效

- 状态图标使用 CSS `animation: spin` 旋转（⟳）
- 旋转速度：1.5s 一圈
- 摘要文字末尾有 `...` 表示进行中
- 背景色：微弱蓝色调（`rgba(82,196,26,0.04)`）

### 6.4 失败态

```
┌─────────────────────────────────────────────┐
│ ✗ ⌨️ run_command rm -rf /          DENIED ▸ │
└─────────────────────────────────────────────┘
```

- ✗ 图标红色
- 摘要中显示失败原因关键词（`DENIED` / `BLOCKED` / `FAILED`）
- 展开后显示完整错误信息

---

## 7. 实现计划

### 7.1 后端改动

| 步骤 | 文件 | 内容 |
|------|------|------|
| 1 | `BridgeHandler.kt` | 新增 `notifyToolStepStart()` 和 `notifyToolStepEnd()` 方法 |
| 2 | `ToolCallDispatcher.kt` | 在 `dispatch()` 中发送 `tool_step_start/end` 事件 |
| 3 | `ChatService.kt` | 在 `handleToolCallCompleteUnified()` 中传入 msgId 给 dispatcher |

### 7.2 前端改动

| 步骤 | 文件 | 内容 |
|------|------|------|
| 4 | `types/bridge.d.ts` | 新增 `tool_step_start` / `tool_step_end` 事件类型 |
| 5 | `eventReducer.ts` | 新增两个 case，更新 message.toolSteps |
| 6 | `components/ToolStepRow.tsx` | **新建** — 单个工具步骤组件 |
| 7 | `components/ToolStepsBar.tsx` | **新建** — 步骤容器组件 |
| 8 | `components/MessageBubble.tsx` | 在 assistant 消息中渲染 `ToolStepsBar` |
| 9 | `components/ExecutionCard.tsx` | 添加 `toolName` 字段支持（旧路径兼容） |

### 7.3 迁移策略

| 路径 | 行为 |
|------|------|
| `unifiedToolsEnabled=true` | 使用 `tool_step_start/end` + `ToolStepsBar`（新） |
| `unifiedToolsEnabled=false` | 使用 `execution_card/status` + `ExecutionCard`（旧，不动） |

两套 UI 共存，feature flag 切换。验证通过后移除旧路径。

---

## 8. 验收标准

### 功能验收

- [ ] `read_file` 3 次并发调用 → 步骤条显示 3 行，全部折叠态
- [ ] 点击任意步骤行 → 展开显示文件内容，再点收起
- [ ] 执行中步骤图标旋转，完成后自动折叠并显示耗时
- [ ] `edit_file` 步骤显示 `(+N/-M)` 变更摘要
- [ ] `run_command` 失败时显示红色 ✗ 和失败原因
- [ ] 展开内容超过 300px 时可滚动
- [ ] 步骤条嵌入 assistant 消息内部，不生成独立气泡
- [ ] `unifiedToolsEnabled=false` 时回退到旧 ExecutionCard

### 视觉验收

- [ ] 折叠态高度一致，不因内容长度变化
- [ ] 旋转动画流畅，不影响其他组件渲染
- [ ] Dark/Light 主题下均可正常显示
- [ ] 展开收起有 150ms 过渡动画

### 回归验收

- [ ] 旧路径 `ExecutionCard` 功能不受影响
- [ ] 审批弹框 (`ApprovalDialog`) 功能不受影响
- [ ] Session 持久化不受影响
