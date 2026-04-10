export interface BridgeStatus {
  providerName: string
  model: string
  connectionState: 'unconfigured' | 'ready' | 'streaming' | 'error'
}

export interface Bridge {
  isReady: boolean
  sendMessage: (text: string, includeContext: boolean) => void
  newChat: () => void
  openSettings: () => void
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
}

declare global {
  interface Window {
    __bridge: Bridge
  }
}

export {}
