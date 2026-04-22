import { useState, useEffect, useRef, useCallback, memo } from 'react'
import { CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined, RightOutlined, DownOutlined } from '@ant-design/icons'
import type { ToolStepInfo } from '../groupReducer'
import { ToolStepRow } from './ToolStepRow'

interface ToolStepsBarProps {
  steps: ToolStepInfo[]
}

export const ToolStepsBar = memo(function ToolStepsBar({ steps }: ToolStepsBarProps) {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())
  const [barExpanded, setBarExpanded] = useState(true)
  const wasAllDone = useRef(false)

  const hasRunning = steps.some(s => s.status === 'running')
  const allDone = steps.length > 0 && !hasRunning

  // Auto-collapse when transitioning from "has running" to "all done"
  useEffect(() => {
    if (allDone && !wasAllDone.current) {
      setBarExpanded(false)
    }
    wasAllDone.current = allDone
  }, [allDone])

  // When new running steps appear (new round of tools), auto-expand
  useEffect(() => {
    if (hasRunning) {
      setBarExpanded(true)
    }
  }, [hasRunning])

  const toggleExpand = useCallback((id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }, [])

  if (steps.length === 0) return null

  // Summary line when collapsed
  if (!barExpanded) {
    const completed = steps.filter(s => s.status === 'completed').length
    const failed = steps.filter(s => s.status === 'failed').length
    const totalMs = steps.reduce((sum, s) => sum + (s.durationMs ?? 0), 0)
    const durationLabel = totalMs < 1000 ? `${totalMs}ms` : `${(totalMs / 1000).toFixed(1)}s`

    return (
      <div className="tool-steps-bar">
        <div
          className="tool-step-summary-row"
          onClick={() => setBarExpanded(true)}
        >
          <span className="tool-step-status-icon">
            {failed > 0
              ? <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
              : <CheckCircleOutlined style={{ color: '#52c41a' }} />
            }
          </span>
          <span className="tool-step-summary-text">
            {failed > 0
              ? `${completed} tools completed · ${failed} failed`
              : `${completed} tools completed`
            }
          </span>
          <span className="tool-step-duration">{durationLabel}</span>
          <span className="tool-step-toggle"><RightOutlined /></span>
        </div>
      </div>
    )
  }

  // Expanded: show all steps (with running indicator if any)
  return (
    <div className="tool-steps-bar">
      {!hasRunning && steps.length > 1 && (
        <div
          className="tool-step-collapse-row"
          onClick={() => setBarExpanded(false)}
        >
          <span className="tool-step-status-icon">
            <CheckCircleOutlined style={{ color: '#52c41a' }} />
          </span>
          <span className="tool-step-summary-text">
            {steps.length} tools
          </span>
          <span className="tool-step-toggle"><DownOutlined /></span>
        </div>
      )}
      {steps.map(step => (
        <ToolStepRow
          key={step.id}
          {...step}
          expanded={expandedIds.has(step.id)}
          onToggleExpand={() => toggleExpand(step.id)}
        />
      ))}
    </div>
  )
})
