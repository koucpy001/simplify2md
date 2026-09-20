// Token -> user-action mapping gate for the encoding layer (plan todo 9c2).
//
// The Kotlin encoding layer rejects an unrepresentable save with the stable
// token `encoding-unmappable` (never Java's default REPLACE -> `?`). This test
// locks the pure mapping token -> "另存为 UTF-8" remedy and the ordering
// contract that fileEnc flips to 'utf-8' BEFORE PickSavePath is invoked, so the
// new file is written as UTF-8. App.vue wires the real bridge functions into
// saveAsUtf8; here it is driven with recording fakes.
//
// Run: npx tsx test-token-mapping.ts

import assert from 'node:assert/strict'
import {
  ENCODING_UNMAPPABLE,
  isEncodingUnmappable,
  remedyForToken,
  saveAsUtf8,
  type SaveAsUtf8Deps,
} from './src/lib/encoding-token'

// ---- pure mapping: token -> remedy -----------------------------------------

assert.strictEqual(remedyForToken(`encoding-unmappable: cannot represent content as ISO-8859-1`), 'save-as-utf8')
assert.strictEqual(remedyForToken(ENCODING_UNMAPPABLE), 'save-as-utf8')
assert.strictEqual(remedyForToken('cancelled'), 'none')
assert.strictEqual(remedyForToken('some other bridge error'), 'none')
assert.strictEqual(remedyForToken(''), 'none')
assert.strictEqual(remedyForToken(undefined), 'none')
assert.strictEqual(remedyForToken(null), 'none')
assert.strictEqual(remedyForToken(42), 'none')
assert.strictEqual(remedyForToken(new Error('encoding-unmappable: x')), 'none', 'an Error object is not a message string')

assert.strictEqual(isEncodingUnmappable('encoding-unmappable: x'), true)
assert.strictEqual(isEncodingUnmappable('cancelled'), false)
assert.strictEqual(isEncodingUnmappable(undefined), false)

console.log('mapping OK: encoding-unmappable -> save-as-utf8, everything else -> none')

// ---- ordering: fileEnc flips BEFORE PickSavePath ---------------------------

interface RecordingDeps {
  calls: string[]
  pickResult: string | null
  saveError?: Error
}

function makeDeps(rec: RecordingDeps): SaveAsUtf8Deps {
  return {
    setFileEnc(enc: string) {
      rec.calls.push(`setFileEnc(${enc})`)
    },
    async pickSavePath(name: string) {
      rec.calls.push(`pickSavePath(${name})`)
      return rec.pickResult
    },
    async saveFile(path: string, content: string, encoding: string, newline: string) {
      rec.calls.push(`saveFile(${path},${encoding},${newline})`)
      if (rec.saveError) throw rec.saveError
    },
  }
}

{
  const rec: RecordingDeps = { calls: [], pickResult: '/tmp/out.md' }
  const result = await saveAsUtf8(makeDeps(rec), 'note.md', 'café', 'lf')
  assert.deepStrictEqual(rec.calls, [
    'setFileEnc(utf-8)',
    'pickSavePath(note.md)',
    'saveFile(/tmp/out.md,utf-8,lf)',
  ], 'fileEnc must flip to utf-8 BEFORE PickSavePath, and the save must use utf-8')
  assert.strictEqual(result.saved, true)
  assert.strictEqual(result.path, '/tmp/out.md')
  console.log('ordering OK: setFileEnc(utf-8) precedes pickSavePath; saveFile uses utf-8')
}

{
  // A cancelled picker must not call saveFile and must report saved=false.
  const rec: RecordingDeps = { calls: [], pickResult: null }
  const result = await saveAsUtf8(makeDeps(rec), 'note.md', 'café', 'lf')
  assert.deepStrictEqual(rec.calls, ['setFileEnc(utf-8)', 'pickSavePath(note.md)'])
  assert.strictEqual(result.saved, false)
  assert.strictEqual(result.path, null)
  console.log('cancel OK: picker cancel -> no saveFile, saved=false')
}

{
  // A save failure propagates so the caller keeps the document dirty.
  const rec: RecordingDeps = { calls: [], pickResult: '/tmp/out.md', saveError: new Error('disk full') }
  await assert.rejects(
    () => saveAsUtf8(makeDeps(rec), 'note.md', 'café', 'lf'),
    /disk full/,
  )
  console.log('failure OK: saveFile rejection propagates (dirty stays, draft kept)')
}

console.log('')
console.log('TOKEN MAPPING OK: remedy mapping + fileEnc-before-PickSavePath ordering hold.')