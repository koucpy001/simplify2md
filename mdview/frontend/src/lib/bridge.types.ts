// Platform-neutral types for the `@bridge` shim.
//
// Both implementations (bridge.wails.ts for desktop, bridge.android.ts for the
// native Android WebView shell) MUST expose the exact same 20 runtime symbols
// and the exact same return shapes. Keeping the shared types here is what makes
// that contract auditable: a divergence in a return shape is a type error in
// the other shim, not a runtime surprise.
//
// These are type-only declarations: they erase at build time and never appear
// in the runtime export set counted by test-bridge-parity.ts.

// Desktop returns `{ b64, mime }`; Android returns `{ url, mime }` (the file is
// streamed by a local endpoint instead of being base64-inlined over the bridge).
// Consumers branch on whichever field is present.
export interface ImageDataLike {
  b64?: string
  url?: string
  mime: string
}

// Desktop `main.OpenResult` has path/content/encoding/newline; Android adds the
// SAF-derived `name` (DISPLAY_NAME) and `readonly` (missing FLAG_SUPPORTS_WRITE).
// Both are optional so the desktop shape remains a superset-compatible subset.
export interface OpenResultLike {
  path: string
  content: string
  encoding: string
  newline: string
  readonly?: boolean
  name?: string
}

// The Go binding returns a bare `[]string` of paths; Android stores document
// URIs plus a provider-supplied display name. The unified entry carries both so
// the dropdown never renders a percent-encoded URI.
export interface RecentEntryLike {
  id: string
  name: string
}

export interface DraftInfoLike {
  key: string
  modTime: number
}

export interface UpdateInfoLike {
  hasUpdate: boolean
  latestTag: string
  htmlURL: string
}
