// mdview:open-text payload helper tests (plan todo 17).
//
// Shared plain text -> unnamed document payload. The requestSwitch guard and
// the Kotlin routing are todo 18 deliverables and are NOT asserted here.
//
// Run: npx tsx test-open-text.ts

import assert from 'node:assert/strict'
import { openTextPayload } from './src/lib/bridge-events'

// ---- ordinary text -----------------------------------------------------------
assert.deepEqual(openTextPayload('# shared note'), {
  filePath: '',
  content: '# shared note',
  dirty: true,
})
console.log("plain text           -> { filePath:'', content, dirty:true }")

// ---- text that must survive verbatim (quotes / newlines / unicode) -----------
const tricky = 'line1\nline2 "quoted" \u2028\u2029 中文'
assert.deepEqual(openTextPayload(tricky), { filePath: '', content: tricky, dirty: true })
console.log('tricky text          -> preserved verbatim (newlines, quotes, U+2028/9)')

// ---- empty text is still a valid unnamed document ----------------------------
assert.deepEqual(openTextPayload(''), { filePath: '', content: '', dirty: true })
console.log("empty text           -> { filePath:'', content:'', dirty:true }")

// ---- invariants --------------------------------------------------------------
for (const text of ['a', '', 'x'.repeat(100_000)]) {
  const p = openTextPayload(text)
  assert.equal(p.filePath, '', 'filePath must always be empty (never bound to a document)')
  assert.equal(p.dirty, true, 'shared text is always unsaved')
  assert.equal(p.content, text)
}
console.log('ALL OPEN-TEXT TESTS PASSED')
