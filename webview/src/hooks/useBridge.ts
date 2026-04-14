import { useEffect, useRef, useState } from 'react'
import { BridgeStatus } from '../types/bridge'

interface BridgeCallbacks {
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
  onContextFile: (fileName: string) => void
  onTheme: (theme: 'dark' | 'light') => void
  onRestoreMessages: (messages: string) => void
}

export function useBridge(callbacks: BridgeCallbacks) {
  const [bridgeReady, setBridgeReady] = useState(() => window.__bridge?.isReady === true)
  const frontendReadySentRef = useRef(false)
  const callbacksRef = useRef(callbacks)

  useEffect(() => {
    callbacksRef.current = callbacks
    if (!window.__bridge) {
      return
    }

    window.__bridge.onStart = callbacks.onStart
    window.__bridge.onToken = callbacks.onToken
    window.__bridge.onEnd = callbacks.onEnd
    window.__bridge.onError = callbacks.onError
    window.__bridge.onStatus = callbacks.onStatus
    window.__bridge.onContextFile = callbacks.onContextFile
    window.__bridge.onTheme = callbacks.onTheme
    window.__bridge.onRestoreMessages = callbacks.onRestoreMessages
  }, [callbacks])

  useEffect(() => {
    const setup = () => {
      const currentCallbacks = callbacksRef.current
      if (!window.__bridge) {
        window.__bridge = {
          isReady: false,
          sendMessage: () => {},
          newChat: () => {},
          openSettings: () => {},
          frontendReady: () => {},
          onStart: currentCallbacks.onStart,
          onToken: currentCallbacks.onToken,
          onEnd: currentCallbacks.onEnd,
          onError: currentCallbacks.onError,
          onStatus: currentCallbacks.onStatus,
          onContextFile: currentCallbacks.onContextFile,
          onTheme: currentCallbacks.onTheme,
          onRestoreMessages: currentCallbacks.onRestoreMessages,
        }
      } else {
        window.__bridge.onStart = currentCallbacks.onStart
        window.__bridge.onToken = currentCallbacks.onToken
        window.__bridge.onEnd = currentCallbacks.onEnd
        window.__bridge.onError = currentCallbacks.onError
        window.__bridge.onStatus = currentCallbacks.onStatus
        window.__bridge.onContextFile = currentCallbacks.onContextFile
        window.__bridge.onTheme = currentCallbacks.onTheme
        window.__bridge.onRestoreMessages = currentCallbacks.onRestoreMessages
      }

      const isReady = window.__bridge.isReady === true
      setBridgeReady(isReady)
      if (isReady && !frontendReadySentRef.current) {
        frontendReadySentRef.current = true
        window.__bridge.frontendReady()
      }
    }

    setup()
    document.addEventListener('bridge_ready', setup)
    return () => document.removeEventListener('bridge_ready', setup)
  }, [])

  return bridgeReady
}
