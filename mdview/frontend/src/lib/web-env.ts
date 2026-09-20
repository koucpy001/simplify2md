// DOM storage / secure-context probe (plan todo 20b).
//
// The Android WebView serves the app from `https://appassets.androidplatform.net`
// so `window.isSecureContext` should be true and `localStorage` available
// (`domStorageEnabled`). Neither is guaranteed on every device / WebView build,
// and a silent failure would lose view-mode / theme persistence without any
// explanation. This probe returns a user-facing message when the environment is
// degraded so the caller can surface it in the status bar.

export function probeDomEnvironment(
  win: { isSecureContext?: boolean; localStorage?: Storage | null },
): string | null {
  const problems: string[] = []
  if (win.isSecureContext !== true) {
    problems.push('页面不在安全上下文中（isSecureContext=false）')
  }
  try {
    const storage = win.localStorage
    if (!storage) {
      problems.push('localStorage 不可用')
    } else {
      const key = 'mdview.probe'
      storage.setItem(key, '1')
      const ok = storage.getItem(key) === '1'
      storage.removeItem(key)
      if (!ok) problems.push('localStorage 读写失败')
    }
  } catch {
    problems.push('localStorage 访问被拒绝（如隐私模式）')
  }
  if (problems.length === 0) return null
  return '存储环境受限：' + problems.join('；') + '，视图/主题等偏好可能无法保存'
}
