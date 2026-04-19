# Phase 1：统一事件通道设计

**日期：** 2026-04-19
**状态：** Draft
**影响范围：** BridgeHandler, useBridge, App.tsx, eventReducer (新增)
**前置依赖：** 无
**后续依赖：** Phase 2（前端消息分组）基于本阶段的统一事件通道

---

## 背景

CodePlanGUI 使用 Kotlin 后端通过 JBCefJSQuery 与 React 前端通信。当前在 `window.__bridge` 上注册了 15 个独立回调（`onStart`、`onToken`、`onEnd`、`onError`、`onStructuredError`、`onContextFile`、`onTheme`、`onStatus`、`onExecutionCard`、`onApprovalRequest`、`onExecutionStatus`、`onRestoreMessages`、`onLog`、`onContinuation`、`onRemoveMessage`）。每个回调独立通过 `useState` 更新 React 状态。

**问题：**
1. **状态逻辑分散**——15 个 `useCallback` 钩子分布在 App.tsx 中，难以推理状态转换。
2. **可扩展性差**——添加 reasoning 支持或多轮工具调用需要更多回调和更多 hack。
3. **调试困难**——事件流通过 15 个独立回调进入，无法统一追踪和日志记录。

**本阶段目标：** 用单一 `onEvent(type, payload)` 通道替代 15 个回调，引入 `eventReducer` 集中管理状态转换。行为完全不变——这是纯重构。

---

## 1.1 事件类型协议

所有事件使用统一格式：

```
StreamEvent = { type: string, payload: JSON 字符串 }
```

事件类型与现有回调一一对应：

| 事件类型 | Payload | 替代 |
|---|---|---|
| `start` | `{msgId: string}` | `onStart` |
| `token` | `{text: string}` | `onToken` |
| `end` | `{msgId: string}` | `onEnd` |
| `round_end` | `{msgId: string}` | *(新增)* — 轮次以 tool_calls 结束但非最终结束 |
| `error` | `{message: string}` | `onError` |
| `structured_error` | `{type: string, message: string, action?: string}` | `onStructuredError` |
| `status` | `{providerName, model, connectionState, contextFile}` | `onStatus` |
| `context_file` | `{fileName: string}` | `onContextFile` |
| `theme` | `{mode: "dark" \| "light"}` | `onTheme` |
| `execution_card` | `{requestId, command, description}` | `onExecutionCard` |
| `approval_request` | `{requestId, command, description}` | `onApprovalRequest` |
| `execution_status` | `{requestId, status, result}` | `onExecutionStatus` |
| `log` | `{requestId, line, type}` | `onLog` |
| `continuation` | `{current: number, max: number}` | `onContinuation` |
| `remove_message` | `{msgId: string}` | `onRemoveMessage` |
| `restore_messages` | `{messages: string}`（JSON 编码的数组） | `onRestoreMessages` |

注意：`round_end` 在 Phase 1 作为空操作占位符引入，使 Phase 2 可以消费它而无需再次修改 Kotlin。Phase 1 中后端不会发送它。

---

## 1.2 Kotlin 端：BridgeHandler 改动

**文件：** `BridgeHandler.kt`

新增一个核心方法，生成 JS 字符串但不执行（调用方决定调度策略）：

```kotlin
private fun buildEventJS(type: String, payload: Map<String, Any?>): String {
    // 使用项目现有的 kotlinx.serialization 进行安全的 JSON 编码
    // 调用方传入 Map（而非预编码字符串）以避免双重编码问题
    val jsonPayload = json.encodeToString(payload)
    return "window.__bridge.onEvent('$type', $jsonPayload)"
}
```

每个现有 `notifyXxx` 方法改为内部调用 `buildEventJS`，但保持相同的公开签名。调度策略（立即 vs 批量）不变：

| 方法 | 策略 | 调度方式 |
|---|---|---|
| `notifyStart` | `flushAndPush`（立即，先刷空待处理批次） | `flushAndPush(buildEventJS("start", ...))` |
| `notifyToken` | `enqueueJS`（16ms 批量） | `enqueueJS(buildEventJS("token", ...))` |
| `notifyEnd` | `flushAndPush` | `flushAndPush(buildEventJS("end", ...))` |
| `notifyError` | `flushAndPush` | `flushAndPush(buildEventJS("error", ...))` |
| `notifyStructuredError` | `flushAndPush` | `flushAndPush(buildEventJS("structured_error", ...))` |
| `notifyExecutionCard` | `flushAndPush` | `flushAndPush(buildEventJS("execution_card", ...))` |
| `notifyApprovalRequest` | `flushAndPush` | `flushAndPush(buildEventJS("approval_request", ...))` |
| `notifyExecutionStatus` | `flushAndPush` | `flushAndPush(buildEventJS("execution_status", ...))` |
| `notifyLog` | `enqueueJS` | `enqueueJS(buildEventJS("log", ...))` |
| `notifyContinuation` | `pushJS`（立即，不刷空） | `pushJS(buildEventJS("continuation", ...))` |
| `notifyRemoveMessage` | `flushAndPush` | `flushAndPush(buildEventJS("remove_message", ...))` |
| `notifyRestoreMessages` | `flushAndPush` | `flushAndPush(buildEventJS("restore_messages", ...))` |
| `notifyStatus` | `flushAndPush` | `flushAndPush(buildEventJS("status", ...))` |
| `notifyContextFile` | `pushJS`（立即，不刷空） | `pushJS(buildEventJS("context_file", ...))` |
| `notifyTheme` | `pushJS`（立即，不刷空） | `pushJS(buildEventJS("theme", ...))` |

注意：`pushJS` 立即发送但不刷空待处理批次。这是 `notifyContextFile` 和 `notifyTheme` 的当前行为，保持不变。这些事件是非关键性的，不需要相对于待处理 token 保持顺序。

**为 Phase 2 准备的新方法：**

```kotlin
fun notifyRoundEnd(msgId: String) =
    flushAndPush(buildEventJS("round_end", mapOf("msgId" to msgId)))
```

此方法在 Phase 1 添加但不调用。Phase 2 激活它。

**重要实现细节：** `buildEventJS` 仅生成 JS 字符串，不执行。调用方（`notifyXxx`）决定将其传给 `enqueueJS`、`flushAndPush` 还是 `pushJS`，与现有方式一致。这保留了当前的顺序保证：结构事件总是在执行前刷空待处理 token。

**`ChatService.kt`：零改动。** 继续调用 `bridgeHandler?.notifyStart(msgId)` 等。

---

## 1.3 前端：Bridge 接口变更

**文件：** `types/bridge.d.ts`

```typescript
interface Bridge {
  // 单一事件通道（Kotlin → JS）
  onEvent: (type: string, payloadJson: string) => void

  // 动作方法（JS → Kotlin）— 不变
  sendMessage: (text: string, includeContext: boolean) => void
  approvalResponse: (requestId: string, action: string, addToWhitelist?: boolean) => void
  cancelStream: () => void
  newChat: () => void
  openSettings: () => void
  debugLog: (message: string) => void
  frontendReady: () => void
}
```

---

## 1.4 前端：useBridge Hook

**文件：** `useBridge.ts`

用单一 `onEvent` 处理器替代 15 个独立回调注册。现有的 `bridge_ready` 生命周期和动作方法注入模式保持不变——仅事件接收端变更。

```typescript
type EventHandler = (type: string, payload: any) => void

export function useBridge(onEvent: EventHandler): boolean {
  const [bridgeReady, setBridgeReady] = useState(false)
  const frontendReadySentRef = useRef(false)
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  const setup = useCallback(() => {
    // 不要完全覆盖 window.__bridge。
    // BridgeHandler 通过 JBCefJSQuery 注入动作方法（sendMessage、approvalResponse 等）。
    // 我们仅在现有对象上设置 onEvent 处理器，
    // 或在 BridgeHandler 尚未注入时创建占位对象。
    if (!window.__bridge) {
      window.__bridge = {} as any
    }
    window.__bridge.onEvent = (type: string, payloadJson: string) => {
      try {
        const payload = JSON.parse(payloadJson)
        onEventRef.current(type, payload)
      } catch (e) {
        console.warn(`[CodePlanGUI] Failed to parse event payload: type=${type}`, e)
      }
    }
    setBridgeReady(true)

    // 如果尚未发送，发送 frontendReady 信号
    if (!frontendReadySentRef.current && window.__bridge.frontendReady) {
      frontendReadySentRef.current = true
      window.__bridge.frontendReady()
    }
  }, [])

  useEffect(() => {
    setup()
    // 监听 bridge_ready 事件（BridgeHandler JS 注入后触发）
    document.addEventListener("bridge_ready", setup)
    return () => document.removeEventListener("bridge_ready", setup)
  }, [setup])

  return bridgeReady
}
```

与当前实现的关键区别：
- 15 个独立回调参数（`onStart`、`onToken` 等）被单一 `onEvent` 处理器替代。
- `onEventRef` 模式确保始终调用最新的处理器而无需每次渲染重新注册。
- `bridge_ready` 事件监听器、`frontendReady` 信号和 `bridgeReady` 状态全部保留。

---

## 1.5 前端：eventReducer

**文件：** 新文件 `eventReducer.ts`（从 App.tsx 逻辑中提取）

```typescript
interface AppState {
  messages: Message[]
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: "dark" | "light"
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
}

export function eventReducer(state: AppState, type: string, payload: any): AppState {
  switch (type) {
    case "start":
      return {
        ...state,
        isLoading: true,
        error: null,
        messages: [...state.messages, { id: payload.msgId, role: "assistant", content: "", isStreaming: true }],
      }

    case "token":
      return {
        ...state,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, content: m.content + payload.text } : m
        ),
      }

    case "end":
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
      }

    case "round_end":
      // Phase 1：空操作，Phase 2 激活
      return state

    case "error":
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
        error: { type: "runtime", message: payload.message },
      }

    case "structured_error":
      return {
        ...state,
        isLoading: false,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
        error: { type: payload.type, message: payload.message, action: payload.action },
      }

    case "execution_card":
      return {
        ...state,
        messages: [...state.messages, {
          id: payload.requestId,
          role: "execution" as const,
          content: "",
          execution: { requestId: payload.requestId, command: payload.command, status: "running" as ExecutionStatus },
        }],
      }

    case "approval_request":
      return {
        ...state,
        approvalRequestId: payload.requestId,
        approvalCommand: payload.command,
        approvalDescription: payload.description,
        approvalOpen: true,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, status: "waiting" as ExecutionStatus } }
            : m
        ),
      }

    case "execution_status": {
      const result = parseExecutionResultPayload(payload.result)
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, status: payload.status as ExecutionStatus, result } }
            : m
        ),
      }
    }

    case "log":
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, logs: [...(m.execution?.logs || []), { text: payload.line, type: payload.type as LogEntry["type"] }] } }
            : m
        ),
      }

    case "continuation":
      return { ...state, continuationInfo: { current: payload.current, max: payload.max } }

    case "remove_message":
      return { ...state, messages: state.messages.filter(m => m.id !== payload.msgId) }

    case "restore_messages":
      // payload.messages 是 JSON 编码的字符串（后端双重编码）。
      // useBridge 中的外层 JSON.parse 已解码事件 payload，
      // 因此 payload.messages 是需要第二次解析的字符串。
      return { ...state, messages: restoreFlatMessages(JSON.parse(payload.messages)) }

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
```

---

## 1.6 前端：App.tsx 改动

用单一处理器替代 15 个 `useCallback`：

```typescript
const emitFrontendDebugLog = useCallback((message: string) => {
  window.__bridge?.debugLog(message)
}, [])

const handleEvent = useCallback((type: string, payload: any) => {
  // 调试日志副作用（从原始回调中保留）
  if (type === "execution_card") {
    emitFrontendDebugLog(`[approval-ui] received execution card requestId=${payload.requestId} command=${payload.command}`)
  } else if (type === "approval_request") {
    emitFrontendDebugLog(`[approval-ui] received approval request requestId=${payload.requestId}`)
  } else if (type === "execution_status") {
    emitFrontendDebugLog(`[approval-ui] received execution status requestId=${payload.requestId} status=${payload.status}`)
  }

  setState(prev => eventReducer(prev, type, payload))
}, [emitFrontendDebugLog])
```

`useBridge` 接收 `handleEvent` 而非 15 个独立回调。

App.tsx 中所有其他逻辑（handleSend、handleKeyDown、handleNewChat、handleApprovalAllow/Deny、handleCancel）保持不变——它们使用 `window.__bridge?.sendMessage()` 等动作方法，不是事件回调。

---

## 1.7 改动总结

| 文件 | 改动 |
|---|---|
| `BridgeHandler.kt` | 内部重写 `notifyXxx` 方法使用 `buildEventJS`；新增 `notifyRoundEnd`（未使用） |
| `useBridge.ts` | 15 个回调参数替换为单一 `EventHandler`；保留 bridge_ready 生命周期 |
| `App.tsx` | 15 个 `useCallback` 替换为单一 `handleEvent` + `setState` reducer；保留调试日志副作用 |
| 新增：`eventReducer.ts` | 纯函数，将事件映射到状态变更 |
| `types/bridge.d.ts` | 更新 `Bridge` 接口 |
| `ChatService.kt` | **零改动** |

---

## 部署清单

1. 在 `BridgeHandler.kt` 中实现 `buildEventJS`
2. 重写所有 `notifyXxx` 内部实现
3. 新增 `notifyRoundEnd`（未使用，为 Phase 2 预留）
4. 创建 `eventReducer.ts`
5. 更新 `useBridge.ts` 为单一 `onEvent`
6. 更新 `App.tsx` 使用 `eventReducer`
7. 更新 `types/bridge.d.ts`
8. **测试验证：** 所有现有流程（单轮对话、工具调用、审批、续写、恢复）行为必须与改动前完全一致

## 回滚安全性

本阶段是纯透明重构——所有外部行为不变。如有问题可直接回滚，无数据迁移风险。
