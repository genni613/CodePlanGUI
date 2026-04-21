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

export interface BridgeError {
  type: 'config' | 'network' | 'runtime'
  message: string
  action?: 'openSettings' | 'retry'
}

export interface Bridge {
  isReady: boolean
  // Single event channel (Kotlin → JS)
  onEvent: (type: string, payloadJson: string) => void
  // Action methods (JS → Kotlin)
  sendMessage: (text: string, includeContext: boolean) => void
  newChat: () => void
  openSettings: () => void
  cancelStream: () => void
  frontendReady: () => void
  debugLog: (text: string) => void
  approvalResponse: (requestId: string, decision: 'allow' | 'deny', addToWhitelist?: boolean) => void
  openFile: (path: string, line?: number) => void
}

declare global {
  interface Window {
    __bridge: Bridge
  }
}

export {}
