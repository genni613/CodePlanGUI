interface FileChangeInlineProps {
  path: string
  added: number
  removed: number
  onOpenFile?: (path: string) => void
}

export function FileChangeInline({ path, added, removed, onOpenFile }: FileChangeInlineProps) {
  const fileName = path.split('/').pop() || path
  const stats = `(+${added} / -${removed})`

  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        padding: '2px 8px',
        borderRadius: 4,
        background: 'var(--bg-secondary, rgba(255,255,255,0.06))',
        fontSize: 12,
        color: 'var(--text-secondary, #888)',
        cursor: onOpenFile ? 'pointer' : 'default',
      }}
      onClick={() => onOpenFile?.(path)}
      title={path}
    >
      <span>{fileName}</span>
      <span>{stats}</span>
      {onOpenFile && (
        <a
          style={{ color: 'var(--color-primary, #d2a15e)', fontSize: 11 }}
          onClick={(e) => { e.stopPropagation(); onOpenFile(path) }}
        >
          Open in Editor
        </a>
      )}
    </div>
  )
}
