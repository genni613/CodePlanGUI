import type { ExecutionCardData, ExecutionStatus, LogEntry } from './components/ExecutionCard.js'
import type { BridgeError, BridgeStatus } from './types/bridge.js'
import { parseExecutionResultPayload } from './executionStatus.js'
import { applyBridgeStatus, applyContextFile } from './statusState.js'

// ─── Group types ────────────────────────────────────────────────────

export type AssistantChild =
  | { kind: 'execution'; data: ExecutionCardData }
  | { kind: 'text'; id: string; content: string; isStreaming: boolean }

export type HumanGroup = { type: 'human'; id: string; message: { id: string; content: string } }
export type AssistantGroup = { type: 'assistant'; id: string; children: AssistantChild[]; isStreaming: boolean }
export type MessageGroup = HumanGroup | AssistantGroup

// ─── State ──────────────────────────────────────────────────────────

export interface GroupState {
  groups: MessageGroup[]
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: 'dark' | 'light'
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
  currentRoundTextIndex: number | null
}

// ─── Helpers ────────────────────────────────────────────────────────

function findLastAssistantIndex(groups: MessageGroup[]): number {
  for (let i = groups.length - 1; i >= 0; i--) {
    if (groups[i].type === 'assistant') return i
  }
  return -1
}

function updateLastAssistant(
  state: GroupState,
  updater: (g: AssistantGroup) => AssistantGroup,
): GroupState {
  const lastIdx = findLastAssistantIndex(state.groups)
  if (lastIdx === -1) return state
  const groups = [...state.groups]
  groups[lastIdx] = updater(groups[lastIdx] as AssistantGroup)
  return { ...state, groups }
}

function restoreToGroups(flat: Array<{ id: string; role: string; content: string }>): MessageGroup[] {
  const groups: MessageGroup[] = []
  let currentAssistant: AssistantGroup | null = null

  for (const msg of flat) {
    if (msg.role !== 'user' && msg.role !== 'assistant') continue
    if (msg.role === 'assistant' && msg.content.trim().length === 0) continue

    if (msg.role === 'user') {
      if (currentAssistant) { groups.push(currentAssistant); currentAssistant = null }
      groups.push({ type: 'human', id: msg.id, message: { id: msg.id, content: msg.content } })
    } else {
      if (!currentAssistant) currentAssistant = { type: 'assistant', id: msg.id, children: [], isStreaming: false }
      currentAssistant.children.push({ kind: 'text', id: `text-${msg.id}`, content: msg.content, isStreaming: false })
    }
  }
  if (currentAssistant) groups.push(currentAssistant)
  return groups
}

// ─── Reducer ────────────────────────────────────────────────────────

export function groupReducer(state: GroupState, type: string, payload: any): GroupState {
  switch (type) {
    case 'start': {
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type === 'assistant' && (lastGroup as AssistantGroup).isStreaming) {
        return { ...state, isLoading: true, error: null, currentRoundTextIndex: null }
      }
      return {
        ...state,
        isLoading: true,
        error: null,
        currentRoundTextIndex: null,
        groups: [...state.groups, { type: 'assistant', id: payload.msgId, children: [], isStreaming: true }],
      }
    }

    case 'token': {
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type !== 'assistant') return state

      const group = lastGroup as AssistantGroup
      const newTextIndex = state.currentRoundTextIndex

      if (newTextIndex !== null && group.children[newTextIndex]?.kind === 'text') {
        const existing = group.children[newTextIndex] as AssistantChild & { kind: 'text' }
        const updated = [...group.children]
        updated[newTextIndex] = { kind: 'text', id: existing.id, content: existing.content + payload.text, isStreaming: existing.isStreaming }
        const groups = [...state.groups]
        groups[groups.length - 1] = { ...group, children: updated }
        return { ...state, groups }
      }

      const textChild: AssistantChild = { kind: 'text', id: `text-${crypto.randomUUID()}`, content: payload.text, isStreaming: true }
      const appendIndex = group.children.length
      const groups = [...state.groups]
      groups[groups.length - 1] = { ...group, children: [...group.children, textChild] }
      return { ...state, groups, currentRoundTextIndex: appendIndex }
    }

    case 'execution_card': {
      return updateLastAssistant(state, group => ({
        ...group,
        children: [...group.children, {
          kind: 'execution',
          data: { requestId: payload.requestId, command: payload.command, status: 'running' as ExecutionStatus },
        }],
      }))
    }

    case 'log': {
      return updateLastAssistant(state, group => ({
        ...group,
        children: group.children.map(child =>
          child.kind === 'execution' && child.data.requestId === payload.requestId
            ? { ...child, data: { ...child.data, logs: [...(child.data.logs || []), { text: payload.line, type: payload.type as LogEntry['type'] }] } }
            : child
        ),
      }))
    }

    case 'execution_status': {
      const result = parseExecutionResultPayload(payload.result)
      return updateLastAssistant(state, group => ({
        ...group,
        children: group.children.map(child =>
          child.kind === 'execution' && child.data.requestId === payload.requestId
            ? { ...child, data: { ...child.data, status: payload.status as ExecutionStatus, result } }
            : child
        ),
      }))
    }

    case 'approval_request': {
      return {
        ...updateLastAssistant(state, group => ({
          ...group,
          children: group.children.map(child =>
            child.kind === 'execution' && child.data.requestId === payload.requestId
              ? { ...child, data: { ...child.data, status: 'waiting' as ExecutionStatus } }
              : child
          ),
        })),
        approvalRequestId: payload.requestId,
        approvalCommand: payload.command,
        approvalDescription: payload.description,
        approvalOpen: true,
      }
    }

    case 'round_end': {
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

    case 'end': {
      return updateLastAssistant({
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
      }, group => ({
        ...group,
        isStreaming: false,
        children: group.children.map(child =>
          child.kind === 'text' ? { ...child, isStreaming: false } : child
        ),
      }))
    }

    case 'error': {
      const groups = state.groups.map(g => {
        if (g.type === 'assistant' && (g as AssistantGroup).isStreaming) {
          return {
            ...g,
            isStreaming: false,
            children: (g as AssistantGroup).children.map(child =>
              child.kind === 'text' ? { ...child, isStreaming: false } : child
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
        error: { type: 'runtime' as const, message: payload.message },
      }
    }

    case 'structured_error': {
      const groups = state.groups.map(g => {
        if (g.type === 'assistant' && (g as AssistantGroup).isStreaming) {
          return {
            ...g,
            isStreaming: false,
            children: (g as AssistantGroup).children.map(child =>
              child.kind === 'text' ? { ...child, isStreaming: false } : child
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

    case 'continuation':
      return { ...state, continuationInfo: { current: payload.current, max: payload.max } }

    case 'restore_messages':
      return { ...state, groups: restoreToGroups(JSON.parse(payload.messages)) }

    case 'status':
      return { ...state, status: applyBridgeStatus(state.status, payload) }

    case 'context_file':
      return { ...state, status: applyContextFile(state.status, payload.fileName) }

    case 'theme':
      return { ...state, themeMode: payload.mode }

    default:
      return state
  }
}
