// Pure mapping from bridge rejection tokens to user-facing remedies (plan todo 9c2).
//
// The Kotlin encoding layer rejects an unrepresentable save with the stable
// token `encoding-unmappable` (never Java's default REPLACE -> `?`). This module
// owns the pure mapping token -> remedy and the "另存为 UTF-8" save sequence,
// so the ordering contract is unit-testable without a browser:
//
//   fileEnc MUST be flipped to 'utf-8' BEFORE PickSavePath is invoked, so the
//   new file is written as UTF-8 (the plan's explicit ordering assertion).
//
// App.vue wires the real bridge functions into [saveAsUtf8]; test-token-mapping.ts
// drives it with recording fakes.

export const ENCODING_UNMAPPABLE = 'encoding-unmappable'

export type EncodingRemedy = 'save-as-utf8' | 'none'

/** True when a bridge rejection message carries the `encoding-unmappable` token. */
export function isEncodingUnmappable(message: unknown): boolean {
  return typeof message === 'string' && message.includes(ENCODING_UNMAPPABLE)
}

/** Maps a rejection message to the remedy the UI must offer. */
export function remedyForToken(message: unknown): EncodingRemedy {
  return isEncodingUnmappable(message) ? 'save-as-utf8' : 'none'
}

export interface SaveAsUtf8Deps {
  setFileEnc(enc: string): void
  pickSavePath(defaultName: string): Promise<string | null>
  saveFile(path: string, content: string, encoding: string, newline: string): Promise<void>
}

export interface SaveAsUtf8Result {
  saved: boolean
  path: string | null
}

/**
 * The "另存为 UTF-8" remedy. Contract: `setFileEnc('utf-8')` runs BEFORE
 * `pickSavePath`, so the picked file is written as UTF-8. A cancelled picker
 * returns `{ saved: false }` and the caller keeps the document dirty.
 */
export async function saveAsUtf8(
  deps: SaveAsUtf8Deps,
  defaultName: string,
  content: string,
  newline: string,
): Promise<SaveAsUtf8Result> {
  deps.setFileEnc('utf-8')
  const path = await deps.pickSavePath(defaultName)
  if (!path) return { saved: false, path: null }
  await deps.saveFile(path, content, 'utf-8', newline)
  return { saved: true, path }
}