export interface BridgeStatus {
  providerName: string
  model: string
  connectionState: 'unconfigured' | 'ready' | 'streaming' | 'error'
  contextFile?: string
}

export interface Bridge {
  isReady: boolean
  sendMessage: (text: string, includeContext: boolean) => void
  newChat: () => void
  openSettings: () => void
  frontendReady: () => void
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
  onContextFile: (fileName: string) => void
  onTheme: (theme: 'dark' | 'light') => void
  onRestoreMessages: (messages: string) => void
}

declare global {
  interface Window {
    __bridge: Bridge
  }
}

export {}
