// Default `@bridge` implementation: the Wails v2 desktop binding.
//
// vite.config.ts aliases `@bridge` here unless the build runs with
// `--mode android`; tsconfig `paths` point here as well so `vue-tsc` typechecks
// against this surface. App.vue imports every binding through `@bridge`, which
// is why the relative `../wailsjs/...` imports that used to live in App.vue are
// gone: Vite's `resolve.alias` cannot rewrite a relative import specifier.
//
// Behavioural contract: on desktop this file must not change App.vue's runtime
// behaviour. Functions whose Go return shape already matches the unified
// contract are pure re-exports; the three whose shape differs (OpenFile,
// ReadFileAt, GetRecents) are adapted below. The generated files under
// ../../wailsjs are NEVER edited (wails regenerates them).

import {
  GetRecents as wailsGetRecents,
  OpenFile as wailsOpenFile,
  ReadFileAt as wailsReadFileAt,
} from '../../wailsjs/go/main/App'
import type { main } from '../../wailsjs/go/models'
import type {
  DraftInfoLike,
  ImageDataLike,
  OpenResultLike,
  RecentEntryLike,
  UpdateInfoLike,
} from './bridge.types'

// Shape-identical bindings: re-exported unchanged (pure pass-through).
export {
  CheckForUpdate,
  ClearDraft,
  ClearRecents,
  ConfirmExit,
  GetStartupFile,
  ListDrafts,
  LoadDraft,
  LoadImageForSrc,
  PickSavePath,
  RemoveRecent,
  SaveDraft,
  SaveFile,
  SetDirty,
  SetTitle,
} from '../../wailsjs/go/main/App'
export { EventsOn, BrowserOpenURL } from '../../wailsjs/runtime/runtime'

// Unified types are re-exported so App.vue can `import type { ... } from '@bridge'`.
export type {
  DraftInfoLike,
  ImageDataLike,
  OpenResultLike,
  RecentEntryLike,
  UpdateInfoLike,
} from './bridge.types'

// Desktop paths are OS paths ("C:\\docs\\note.md"); the Android contract wants
// a display name in the recent entry. This mirrors App.vue's historical
// baseName() so the dropdown label is byte-for-byte what it always was.
function baseName(p: string): string {
  return p.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || p
}

// Desktop always returns writable whole files: `readonly` is always false and
// `name` is the basename the toolbar already displayed.
function toOpenResult(r: main.OpenResult): OpenResultLike {
  return {
    path: r.path,
    content: r.content,
    encoding: r.encoding,
    newline: r.newline,
    readonly: false,
    name: baseName(r.path),
  }
}

export function OpenFile(): Promise<OpenResultLike> {
  return wailsOpenFile().then(toOpenResult)
}

export function ReadFileAt(path: string): Promise<OpenResultLike> {
  return wailsReadFileAt(path).then(toOpenResult)
}

// Go returns `[]string` of paths. The unified entry is `{ id, name }[]`; on
// desktop `id` IS the path (so routing is unchanged) and `name` is its basename
// (so the label is unchanged).
export async function GetRecents(): Promise<RecentEntryLike[]> {
  const paths = (await wailsGetRecents()) || []
  return paths.map((id) => ({ id, name: baseName(id) }))
}

// Desktop has no startup-ready handshake: the Wails runtime exists before the
// page script runs, so there is nothing to signal. Part of the fixed 20-symbol
// cross-platform contract (Android forwards this to window.__bridge.__bridgeReady).
export function notifyBridgeReady(): void {
  // intentionally empty on desktop
}
