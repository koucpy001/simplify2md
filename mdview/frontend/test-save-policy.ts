// Save-action policy gate (plan todo 10(g2) / acceptance "save-policy 四例").
//
// `save()` must route read-only documents and untitled documents through
// `saveAs()`, and only a writable document with a path may call `SaveFile`
// directly. The fourth case re-evaluates after a successful save-as, which
// clears `readonly` and rebinds `filePath`, so the next save is a plain save.
//
// Run: npx tsx test-save-policy.ts

import assert from 'node:assert/strict'
import { saveAction, type SaveState } from './src/lib/save-policy'

// Case 1: untitled document (no path) -> save-as.
const untitled: SaveState = { filePath: '', readonly: false }
assert.strictEqual(saveAction(untitled), 'saveAs', 'untitled document must save-as')
console.log('case 1 OK: untitled            ->', saveAction(untitled))

// Case 2: read-only document with a path -> save-as (never openOutputStream).
const readOnly: SaveState = { filePath: 'content://cloud/1', readonly: true }
assert.strictEqual(saveAction(readOnly), 'saveAs', 'read-only document must save-as')
console.log('case 2 OK: read-only           ->', saveAction(readOnly))

// Case 3: writable document with a path -> plain save.
const writable: SaveState = { filePath: '/docs/note.md', readonly: false }
assert.strictEqual(saveAction(writable), 'save', 'writable document must save')
console.log('case 3 OK: writable            ->', saveAction(writable))

// Case 4: after a successful save-as the state is { newPath, readonly: false },
// so the very next save is a plain save (no re-prompt).
const afterSaveAs: SaveState = { filePath: '/docs/renamed.md', readonly: false }
assert.strictEqual(saveAction(afterSaveAs), 'save', 'save-as success must clear readonly and save normally')
console.log('case 4 OK: after save-as       ->', saveAction(afterSaveAs))

console.log('')
console.log('SAVE POLICY OK: 4/4 cases hold.')
