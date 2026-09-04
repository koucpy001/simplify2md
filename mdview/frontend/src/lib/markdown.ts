import MarkdownIt from 'markdown-it';
import anchor from 'markdown-it-anchor';
import hljs from 'highlight.js/lib/common';
import 'katex/contrib/mhchem';
// @ts-ignore — types are loose
import katex from '@vscode/markdown-it-katex';
// @ts-ignore — no types shipped
import footnote from 'markdown-it-footnote';
// @ts-ignore — no types shipped
import frontMatter from 'markdown-it-front-matter';
// @ts-ignore — no types shipped
import mark from 'markdown-it-mark';
import cjkFriendly from 'markdown-it-cjk-friendly';
import * as yaml from 'js-yaml';
import DOMPurify from 'dompurify';

// Per-render front-matter capture. markdown-it is synchronous so a
// module-level variable is safe for sequential calls, but this is NOT
// concurrent-safe across interleaved renders.
let lastFrontMatterRaw: string | null = null;

const katexPlugin: any = (katex as any).default ?? katex;

// `html: true` lets documents embed inline HTML like
// `<img src=… style="zoom:50%;">`, `<details>`, `<sub>`, or raw `<table>` —
// shapes markdown can't express and mineru-style exports emit constantly.
// This app only ever renders the user's own local files — no untrusted
// input — the same tradeoff Typora / Obsidian make by default.
export const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: true,
  // Typora-like: a single newline renders as a line break, which is what
  // mineru/AI exports and casual notes expect.
  breaks: true,
  highlight: (code: string, lang: string): string => {
    // Diagram sources (no hljs grammar) render as escaped plain text with the
    // language class intact rather than getting bogus auto-highlight spans.
    if (lang === 'mermaid') return '';
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
      } catch {}
    }
    // Unknown language: hljs auto-detect guesses across dozens of grammars,
    // which is slow — only afford it on small blocks. Large unknown blocks
    // render as escaped plain text instead.
    if (code.length <= AUTO_HIGHLIGHT_MAX) {
      try {
        return hljs.highlightAuto(code).value;
      } catch {}
    }
    return '';
  },
})
  // front-matter must run first so it's stripped from the body before
  // any other plugin/rule sees it.
  .use(frontMatter, (fm: string) => {
    lastFrontMatterRaw = fm;
  })
  // `permalink: false` trips markdown-it-anchor's narrow typings — the
  // runtime option is a plain boolean.
  .use(anchor, { permalink: false, slugify: (s: string) => slugify(s) } as any)
  .use(katexPlugin, { throwOnError: false })
  .use(footnote)
  .use(mark)
  .use(cjkFriendly);

const AUTO_HIGHLIGHT_MAX = 8192;

// CJK-friendly emphasis. `**限制：**硬链接` renders as literal asterisks
// under stock CommonMark, and that shape is everywhere in Chinese writing: a
// bold run ending in a full-width colon, immediately followed by a Han
// character — no space, because CJK doesn't use one. The closing `**` is
// preceded by punctuation and followed by a letter, so it isn't
// right-flanking and can't close. `markdown-it-cjk-friendly` implements the
// CommonMark CJK amendment (commonmark/commonmark-spec#650); ASCII text
// keeps stock behaviour.

// ---- Source line mapping for split-pane scroll sync ----
// Annotate every block-level opening token with `data-source-line` set to
// the 1-indexed source line. App.vue's split-scroll uses these attributes
// to map editor viewport lines to preview elements for accurate alignment.
const BLOCK_OPEN_TYPES = new Set([
  'paragraph_open',
  'heading_open',
  'blockquote_open',
  'list_item_open',
  'bullet_list_open',
  'ordered_list_open',
  'table_open',
  'fence',
  'code_block',
  'hr',
  'html_block',
  'math_block',
]);

md.core.ruler.push('source_line_map', (state) => {
  for (const tok of state.tokens) {
    if (!BLOCK_OPEN_TYPES.has(tok.type)) continue;
    if (!tok.map || tok.map.length < 1) continue;
    const line = tok.map[0] + 1; // 1-indexed
    tok.attrJoin('data-source-line', String(line));
  }
});

// Raw HTML blocks render their content verbatim — the core rule above never
// reaches their output, so documents built around `<div>…<img>…</div>`
// containers (typical mineru output) had no sync anchors at all and the
// split panes drifted apart across those regions. Wrap the raw block in a
// neutral div carrying the line.
const defaultHtmlBlock = md.renderer.rules.html_block;
md.renderer.rules.html_block = function (tokens, idx, options, env, self) {
  const html = defaultHtmlBlock
    ? defaultHtmlBlock(tokens, idx, options, env, self)
    : tokens[idx].content;
  const tok = tokens[idx];
  const line = tok.map && tok.map.length > 0 ? tok.map[0] + 1 : 0;
  if (!line) return html;
  return `<div data-source-line="${line}">${html}</div>`;
};

// Custom core rule: detect GitHub-style task list items (a leading
// `[ ]` / `[x]` in the first inline child of a list item) and:
//   1. add a `task-list-item` class to the <li>
//   2. replace the `[ ] ` / `[x] ` text prefix with an <input type="checkbox">
// We also tag the enclosing <ul>/<ol> with `contains-task-list` so CSS can
// strip the bullet markers. Checkboxes render disabled — this is a viewer.
md.core.ruler.after('inline', 'task_lists', (state) => {
  const tokens = state.tokens;
  const TASK_RE = /^\[([ xX])\][ \u00A0]/;

  for (let i = 0; i < tokens.length; i++) {
    const tok = tokens[i];
    if (tok.type !== 'list_item_open') continue;

    // The first content of a list item is typically:
    //   list_item_open -> paragraph_open -> inline -> paragraph_close -> ...
    // We want the `inline` token's first child to be a text token
    // starting with `[ ] ` or `[x] `.
    const paragraphOpen = tokens[i + 1];
    const inlineTok = tokens[i + 2];
    if (
      !paragraphOpen ||
      paragraphOpen.type !== 'paragraph_open' ||
      !inlineTok ||
      inlineTok.type !== 'inline' ||
      !inlineTok.children ||
      inlineTok.children.length === 0
    ) {
      continue;
    }
    const firstChild = inlineTok.children[0];
    if (firstChild.type !== 'text') continue;
    const m = TASK_RE.exec(firstChild.content);
    if (!m) continue;

    const checked = m[1] !== ' ';
    // Strip the `[ ] ` / `[x] ` prefix from the text token.
    firstChild.content = firstChild.content.slice(m[0].length);

    // Insert an html_inline checkbox at the start of the inline children.
    const checkboxToken = new state.Token('html_inline', '', 0);
    checkboxToken.content = `<input class="task-list-item-checkbox" type="checkbox"${
      checked ? ' checked=""' : ''
    } disabled=""> `;
    inlineTok.children.unshift(checkboxToken);

    // Tag the <li>.
    const existingClass = tok.attrGet('class');
    tok.attrSet(
      'class',
      existingClass ? `${existingClass} task-list-item` : 'task-list-item',
    );

    // Walk back to find the enclosing list token and tag it.
    for (let k = i - 1; k >= 0; k--) {
      const p = tokens[k];
      if (p.type === 'bullet_list_open' || p.type === 'ordered_list_open') {
        const cls = p.attrGet('class');
        if (!cls || !/\bcontains-task-list\b/.test(String(cls))) {
          p.attrSet(
            'class',
            cls ? `${cls} contains-task-list` : 'contains-task-list',
          );
        }
        break;
      }
      if (p.type === 'bullet_list_close' || p.type === 'ordered_list_close') {
        break;
      }
    }
  }
  return false;
});

// GitHub-style callouts: a blockquote whose first line is `[!NOTE]` (or TIP /
// IMPORTANT / WARNING / CAUTION) renders as a tinted callout card with a
// label row — the syntax GitHub and Obsidian users expect to just work. The
// marker line is stripped; the label/icon comes from CSS (`.md-callout` in
// App.vue) so the markup stays free of presentation. Unknown `[!TYPES]` are
// left as plain blockquote text on purpose.
md.core.ruler.after('inline', 'github_callouts', (state) => {
  const tokens = state.tokens;
  const CALLOUT_RE = /^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\][ \t]*/i;
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i].type !== 'blockquote_open') continue;
    const pOpen = tokens[i + 1];
    const inline = tokens[i + 2];
    if (
      !pOpen ||
      pOpen.type !== 'paragraph_open' ||
      !inline ||
      inline.type !== 'inline' ||
      !inline.children ||
      inline.children.length === 0
    ) {
      continue;
    }
    const first = inline.children[0];
    if (first.type !== 'text') continue;
    const m = CALLOUT_RE.exec(first.content);
    if (!m) continue;
    const kind = m[1].toLowerCase();
    tokens[i].attrJoin('class', `md-callout md-callout--${kind}`);
    first.content = first.content.slice(m[0].length);
    if (first.content === '') {
      // Marker sat alone on its line — drop the empty text node and the
      // line break that followed it so the body starts flush.
      const next = inline.children[1];
      inline.children.splice(0, next && (next.type === 'softbreak' || next.type === 'hardbreak') ? 2 : 1);
    }
  }
});

function slugify(s: string): string {
  return s
    .toLowerCase()
    .trim()
    .replace(/[\s\u3000]+/g, '-')
    .replace(/[^\w\-\u4e00-\u9fff]/g, '');
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderFrontMatterHtml(raw: string): string {
  let parsed: unknown;
  try {
    parsed = yaml.load(raw);
  } catch {
    return `<pre class="md-frontmatter md-frontmatter--raw">${escapeHtml(
      raw,
    )}</pre>`;
  }
  if (
    parsed === null ||
    parsed === undefined ||
    typeof parsed !== 'object' ||
    Array.isArray(parsed)
  ) {
    // Not a key/value map — fall back to raw display.
    return `<pre class="md-frontmatter md-frontmatter--raw">${escapeHtml(
      raw,
    )}</pre>`;
  }
  const entries = Object.entries(parsed as Record<string, unknown>);
  if (entries.length === 0) {
    return `<pre class="md-frontmatter md-frontmatter--raw">${escapeHtml(
      raw,
    )}</pre>`;
  }
  const rows = entries
    .map(([k, v]) => {
      const valueText =
        v === null || v === undefined
          ? ''
          : typeof v === 'object'
            ? JSON.stringify(v)
            : String(v);
      return `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(valueText)}</dd>`;
    })
    .join('');
  return `<div class="md-frontmatter"><dl>${rows}</dl></div>`;
}

// Tags that markdown-it will treat as HTML blocks when they start at column 0
// AND are surrounded by blank lines. mineru / many AI-PDF-to-Markdown tools
// emit `<table>...</table>` indented 4 spaces or wedged between text lines
// without blank-line separators, which makes markdown-it treat the chunk as
// a code block (4-space indent) or escape it as inline HTML inside a
// paragraph. The preprocessor pulls these tags back to column 0 and inserts
// blank lines around them so the HTML-block rule fires.
const HTML_BLOCK_PASSTHROUGH_TAGS = [
  'table',
  'div',
  'details',
  'figure',
  'iframe',
  'blockquote',
  'pre',
  'section',
  'article',
  'aside',
];
const HTML_BLOCK_RE = new RegExp(
  `^([ \\t]*)(<(?:${HTML_BLOCK_PASSTHROUGH_TAGS.join('|')})\\b[\\s\\S]*?</(?:${HTML_BLOCK_PASSTHROUGH_TAGS.join('|')})>)[ \\t]*$`,
  'gmi',
);

/** Preprocess: ensure block-level HTML elements (like `<table>` emitted by
 *  mineru) are at column 0 with blank lines around them so markdown-it parses
 *  them as HTML blocks rather than indented code or inline HTML inside a
 *  paragraph. Skipped inside fenced code blocks so we don't mangle code
 *  examples.
 */
function unwrapInlineHtmlBlocks(source: string): string {
  // Split on fenced code blocks (``` or ~~~) and only transform the non-code
  // segments. Lightweight split — markdown-it's own fence parser is the
  // authority but this approximation is sufficient for the common case.
  const FENCE_RE = /(^|\n)(```|~~~)[^\n]*\n[\s\S]*?\n\2[ \t]*(?=\n|$)/g;
  const segments: { text: string; isFence: boolean }[] = [];
  let lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = FENCE_RE.exec(source)) !== null) {
    if (m.index > lastIndex) {
      segments.push({ text: source.slice(lastIndex, m.index), isFence: false });
    }
    segments.push({ text: m[0], isFence: true });
    lastIndex = m.index + m[0].length;
  }
  if (lastIndex < source.length) {
    segments.push({ text: source.slice(lastIndex), isFence: false });
  }
  return segments
    .map((seg) => {
      if (seg.isFence) return seg.text;
      return seg.text.replace(HTML_BLOCK_RE, (_match, _indent, html) => {
        return `\n\n${html}\n\n`;
      });
    })
    .join('');
}

// ---- Malformed table-delimiter normalization ------------------------------
// GFM (and markdown-it) require the delimiter row (the `|---|---|` line under
// the header) to have EXACTLY the same number of cells as the header row. If
// it doesn't — one cell too many or too few — markdown-it rejects the *entire*
// block and renders it as a plain paragraph, so the whole table collapses into
// literal `| … |` text in the preview. AI/LLM exports and PDF-to-Markdown tools
// frequently emit a stray extra `|---|` cell in the delimiter row (e.g. a
// 3-column header with a 4-cell delimiter), which silently breaks the table.
// Typora/Obsidian tolerate this; we do too — same philosophy as the list-indent
// and inline-HTML-block fixups above.
const TABLE_DELIM_CELL_RE = /^\s*:?-+:?\s*$/;

/** Split a table row on unescaped `|` (a `\|` is a literal pipe in a cell). */
function splitTableRow(line: string): string[] {
  const cells: string[] = [];
  let cur = '';
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '\\' && i + 1 < line.length) {
      cur += c + line[i + 1];
      i++;
      continue;
    }
    if (c === '|') {
      cells.push(cur);
      cur = '';
      continue;
    }
    cur += c;
  }
  cells.push(cur);
  return cells;
}

/** Cell count as markdown-it sees it: split on `|`, drop the empty leading /
 *  trailing cells produced by an outer `| … |` border. */
function tableRowCells(line: string): string[] {
  const cells = splitTableRow(line.trim());
  if (cells.length && cells[0].trim() === '') cells.shift();
  if (cells.length && cells[cells.length - 1].trim() === '') cells.pop();
  return cells;
}

function isTableDelimiterRow(line: string): boolean {
  const cells = tableRowCells(line);
  if (cells.length === 0) return false;
  // Tolerate empty cells (AI exports emit `| --- |  | --- |`); markdown-it
  // rejects those outright, but as long as there's at least one real `---`
  // cell and no cell holds actual content, it's a mangled delimiter row we
  // can repair. Requiring ≥1 real cell keeps an all-empty `|  |  |` — which
  // is a data row, not a delimiter — from being misclassified.
  let hasRealCell = false;
  for (const c of cells) {
    if (c.trim() === '') continue;
    if (!TABLE_DELIM_CELL_RE.test(c)) return false;
    hasRealCell = true;
  }
  return hasRealCell;
}

/** Rebuild one delimiter cell, preserving its alignment colons. */
function normalizeDelimiterCell(raw: string | undefined): string {
  const t = (raw ?? '').trim();
  const left = t.startsWith(':');
  const right = t.endsWith(':');
  if (left && right) return ':---:';
  if (right) return '---:';
  if (left) return ':---';
  return '---';
}

/** When a delimiter row is malformed — its cell count differs from the header,
 *  or it contains an empty cell markdown-it rejects — rewrite it to exactly the
 *  header's column count, padding missing cells with `---`, dropping surplus
 *  ones, and keeping each surviving cell's alignment. Fenced code is skipped so
 *  `| a | b |` samples inside ``` blocks are left untouched. */
function normalizeTableDelimiters(source: string): string {
  const lines = source.split('\n');
  const out: string[] = [];
  let inFence = false;
  let fenceChar = '';
  const fenceRe = /^(\s*)(```+|~~~+)/;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const fm = fenceRe.exec(line);
    if (fm) {
      if (!inFence) {
        inFence = true;
        fenceChar = fm[2][0];
        out.push(line);
        continue;
      }
      if (fm[2][0] === fenceChar) {
        inFence = false;
        out.push(line);
        continue;
      }
    }
    if (inFence) {
      out.push(line);
      continue;
    }

    const next = lines[i + 1];
    const headerLooksTabular = line.includes('|') && line.trim() !== '';
    if (
      headerLooksTabular &&
      next !== undefined &&
      next.includes('|') &&
      isTableDelimiterRow(next)
    ) {
      const headerCells = tableRowCells(line);
      const delimCells = tableRowCells(next);
      // Repair when the delimiter's column count differs from the header, OR
      // when it has an empty cell markdown-it would choke on. A count-matched,
      // fully-valid delimiter is left byte-for-byte untouched.
      const needsRepair =
        delimCells.length !== headerCells.length ||
        delimCells.some((c) => c.trim() === '');
      if (headerCells.length >= 1 && needsRepair) {
        const fixed: string[] = [];
        for (let k = 0; k < headerCells.length; k++) {
          fixed.push(normalizeDelimiterCell(delimCells[k]));
        }
        const indent = (next.match(/^\s*/) as RegExpMatchArray)[0];
        out.push(line); // header unchanged
        out.push(`${indent}| ${fixed.join(' | ')} |`); // corrected delimiter
        i++; // skip the original malformed delimiter
        continue;
      }
    }
    out.push(line);
  }
  return out.join('\n');
}

/**
 * Re-indent nested list items to consistent 2-space steps.
 *
 * CommonMark turns content indented ≥4 spaces past its list item's content
 * column into an *indented code block*. So a sub-list a user typed with a Tab
 * (8 columns) under a 2-space parent silently renders as a code block with the
 * `*`/`**` markers shown literally. Typora / Obsidian re-indent instead of
 * punishing the typo; we do the same before parsing. We walk the document,
 * derive each marker's nesting depth from the sequence of indents seen so far,
 * and rewrite its leading whitespace to depth*2 spaces; continuation lines of
 * an item shift by the same delta. Fenced code and genuine top-level indented
 * code blocks (no enclosing list) are left untouched.
 */
function normalizeListIndent(source: string): string {
  const expand = (ws: string): number => {
    let n = 0;
    for (const c of ws) n += c === '\t' ? 4 - (n % 4) : 1;
    return n;
  };
  const lines = source.split('\n');
  const out: string[] = [];
  // Track each level's `markerWidth` (marker glyph + trailing spaces) so a
  // nested item re-indents under its PARENT's content column instead of a
  // fixed 2-space step. Ordered markers are 3+ chars wide (`1. `, `10. `), and
  // CommonMark only nests a child when it's indented by at least the parent
  // marker width — a flat +2 would leave ordered sublists under-indented, so
  // markdown-it flattens them into siblings.
  const stack: { orig: number; norm: number; markerWidth: number }[] = [];
  let inFence = false;
  let fenceChar = '';
  let curDelta = 0;
  let curOrig = -1;
  const markRe = /^(\s*)([-*+]|\d{1,9}[.)])(\s+)(.*)$/;
  const fenceRe = /^(\s*)(```+|~~~+)/;
  for (const line of lines) {
    const fm = fenceRe.exec(line);
    if (fm) {
      if (!inFence) {
        inFence = true;
        fenceChar = fm[2][0];
        out.push(line);
        continue;
      }
      if (fm[2][0] === fenceChar) {
        inFence = false;
        out.push(line);
        continue;
      }
    }
    if (inFence) {
      out.push(line);
      continue;
    }
    const m = markRe.exec(line);
    if (m) {
      const orig = expand(m[1]);
      // Content column = marker glyph + its trailing spaces. A child list must
      // clear this to nest (CommonMark), so we re-indent children to exactly it.
      const markerWidth = m[2].length + m[3].length;
      while (stack.length && orig < stack[stack.length - 1].orig) stack.pop();
      const top = stack[stack.length - 1];
      let norm: number;
      if (top && orig === top.orig) {
        norm = top.norm;
        // Siblings can differ in marker width (`9.` → `10.`); keep this item's
        // width for ITS children.
        top.markerWidth = markerWidth;
      } else if (top && orig > top.orig) {
        norm = top.norm + top.markerWidth;
        stack.push({ orig, norm, markerWidth });
      } else {
        norm = 0;
        stack.length = 0;
        stack.push({ orig, norm, markerWidth });
      }
      curDelta = norm - orig;
      curOrig = orig;
      out.push(' '.repeat(norm) + m[2] + m[3] + m[4]);
    } else if (line.trim() === '') {
      out.push(line);
    } else {
      const leadWs = (line.match(/^\s*/) as RegExpMatchArray)[0];
      const lead = expand(leadWs);
      if (stack.length && curOrig >= 0 && lead >= curOrig) {
        out.push(' '.repeat(Math.max(0, lead + curDelta)) + line.slice(leadWs.length));
      } else {
        stack.length = 0;
        curDelta = 0;
        curOrig = -1;
        out.push(line);
      }
    }
  }
  return out.join('\n');
}

// `typographer: true` also enables markdown-it's `smartquotes` rule, which
// rewrites straight quotes to curly ones (' → U+2019). Fonts that resolve
// U+2019 through a CJK fallback draw it fullwidth, so "test's" renders as
// "test'　s" and the preview stops matching the typed source. Keep straight
// quotes straight; the rest of typographer ((c) → ©, --- → —, ellipsis) stays.
md.disable('smartquotes');

/**
 * mineru / PDF-export escaping repair. mineru writes LaTeX with
 * markdown-escaped specials and doubled command backslashes inside math:
 * `\\begin{array}{r l} s \_ {t} \& = \\left\[ j 2 \\pi ... \\end{array}\\tag{1}`.
 * KaTeX receives that escaped soup and renders garbage — subscripts show as
 * literal `\_ {t}`, `\&` breaks array alignment, `\\begin` is not a command.
 * Undo the escaping outside fenced code blocks and inline-code spans:
 *   `\\` + letter → `\` + letter   (`\\pi` → `\pi`)
 *   `\_` + `{`    → `_`            (`s \_ {t}` → `s _{t}`)
 *   `\&`          → `&`            (array alignment cells)
 *   `\[` / `\]`   → `[` / `]`      (`\\left\[` → `\left[`)
 * A `\\` NOT directly followed by a letter is a genuine LaTeX row break and
 * stays intact; clean files contain none of these patterns and pass through
 * unchanged.
 */
function normalizeMathEscapes(source: string): string {
  const applyRules = (seg: string): string =>
    seg
      .replace(/\\\\(?=[A-Za-z])/g, '\\')
      .replace(/\\_ *(?=\{)/g, '_')
      .replace(/\\&/g, '&')
      .replace(/\\\[/g, '[')
      .replace(/\\\]/g, ']')
      // mineru drops the backslash before brace delimiters after \left /
      // \right / sizing commands (`\left{ … \right}` for set braces), so KaTeX
      // reads the brace as a group open/close and fails. Restore `\{` / `\}`.
      // Already-correct `\left\{` is untouched (next char is `\`, not a brace).
      .replace(/(\\(?:left|right|big|Big|bigg|Bigg)) *([{}])/g, '$1\\$2')
      // mineru encodes a combining tilde (f̃) as `\~ f`; KaTeX's `\~` needs a
      // group argument — render it as the accent it means, `\tilde{f}`.
      .replace(/\\~ *([A-Za-z])/g, '\\tilde{$1}')
      // mineru markdown-escapes the conjugate/multiplication star (`^ {\*}`);
      // KaTeX chokes on `\*` — a bare `*` renders it correctly and prose
      // `\*` → `*` is render-identical.
      .replace(/\\\*/g, '*');

  // KaTeX spaces \begin{array} rows on a fixed ~1.86em baseline pitch no
  // matter how tall the row content is, so tall rows overlap the next row.
  // Widen every row break by the height of the row ABOVE it: \sum / \int /
  // \lim draw limits above+below (~3.45em pitch needed), \frac / \sqrt stack
  // boxes (~2.55em), plain rows keep the default pitch. `\\tag` etc. are
  // already unescaped by applyRules at this point, so a remaining `\\` is a
  // genuine row break.
  const rowSpacingFor = (row: string): string =>
    /\\(?:sum|int|lim)(?![a-zA-Z])/.test(row)
      ? '\\\\[1.9em]'
      : /\\(?:dfrac|tfrac|frac|sqrt)(?![a-zA-Z])/.test(row)
        ? '\\\\[1em]'
        : '\\\\';
  const addArrayRowSpace = (seg: string): string =>
    seg.replace(
      /(\\begin\{array\}\{[^}]*\})([\s\S]*?)(\\end\{array\})/g,
      (_m, open: string, body: string, close: string) => {
        const rows = body.split('\\\\');
        const rebuilt = rows
          .map((row, i) => (i === rows.length - 1 ? row : row + rowSpacingFor(row)))
          .join('');
        return open + rebuilt + close;
      },
    );
  // Keep inline-code spans (`...`) verbatim — `C:\\path` must survive.
  const unescapeLine = (line: string): string => {
    const parts = line.split('`');
    if (parts.length < 2) return applyRules(line);
    // An odd number of backticks means a span is unterminated — there is no
    // valid inline-code segment, so keep the backtick(s) literal and apply the
    // escape-repair rules to the whole line. This also avoids the previous crash
    // where the dangling tail index was undefined / a second backtick was
    // injected.
    if (parts.length % 2 === 0) return applyRules(line);
    let out = '';
    for (let i = 0; i + 1 < parts.length; i += 2) {
      out += applyRules(parts[i]) + '`' + parts[i + 1] + '`';
    }
    // `parts.length` is odd here, so the loop leaves exactly one unpaired tail
    // segment (a real string), never an undefined index.
    out += applyRules(parts[parts.length - 1]);
    return out;
  };
  const fenceRe = /^(\s*)(```+|~~~+)/;
  let inFence = false;
  let fenceChar = '';
  const out: string[] = [];
  // Text between fences accumulates so the multi-line array row-spacing pass
  // can see whole `\begin{array}…\end{array}` blocks; fences pass through
  // verbatim and are never touched.
  let buf: string[] = [];
  const flush = () => {
    if (buf.length) {
      out.push(addArrayRowSpace(buf.join('\n')));
      buf = [];
    }
  };
  for (const line of source.split('\n')) {
    const fm = fenceRe.exec(line);
    if (fm) {
      if (!inFence) {
        inFence = true;
        fenceChar = fm[2][0];
      } else if (fm[2][0] === fenceChar) {
        inFence = false;
      }
      flush();
      out.push(line);
      continue;
    }
    if (inFence) out.push(line);
    else buf.push(unescapeLine(line));
  }
  flush();
  return out.join('\n');
}

/**
 * Run the source through every leniency preprocessor (inline-HTML-block
 * unwrapping, malformed table-delimiter repair, list re-indent) that makes
 * AI/PDF-exported Markdown render like Typora/Obsidian.
 */
function preprocessMarkdown(source: string): string {
  let s = normalizeTableDelimiters(unwrapInlineHtmlBlocks(source || ''));
  s = normalizeMathEscapes(s);
  return normalizeListIndent(s);
}

// XSS hardening. With `html: true` markdown-it passes raw HTML through
// verbatim, so a malicious document's `<img onerror=…>` would execute inside the
// WebView and could drive the Wails bindings (arbitrary file read/write). We
// sanitize the rendered markup before it ever reaches `v-html`.
//
// In the browser (Wails/WebView2) a real DOM is present, so DOMPurify strips
// event handlers and the dangerous tags below. Under Node (the pipeline test
// harness has no DOM) DOMPurify degrades to a no-op, so a minimal equivalent
// strip keeps the security invariant honest and testable without adding a DOM
// dep. Both paths keep `class`/`id`/`data-*` and styling intact, so the
// fixture assertions (task-list-item, md-callout, hljs-keyword, mark, input
// checkbox, …) are unaffected.
const SANITIZE_FORBID_TAGS = ['script', 'iframe', 'object', 'embed', 'form'];

function sanitizeHtml(html: string): string {
  try {
    if (typeof DOMPurify.sanitize === 'function') {
      return DOMPurify.sanitize(html, { FORBID_TAGS: SANITIZE_FORBID_TAGS });
    }
  } catch {
    /* no DOM available — fall through to the regex strip below */
  }
  return html
    .replace(/\son[a-z]+\s*=\s*"(?:[^"]*)"/gi, '')
    .replace(/\son[a-z]+\s*=\s*'(?:[^']*)'/gi, '')
    .replace(/\son[a-z]+\s*=\s*[^\s>]+/gi, '')
    .replace(/<\/?(?:script|iframe|object|embed|form)\b[^>]*>/gi, '');
}

export function renderMarkdown(source: string): string {
  lastFrontMatterRaw = null;
  const normalized = preprocessMarkdown(source);
  const body = sanitizeHtml(md.render(normalized));
  if (lastFrontMatterRaw !== null) {
    const fmHtml = renderFrontMatterHtml(lastFrontMatterRaw);
    lastFrontMatterRaw = null;
    return sanitizeHtml(fmHtml) + body;
  }
  return body;
}

/**
 * Extract the `imageRoot` field from a document's YAML front matter.
 * Supports aliases `image_root` and (Typora) `typora-root-url`.
 * Returns null if no front matter or no such field.
 *
 * Parsing is a minimal regex — we don't want a full YAML dep just for this.
 * Good enough for single-line string values like:
 *   imageRoot: ./images
 *   imageRoot: "D:\\blog\\assets"
 *   imageRoot: '/Users/foo/blog/assets'
 */
export function extractImageRoot(source: string): string | null {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(source);
  if (!m) return null;
  const fm = m[1];
  const im = /^(?:imageRoot|image_root|typora-root-url)\s*:\s*(.+?)\s*$/m.exec(fm);
  if (!im) return null;
  return im[1].replace(/^["']|["']$/g, '').trim() || null;
}

export interface OutlineItem {
  level: number;
  text: string;
  slug: string;
  line: number;
}

export function extractOutline(source: string): OutlineItem[] {
  const lines = source.split('\n');
  const items: OutlineItem[] = [];
  let inFence = false;
  // Front matter (`---` … `---`) is not part of the document body: its closing
  // `---` would otherwise be misread as a level-2 setext heading.
  let inFrontMatter = lines.length > 0 && /^---\s*$/.test(lines[0]);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (inFrontMatter) {
      // Stay in front matter until the closing `---` (never the opening line).
      if (i > 0 && /^---\s*$/.test(line)) inFrontMatter = false;
      continue;
    }
    // Fence toggle must match the rest of the pipeline: both ``` and ~~~.
    const fence = /^(\s*)(```+|~~~+)/.exec(line);
    if (fence) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;
    // ATX heading: `#` … `######`.
    const m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (m) {
      const level = m[1].length;
      const text = m[2];
      items.push({ level, text, slug: slugify(text), line: i + 1 });
      continue;
    }
    // Setext heading: the current line is the title, the next line is `===`
    // (level 1) or `---` (level 2). Conservatively require the title line to be
    // non-empty and not a table row (contains `|`), and only the `---`/`===`
    // underline form (a thematic break on its own line is not a heading).
    const next = lines[i + 1];
    if (next !== undefined && /^(?:=+|-+)\s*$/.test(next)) {
      const title = line.trim();
      if (title && !title.includes('|')) {
        const level = /^-+\s*$/.test(next.trim()) ? 2 : 1;
        items.push({ level, text: title, slug: slugify(title), line: i + 1 });
      }
    }
  }
  return items;
}
