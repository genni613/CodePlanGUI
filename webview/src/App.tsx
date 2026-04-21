import { useCallback, useEffect, useRef, useState } from 'react'
import { BorderOutlined, SendOutlined } from '@ant-design/icons'
import { Button, ConfigProvider, Switch, Tooltip, Typography, theme as antdTheme } from 'antd'
import { v4 as uuidv4 } from 'uuid'
import { AssistantGroup } from './components/AssistantGroup'
import { ApprovalDialog } from './components/ApprovalDialog'
import { ErrorBanner } from './components/ErrorBanner'
import { ProviderBar } from './components/ProviderBar'
import { getComposerReadiness } from './composerState'
import { getContextToggleMeta } from './contextState'
import { stringifyExecutionResultPayload } from './executionStatus'
import { GroupState, groupReducer } from './groupReducer'
import { useBridge } from './hooks/useBridge'
import { prepareSendPayload } from './sendState'
import { BridgeStatus } from './types/bridge'
import './App.css'

const initialAppState: GroupState = {
  groups: [],
  isLoading: false,
  error: null,
  status: {
    providerName: '',
    model: '',
    connectionState: 'unconfigured',
    contextFile: '',
  },
  themeMode: 'dark',
  approvalOpen: false,
  approvalRequestId: '',
  approvalCommand: '',
  approvalDescription: '',
  approvalToolName: '',
  fileChangeAutos: [],
  continuationInfo: null,
  currentRoundTextIndex: null,
}

export default function App() {
  const [appState, setAppState] = useState<GroupState>(initialAppState)
  const [inputText, setInputText] = useState('')
  const isComposingRef = useRef(false)
  const [includeContext, setIncludeContext] = useState(true)
  const messagesEndRef = useRef<HTMLDivElement>(null)

<<<<<<< Updated upstream
  const { groups, isLoading, error, status, themeMode, approvalOpen, approvalRequestId, approvalCommand, approvalDescription, continuationInfo } = appState
=======
  const { messages, isLoading, error, status, themeMode, approvalOpen, approvalRequestId, approvalCommand, approvalDescription, approvalToolName, continuationInfo } = appState
>>>>>>> Stashed changes

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
  }, [groups])

  const emitFrontendDebugLog = useCallback((message: string) => {
    window.__bridge?.debugLog(message)
  }, [])

  const handleEvent = useCallback((type: string, payload: any) => {
    if (type === 'execution_card') {
      emitFrontendDebugLog(`[approval-ui] received execution card requestId=${payload.requestId} command=${payload.command} description=${payload.description}`)
    } else if (type === 'approval_request') {
      emitFrontendDebugLog(`[approval-ui] received approval request requestId=${payload.requestId} command=${payload.command} description=${payload.description}`)
    } else if (type === 'execution_status') {
      const rawResult = stringifyExecutionResultPayload(payload.result)
      emitFrontendDebugLog(`[approval-ui] received execution status requestId=${payload.requestId} status=${payload.status} result=${rawResult.slice(0, 240)}`)
    }

    setAppState(prev => groupReducer(prev, type, payload))
  }, [emitFrontendDebugLog])

  const handleApprovalAllow = useCallback((addToWhitelist: boolean) => {
    emitFrontendDebugLog(`[approval-ui] modal allow clicked requestId=${approvalRequestId} addToWhitelist=${addToWhitelist}`)
    setAppState(prev => ({ ...prev, approvalOpen: false }))
    window.__bridge?.approvalResponse(approvalRequestId, 'allow', addToWhitelist)
  }, [approvalRequestId, emitFrontendDebugLog])

  const handleApprovalDeny = useCallback(() => {
    emitFrontendDebugLog(`[approval-ui] modal deny clicked requestId=${approvalRequestId}`)
    setAppState(prev => ({ ...prev, approvalOpen: false }))
    window.__bridge?.approvalResponse(approvalRequestId, 'deny')
  }, [approvalRequestId, emitFrontendDebugLog])

  // Build theme algorithm for Ant Design
  const themeAlgorithm = themeMode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm

  const bridgeReady = useBridge(handleEvent)

  // Clear stale errors when the bridge reconnects (e.g., after webview reload)
  useEffect(() => {
    if (bridgeReady) {
      setAppState(prev => ({ ...prev, error: null }))
    }
  }, [bridgeReady])

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
        setAppState(prev => ({ ...prev, error: { type: 'runtime' as const, message: composerReadiness.reason! } }))
      }
      return
    }

    const payload = prepareSendPayload(composerReadiness.text, isLoading, bridgeReady)
    if (!payload) return

    const userMsgId = uuidv4()
    setAppState(prev => ({
      ...prev,
      groups: [...prev.groups, { type: 'human' as const, id: userMsgId, message: { id: userMsgId, content: payload.text } }],
    }))
    setInputText('')
    window.__bridge?.sendMessage(payload.text, includeContext)
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !isComposingRef.current) {
      event.preventDefault()
      handleSend()
    }
  }

  const handleNewChat = () => {
    setAppState(prev => ({
      ...prev,
      groups: [],
      error: null,
      isLoading: false,
      currentRoundTextIndex: null,
    }))
    window.__bridge?.newChat()
  }

  const handleCancel = useCallback(() => {
    if (!isLoading) return
    window.__bridge?.cancelStream()
  }, [isLoading])

  // ESC key to cancel streaming
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isLoading) {
        e.preventDefault()
        window.__bridge?.cancelStream()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [isLoading])

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
        <ApprovalDialog
          open={approvalOpen}
          command={approvalCommand}
          description={approvalDescription}
          onAllow={handleApprovalAllow}
          onDeny={handleApprovalDeny}
        />
        <ProviderBar
          onNewChat={handleNewChat}
          onOpenSettings={() => window.__bridge?.openSettings()}
          status={status}
          bridgeReady={bridgeReady}
        />

        {error && <ErrorBanner error={error} onClose={() => setAppState(prev => ({ ...prev, error: null }))} />}

        <div className="messages-area">
          {groups.length === 0 && (
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

          {groups.map(group => {
            if (group.type === 'human') {
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

          {isLoading && !groups.some(g =>
            g.type === 'assistant' && g.children.some(c => c.kind === 'text' && c.isStreaming)
          ) && (
            <div className="continuation-indicator">
              <span className="continuation-spinner" />
              {continuationInfo && <span className="continuation-text">续写中 {continuationInfo.current}/{continuationInfo.max}</span>}
            </div>
          )}

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
              icon={isLoading ? <BorderOutlined /> : <SendOutlined />}
              onClick={isLoading ? handleCancel : handleSend}
              disabled={!isLoading && !composerReadiness.canSend}
              title={isLoading ? '停止生成 (Esc)' : (composerReadiness.reason ?? 'Send')}
              size="small"
              className="send-button"
            />
          </div>
        </div>
      </div>
    </ConfigProvider>
  )
}
