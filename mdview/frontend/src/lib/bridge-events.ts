// Bridge event registration + startup-ready gate (plan todo 17).
//
// Every Kotlin-to-JS event listener used to be registered inline in App.vue's
// onMounted, which made the registration set untestable outside a browser. This
// module owns the registration so a tsx test can drive it with a fake
// `EventsOn` and assert (a) that ALL five handlers are registered and (b) the
// `notifyBridgeReady` timing contract (a pending draft-recovery modal must NOT
// send ready; each onMounted exit path sends ready at most once).
//
// The five event names (three pre-existing + two new in todo 17):
//   mdview:confirm-exit  mdview:open-path  mdview:file-changed
//   mdview:ime           (new: keyboard height from the native inset listener)
//   mdview:open-text     (new: shared plain text; the requestSwitch guard and
//                         the Kotlin routing are completed by todo 18)
//
// `mdview:ime` and `mdview:open-text` are EVENT names, not bridge functions:
// the frozen 20-symbol `@bridge` export set is unchanged.

// Shape shared by both `@bridge` shims (wailsjs runtime + android EventsOn).
export type EventsOnLike = (eventName: string, callback: (...data: any[]) => void) => void

/** The five event names registerBridgeEvents must wire, in registration order. */
export const BRIDGE_EVENT_NAMES = [
  'mdview:confirm-exit',
  'mdview:open-path',
  'mdview:file-changed',
  'mdview:ime',
  'mdview:open-text',
] as const

/** Handlers App.vue supplies; each maps 1:1 to one event name above. */
export interface BridgeEventHandlers {
  confirmExit(): void
  openPath(path: string): void
  fileChanged(): void
  /** Receives the ALREADY-VALIDATED CSS value from imeInsetPx(). */
  imeHeight(cssValue: string): void
  openText(payload: OpenTextPayload): void
}

// ---- IME height -> CSS variable (pure, tsx-tested) ---------------------------

/** The CSS custom property the IME handler writes for the editor fallback. */
export const IME_HEIGHT_CSS_VAR = '--mdview-ime-height'

/**
 * Sanity ceiling for a reported keyboard height, in CSS pixels. A real IME is
 * at most a few hundred px; anything beyond this is a broken payload and must
 * not be allowed to push the whole page off-screen.
 */
export const MAX_IME_HEIGHT_PX = 10_000

/**
 * Keyboard height -> the value written into IME_HEIGHT_CSS_VAR.
 *
 * Pure and total: 0, negative, non-finite (NaN/Infinity) and missing values all
 * collapse to '0px' (keyboard closed); fractional heights are floored; absurdly
 * large heights are clamped to MAX_IME_HEIGHT_PX.
 */
export function imeInsetPx(rawHeight: unknown): string {
  const height = typeof rawHeight === 'number' ? rawHeight : Number.NaN
  if (!Number.isFinite(height) || height <= 0) return '0px'
  return `${Math.min(Math.floor(height), MAX_IME_HEIGHT_PX)}px`
}

// ---- mdview:open-text payload (pure, tsx-tested) -----------------------------

/**
 * Payload for opening shared plain text as an unnamed document. `filePath` is
 * always '' (never bound to a real document, so saving goes through saveAs and
 * cannot overwrite anything) and `dirty` is always true (the text is unsaved).
 */
export interface OpenTextPayload {
  filePath: string
  content: string
  dirty: boolean
}

export function openTextPayload(text: string): OpenTextPayload {
  return { filePath: '', content: typeof text === 'string' ? text : '', dirty: true }
}

// ---- registration ------------------------------------------------------------

/**
 * Registers all five event handlers on the injected `EventsOn`. App.vue's
 * onMounted calls this exactly once; the `mdview:ime` handler lives HERE and
 * must never also be registered in App.vue (double registration would run the
 * IME side effects twice per keyboard frame).
 */
export function registerBridgeEvents(events: EventsOnLike, handlers: BridgeEventHandlers): void {
  events('mdview:confirm-exit', () => {
    handlers.confirmExit()
  })
  // A second launch of the exe (another double-clicked file) is routed here
  // by the single-instance lock in main.go (desktop) / onNewIntent (todo 18).
  events('mdview:open-path', (...data: any[]) => {
    const p = data[0]
    if (typeof p === 'string' && p) handlers.openPath(p)
  })
  events('mdview:file-changed', () => {
    handlers.fileChanged()
  })
  events('mdview:ime', (...data: any[]) => {
    const payload = data[0]
    const rawHeight =
      typeof payload === 'object' && payload !== null && 'height' in payload
        ? (payload as { height?: unknown }).height
        : undefined
    handlers.imeHeight(imeInsetPx(rawHeight))
  })
  events('mdview:open-text', (...data: any[]) => {
    const text = data[0]
    handlers.openText(openTextPayload(typeof text === 'string' ? text : ''))
  })
}

// ---- startup-ready gate ------------------------------------------------------

/**
 * Gates the single `notifyBridgeReady()` call behind startup settle points.
 *
 * Contract (plan todo 17/18, review L1):
 * - `hold()` marks a settle point as pending; while any hold is open, ready is
 *   NOT sent (a pending draft-recovery modal keeps its hold open).
 * - `release()` closes one hold; when the last one closes, `notify` fires.
 * - `notify` fires AT MOST ONCE per gate lifetime: both onMounted exit paths
 *   (startup-file branch and recents-restore branch) run the same hold/release
 *   cycle, and the second cycle must be a no-op.
 */
export interface StartupReadyGate {
  hold(): void
  release(): void
  isReady(): boolean
}

export function createStartupReadyGate(notify: () => void): StartupReadyGate {
  let openHolds = 0
  let fired = false
  const maybeFire = (): void => {
    if (openHolds === 0 && !fired) {
      fired = true
      notify()
    }
  }
  return {
    hold: (): void => {
      openHolds += 1
    },
    release: (): void => {
      if (openHolds > 0) openHolds -= 1
      maybeFire()
    },
    isReady: (): boolean => fired,
  }
}
