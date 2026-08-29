// Pipeline test: run soloMD's renderMarkdown on the real academic md file
// and assert formulas render to KaTeX HTML, images become <img> tags,
// scroll-sync anchors exist, and outline extraction works.
// Run with: node node_modules/tsx/dist/cli.mjs test-pipeline.ts
import { readFileSync } from 'node:fs'
import { renderMarkdown, extractOutline } from './src/lib/markdown'

const file =
  '../../hybrid_auto/Frequency_Modulation_Nonlinearity_Correction_for_FMCW_SAL_Based_on_WVD_With_Gradient_Rotation_Enhancement.md'
const md = readFileSync(file, 'utf8')
const html = renderMarkdown(md)

const katexCount = (html.match(/katex/g) || []).length
const imgCount = (html.match(/<img\b/gi) || []).length
const rawDollarDollar = (html.match(/\$\$/g) || []).length
const tableCount = (html.match(/<table\b/gi) || []).length
const syncAnchors = (html.match(/data-source-line/g) || []).length
const headingIds = (html.match(/<h[1-6][^>]*?\sid=/gi) || []).length
const outline = extractOutline(md)
// mineru leftovers: doubled command backslashes (`\\left`, `\\tag`) and
// escaped subscripts (`\_ {`) must all be gone from the rendered output.
const mineruLeft = (html.match(/\\\\left|\\\\tag|\\_ \{/g) || []).length
// The authoritative formula-health metric: KaTeX parse failures.
const katexErrors = (html.match(/katex-error/g) || []).length

console.log('=== Pipeline test on hybrid_auto md ===')
console.log('md length    :', md.length)
console.log('html length  :', html.length)
console.log('KaTeX hits   :', katexCount, katexCount > 0 ? 'PASS' : 'FAIL — formulas did not render')
console.log('<img> tags   :', imgCount, imgCount > 0 ? 'PASS' : 'FAIL — no images in html')
console.log('mineru esc.  :', mineruLeft, mineruLeft === 0 ? 'PASS' : 'FAIL — escaped LaTeX remains')
console.log('katex errors :', katexErrors, katexErrors === 0 ? 'PASS' : 'FAIL — KaTeX parse failures')
console.log('raw $$ left  :', rawDollarDollar, rawDollarDollar === 0 ? 'PASS' : 'WARN — raw $$ remains')
console.log('<table> tags :', tableCount)
console.log('sync anchors :', syncAnchors, syncAnchors > 0 ? 'PASS' : 'FAIL — no data-source-line')
console.log('heading ids  :', headingIds, headingIds > 0 ? 'PASS' : 'FAIL — no heading ids for anchors')
console.log('outline items:', outline.length, outline.length > 0 ? 'PASS' : 'FAIL')
console.log('outline head :', JSON.stringify(outline.slice(0, 3)))

const i = html.indexOf('katex')
if (i >= 0) console.log('math snippet :', html.slice(i, i + 160).replace(/\n/g, ' '))
const j = html.indexOf('<img')
if (j >= 0) console.log('img snippet  :', html.slice(j, j + 120).replace(/\n/g, ' '))
