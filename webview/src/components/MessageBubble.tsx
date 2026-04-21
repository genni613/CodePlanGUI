import { memo, useEffect, useRef, useState } from 'react'
import { CheckOutlined, CopyOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'
import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import type { ExecutionCardData } from './ExecutionCard'
import { ExecutionCard } from './ExecutionCard'
import { ToolStepsBar } from './ToolStepsBar'
import type { Message } from '../eventReducer'

const marked = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code, lang) {
      const language = hljs.getLanguage(lang) ? lang : 'plaintext'
      return hljs.highlight(code, { language }).value
    },
  }),
)

export type { Message } from '../eventReducer'

interface MessageBubbleProps {
  message: Message
}

async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Fall through to the legacy copy path.
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

export const MessageBubble = memo(function MessageBubble({ message }: MessageBubbleProps) {
  if (message.role === 'execution' && message.execution) {
    return (
      <div className="message-row message-row-assistant">
        <ExecutionCard data={message.execution} />
      </div>
    )
  }

  const isUser = message.role === 'user'
  const htmlRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (isUser || !htmlRef.current) return

    const raw = marked.parse(message.content) as string
    htmlRef.current.innerHTML = DOMPurify.sanitize(raw)

    htmlRef.current.querySelectorAll('pre').forEach((pre) => {
      const code = pre.querySelector('code')
      if (!code || pre.querySelector('button')) return

      pre.classList.add('assistant-code-block')
      const mount = document.createElement('div')
      mount.className = 'bubble-copy-anchor'
      pre.appendChild(mount)

      const root = document.createElement('div')
      mount.appendChild(root)
      // Use native DOM button to keep the block self-contained after innerHTML writes.
      const button = document.createElement('button')
      button.className = 'bubble-copy-fallback'
      button.textContent = 'Copy'
      button.onclick = async () => {
        const success = await copyText(code.textContent || '')
        if (!success) return
        button.textContent = 'Copied'
        window.setTimeout(() => {
          button.textContent = 'Copy'
        }, 2000)
      }
      root.appendChild(button)
    })
  }, [isUser, message.content])

  if (isUser) {
    return (
      <div className="message-row message-row-user">
        <div className="message-bubble message-bubble-user">
          <Typography.Text>{message.content}</Typography.Text>
        </div>
      </div>
    )
  }

  return (
    <div className="message-row message-row-assistant">
      <div className="message-bubble message-bubble-assistant">
        <div className="assistant-bubble-header">
          <span className="assistant-bubble-label">assistant</span>
          <CopyButton text={message.content} />
        </div>
        {message.toolSteps && message.toolSteps.length > 0 && (
          <ToolStepsBar steps={message.toolSteps} />
        )}
        <div ref={htmlRef} className="assistant-markdown" />
        {message.isStreaming && <span className="stream-cursor" />}
      </div>
    </div>
  )
})
