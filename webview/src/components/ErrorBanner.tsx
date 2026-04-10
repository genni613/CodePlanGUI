import { Alert } from 'antd'

interface Props {
  message: string
  onClose: () => void
}

export function ErrorBanner({ message, onClose }: Props) {
  return (
    <Alert
      message={message}
      type="error"
      closable
      onClose={onClose}
      className="error-banner"
    />
  )
}
