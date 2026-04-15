import { useEffect, useRef, useState } from 'react'
import { Typography } from 'antd'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  LoadingOutlined,
  StopOutlined,
  LockOutlined,
  DownOutlined,
  RightOutlined,
} from '@ant-design/icons'

export type ExecutionStatus = 'waiting' | 'running' | 'done' | 'blocked' | 'denied' | 'timeout'

export interface LogEntry {
  text: string
  type: 'stdout' | 'stderr' | 'info'
}

export interface ExecutionCardData {
  requestId: string
  command: string
  status: ExecutionStatus
  result?: {
    status: 'ok' | 'error' | 'blocked' | 'denied' | 'timeout'
    exit_code?: number
    stdout?: string
    stderr?: string
    duration_ms?: number
    truncated?: boolean
    reason?: string
    timeout_seconds?: number
  }
  logs?: LogEntry[]
}

interface ExecutionCardProps {
  data: ExecutionCardData
}

function LogOutput({ logs, isStreaming }: { logs: LogEntry[]; isStreaming: boolean }) {
  const [collapsed, setCollapsed] = useState(false)
  const bodyRef = useRef<HTMLDivElement>(null)
  const wasStreamingRef = useRef(isStreaming)

  // Auto-collapse when streaming finishes (running → done)
  useEffect(() => {
    if (isStreaming) {
      wasStreamingRef.current = true
    } else if (wasStreamingRef.current) {
      wasStreamingRef.current = false
      setCollapsed(true)
    }
  }, [isStreaming])

  // Auto-scroll to bottom
  useEffect(() => {
    if (bodyRef.current && !collapsed) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight
    }
  }, [logs.length, collapsed])

  if (logs.length === 0) return null

  return (
    <div className="exec-log-panel">
      <div className="exec-log-header" onClick={() => setCollapsed(!collapsed)}>
        {collapsed ? <RightOutlined /> : <DownOutlined />}
        <span className="exec-log-title">Output ({logs.length})</span>
      </div>
      {!collapsed && (
        <div ref={bodyRef} className="exec-log-body">
          {logs.map((entry, i) => (
            <div key={i} className={`exec-log-line exec-log-${entry.type}`}>
              {entry.text}
            </div>
          ))}
          {isStreaming && <span className="stream-cursor" />}
        </div>
      )}
    </div>
  )
}

export function ExecutionCard({ data }: ExecutionCardProps) {
  const { command, status, result, logs } = data
  const isStreaming = status === 'running'
  const hasLogs = logs && logs.length > 0

  const header = () => {
    switch (status) {
      case 'waiting':
        return <><LockOutlined style={{ marginRight: 6 }} />等待审批</>
      case 'running':
        return <><LoadingOutlined style={{ marginRight: 6 }} />执行中</>
      case 'blocked':
        return <><StopOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />已拦截 · {result?.reason}</>
      case 'denied':
        return <><StopOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />用户拒绝</>
      case 'timeout':
        return <><ClockCircleOutlined style={{ marginRight: 6, color: '#faad14' }} />超时 · {result?.timeout_seconds}s</>
      case 'done': {
        if (!result) return null
        const success = result.status === 'ok'
        const duration = result.duration_ms ? `${(result.duration_ms / 1000).toFixed(1)}s` : ''
        return success
          ? <><CheckCircleOutlined style={{ marginRight: 6, color: '#52c41a' }} />完成 · exit {result.exit_code} · {duration}</>
          : <><CloseCircleOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />失败 · exit {result.exit_code} · {duration}</>
      }
    }
  }

  return (
    <div className="exec-card">
      <div className="exec-card-header">{header()}</div>
      <Typography.Text code className="exec-card-command">$ {command}</Typography.Text>
      {hasLogs && <LogOutput logs={logs!} isStreaming={isStreaming} />}
      {!hasLogs && result?.stdout && <OutputBlock text={result.stdout} label="stdout" />}
      {!hasLogs && result?.stderr && <OutputBlock text={result.stderr} label="stderr" />}
      {result?.truncated && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          [output truncated]
        </Typography.Text>
      )}
    </div>
  )
}

const PREVIEW_LINES = 5

function OutputBlock({ text, label }: { text: string; label: string }) {
  const lines = text.split('\n')
  const [expanded, setExpanded] = useState(lines.length <= PREVIEW_LINES)
  const visible = expanded ? lines : lines.slice(0, PREVIEW_LINES)

  return (
    <div style={{ marginTop: 8 }}>
      {label && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {label}
        </Typography.Text>
      )}
      <pre
        style={{
          margin: '4px 0',
          fontSize: 12,
          overflowX: 'auto',
          background: 'rgba(0,0,0,0.04)',
          padding: '6px 10px',
          borderRadius: 4,
        }}
      >
        {visible.join('\n')}
      </pre>
      {!expanded && (
        <Typography.Link style={{ fontSize: 12 }} onClick={() => setExpanded(true)}>
          ▼ show {lines.length - PREVIEW_LINES} more lines
        </Typography.Link>
      )}
    </div>
  )
}
