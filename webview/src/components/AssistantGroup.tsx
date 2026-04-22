import { memo, useState } from 'react'
import { CheckOutlined, CopyOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'
import type { AssistantGroup as AssistantGroupType, ToolStepInfo } from '../groupReducer'
import { AssistantMarkdown } from './AssistantMarkdown'
import { ExecutionCard } from './ExecutionCard'
import { ToolStepsBar } from './ToolStepsBar'

async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Fall through
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()

  try {
    return document.execCommand('copy')
  } finally {
    document.body.removeChild(textarea)
  }
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    const success = await copyText(text)
    if (!success) return

    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Button
      type="text"
      size="small"
      icon={copied ? <CheckOutlined /> : <CopyOutlined />}
      onClick={handleCopy}
      className="bubble-copy-button"
    />
  )
}

interface AssistantGroupProps {
  group: AssistantGroupType
  toolSteps?: ToolStepInfo[]
}

export const AssistantGroup = memo(function AssistantGroup({ group, toolSteps }: AssistantGroupProps) {
  return (
    <div className="assistant-group">
      {toolSteps && toolSteps.length > 0 && (
        <ToolStepsBar steps={toolSteps} />
      )}
      {group.children.map(child => {
        if (child.kind === 'execution') {
          return <ExecutionCard key={child.data.requestId} data={child.data} />
        }
        return (
          <div key={child.id} className="message-row message-row-assistant">
            <div className="message-bubble message-bubble-assistant">
              <div className="assistant-bubble-header">
                <span className="assistant-bubble-label">assistant</span>
                <CopyButton text={child.content} />
              </div>
              <AssistantMarkdown content={child.content} />
              {child.isStreaming && <span className="stream-cursor" />}
            </div>
          </div>
        )
      })}
    </div>
  )
})
