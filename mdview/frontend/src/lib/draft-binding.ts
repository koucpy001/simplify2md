// Draft ↔ document binding decision (plan todo 12, review M2/D4).
//
// A draft key is `sha1(uri)` (or the literal 'untitled'), which is irreversible:
// there is no way to recover the document a named draft belongs to. The only
// safe test is whether the key equals the key of the CURRENTLY loaded document.
//
// Recovery must therefore decide between:
//   - 'document': the draft belongs to the open document; keep `filePath`.
//   - 'untitled': the draft cannot be bound (unnamed draft, or a named draft
//     whose key does not match the open document). `filePath` MUST be cleared,
//     or saving would write the recovered draft back over the startup-restored
//     recents file it never came from.
//
// Kept pure so the branch is unit-testable without a browser.

export const UNTITLED_DRAFT_KEY = 'untitled'

export type DraftTarget =
  | { kind: 'document'; path: string }
  | { kind: 'untitled' }

export interface DraftBindingInput {
  /** Key of the draft being recovered. */
  draftKey: string
  /** Key the currently loaded document would be saved under. */
  currentDraftKey: string
  /** `filePath` of the currently loaded document ('' when untitled). */
  currentFilePath: string
}

export function decideDraftTarget(input: DraftBindingInput): DraftTarget {
  if (input.draftKey === UNTITLED_DRAFT_KEY) return { kind: 'untitled' }
  if (input.currentFilePath && input.currentDraftKey === input.draftKey) {
    return { kind: 'document', path: input.currentFilePath }
  }
  return { kind: 'untitled' }
}

/** The `filePath` a recovered draft must adopt: '' for an unbound draft. */
export function draftFilePath(target: DraftTarget): string {
  return target.kind === 'untitled' ? '' : target.path
}
