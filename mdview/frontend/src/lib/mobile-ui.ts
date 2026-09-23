// Pure mobile-UI decision policies for the shared App.vue (plan android-gui-mobile).
//
// The phone UI is the desktop three-pane layout verbatim; these three pure
// functions carry the only behavioural decisions of the mobile adaptation so
// they can be gated without a browser or a device (test-mobile-ui.ts).
//
// App.vue is shared with the desktop build: on desktop `isPhoneLayout` is
// false, so every function returns the historical behaviour unchanged.

export type ViewMode = 'split' | 'edit' | 'preview'
export type Theme = 'light' | 'dark'

/**
 * D1 + D4: final view mode for this session.
 *
 * Rules, in priority order:
 *  1. On a phone layout (< 640px, D4) 'split' is never allowed: a stored
 *     'split' preference falls back to 'preview' (never a broken two-column
 *     phone layout), and so does an absent preference (D1: reading-first).
 *  2. A stored user choice other than 'split' always wins, on every platform.
 *  3. Desktop with no stored choice keeps the historical 'split' default.
 */
export function initialViewMode(input: {
  isPhoneLayout: boolean
  stored: string | null
}): ViewMode {
  const stored = input.stored
  if (input.isPhoneLayout) {
    if (stored === 'edit' || stored === 'preview') return stored
    return 'preview' // covers null, 'split', and any garbage value
  }
  if (stored === 'split' || stored === 'edit' || stored === 'preview') return stored
  return 'split'
}

/**
 * D4 companion: a live preference change must not be able to put a phone into
 * 'split' (e.g. a stored value restored before layout was measured, or a
 * stale localStorage from a tablet-width session). Non-split values pass
 * through untouched so the user's explicit choice is never overwritten.
 */
export function coerceViewModeForPhone(mode: ViewMode): ViewMode {
  return mode === 'split' ? 'preview' : mode
}

/**
 * D2: toolbar overflow grouping. The main row keeps the actions a reader needs
 * at a glance; everything else moves into the `⋯` menu. Pure data so the
 * template stays declarative and the grouping is testable.
 *
 * Main row: 打开 / 保存 / 查找 / 编辑·预览 toggle / ⋯
 * Overflow: 另存为 / 分屏 (hidden on phone by D4) / 大纲 / 暗色 / 检查更新
 */
export interface ToolbarAction {
  id: 'open' | 'save' | 'saveAs' | 'find' | 'outline' | 'theme' | 'update'
}

export function toolbarOverflowGrouping(isPhoneLayout: boolean): {
  mainRow: ToolbarAction[]
  overflow: ToolbarAction[]
} {
  if (isPhoneLayout) {
    return {
      mainRow: [
        { id: 'open' },
        { id: 'save' },
        { id: 'find' },
      ],
      overflow: [
        { id: 'saveAs' },
        { id: 'outline' },
        { id: 'theme' },
        { id: 'update' },
      ],
    }
  }
  // Desktop: everything stays on the single main row (unchanged behaviour).
  return {
    mainRow: [
      { id: 'open' },
      { id: 'save' },
      { id: 'saveAs' },
      { id: 'find' },
      { id: 'outline' },
      { id: 'theme' },
      { id: 'update' },
    ],
    overflow: [],
  }
}

/**
 * D5: initial theme. Only when the user has never chosen (no stored value)
 * does the system preference apply; a stored choice always wins; the fallback
 * stays 'light' (historical behaviour) when neither is available.
 */
export function initialTheme(input: {
  stored: string | null
  systemPrefersDark: boolean | null // null = matchMedia unavailable
}): Theme {
  if (input.stored === 'dark') return 'dark'
  if (input.stored === 'light') return 'light'
  if (input.systemPrefersDark === true) return 'dark'
  return 'light'
}
