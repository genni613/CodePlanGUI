export function prepareSendPayload(
  inputText: string,
  isLoading: boolean,
  isBridgeReady: boolean,
): { text: string } | null {
  const text = inputText.trim()
  if (!isBridgeReady || isLoading || !text) {
    return null
  }
  return { text }
}
