// Render-pipeline test. Runs against testdata/fixture.md — self-contained,
// so it works on a fresh clone — and, when the local hybrid_auto sample (a
// real mineru-converted paper, not committed) is present, smoke-renders that
// too. Exits non-zero on any failed check, so CI can gate on it.
// Run with: npx tsx test-pipeline.ts
import { existsSync, readFileSync } from 'node:fs'
import { renderMarkdown, extractImageRoot, extractOutline } from './src/lib/markdown'
import { LruCache } from './src/lib/lru.ts'

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
  if (!ok) failures++
}

interface Audit {
  katex: number
  katexErrors: number
  mineruLeft: number
  rawDollar: number
  syncAnchors: number
  headingIds: number
}

// Invariants every rendered document must satisfy.
function auditCore(name: string, md: string, html: string) {
  const a: Audit = {
    katex: (html.match(/katex/g) || []).length,
    katexErrors: (html.match(/katex-error/g) || []).length,
    // mineru leftovers: doubled command backslashes (`\\left`, `\\tag`) and
    // escaped subscripts (`\_ {`) must all be gone from the rendered output.
    mineruLeft: (html.match(/\\\\left|\\\\tag|\\_ \{/g) || []).length,
    rawDollar: (html.match(/\$\$/g) || []).length,
    syncAnchors: (html.match(/data-source-line/g) || []).length,
    headingIds: (html.match(/<h[1-6][^>]*?\sid=/gi) || []).length,
  }
  const outline = extractOutline(md)
  check(`${name}: formulas render to KaTeX`, a.katex > 0, `hits=${a.katex}`)
  check(`${name}: no KaTeX parse failures`, a.katexErrors === 0, `errors=${a.katexErrors}`)
  check(`${name}: mineru escapes repaired`, a.mineruLeft === 0, `left=${a.mineruLeft}`)
  check(`${name}: no raw $$ left`, a.rawDollar === 0, `count=${a.rawDollar}`)
  check(`${name}: scroll-sync anchors present`, a.syncAnchors > 0, `count=${a.syncAnchors}`)
  check(`${name}: heading ids for anchors`, a.headingIds > 0, `count=${a.headingIds}`)
  check(`${name}: outline extracted`, outline.length > 0, `items=${outline.length}`)
  return a
}

// ---- fixture (always available) ----
const md = readFileSync(new URL('./testdata/fixture.md', import.meta.url), 'utf8')
const html = renderMarkdown(md)
console.log(`fixture: ${md.length} chars -> ${html.length} chars of html`)

auditCore('fixture', md, html)
check('fixture: front matter rendered', /md-frontmatter/.test(html))
check('fixture: imageRoot extracted', extractImageRoot(md) === './images')
check('fixture: malformed table repaired', (html.match(/<table/gi) || []).length >= 2, 'need the repaired md table + the raw HTML one')
check('fixture: CJK emphasis parses', !html.includes('**限制：**'), 'literal asterisks must not survive')
check('fixture: task list items', /task-list-item/.test(html))
check('fixture: GitHub callout', /md-callout md-callout--note/.test(html))
check('fixture: footnotes', /footnote/.test(html))
check('fixture: relative image tag', /<img\b/i.test(html))
check('fixture: code highlighted', /hljs-keyword/.test(html))
check('fixture: large/unknown code escaped, not auto-highlighted', !/cb-line/.test(html))

// ---- optional real sample ----
const sample =
  '../../hybrid_auto/Frequency_Modulation_Nonlinearity_Correction_for_FMCW_SAL_Based_on_WVD_With_Gradient_Rotation_Enhancement.md'
if (existsSync(sample)) {
  console.log(`\nsample: ${sample}`)
  const smd = readFileSync(sample, 'utf8')
  auditCore('sample', smd, renderMarkdown(smd))
} else {
  console.log('\nsample: hybrid_auto file not present (optional), skipped')
}

// ---- adversarial regression cases ----
// Odd number of backticks must not crash the pipeline (CRITICAL-2).
let oddOk = true
try {
  renderMarkdown('a ` b')
  renderMarkdown('按下 ` 键')
} catch {
  oddOk = false
}
check('adversarial: odd backtick count does not throw', oddOk)

// A `~~~` fence must hide inner ATX headings from the outline (MINOR, task 6).
const tildeSrc = '~~~\n# hidden\n~~~\n# visible\n'
const tildeOutline = extractOutline(tildeSrc)
check(
  'adversarial: ~~~ fence inner heading excluded',
  !tildeOutline.some((o) => o.text === 'hidden') &&
    tildeOutline.some((o) => o.text === 'visible'),
)

// Setext headings (`===` / `---`) must be extracted (MINOR, task 6).
const setextSrc = 'Title One\n===\n\nTitle Two\n---\n'
const setextOutline = extractOutline(setextSrc)
check(
  'adversarial: setext === heading extracted',
  setextOutline.some((o) => o.text === 'Title One' && o.level === 1),
)
check(
  'adversarial: setext --- heading extracted',
  setextOutline.some((o) => o.text === 'Title Two' && o.level === 2),
)

// An unclosed `<table>` must not hang or throw — it should render to output.
let tableOk = true
let tableHtml = ''
try {
  tableHtml = renderMarkdown('before\n<table>\nafter')
} catch {
  tableOk = false
}
check('adversarial: unclosed <table> renders without throwing', tableOk && tableHtml.length > 0)

// DOMPurify must strip event handlers from embedded HTML (CRITICAL-1).
const xssHtml = renderMarkdown('<img src=x onerror=alert(1)>')
check('adversarial: DOMPurify strips onerror handler', !/onerror/i.test(xssHtml))

// ---- acceptance evidence: plan todo 8 / 9 / 12 ----

// Timing (todo 8): a ~2MB doc with an unclosed <table> must render well under
// budget. Threshold is relaxed to 5s on Windows (CI runner slow/volatile, ORACLE-M2).
// No shell-out / subprocess. Few sizable paragraphs keep node count modest so the Node-side
// jsdom DOMPurify cost (scales with node count) stays bounded and representative.
{
  const para =
    '这是一段较长的中文段落，用于填充文档体积。'.repeat(20) +
    ' Some English text to pad paragraph length so each block is sizable. '.repeat(20)
  const big = (para + '\n\n').repeat(1100) // ~2MB source, ~1100 nodes (jsdom DOMPurify cost scales with node count)
  const doc = big + '\nbefore\n<table>\nafter'
  const start = Date.now()
  let ok = true
  let out = ''
  try {
    out = renderMarkdown(doc)
  } catch {
    ok = false
  }
  const elapsed = Date.now() - start
  const limit = process.platform === 'win32' ? 5000 : 1000
  console.log(`timing: ${elapsed}ms (limit ${limit}ms) on ${process.platform}`)
  check(
    'timing: ~2MB doc + unclosed <table> renders under budget',
    ok && out.length > 0 && elapsed < limit,
    `${elapsed}ms`,
  )
}

// LRU cache (todo 9): dual budget — item count AND total base64 bytes.
{
  // (a) capacity eviction: >64 items -> oldest evicted, newest retained.
  const c = new LruCache()
  for (let i = 0; i < 70; i++) c.set('k' + i, 'v' + i)
  check('lru: capacity eviction keeps <=64 items', c.size === 64, `size=${c.size}`)
  check('lru: capacity eviction keeps newest', c.has('k69'))
  check('lru: capacity eviction drops oldest', !c.has('k0'))

  // (b) byte-budget eviction: total base64 exceeds budget -> oldest evicted.
  // Use a small 1MB budget so the test stays fast/non-flaky; the production
  // default is 256MB (asserted via source below).
  const b = new LruCache(200, 1024 * 1024)
  const chunk = 'x'.repeat(300 * 1024) // ~300KB payload
  for (let i = 0; i < 4; i++) b.set('b' + i, chunk)
  check(
    'lru: byte-budget eviction drops oldest over budget',
    b.size < 4 && !b.has('b0') && b.has('b3'),
    `size=${b.size}`,
  )
  const lruSrc = readFileSync('src/lib/lru.ts', 'utf8')
  check('lru: production byte budget is 256MB', lruSrc.includes('256 * 1024 * 1024'))

  // (c) failed '' entry: present but falsy-looking; re-set does not grow size.
  const f = new LruCache()
  f.set('fail', '')
  check("lru: failed '' entry get returns ''", f.get('fail') === '')
  check('lru: failed entry counted in size', f.size === 1, `size=${f.size}`)
  check('lru: failed entry has() true', f.has('fail') === true)
  f.set('fail', '') // re-set same key — must not re-read or grow
  check('lru: re-set same key does not grow size', f.size === 1, `size=${f.size}`)
  check("lru: re-set still returns ''", f.get('fail') === '')
}

// Mermaid marker (todo 12): a mermaid fence keeps the `language-mermaid` class,
// and markdown.ts must NOT import mermaid itself — only App.vue dynamically
// imports it on demand. Source check via readFileSync, no shell-out (ORACLE-M2).
{
  const mmd = 'before\n\n```mermaid\ngraph TD; A-->B\n```\n\nafter'
  const mmdHtml = renderMarkdown(mmd)
  check('mermaid: fence keeps language-mermaid class', /language-mermaid/.test(mmdHtml))
  const mdSrc = readFileSync('src/lib/markdown.ts', 'utf8')
  check("mermaid: markdown.ts does NOT import('mermaid')", !mdSrc.includes("import('mermaid')"))
}

if (failures) {
  console.error(`\n${failures} check(s) FAILED`)
  process.exit(1)
}
console.log('\nAll checks passed')
