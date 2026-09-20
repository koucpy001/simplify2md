// Path-utility gate (plan todo 12 / Verification strategy).
//
// `baseName`/`dirOf` must be platform-neutral: Windows paths AND opaque
// `content://` URIs. `selectDisplayName` is the displayPath selection logic:
// prefer the provider display name (OpenResult.name), fall back to
// baseName(filePath), then the untitled label.
//
// Run: npx tsx test-paths.ts

import assert from 'node:assert/strict'
import { baseName, dirOf, selectDisplayName } from './src/lib/paths'

// ---- baseName: Windows + POSIX + URI ---------------------------------------
assert.strictEqual(baseName('C:\\docs\\note.md'), 'note.md')
assert.strictEqual(baseName('C:/docs/note.md'), 'note.md')
assert.strictEqual(baseName('C:\\docs\\sub dir\\文件 名.md'), '文件 名.md')
assert.strictEqual(baseName('content://authority/document/note.md'), 'note.md')
assert.strictEqual(baseName('content://authority/document/note%20a.md'), 'note%20a.md')
assert.strictEqual(baseName('note.md'), 'note.md', 'no separator: the whole id is the name')
assert.strictEqual(baseName('/a/b/'), 'b', 'trailing separator is stripped')
assert.strictEqual(baseName(''), '')
console.log('baseName OK: Windows, POSIX and URI forms')

// ---- dirOf: platform-neutral parent ----------------------------------------
assert.strictEqual(dirOf('C:\\docs\\note.md'), 'C:\\docs')
assert.strictEqual(dirOf('C:/docs/note.md'), 'C:/docs')
assert.strictEqual(dirOf('content://authority/document/note.md'), 'content://authority/document')
assert.strictEqual(dirOf('note.md'), '', 'no separator -> no directory')
assert.strictEqual(dirOf('/note.md'), '', 'root file -> no directory')
assert.strictEqual(dirOf('/a/b/'), '/a', 'trailing separator is stripped')
assert.strictEqual(dirOf(''), '')
console.log('dirOf OK: parent without a trailing separator')

// The relative-.md-link join (App.vue preview delegation) must be neutral.
const joined = dirOf('content://authority/document/note.md') + '/' + 'images/a.md'
assert.strictEqual(joined, 'content://authority/document/images/a.md')
console.log('relative-link join OK:', joined)

// ---- displayPath selection -------------------------------------------------
assert.strictEqual(
  selectDisplayName('年度报告.md', 'content://provider/document/msf%3A1000000123'),
  '年度报告.md',
  'a provider display name always wins over the opaque URI',
)
assert.strictEqual(
  selectDisplayName('', 'C:\\docs\\note.md'),
  'note.md',
  'empty name falls back to baseName(filePath)',
)
assert.strictEqual(selectDisplayName(undefined, '/docs/note.md'), 'note.md')
assert.strictEqual(selectDisplayName('   ', '/docs/note.md'), 'note.md', 'whitespace-only name is not a name')
assert.strictEqual(selectDisplayName('', ''), '未标题.md', 'untitled fallback is preserved')
assert.strictEqual(selectDisplayName(null, ''), '未标题.md')
console.log('displayPath selection OK: name > baseName(filePath) > 未标题.md')

console.log('')
console.log('PATHS OK: baseName/dirOf are platform-neutral and displayPath prefers OpenResult.name.')
