# Phase 2：前端消息分组设计

**日期：** 2026-04-19
**状态：** Draft
**影响范围：** App.tsx, groupReducer (新增), AssistantGroup.tsx (新增), AssistantMarkdown.tsx (新增), MessageBubble.tsx, ChatService.kt
**前置依赖：** Phase 1（统一事件通道）已完成

---

## 背景

Phase 1 已将 15 个独立回调统一为单一 `onEvent(type, payload)` 通道，状态变更集中在 `eventReducer` 中处理。当前前端仍使用扁平 `Message[]` 数组渲染消息。

**现有问题：**
1. **排序 hack**——`ChatService.kt` 中使用了 lazy `notifyStart` + `notifyRemoveMessage` + `bridgeNotifiedStart` 集合来保证执行卡片出现在最终助手文本气泡之前。
2. **执行卡片散落**——多轮工具调用时，多张执行卡片和文本气泡平级排列，无法折叠，可能占满屏幕。
3. **加载指示器复杂**——需要两段独立条件判断加载状态。

**本阶段目标：** 引入 `MessageGroup[]` 替代 `Message[]` 渲染，将执行卡片和最终回答归入同一 assistant 组，消除后端排序 hack。

---

## 2.1 数据模型

```typescript
type MessageGroup =
  | { type: "human"; id: string; message: { id: string; content: string } }
  | { type: "assistant"; id: string; children: AssistantChild[]; isStreaming: boolean }

type AssistantChild =
  | { kind: "execution"; data: ExecutionCardData }
  | { kind: "text"; id: string; content: string; isStreaming: boolean }
```

关键属性：
- `MessageGroup` 是**纯前端概念**。后端和持久化层不感知。
- 每个组有自己的 `id`（assistant 组从 `msgId` 派生）。
- `assistant` 组的 `children` 是有序列表——执行卡片和文本按事件到达顺序排列。
- 单个 assistant 组跨越一次用户回合的所有 API 轮次（包括多轮工具调用和自动续写）。
- **msgId 不变量**：`ChatService.kt` 在单个用户回合的所有 API 轮次中使用相同的 `msgId`（`activeMessageId`）。Round 1 的 `start(msgId)` 和 Round 2 的 `start(msgId)` 携带相同的 `msgId`。组的 `id` 字段使用此 `msgId`，`start` 处理器通过检查最后一个组是否仍在流式传输来检测续写轮次。

---

## 2.2 事件 → 组映射：groupReducer

**文件：** `groupReducer.ts`（替代 Phase 1 的 `eventReducer.ts`）

```typescript
interface GroupState {
  groups: MessageGroup[]
  // 非消息状态保持不变
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: "dark" | "light"
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
  // 跟踪当前轮次的文本子项，用于 round_end 时可能的丢弃
  currentRoundTextIndex: number | null
}

export function groupReducer(state: GroupState, type: string, payload: any): GroupState {
  switch (type) {
    case "start": {
      // 创建新的 assistant 组（如果是续写轮次则复用）
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type === "assistant" && lastGroup.isStreaming) {
        // 这是续写/工具调用轮次——复用现有组
        return { ...state, isLoading: true, error: null, currentRoundTextIndex: null }
      }
      return {
        ...state,
        isLoading: true,
        error: null,
        currentRoundTextIndex: null,
        groups: [...state.groups, { type: "assistant", id: payload.msgId, children: [], isStreaming: true }],
      }
    }

    case "token": {
      // 查找或创建当前轮次的文本子项
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type !== "assistant") return state

      const group = lastGroup as AssistantGroup
      let newTextIndex = state.currentRoundTextIndex

      if (newTextIndex !== null && group.children[newTextIndex]?.kind === "text") {
        // 追加到现有文本子项
        const updated = [...group.children]
        updated[newTextIndex] = {
          ...updated[newTextIndex],
          content: (updated[newTextIndex] as TextChild).content + payload.text,
        }
        const groups = [...state.groups]
        groups[groups.length - 1] = { ...group, children: updated }
        return { ...state, groups }
      }

      // 创建新文本子项
      const textChild: AssistantChild = { kind: "text", id: `text-${Date.now()}`, content: payload.text, isStreaming: true }
      newTextIndex = group.children.length  // 新追加子项的索引
      const groups = [...state.groups]
      groups[groups.length - 1] = { ...group, children: [...group.children, textChild] }
      return { ...state, groups, currentRoundTextIndex: newTextIndex }
    }

    case "execution_card": {
      return updateLastAssistant(state, group => ({
        ...group,
        children: [...group.children, {
          kind: "execution",
          data: { requestId: payload.requestId, command: payload.command, status: "running" as ExecutionStatus },
        }],
      }))
    }

    case "log": {
      return updateLastAssistant(state, group => ({
        ...group,
        children: group.children.map(child =>
          child.kind === "execution" && child.data.requestId === payload.requestId
            ? { ...child, data: { ...child.data, logs: [...(child.data.logs || []), { text: payload.line, type: payload.type }] } }
            : child
        ),
      }))
    }

    case "execution_status": {
      const result = parseExecutionResultPayload(payload.result)
      return updateLastAssistant(state, group => ({
        ...group,
        children: group.children.map(child =>
          child.kind === "execution" && child.data.requestId === payload.requestId
            ? { ...child, data: { ...child.data, status: payload.status, result } }
            : child
        ),
      }))
    }

    case "approval_request": {
      return {
        ...updateLastAssistant(state, group => ({
          ...group,
          children: group.children.map(child =>
            child.kind === "execution" && child.data.requestId === payload.requestId
              ? { ...child, data: { ...child.data, status: "waiting" } }
              : child
          ),
        })),
        approvalRequestId: payload.requestId,
        approvalCommand: payload.command,
        approvalDescription: payload.description,
        approvalOpen: true,
      }
    }

    case "round_end": {
      // 丢弃当前轮次的文本子项（tool_calls 之前的中间 token）
      if (state.currentRoundTextIndex !== null) {
        return updateLastAssistant({
          ...state,
          currentRoundTextIndex: null,
        }, group => ({
          ...group,
          children: group.children.filter((_, i) => i !== state.currentRoundTextIndex),
        }))
      }
      return state
    }

    case "end": {
      return updateLastAssistant({
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
      }, group => ({
        ...group,
        isStreaming: false,
        children: group.children.map(child =>
          child.kind === "text" ? { ...child, isStreaming: false } : child
        ),
      }))
    }

    case "error": {
      const groups = state.groups.map(g => {
        if (g.type === "assistant" && g.isStreaming) {
          return {
            ...g,
            isStreaming: false,
            children: g.children.map(child =>
              child.kind === "text" ? { ...child, isStreaming: false } : child
            ),
          }
        }
        return g
      })
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
        groups,
        error: { type: "runtime", message: payload.message },
      }
    }

    case "structured_error": {
      const groups = state.groups.map(g => {
        if (g.type === "assistant" && g.isStreaming) {
          return {
            ...g,
            isStreaming: false,
            children: g.children.map(child =>
              child.kind === "text" ? { ...child, isStreaming: false } : child
            ),
          }
        }
        return g
      })
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
        groups,
        error: { type: payload.type, message: payload.message, action: payload.action },
      }
    }

    case "continuation":
      return { ...state, continuationInfo: { current: payload.current, max: payload.max } }

    case "restore_messages":
      return { ...state, groups: restoreToGroups(JSON.parse(payload.messages)) }

    // 非消息事件直接透传
    case "status":
      return { ...state, status: applyBridgeStatus(state.status, payload) }
    case "context_file":
      return { ...state, status: applyContextFile(state.status, payload.fileName) }
    case "theme":
      return { ...state, themeMode: payload.mode }

    default:
      return state
  }
}

// 辅助函数：更新数组中最后一个 assistant 组
function updateLastAssistant(state: GroupState, updater: (g: AssistantGroup) => AssistantGroup, extraState?: Partial<GroupState>): GroupState {
  const lastIdx = findLastAssistantIndex(state.groups)
  if (lastIdx === -1) return state
  const groups = [...state.groups]
  groups[lastIdx] = updater(groups[lastIdx] as AssistantGroup)
  return { ...state, ...extraState, groups }
}
```

---

## 2.3 轮次 Token 丢弃行为

当轮次以 `round_end`（tool_calls）结束时，该轮次创建的任何文本子项都会被丢弃。Round 1 的 token 被丢弃，仅显示执行卡片 + Round 2 的最终回答。

典型工具调用回合的事件序列：

```
用户发送消息
  → 前端：创建 human 组

Round 1:
  start(msgId)           → 创建 assistant 组（或复用）
  token("我来帮你...")    → 追加文本子项（由 currentRoundTextIndex 跟踪）
  execution_card(cmd1)   → 追加执行子项
  execution_status(done) → 更新执行子项
  round_end(msgId)       → 丢弃文本子项，保留执行子项

Round 2:
  start(msgId)           → 复用现有 assistant 组（isStreaming 仍为 true）
  token("这是结果...")    → 追加新文本子项
  end(msgId)             → 结束组

结果：assistant 组 children = [execution_card_1, text("这是结果...")]
```

---

## 2.4 后端改动

**文件：** `ChatService.kt`

**移除：**
- `bridgeNotifiedStart` 可变集合——不再需要
- `onFinishReason` 中的 `notifyRemoveMessage` 调用（第 477 行）——不再需要
- `onToken` 中的 lazy `notifyStart` 逻辑（第 422-425 行）——`notifyStart` 现在在轮次开始时无条件发送

**新增：**
- 当 `finish_reason = "tool_calls"` 时调用 `notifyRoundEnd(msgId)`（替代 `notifyRemoveMessage` 块）
- 在 `startStreamingRound()` 开头调用 `notifyStart(msgId)`（当前是延迟的）

**简化后的 `onFinishReason("tool_calls")`：**

```kotlin
// 改动前（hack 方式）
if (msgId in bridgeNotifiedStart) {
    bridgeHandler?.notifyRemoveMessage(msgId)
    bridgeNotifiedStart.remove(msgId)
}
scope.launch { handleToolCallComplete(msgId, capturedBuffer) }

// 改动后（干净方式）
bridgeHandler?.notifyRoundEnd(msgId)
scope.launch { handleToolCallComplete(msgId, capturedBuffer) }
```

**简化后的 `onToken`（不再有延迟初始化）：**

```kotlin
onToken = { token ->
    if (activeMessageId == msgId) {
        responseBuffer.append(token)
        // 无延迟 notifyStart 检查——start 已在前面发送
        bridgeHandler?.notifyToken(token)
    }
}
```

**`startStreamingRound()` 现在在顶部发送 `notifyStart`：**

```kotlin
private fun startStreamingRound(msgId: String, request: Request, toolsEnabled: Boolean) {
    bridgeHandler?.notifyStart(msgId)  // 无条件发送
    // ... 其余不变
}
```

**时序考虑：** 当前工具未启用时，`notifyStart` 从 `sendMessage()` 发送（HTTP 请求之前）。移到 `startStreamingRound()` 意味着它稍后触发（请求构建之后）。这是可接受的，因为前端在响应 `start` 时创建 assistant 组，而 `sendMessage()` 和 `startStreamingRound()` 之间的延迟可忽略（无网络往返——仅对象构建）。不会出现可见的空气泡闪烁。

---

## 2.5 渲染

**新文件：** `components/AssistantGroup.tsx`

```tsx
function AssistantGroup({ group }: { group: AssistantGroup }) {
  return (
    <div className="assistant-group">
      {group.children.map(child => {
        if (child.kind === "execution") {
          return <ExecutionCard key={child.data.requestId} data={child.data} />
        }
        return (
          <div key={child.id} className="message-row message-row-assistant">
            <div className="message-bubble message-bubble-assistant">
              <div className="assistant-bubble-header">
                <span className="assistant-bubble-label">assistant</span>
                <CopyButton text={child.content} />
              </div>
              <AssistantMarkdown content={child.content} />
              {child.isStreaming && <span className="stream-cursor" />}
            </div>
          </div>
        )
      })}
    </div>
  )
}
```

`AssistantMarkdown` 从 `MessageBubble.tsx` 中提取当前的 markdown 渲染逻辑（`useEffect` 中的 `marked.parse` + `DOMPurify.sanitize` + 代码块复制按钮）。

**用户消息渲染** 变为更简单的组件，因为 `MessageBubble` 不再需要处理 `role === "execution"`：

```tsx
{groups.map(group => {
  if (group.type === "human") {
    return (
      <div key={group.id} className="message-row message-row-user">
        <div className="message-bubble message-bubble-user">
          <Typography.Text>{group.message.content}</Typography.Text>
        </div>
      </div>
    )
  }
  return <AssistantGroup key={group.id} group={group} />
})}
```

**执行卡片自动折叠：** 已在 `ExecutionCard.tsx` 中实现（`LogOutput` 在 `isStreaming` 从 true 变为 false 时自动折叠）。无需改动。

**加载指示器简化：**

```tsx
// 改动前：两个独立条件
{(continuationInfo) && <spinner />}
{(!continuationInfo && isLoading && !messages.some(m => m.isStreaming) && messages.some(m => m.role === "execution")) && <spinner />}

// 改动后：单一条件
{isLoading && <continuation-spinner-or-generic-spinner />}
```

由于 `isStreaming` 现在在组级别，前端始终知道当前回合是否仍在进行。

---

## 2.6 会话持久化

**后端：** `SessionStore.kt` 不变——仍存储 `Message[]` 扁平数组（仅用户 + 助手消息，无执行数据）。

**前端恢复：** `restoreToGroups()` 将扁平消息转换为分组结构：

```typescript
function restoreToGroups(flat: Array<{id: string; role: string; content: string}>): MessageGroup[] {
  const groups: MessageGroup[] = []
  let currentAssistant: AssistantGroup | null = null

  for (const msg of flat) {
    if (msg.role !== "user" && msg.role !== "assistant") continue
    if (msg.role === "assistant" && msg.content.trim().length === 0) continue

    if (msg.role === "user") {
      if (currentAssistant) { groups.push(currentAssistant); currentAssistant = null }
      groups.push({ type: "human", id: msg.id, message: { id: msg.id, content: msg.content } })
    } else {
      if (!currentAssistant) currentAssistant = { type: "assistant", id: msg.id, children: [], isStreaming: false }
      currentAssistant.children.push({ kind: "text", id: `text-${msg.id}`, content: msg.content, isStreaming: false })
    }
  }
  if (currentAssistant) groups.push(currentAssistant)
  return groups
}
```

---

## 2.7 改动总结

| 文件 | 改动 |
|---|---|
| `ChatService.kt` | 移除 `bridgeNotifiedStart`，移除 `notifyRemoveMessage`，新增 `notifyRoundEnd` 调用，无条件发送 `notifyStart` |
| `BridgeHandler.kt` | 无新改动（Phase 1 已添加 `notifyRoundEnd`） |
| `App.tsx` | 状态从 `messages` → `groups`；用 `groupReducer` 替换 `eventReducer`；简化渲染循环和加载指示器 |
| 新增：`groupReducer.ts` | 替换 `eventReducer.ts`；将事件映射到组状态 |
| 新增：`components/AssistantGroup.tsx` | 渲染 assistant 组（执行卡片 + 文本） |
| 新增：`components/AssistantMarkdown.tsx` | 从 `MessageBubble.tsx` 提取的 markdown 渲染 |
| `MessageBubble.tsx` | 简化为仅用户使用（或移除，内联到 App.tsx 渲染中） |
| `types/bridge.d.ts` | 新增 `MessageGroup` 和 `AssistantChild` 类型 |

---

## 2.8 可移除内容

Phase 2 完成后，以下内容可以清理：

| 内容 | 原因 |
|---|---|
| BridgeHandler 中的 `notifyRemoveMessage` | 不再调用；组处理排序 |
| ChatService 中的 `bridgeNotifiedStart` | 不再需要；`notifyStart` 无条件发送 |
| ChatService `onToken` 中的延迟气泡创建逻辑 | 不再需要 |
| `remove_message` 事件类型 | 不再发送 |
| `Message` 类型中的 `role: "execution"` | 执行卡片存在于 `AssistantChild` 中 |
| `eventReducer.ts` | 被 `groupReducer.ts` 替代 |

---

## 部署清单

1. 创建 `MessageGroup` 和 `AssistantChild` 类型
2. 创建 `groupReducer.ts`（替代 `eventReducer.ts`）
3. 创建 `AssistantGroup.tsx` 和 `AssistantMarkdown.tsx`
4. 更新 `App.tsx` 状态（`messages` → `groups`）和渲染
5. 简化 `ChatService.kt`（移除 hack，新增 `notifyRoundEnd` 调用，无条件 `notifyStart`）
6. **测试验证：**
   - 工具调用流程：执行卡片在最终文本之前，无 `notifyRemoveMessage`
   - 多轮工具调用：正确分组在同一个 assistant 组内
   - 执行卡片自动折叠：运行时展开，完成后自动折叠
   - 会话恢复：历史消息正确转换为分组结构

## 回滚安全性

本阶段的后端改动（移除 hack）和前端改动（分组渲染）可以一起回滚。回滚后恢复 Phase 1 的扁平渲染，统一事件通道不受影响。
