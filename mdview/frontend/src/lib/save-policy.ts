// Pure save-action policy for the shared App.vue (plan todo 10(g2)).
//
// `save()` must not attempt `SaveFile` on a URI the provider refuses to write
// (Android SAF cloud / mail-attachment providers report no FLAG_SUPPORTS_WRITE),
// and an untitled document has nowhere to save to either. Both cases route
// through `saveAs()`. Keeping the rule in a pure function lets
// test-save-policy.ts cover the four cases without a browser or a device.
//
// App.vue is shared with the desktop build; on desktop `readonly` is always
// false, so behaviour there is unchanged.

export interface SaveState {
  filePath: string
  readonly: boolean
}

export type SaveAction = 'save' | 'saveAs'

/**
 * Routing rule:
 *  - no path yet (untitled)  -> saveAs
 *  - read-only document      -> saveAs
 *  - writable document       -> save
 */
export function saveAction(state: SaveState): SaveAction {
  if (!state.filePath) return 'saveAs'
  return state.readonly ? 'saveAs' : 'save'
}
