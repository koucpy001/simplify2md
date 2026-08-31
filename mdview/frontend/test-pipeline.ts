// Render-pipeline test. Runs against testdata/fixture.md — self-contained,
// so it works on a fresh clone — and, when the local hybrid_auto sample (a
// real mineru-converted paper, not committed) is present, smoke-renders that
// too. Exits non-zero on any failed check, so CI can gate on it.
// Run with: npx tsx test-pipeline.ts
import { existsSync, readFileSync } from 'node:fs'
import { renderMarkdown, extractImageRoot, extractOutline } from './src/lib/markdown'

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

if (failures) {
  console.error(`\n${failures} check(s) FAILED`)
  process.exit(1)
}
console.log('\nAll checks passed')
