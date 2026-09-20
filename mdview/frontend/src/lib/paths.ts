// Platform-neutral path utilities (plan todo 12).
//
// `id` is whatever the bridge uses to address a document: a Windows path on
// desktop, an opaque `content://` URI on Android. These helpers only split on
// `/` and `\`; they never decode percent-escapes and never assume a filesystem.
//
// baseName is a FALLBACK and desktop-path helper only — on Android the real
// display name comes from the provider's DISPLAY_NAME (`OpenResult.name`), never
// from the URI's last segment, which is junk for opaque providers.

export function baseName(id: string): string {
  return id.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || id
}

/** Parent directory of `id` without a trailing separator ('' when none). */
export function dirOf(id: string): string {
  const trimmed = id.replace(/[\\/]+$/, '')
  const idx = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  return idx <= 0 ? '' : trimmed.slice(0, idx)
}

/**
 * The name shown in the toolbar / save-as default / status bar, in priority
 * order: provider display name, path basename, untitled fallback. This is the
 * single source for `displayPath` so no site can show a raw URI.
 */
export function selectDisplayName(name: string | null | undefined, path: string, fallback = '未标题.md'): string {
  const trimmed = (name || '').trim()
  if (trimmed) return trimmed
  if (path) return baseName(path)
  return fallback
}
