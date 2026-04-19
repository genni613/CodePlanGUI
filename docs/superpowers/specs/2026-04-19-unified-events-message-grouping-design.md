# Unified Event System & Message Grouping Design

**Date:** 2026-04-19
**Status:** Draft
**Scope:** BridgeHandler, useBridge, App.tsx, ChatService, new grouping components

---

## Background

CodePlanGUI uses a Kotlin backend communicating with a React frontend via JBCefJSQuery. Currently, 15 independent callbacks (`onStart`, `onToken`, `onEnd`, `onError`, `onStructuredError`, `onContextFile`, `onTheme`, `onStatus`, `onExecutionCard`, `onApprovalRequest`, `onExecutionStatus`, `onRestoreMessages`, `onLog`, `onContinuation`, `onRemoveMessage`) are registered on `window.__bridge`. Each callback independently updates React state via `useState`. This creates several problems:

1. **State logic scattered** across 15 `useCallback` hooks in App.tsx, making it hard to reason about state transitions.
2. **Ordering hacks** in `ChatService.kt` (lazy `notifyStart` + `notifyRemoveMessage` + `bridgeNotifiedStart` set) to ensure execution cards appear before the final assistant text bubble.
3. **Poor extensibility** — adding reasoning support or multi-round tool calls requires more callbacks and more hacks.

This design introduces two changes in sequence:
- **Phase 1:** Unified event channel — single `onEvent(type, payload)` replacing 15 callbacks.
- **Phase 2:** Frontend message grouping — `MessageGroup[]` replacing flat `Message[]` for rendering, eliminating backend ordering hacks.

---

## Phase 1: Unified Event Channel

### 1.1 Event Type Protocol

All events use a single format:

```
StreamEvent = { type: string, payload: JSON string }
```

Event types map 1:1 from existing callbacks:

| Event Type | Payload | Replaces |
|---|---|---|
| `start` | `{msgId: string}` | `onStart` |
| `token` | `{text: string}` | `onToken` |
| `end` | `{msgId: string}` | `onEnd` |
| `round_end` | `{msgId: string}` | *(new)* — round ended with tool_calls, not final |
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
| `remove_message` | `{msgId: string}` | `onRemoveMessage` — kept for Phase 1, removable in Phase 2 |
| `restore_messages` | `{messages: string}` (JSON-encoded array) | `onRestoreMessages` |

Note: `round_end` is introduced in Phase 1 as a no-op placeholder so Phase 2 can consume it without another Kotlin change. In Phase 1 the backend never emits it.

### 1.2 Kotlin: BridgeHandler Changes

**File:** `BridgeHandler.kt`

Add a core method that produces a JS string (does not execute it — the caller decides dispatch strategy):

```kotlin
private fun buildEventJS(type: String, payload: String): String {
    // Use project's existing kotlinx.serialization for safe JSON encoding
    val jsonPayload = Json.encodeToString(payload)
    return "window.__bridge.onEvent('${type}', $jsonPayload)"
}
```

Each existing `notifyXxx` method changes its internal implementation to call `buildEventJS` while keeping the same public signature. Dispatch strategy (immediate vs batched) remains unchanged:

| Method | Strategy | Dispatches |
|---|---|---|
| `notifyStart` | `flushAndPush` (immediate, flushes pending batch first) | `flushAndPush(buildEventJS("start", ...))` |
| `notifyToken` | `enqueueJS` (batched 16ms) | `enqueueJS(buildEventJS("token", ...))` |
| `notifyEnd` | `flushAndPush` | `flushAndPush(buildEventJS("end", ...))` |
| `notifyError` | `flushAndPush` | `flushAndPush(buildEventJS("error", ...))` |
| `notifyStructuredError` | `flushAndPush` | `flushAndPush(buildEventJS("structured_error", ...))` |
| `notifyExecutionCard` | `flushAndPush` | `flushAndPush(buildEventJS("execution_card", ...))` |
| `notifyApprovalRequest` | `flushAndPush` | `flushAndPush(buildEventJS("approval_request", ...))` |
| `notifyExecutionStatus` | `flushAndPush` | `flushAndPush(buildEventJS("execution_status", ...))` |
| `notifyLog` | `enqueueJS` | `enqueueJS(buildEventJS("log", ...))` |
| `notifyContinuation` | `pushJS` (immediate, no flush) | `pushJS(buildEventJS("continuation", ...))` |
| `notifyRemoveMessage` | `flushAndPush` | `flushAndPush(buildEventJS("remove_message", ...))` |
| `notifyRestoreMessages` | `flushAndPush` | `flushAndPush(buildEventJS("restore_messages", ...))` |
| `notifyStatus` | `flushAndPush` | `flushAndPush(buildEventJS("status", ...))` |
| `notifyContextFile` | `pushJS` (immediate, no flush) | `pushJS(buildEventJS("context_file", ...))` |
| `notifyTheme` | `pushJS` (immediate, no flush) | `pushJS(buildEventJS("theme", ...))` |

Note: `pushJS` sends immediately without flushing the pending batch. This is the current behavior for `notifyContextFile` and `notifyTheme` and is preserved as-is. These events are non-critical and do not need to be ordered relative to pending tokens.

**New method for Phase 2 readiness:**

```kotlin
fun notifyRoundEnd(msgId: String) =
    flushAndPush(buildEventJS("round_end", """{"msgId":"$msgId"}"""))
```

This method is added in Phase 1 but not called. Phase 2 activates it.

**Important implementation detail:** `buildEventJS` only produces the JS string — it does not execute it. The caller (`notifyXxx`) decides whether to pass it to `enqueueJS`, `flushAndPush`, or `pushJS`, exactly as today. This preserves the current ordering guarantee: structural events always flush pending tokens before executing.

**`ChatService.kt`: Zero changes.** It continues calling `bridgeHandler?.notifyStart(msgId)`, etc.

### 1.3 Frontend: Bridge Interface Change

**File:** `types/bridge.d.ts`

```typescript
interface Bridge {
  // Single event channel (Kotlin → JS)
  onEvent: (type: string, payloadJson: string) => void

  // Action methods (JS → Kotlin) — unchanged
  sendMessage: (text: string, includeContext: boolean) => void
  approvalResponse: (requestId: string, action: string, addToWhitelist?: boolean) => void
  cancelStream: () => void
  newChat: () => void
  openSettings: () => void
  debugLog: (message: string) => void
  frontendReady: () => void
}
```

### 1.4 Frontend: useBridge Hook

**File:** `useBridge.ts`

Replace 15 individual callback registrations with a single `onEvent` handler. The existing `bridge_ready` lifecycle and action method injection pattern is preserved — only the event-receiving side changes.

```typescript
type EventHandler = (type: string, payload: any) => void

export function useBridge(onEvent: EventHandler): boolean {
  const [bridgeReady, setBridgeReady] = useState(false)
  const frontendReadySentRef = useRef(false)
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  const setup = useCallback(() => {
    // Do NOT overwrite window.__bridge entirely.
    // BridgeHandler injects action methods (sendMessage, approvalResponse, etc.)
    // via JBCefJSQuery. We only set the onEvent handler on the existing object,
    // or create a stub object if BridgeHandler hasn't injected yet.
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

    // Send frontendReady signal if not already sent
    if (!frontendReadySentRef.current && window.__bridge.frontendReady) {
      frontendReadySentRef.current = true
      window.__bridge.frontendReady()
    }
  }, [])

  useEffect(() => {
    setup()
    // Listen for bridge_ready event (fired by BridgeHandler after JS injection)
    document.addEventListener("bridge_ready", setup)
    return () => document.removeEventListener("bridge_ready", setup)
  }, [setup])

  return bridgeReady
}
```

Key differences from the current implementation:
- 15 individual callback params (`onStart`, `onToken`, etc.) are replaced by a single `onEvent` handler.
- The `onEventRef` pattern ensures the latest handler is always called without re-registering on every render.
- The `bridge_ready` event listener, `frontendReady` signal, and `bridgeReady` state are all preserved.

### 1.5 Frontend: eventReducer

**File:** New file `eventReducer.ts` (extracted from App.tsx logic)

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
      // Phase 1: treat same as end for backward compat
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
      // payload.messages is a JSON-encoded string (double-encoded by the backend).
      // The outer JSON.parse in useBridge already decoded the event payload,
      // so payload.messages is a string that needs a second parse.
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

### 1.6 Frontend: App.tsx Changes

Replace 15 `useCallback` hooks with a single handler:

```typescript
const emitFrontendDebugLog = useCallback((message: string) => {
  window.__bridge?.debugLog(message)
}, [])

const handleEvent = useCallback((type: string, payload: any) => {
  // Debug logging side effects (preserved from original callbacks)
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

`useBridge` receives `handleEvent` instead of 15 separate callbacks.

All other App.tsx logic (handleSend, handleKeyDown, handleNewChat, handleApprovalAllow/Deny, handleCancel) remains unchanged — they use `window.__bridge?.sendMessage()` etc. which are action methods, not event callbacks.

### 1.7 Phase 1 Change Summary

| File | Change |
|---|---|
| `BridgeHandler.kt` | Internal rewrite of `notifyXxx` methods to use `buildEventJS`; add `notifyRoundEnd` |
| `useBridge.ts` | Replace 15 callback params with single `EventHandler`; preserve bridge_ready lifecycle |
| `App.tsx` | Replace 15 `useCallback` with single `handleEvent` + `setState` reducer; preserve debug log side effects |
| New: `eventReducer.ts` | Pure function mapping events to state changes |
| `types/bridge.d.ts` | Update `Bridge` interface |
| `ChatService.kt` | **Zero changes** |

---

## Phase 2: Frontend Message Grouping

### 2.1 Data Model

```typescript
type MessageGroup =
  | { type: "human"; id: string; message: { id: string; content: string } }
  | { type: "assistant"; id: string; children: AssistantChild[]; isStreaming: boolean }

type AssistantChild =
  | { kind: "execution"; data: ExecutionCardData }
  | { kind: "text"; id: string; content: string; isStreaming: boolean }
```

Key properties:
- `MessageGroup` is a **frontend-only** concept. Backend and persistence layer are unaware.
- Each group has its own `id` (derived from `msgId` for assistant groups).
- `assistant` group's `children` are an ordered list — execution cards and text appear in event arrival order.
- A single assistant group spans all API rounds for one user turn (including multi-round tool calls and auto-continuations).
- **msgId invariant**: `ChatService.kt` uses the same `msgId` (`activeMessageId`) across all API rounds within a single user turn. This means `start(msgId)` in Round 1 and `start(msgId)` in Round 2 carry the same `msgId`. The group's `id` field uses this `msgId`, and the `start` handler detects continuation rounds by checking if the last group is still streaming.

### 2.2 Event → Group Mapping: groupReducer

**File:** `groupReducer.ts` (replaces `eventReducer.ts` from Phase 1)

```typescript
interface GroupState {
  groups: MessageGroup[]
  // Non-message state remains the same
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: "dark" | "light"
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
  // Track current round's text child for potential discard on round_end
  currentRoundTextIndex: number | null
}

export function groupReducer(state: GroupState, type: string, payload: any): GroupState {
  switch (type) {
    case "start": {
      // Create a new assistant group (or reuse if this is a continuation round)
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type === "assistant" && lastGroup.isStreaming) {
        // This is a continuation/tool-call round — reuse the existing group
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
      // Find or create the current round's text child
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type !== "assistant") return state

      const group = lastGroup as AssistantGroup
      let newTextIndex = state.currentRoundTextIndex

      if (newTextIndex !== null && group.children[newTextIndex]?.kind === "text") {
        // Append to existing text child
        const updated = [...group.children]
        updated[newTextIndex] = {
          ...updated[newTextIndex],
          content: (updated[newTextIndex] as TextChild).content + payload.text,
        }
        const groups = [...state.groups]
        groups[groups.length - 1] = { ...group, children: updated }
        return { ...state, groups }
      }

      // Create new text child
      const textChild: AssistantChild = { kind: "text", id: `text-${Date.now()}`, content: payload.text, isStreaming: true }
      newTextIndex = group.children.length  // index of the newly appended child
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
      // Discard current round's text child (intermediate tokens before tool_calls)
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

    // Non-message events pass through unchanged
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

// Helper: update the last assistant group in the array
function updateLastAssistant(state: GroupState, updater: (g: AssistantGroup) => AssistantGroup, extraState?: Partial<GroupState>): GroupState {
  const lastIdx = findLastAssistantIndex(state.groups)
  if (lastIdx === -1) return state
  const groups = [...state.groups]
  groups[lastIdx] = updater(groups[lastIdx] as AssistantGroup)
  return { ...state, ...extraState, groups }
}
```

### 2.3 Round Token Discard Behavior

When a round ends with `round_end` (tool_calls), any text child created during that round is discarded. This implements the user's choice: "Round 1 tokens are dropped, only execution cards + Round 2 final answer are shown."

Event sequence for a typical tool call turn:

```
User sends message
  → Frontend: create human group

Round 1:
  start(msgId)           → create assistant group (or reuse)
  token("我来帮你...")    → append text child (tracked by currentRoundTextIndex)
  execution_card(cmd1)   → append execution child
  execution_status(done) → update execution child
  round_end(msgId)       → discard text child, keep execution child

Round 2:
  start(msgId)           → reuse existing assistant group (isStreaming still true)
  token("这是结果...")    → append new text child
  end(msgId)             → finalize group

Result: assistant group children = [execution_card_1, text("这是结果...")]
```

### 2.4 Backend Changes for Phase 2

**File:** `ChatService.kt`

**Remove:**
- `bridgeNotifiedStart` mutable set — no longer needed
- `notifyRemoveMessage` call in `onFinishReason` (line 477) — no longer needed
- Lazy `notifyStart` logic in `onToken` (lines 422-425) — `notifyStart` is now sent unconditionally at round start

**Add:**
- `notifyRoundEnd(msgId)` call when `finish_reason = "tool_calls"` (replaces the `notifyRemoveMessage` block)
- `notifyStart(msgId)` call at the beginning of `startStreamingRound()` for the initial round (currently deferred)

**Simplified `onFinishReason("tool_calls")`:**

```kotlin
// Before (hacky)
if (msgId in bridgeNotifiedStart) {
    bridgeHandler?.notifyRemoveMessage(msgId)
    bridgeNotifiedStart.remove(msgId)
}
scope.launch { handleToolCallComplete(msgId, capturedBuffer) }

// After (clean)
bridgeHandler?.notifyRoundEnd(msgId)
scope.launch { handleToolCallComplete(msgId, capturedBuffer) }
```

**Simplified `onToken` (no more lazy init):**

```kotlin
onToken = { token ->
    if (activeMessageId == msgId) {
        responseBuffer.append(token)
        // No lazy notifyStart check — start was already sent
        bridgeHandler?.notifyToken(token)
    }
}
```

**`startStreamingRound()` now sends `notifyStart` at the top:**

```kotlin
private fun startStreamingRound(msgId: String, request: Request, toolsEnabled: Boolean) {
    bridgeHandler?.notifyStart(msgId)  // unconditional
    // ... rest unchanged
}
```

**Timing consideration:** Currently when tools are disabled, `notifyStart` is sent from `sendMessage()` (before the HTTP request). Moving it to `startStreamingRound()` means it fires slightly later (after the request is built). This is acceptable because the frontend creates the assistant group in response to `start`, and the delay between `sendMessage()` and `startStreamingRound()` is negligible (no network round trip — just object construction). No visible flash of empty bubble is expected.

### 2.5 Rendering

**New file:** `components/AssistantGroup.tsx`

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

`AssistantMarkdown` extracts the current markdown rendering logic from `MessageBubble.tsx` (the `useEffect` with `marked.parse` + `DOMPurify.sanitize` + code block copy buttons).

**User message rendering** becomes a simpler component since `MessageBubble` no longer needs to handle `role === "execution"`:

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

**Execution card auto-collapse:** Already implemented in `ExecutionCard.tsx` (`LogOutput` auto-collapses when `isStreaming` transitions from true to false). No changes needed.

**Loading indicator simplification:**

```tsx
// Before: two separate conditions
{(continuationInfo) && <spinner />}
{(!continuationInfo && isLoading && !messages.some(m => m.isStreaming) && messages.some(m => m.role === "execution")) && <spinner />}

// After: single condition
{isLoading && <continuation-spinner-or-generic-spinner />}
```

Since `isStreaming` is now on the group level, the frontend always knows whether the current turn is still active.

### 2.6 Session Persistence

**Backend:** `SessionStore.kt` unchanged — still stores `Message[]` flat array (user + assistant messages only, no execution data).

**Frontend restore:** `restoreToGroups()` converts flat messages to grouped structure:

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

### 2.7 Phase 2 Change Summary

| File | Change |
|---|---|
| `ChatService.kt` | Remove `bridgeNotifiedStart`, remove `notifyRemoveMessage`, add `notifyRoundEnd`, send `notifyStart` unconditionally |
| `BridgeHandler.kt` | No new changes (Phase 1 already added `notifyRoundEnd`) |
| `App.tsx` | State from `messages` → `groups`; replace `eventReducer` with `groupReducer`; simplify rendering loop and loading indicators |
| New: `groupReducer.ts` | Replaces `eventReducer.ts`; maps events to group state |
| New: `components/AssistantGroup.tsx` | Renders assistant group (execution cards + text) |
| New: `components/AssistantMarkdown.tsx` | Extracted markdown rendering from `MessageBubble.tsx` |
| `MessageBubble.tsx` | Simplified to user-only (or removed, inlined into App.tsx render) |
| `types/bridge.d.ts` | Add `StreamEvent` and `MessageGroup` types |

### 2.8 What Gets Removed

After Phase 2 is complete, these can be cleaned up:

| What | Why |
|---|---|
| `notifyRemoveMessage` in BridgeHandler | No longer called; group handles ordering |
| `bridgeNotifiedStart` in ChatService | No longer needed; `notifyStart` is unconditional |
| Lazy bubble creation logic in ChatService `onToken` | No longer needed |
| `remove_message` event type | No longer emitted |
| `role: "execution"` in `Message` type | Execution cards live inside `AssistantChild` |

---

## Migration Strategy

### Phase 1 deployment checklist:
1. Implement `dispatchEvent` in `BridgeHandler.kt`
2. Rewrite all `notifyXxx` internals
3. Add `notifyRoundEnd` (unused)
4. Create `eventReducer.ts`
5. Update `useBridge.ts` to single `onEvent`
6. Update `App.tsx` to use `eventReducer`
7. Update `types/bridge.d.ts`
8. Test: all existing flows (single turn, tool calls, approval, continuation, restore) must behave identically

### Phase 2 deployment checklist:
1. Create `MessageGroup` types
2. Create `groupReducer.ts`
3. Create `AssistantGroup.tsx` and `AssistantMarkdown.tsx`
4. Update `App.tsx` state and rendering
5. Simplify `ChatService.kt` (remove hacks, add `notifyRoundEnd` calls)
6. Test: tool call flow must show execution cards before final text without `notifyRemoveMessage`; multi-round tool calls must group correctly; auto-collapse must work

### Rollback safety:
- Phase 1 and Phase 2 can be deployed independently since Phase 1 is a transparent refactor.
- If Phase 2 has issues, reverting the frontend changes restores the flat rendering while keeping the unified event system.
