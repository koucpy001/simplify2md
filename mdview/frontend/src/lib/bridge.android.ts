// Android `@bridge` implementation: the native WebView shell.
//
// This module is the alias target only for Vite builds run with
// `--mode android` (see vite.config.ts), and it is the reason the packaged
// bundle contains the greppable marker `__bridgeCall` (asserted by
// .github/workflows/android-ci.yml). The default desktop bundle never includes
// this file and therefore never references window.__bridge*.
//
// Transport contract, owned by android/app/src/main/java/.../bridge/Bridge.kt:
//   JS   -> Kotlin : window.__bridge.__bridgeCall(requestId, method, argsJson)  (void)
//                    window.__bridge.__bridgeReady()
//   Kotlin -> JS   : window.__bridgeResolve(requestId, json)
//                    window.__bridgeReject(requestId, message)
//                    window.__bridgeEmit(name, payloadJson)
//                    window.__bridgeReset()
//
// The 20 exported symbols are identical to bridge.wails.ts; test-bridge-parity.ts
// enforces that.

import type {
  DraftInfoLike,
  ImageDataLike,
  OpenResultLike,
  RecentEntryLike,
  UpdateInfoLike,
} from './bridge.types'

export type {
  DraftInfoLike,
  ImageDataLike,
  OpenResultLike,
  RecentEntryLike,
  UpdateInfoLike,
} from './bridge.types'

interface BridgeHost {
  __bridgeCall(requestId: string, method: string, argsJson: string): void
  __bridgeReady?(): void
}

declare global {
  interface Window {
    __bridge: BridgeHost
    __bridgeResolve?: (requestId: string, json: string) => void
    __bridgeReject?: (requestId: string, message: string) => void
    __bridgeEmit?: (name: string, payloadJson: string) => void
    __bridgeReset?: () => void
  }
}

interface PendingCall {
  resolve: (value: unknown) => void
  reject: (reason: unknown) => void
}

const pending = new Map<string, PendingCall>()
const listeners = new Map<string, Set<(...data: any[]) => void>>()
let requestSeq = 0

function nextRequestId(): string {
  requestSeq += 1
  return `b${requestSeq.toString(36)}-${Date.now().toString(36)}`
}

// Wrap the void Kotlin call into a Promise settled by __bridgeResolve /
// __bridgeReject. The requestId is generated here, so concurrent calls never
// collide and a late reply after a reset is simply ignored.
function bridgeCall<T>(method: string, args: unknown[]): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const requestId = nextRequestId()
    pending.set(requestId, {
      resolve: resolve as (value: unknown) => void,
      reject,
    })
    try {
      window.__bridge.__bridgeCall(requestId, method, JSON.stringify(args))
    } catch (e) {
      // A synchronous refusal must still settle the Promise; leaving it pending
      // is exactly the "opening… forever" hang the reset path guards against.
      pending.delete(requestId)
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

// Kotlin calls window.__bridgeReset() when the Activity/WebView is destroyed.
// Every in-flight Promise MUST settle or the UI hangs forever on its await.
// The message contains `cancelled` so App.vue's existing /cancelled/i handling
// (openFileDialog) treats the teardown as a normal user cancellation.
function rejectAllPending(): void {
  const error = new Error('bridge-reset: cancelled')
  for (const call of pending.values()) call.reject(error)
  pending.clear()
}

if (typeof window !== 'undefined') {
  window.__bridgeResolve = (requestId: string, json: string): void => {
    const call = pending.get(requestId)
    if (!call) return
    pending.delete(requestId)
    try {
      call.resolve(json ? JSON.parse(json) : undefined)
    } catch (e) {
      call.reject(e instanceof Error ? e : new Error(String(e)))
    }
  }

  window.__bridgeReject = (requestId: string, message: string): void => {
    const call = pending.get(requestId)
    if (!call) return
    pending.delete(requestId)
    call.reject(new Error(message))
  }

  window.__bridgeEmit = (name: string, payloadJson: string): void => {
    let payload: unknown
    try {
      payload = payloadJson ? JSON.parse(payloadJson) : undefined
    } catch {
      payload = undefined
    }
    const set = listeners.get(name)
    if (!set) return
    // Iterate a copy so a handler that unsubscribes itself cannot mutate the
    // live set mid-dispatch.
    for (const callback of [...set]) {
      try {
        callback(payload)
      } catch (e) {
        console.error('[bridge] event handler failed:', name, e)
      }
    }
  }

  window.__bridgeReset = (): void => {
    rejectAllPending()
  }
}

// ---- the 17 App functions ---------------------------------------------------

export function OpenFile(): Promise<OpenResultLike> {
  return bridgeCall<OpenResultLike>('OpenFile', [])
}

export function SaveFile(path: string, content: string, encoding: string, newline: string): Promise<void> {
  return bridgeCall<void>('SaveFile', [path, content, encoding, newline])
}

export function PickSavePath(defaultName: string): Promise<string> {
  return bridgeCall<string>('PickSavePath', [defaultName])
}

export function LoadImageForSrc(src: string, filePath: string, imageRoot: string): Promise<ImageDataLike> {
  return bridgeCall<ImageDataLike>('LoadImageForSrc', [src, filePath, imageRoot])
}

export function ReadFileAt(path: string): Promise<OpenResultLike> {
  return bridgeCall<OpenResultLike>('ReadFileAt', [path])
}

export function GetRecents(): Promise<RecentEntryLike[]> {
  return bridgeCall<RecentEntryLike[]>('GetRecents', [])
}

export function GetStartupFile(): Promise<string> {
  return bridgeCall<string>('GetStartupFile', [])
}

export function RemoveRecent(id: string): Promise<void> {
  return bridgeCall<void>('RemoveRecent', [id])
}

export function SetDirty(dirty: boolean): Promise<void> {
  return bridgeCall<void>('SetDirty', [dirty])
}

export function SetTitle(title: string): Promise<void> {
  return bridgeCall<void>('SetTitle', [title])
}

export function ConfirmExit(): Promise<void> {
  return bridgeCall<void>('ConfirmExit', [])
}

export function CheckForUpdate(): Promise<UpdateInfoLike> {
  return bridgeCall<UpdateInfoLike>('CheckForUpdate', [])
}

export function SaveDraft(key: string, content: string): Promise<void> {
  return bridgeCall<void>('SaveDraft', [key, content])
}

export function LoadDraft(key: string): Promise<string> {
  return bridgeCall<string>('LoadDraft', [key])
}

export function ListDrafts(): Promise<DraftInfoLike[]> {
  return bridgeCall<DraftInfoLike[]>('ListDrafts', [])
}

export function ClearDraft(key: string): Promise<void> {
  return bridgeCall<void>('ClearDraft', [key])
}

export function ClearRecents(): Promise<void> {
  return bridgeCall<void>('ClearRecents', [])
}

// ---- the 2 runtime symbols + the ready handshake ----------------------------

export function EventsOn(eventName: string, callback: (...data: any[]) => void): () => void {
  let set = listeners.get(eventName)
  if (!set) {
    set = new Set()
    listeners.set(eventName, set)
  }
  set.add(callback)
  return (): void => {
    const current = listeners.get(eventName)
    if (!current) return
    current.delete(callback)
    if (current.size === 0) listeners.delete(eventName)
  }
}

export function BrowserOpenURL(url: string): void {
  // Fire-and-forget to match the Wails runtime's `void` signature; an unhandled
  // rejection must never leak from a link click.
  bridgeCall<void>('BrowserOpenURL', [url]).catch(() => {})
}

export function notifyBridgeReady(): void {
  if (typeof window === 'undefined') return
  try {
    window.__bridge?.__bridgeReady?.()
  } catch (e) {
    console.error('[bridge] ready signal failed:', e)
  }
}
