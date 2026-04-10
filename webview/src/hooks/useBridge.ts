import { useEffect, useState } from 'react'
import { BridgeStatus } from '../types/bridge'

interface BridgeCallbacks {
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
}

export function useBridge(callbacks: BridgeCallbacks) {
  const [bridgeReady, setBridgeReady] = useState(() => window.__bridge?.isReady === true)

  useEffect(() => {
    const setup = () => {
      if (!window.__bridge) {
        window.__bridge = {
          isReady: false,
          sendMessage: () => {},
          newChat: () => {},
          openSettings: () => {},
          onStart: callbacks.onStart,
          onToken: callbacks.onToken,
          onEnd: callbacks.onEnd,
          onError: callbacks.onError,
          onStatus: callbacks.onStatus,
        }
      }

      window.__bridge.onStart = callbacks.onStart
      window.__bridge.onToken = callbacks.onToken
      window.__bridge.onEnd = callbacks.onEnd
      window.__bridge.onError = callbacks.onError
      window.__bridge.onStatus = callbacks.onStatus
      setBridgeReady(window.__bridge.isReady === true)
    }

    setup()
    document.addEventListener('bridge_ready', setup)
    return () => document.removeEventListener('bridge_ready', setup)
  }, [callbacks.onStart, callbacks.onToken, callbacks.onEnd, callbacks.onError, callbacks.onStatus])

  return bridgeReady
}
