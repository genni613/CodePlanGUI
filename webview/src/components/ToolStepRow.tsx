import { memo, useState } from 'react'
import { CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined, RightOutlined, DownOutlined } from '@ant-design/icons'
import type { ToolStepInfo } from '../eventReducer'

const TOOL_ICONS: Record<string, string> = {
  read_file: '\u{1F4D6}',
  list_files: '\u{1F4C2}',
  grep_files: '\u{1F50D}',
  edit_file: '\u{270F}\u{FE0F}',
  write_file: '\u{1F4DD}',
  run_command: '\u{2328}\u{FE0F}',
  run_powershell: '\u{2328}\u{FE0F}',
}

interface ToolStepRowProps extends ToolStepInfo {
  onToggleExpand: () => void
}

const MAX_OUTPUT_CHARS = 2000

export const ToolStepRow = memo(function ToolStepRow({
  toolName,
  targetSummary,
  status,
  durationMs,
  output,
  diffStats,
  expanded,
  onToggleExpand,
}: ToolStepRowProps) {
  const icon = TOOL_ICONS[toolName] ?? '\u{1F527}'
  const isRunning = status === 'running'
  const isFailed = status === 'failed'
  const canExpand = !isRunning && !!output

  const diffLabel = diffStats ? ` (+${diffStats.added}/-${diffStats.removed})` : ''
  const durationLabel = durationMs != null
    ? durationMs < 1000 ? `${durationMs}ms`
    : `${(durationMs / 1000).toFixed(1)}s`
    : ''

  return (
    <div className={`tool-step-row tool-step-row--${status}`}>
      <div
        className="tool-step-header"
        onClick={canExpand ? onToggleExpand : undefined}
        style={{ cursor: canExpand ? 'pointer' : 'default' }}
      >
        <span className="tool-step-status-icon">
          {isRunning && <LoadingOutlined spin />}
          {status === 'completed' && <CheckCircleOutlined style={{ color: '#52c41a' }} />}
          {isFailed && <CloseCircleOutlined style={{ color: '#ff4d4f' }} />}
        </span>
        <span className="tool-step-tool-icon">{icon}</span>
        <span className="tool-step-summary">
          {toolName} {targetSummary}{diffLabel}
          {isRunning && <span className="tool-step-ellipsis">...</span>}
        </span>
        <span className="tool-step-duration">{durationLabel}</span>
        {canExpand && (
          <span className="tool-step-toggle">
            {expanded ? <DownOutlined /> : <RightOutlined />}
          </span>
        )}
      </div>
      {expanded && output && (
        <div className="tool-step-output">
          <pre className="tool-step-output-pre">
            {output.length > MAX_OUTPUT_CHARS
              ? output.slice(0, MAX_OUTPUT_CHARS) + '\n\n... truncated'
              : output}
          </pre>
        </div>
      )}
    </div>
  )
})
