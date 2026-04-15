export interface BridgeStatus {
  providerName: string
  model: string
  connectionState: 'unconfigured' | 'ready' | 'streaming' | 'error'
  contextFile?: string
}

export interface ExecutionResult {
  status: 'ok' | 'error' | 'blocked' | 'denied' | 'timeout'
  exit_code?: number
  stdout?: string
  stderr?: string
  duration_ms?: number
  truncated?: boolean
  reason?: string
  timeout_seconds?: number
}

export interface Bridge {
  isReady: boolean
  sendMessage: (text: string, includeContext: boolean) => void
  newChat: () => void
  openSettings: () => void
  cancelStream: () => void
  frontendReady: () => void
  debugLog: (text: string) => void
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
  onContextFile: (fileName: string) => void
  onTheme: (theme: 'dark' | 'light') => void
  approvalResponse: (requestId: string, decision: 'allow' | 'deny') => void
  onApprovalRequest: (requestId: string, command: string, description: string) => void
  onExecutionStatus: (requestId: string, status: string, result: string) => void
  onLog: (msgId: string, logLine: string, type: string) => void
  onRestoreMessages: (messages: string) => void
}

declare global {
  interface Window {
    __bridge: Bridge
  }
}

export {}
