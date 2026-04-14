import { useCallback, useEffect, useRef, useState } from 'react'
import { SendOutlined } from '@ant-design/icons'
import { Button, ConfigProvider, Switch, Tooltip, Typography, theme as antdTheme } from 'antd'
import { v4 as uuidv4 } from 'uuid'
import { ErrorBanner } from './components/ErrorBanner'
import { Message, MessageBubble } from './components/MessageBubble'
import { ProviderBar } from './components/ProviderBar'
import { getComposerReadiness } from './composerState'
import { getContextToggleMeta } from './contextState'
import { useBridge } from './hooks/useBridge'
import { prepareSendPayload } from './sendState'
import { applyBridgeStatus, applyContextFile } from './statusState'
import { BridgeStatus } from './types/bridge'
import './App.css'

export default function App() {
  const [messages, setMessages] = useState<Message[]>([])
  const [inputText, setInputText] = useState('')
  const isComposingRef = useRef(false)
  const [isLoading, setIsLoading] = useState(false)
  const [includeContext, setIncludeContext] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<BridgeStatus>({
    providerName: '',
    model: '',
    connectionState: 'unconfigured',
    contextFile: '',
  })
  const [themeMode, setThemeMode] = useState<'dark' | 'light'>('dark')
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // Apply theme class to document root
  useEffect(() => {
    document.documentElement.classList.remove('theme-dark', 'theme-light')
    document.documentElement.classList.add(`theme-${themeMode}`)
  }, [themeMode])

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }

  useEffect(() => {
    scrollToBottom()
  }, [messages])

  const onStart = useCallback((msgId: string) => {
    setIsLoading(true)
    setError(null)
    setMessages((prev) => [
      ...prev,
      { id: msgId, role: 'assistant', content: '', isStreaming: true },
    ])
  }, [])

  const onToken = useCallback((token: string) => {
    setMessages((prev) =>
      prev.map((message) =>
        message.isStreaming ? { ...message, content: message.content + token } : message,
      ),
    )
  }, [])

  const onEnd = useCallback((_msgId: string) => {
    setIsLoading(false)
    setMessages((prev) =>
      prev.map((message) => (message.isStreaming ? { ...message, isStreaming: false } : message)),
    )
  }, [])

  const onError = useCallback((message: string) => {
    setIsLoading(false)
    setMessages((prev) =>
      prev.map((item) => (item.isStreaming ? { ...item, isStreaming: false } : item)),
    )
    setError(message)
  }, [])

  const onContextFile = useCallback((fileName: string) => {
    setStatus(prev => applyContextFile(prev, fileName))
  }, [])

  const onTheme = useCallback((newTheme: 'dark' | 'light') => {
    setThemeMode(newTheme)
  }, [])

  const onStatus = useCallback((nextStatus: BridgeStatus) => {
    setStatus(prev => applyBridgeStatus(prev, nextStatus))
  }, [])

  const onRestoreMessages = useCallback((messagesJson: string) => {
    try {
      const restored = JSON.parse(messagesJson) as Array<{ id: string; role: string; content: string }>
      setMessages(restored.map(m => ({
        id: m.id,
        role: m.role as 'user' | 'assistant',
        content: m.content,
        isStreaming: false,
      })))
    } catch {
      // ignore malformed restore data
    }
  }, [])

  // Build theme algorithm for Ant Design
  const themeAlgorithm = themeMode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm

  const bridgeReady = useBridge({ onStart, onToken, onEnd, onError, onStatus, onContextFile, onTheme, onRestoreMessages })
  const composerReadiness = getComposerReadiness({
    inputText,
    isLoading,
    isBridgeReady: bridgeReady,
    connectionState: status.connectionState,
  })
  const contextToggleMeta = getContextToggleMeta(includeContext, status.contextFile || '')

  const handleSend = () => {
    if (!composerReadiness.canSend) {
      if (composerReadiness.reason && composerReadiness.text) {
        setError(composerReadiness.reason)
      }
      return
    }

    const payload = prepareSendPayload(composerReadiness.text, isLoading, bridgeReady)
    if (!payload) return

    const userMsgId = uuidv4()
    setMessages((prev) => [...prev, { id: userMsgId, role: 'user', content: payload.text }])
    setInputText('')
    console.log('[CodePlanGUI] handleSend:', payload.text, 'includeContext:', includeContext)
    window.__bridge?.sendMessage(payload.text, includeContext)
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !isComposingRef.current) {
      event.preventDefault()
      handleSend()
    }
  }

  const handleNewChat = () => {
    setMessages([])
    setError(null)
    setIsLoading(false)
    window.__bridge?.newChat()
  }

  const adjustTextareaHeight = (element: HTMLTextAreaElement) => {
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, 120)}px`
  }

  return (
    <ConfigProvider
      theme={{
        algorithm: themeAlgorithm,
        token: {
          colorPrimary: '#d2a15e',
          colorInfo: '#d2a15e',
          borderRadius: 16,
          fontFamily:
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Inter', sans-serif",
        },
      }}
    >
      <div className="app-shell">
        <ProviderBar
          onNewChat={handleNewChat}
          onOpenSettings={() => window.__bridge?.openSettings()}
          status={status}
          bridgeReady={bridgeReady}
        />

        {error && <ErrorBanner message={error} onClose={() => setError(null)} />}

        <div className="messages-area">
          {messages.length === 0 && (
            <div className="empty-state">
              <div className="empty-card">
                <div className="empty-icon">✦</div>
                <div className="empty-kicker">Ready for context</div>
                <Typography.Title level={3} className="empty-title">
                  向 AI 提问，或选中代码后右键 Ask AI
                </Typography.Title>
                <div className="empty-copy">
                  当前会话支持流式输出、上下文注入和 Markdown 代码块复制。输入区支持
                  <strong> Enter 发送</strong>，<strong>Shift+Enter 换行</strong>。
                </div>
                {status.connectionState === 'unconfigured' && (
                  <Button type="link" onClick={() => window.__bridge?.openSettings()}>
                    打开 Settings 配置 Provider
                  </Button>
                )}
              </div>
            </div>
          )}

          {messages.map((message) => (
            <MessageBubble key={message.id} message={message} />
          ))}

          <div ref={messagesEndRef} />
        </div>

        <div className="input-area">
          <div className="input-meta">
            <div className="context-toggle">
              <Tooltip title={contextToggleMeta.title}>
                <Switch size="small" checked={includeContext} onChange={setIncludeContext} />
              </Tooltip>
              <span className="context-caption context-file-label" title={contextToggleMeta.title}>
                {contextToggleMeta.label}
              </span>
            </div>
          </div>

          <div className="composer-row">
            <textarea
              value={inputText}
              onChange={(event) => {
                setInputText(event.target.value)
                adjustTextareaHeight(event.target)
              }}
              onCompositionStart={() => { isComposingRef.current = true }}
              onCompositionEnd={(event) => {
                isComposingRef.current = false
                setInputText((event.target as HTMLTextAreaElement).value)
              }}
              onKeyDown={handleKeyDown}
              placeholder="输入问题... (Enter 发送，Shift+Enter 换行)"
              disabled={isLoading}
              rows={1}
              className="composer-input"
            />
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleSend}
              disabled={!composerReadiness.canSend}
              title={composerReadiness.reason ?? 'Send'}
              size="small"
              className="send-button"
            />
          </div>
        </div>
      </div>
    </ConfigProvider>
  )
}
