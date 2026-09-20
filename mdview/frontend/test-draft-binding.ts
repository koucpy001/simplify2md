// Draft-binding gate (plan todo 12, review M2/D4 data-loss guard).
//
// sha1(uri) is irreversible, so a named draft can only be bound by key equality
// with the currently loaded document. An unbound draft MUST clear filePath, or
// saving it would overwrite the startup-restored recents[0] file.
//
// Run: npx tsx test-draft-binding.ts

import assert from 'node:assert/strict'
import { decideDraftTarget, draftFilePath, UNTITLED_DRAFT_KEY } from './src/lib/draft-binding'

// ---- unnamed draft: always becomes an untitled document ---------------------
{
  const target = decideDraftTarget({
    draftKey: UNTITLED_DRAFT_KEY,
    currentDraftKey: 'abc123',
    currentFilePath: 'content://provider/document/msf%3A1000000123',
  })
  assert.deepStrictEqual(target, { kind: 'untitled' })
  assert.strictEqual(draftFilePath(target), '', 'unnamed draft must clear filePath (no overwrite of recents[0])')
  console.log("unnamed draft      -> untitled, filePath=''")
}

// ---- named draft whose key matches the open document -----------------------
{
  const target = decideDraftTarget({
    draftKey: 'abc123',
    currentDraftKey: 'abc123',
    currentFilePath: 'content://provider/document/opened',
  })
  assert.deepStrictEqual(target, { kind: 'document', path: 'content://provider/document/opened' })
  assert.strictEqual(draftFilePath(target), 'content://provider/document/opened')
  console.log('named draft, match -> document, filePath kept')
}

// ---- named draft that does NOT match the open document ---------------------
{
  const target = decideDraftTarget({
    draftKey: 'abc123',
    currentDraftKey: 'def456',
    currentFilePath: 'content://provider/document/other',
  })
  assert.deepStrictEqual(target, { kind: 'untitled' })
  assert.strictEqual(draftFilePath(target), '', 'a mismatched named draft must not inherit the open file')
  console.log("named draft, mismatch -> untitled, filePath=''")
}

// ---- named draft with no document currently open ---------------------------
{
  const target = decideDraftTarget({
    draftKey: 'abc123',
    currentDraftKey: UNTITLED_DRAFT_KEY,
    currentFilePath: '',
  })
  assert.deepStrictEqual(target, { kind: 'untitled' })
  assert.strictEqual(draftFilePath(target), '')
  console.log("named draft, no open doc -> untitled, filePath=''")
}

// ---- Windows-path document binds the same way ------------------------------
{
  const target = decideDraftTarget({
    draftKey: 'abc123',
    currentDraftKey: 'abc123',
    currentFilePath: 'C:\\docs\\note.md',
  })
  assert.strictEqual(draftFilePath(target), 'C:\\docs\\note.md')
  console.log('desktop path, match -> document, filePath kept')
}

console.log('')
console.log('DRAFT BINDING OK: only a key-matched draft keeps the open filePath.')
