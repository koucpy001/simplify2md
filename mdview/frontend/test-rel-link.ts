// Relative `.md` link resolution gates (plan todo 20c), including the
// adversarial matrix: `../` escape, query/fragment, percent-encoding,
// absolute paths, non-content URIs and hostile targets.
import { resolveRelativeMdLink, parseDocumentUri } from './src/lib/rel-link'

let failures = 0
function check(name: string, cond: boolean, detail?: unknown) {
  if (cond) {
    console.log('PASS', name)
  } else {
    failures++
    console.error('FAIL', name, detail ?? '')
  }
}

// --- Desktop behaviour unchanged (directory join, Windows paths included). ---
const d1 = resolveRelativeMdLink('notes/b.md', 'C:\\Docs\\a.md')
check('desktop join (legacy mixed separators preserved)', d1.action === 'open' && d1.target === 'C:\\Docs/notes/b.md', d1)
const d2 = resolveRelativeMdLink('sub/x.md', '/home/u/a.md')
check('desktop posix join', d2.action === 'open' && d2.target === '/home/u/sub/x.md', d2)
const d3 = resolveRelativeMdLink('C:\\Other\\b.md', 'C:\\Docs\\a.md')
check('desktop absolute kept', d3.action === 'open' && d3.target === 'C:\\Other\\b.md', d3)
const d4 = resolveRelativeMdLink('../up.md', 'C:\\Docs\\sub\\a.md')
check('desktop .. climbs (legacy raw segments preserved)', d4.action === 'open' && d4.target === 'C:\\Docs\\sub/../up.md', d4)

// --- URI parsing. ---
const p1 = parseDocumentUri('content://com.android.externalstorage.documents/document/primary%3ADocs%2Fa.md')
check('parse plain', p1?.authority === 'com.android.externalstorage.documents' &&
  p1.treeDocId === null && p1.documentId === 'primary:Docs/a.md', p1)
const p2 = parseDocumentUri(
  'content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2Fa.md',
)
check('parse tree-formed', p2?.treeDocId === 'primary:Docs' && p2?.documentId === 'primary:Docs/a.md', p2)

// --- Android, tree-formed current URI: child construction like todo 13. ---
const T = 'content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2Fnotes%2Fa.md'
const a1 = resolveRelativeMdLink('b.md', T)
check('tree sibling', a1.action === 'open' && a1.target ===
  'content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2Fnotes%2Fb.md', a1)
const a2 = resolveRelativeMdLink('../top.md', T)
check('tree parent within tree', a2.action === 'open' && a2.target.endsWith('/document/primary%3ADocs%2Ftop.md'), a2)
const a3 = resolveRelativeMdLink('sub dir/x y.md', T)
check('tree percent-encoded target', a3.action === 'open' &&
  a3.target.endsWith('/document/primary%3ADocs%2Fnotes%2Fsub%20dir%2Fx%20y.md'), a3)

// --- Adversarial: escape, absolute, file:, opaque, plain-document URIs. ---
const e1 = resolveRelativeMdLink('../../../../etc/a.md', T)
check('escape above tree root -> prompt', e1.action === 'prompt', e1)
const e2 = resolveRelativeMdLink('../secret.md', T)
check('.. climbs to tree parent', e2.action === 'open' && e2.target.endsWith('/document/primary%3ADocs%2Fsecret.md'), e2)
const e3 = resolveRelativeMdLink('file:///data/a.md', T)
check('file: scheme -> prompt', e3.action === 'prompt', e3)
const e4 = resolveRelativeMdLink('/data/local/a.md', T)
check('absolute path -> prompt', e4.action === 'prompt', e4)
const e5 = resolveRelativeMdLink('b.md', 'content://com.android.externalstorage.documents/document/primary%3Aa.md')
check('root-level doc (no parent) -> prompt', e5.action === 'prompt', e5)
const e6 = resolveRelativeMdLink('b.md', 'content://com.android.providers.downloads.documents/document/msf%3A100')
check('plain document URI -> guidance prompt', e6.action === 'prompt' && e6.message.includes('授权'), e6)
const e7 = resolveRelativeMdLink('b.md', 'content://weird/onlyauthority')
check('malformed uri -> prompt', e7.action === 'prompt', e7)
// Hostile link must never escape the granted tree: the constructed URI is
// always under treeDocId, and the only sink is ReadFileAt on a SAF URI the
// provider gates with the user's own grant.
const h1 = resolveRelativeMdLink('%2e%2e/secret.md', T)
check('encoded dotdot stays in tree or prompts',
  h1.action === 'prompt' || /tree\/primary%3ADocs\/document\/primary%3ADocs(%2F|\/)/.test(h1.target), h1)

// Query strings are stripped by the caller (App.vue splits on '#' and '?')
// before the resolver runs; the resolver itself never sees a query.
const q1 = resolveRelativeMdLink('b.md', T)
check('clean rel opens', q1.action === 'open', q1)

if (failures > 0) {
  console.error(`${failures} failure(s)`)
  process.exit(1)
}
console.log('ALL PASS')
