<script lang="ts" setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { renderMarkdown, extractImageRoot, extractOutline } from './lib/markdown'
import type { OutlineItem } from './lib/markdown'
import {
  OpenFile,
  SaveFile,
  LoadImageForSrc,
  ReadFileAt,
  GetRecents,
  RemoveRecent,
  SetDirty,
  SetTitle,
  ConfirmExit,
} from '../wailsjs/go/main/App'
import { EventsOn, BrowserOpenURL } from '../wailsjs/runtime/runtime'
import { EditorView } from '@codemirror/view'
import { createMarkdownEditor, setEditorHighlight, replaceEditorDoc } from './lib/cm-editor'

const source = ref('')
const filePath = ref('')
const fileEnc = ref('utf-8')
const fileNewline = ref('lf')
const previewHtml = ref('')
const imageRoot = ref<string | null>(null)
const status = ref('')
const dirty = ref(false)
const recents = ref<string[]>([])
const previewEl = ref<HTMLElement | null>(null)
const editorHost = ref<HTMLElement | null>(null)
let editorView: EditorView | null = null
const imageCache = new Map<string, string>()
let renderTimer: ReturnType<typeof setTimeout> | null = null
let loadingFile = false
const exitConfirmVisible = ref(false)
const fileChangedVisible = ref(false)
const stats = ref({ words: 0, chars: 0 })

// View mode / outline / theme, persisted per-app via localStorage.
type ViewMode = 'split' | 'edit' | 'preview'
type Theme = 'light' | 'dark'
const viewMode = ref<ViewMode>((localStorage.getItem('mdview.viewMode') as ViewMode) || 'split')
const outlineVisible = ref(localStorage.getItem('mdview.outline') === '1')
const theme = ref<Theme>((localStorage.getItem('mdview.theme') as Theme) || 'light')
const outline = ref<OutlineItem[]>([])
let scrollLockUntil = 0

watch(viewMode, (v) => {
  try { localStorage.setItem('mdview.viewMode', v) } catch { /* ignore */ }
  // Coming back from display:none the editor must re-measure its layout.
  if (v !== 'preview' && editorView) nextTick(() => editorView?.requestMeasure())
})
watch(outlineVisible, (v) => { try { localStorage.setItem('mdview.outline', v ? '1' : '0') } catch { /* ignore */ } })
watch(theme, (v) => {
  try { localStorage.setItem('mdview.theme', v) } catch { /* ignore */ }
  if (editorView) setEditorHighlight(editorView, v === 'dark')
})

// ---- find ----
const findVisible = ref(false)
const findText = ref('')
const matchPositions = ref<number[]>([])
const matchIndex = ref(0)
const findInputEl = ref<HTMLInputElement | null>(null)

function computeMatches() {
  const q = findText.value
  if (!q) {
    matchPositions.value = []
    matchIndex.value = 0
    return
  }
  const hay = source.value.toLowerCase()
  const needle = q.toLowerCase()
  const idxs: number[] = []
  let i = hay.indexOf(needle)
  while (i !== -1 && idxs.length < 10000) {
    idxs.push(i)
    i = hay.indexOf(needle, i + needle.length)
  }
  matchPositions.value = idxs
  if (matchIndex.value >= idxs.length) matchIndex.value = 0
}

watch(findText, () => {
  computeMatches()
  applyFindHighlights()
  if (matchPositions.value.length) gotoMatch(matchIndex.value, false)
})

function openFind() {
  findVisible.value = true
  computeMatches()
  nextTick(() => {
    findInputEl.value?.focus()
    findInputEl.value?.select()
  })
  if (matchPositions.value.length) {
    applyFindHighlights()
    gotoMatch(matchIndex.value, false)
  }
}

function closeFind() {
  findVisible.value = false
  clearFindHighlights()
}

function findNext(back: boolean) {
  if (!findText.value) return
  computeMatches()
  const n = matchPositions.value.length
  if (!n) return
  gotoMatch(back ? matchIndex.value - 1 : matchIndex.value + 1, false)
}

function gotoMatch(i: number, focusEditor = true) {
  const idxs = matchPositions.value
  if (!idxs.length) return
  matchIndex.value = ((i % idxs.length) + idxs.length) % idxs.length
  const pos = idxs[matchIndex.value]
  const len = findText.value.length
  if (viewMode.value !== 'preview' && editorView) {
    if (focusEditor || viewMode.value === 'edit') editorView.focus()
    // Lock before dispatch: the programmatic editor scroll must not win the
    // race against the preview mark centering below.
    scrollLockUntil = Date.now() + 60
    editorView.dispatch({
      selection: { anchor: pos, head: pos + len },
      effects: EditorView.scrollIntoView(pos, { y: 'center' }),
    })
  }
  const marks = previewEl.value?.querySelectorAll('mark.find-hit')
  if (viewMode.value !== 'edit' && marks && marks.length) {
    scrollLockUntil = Date.now() + 60
    markCurrentHit()
  } else if (viewMode.value === 'preview') {
    scrollPreviewToLine(source.value.slice(0, pos).split('\n').length)
  }
}

// ---- preview match highlighting ----
// The preview is rendered HTML, so matches are wrapped in <mark> by walking
// text nodes — a plain string replace could hit tag attributes. Marking the
// current hit keeps F3 navigation visible in preview-only mode too.

function clearFindHighlights() {
  const root = previewEl.value
  if (!root) return
  root.querySelectorAll('mark.find-hit').forEach(m => {
    const parent = m.parentNode
    if (!parent) return
    while (m.firstChild) parent.insertBefore(m.firstChild, m)
    m.remove()
    parent.normalize()
  })
}

function applyFindHighlights() {
  const root = previewEl.value
  if (!root) return
  clearFindHighlights()
  const q = findText.value
  if (!findVisible.value || !q) return
  const needle = q.toLowerCase()
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node: Node) {
      const t = node as Text
      const p = t.parentElement
      if (!p || !t.nodeValue) return NodeFilter.FILTER_REJECT
      if (p.closest('annotation, script, style, mark')) return NodeFilter.FILTER_REJECT
      return t.nodeValue.toLowerCase().includes(needle)
        ? NodeFilter.FILTER_ACCEPT
        : NodeFilter.FILTER_REJECT
    },
  })
  const targets: Text[] = []
  let n: Node | null
  while ((n = walker.nextNode())) targets.push(n as Text)
  let k = 0
  for (const node of targets) {
    const text = node.nodeValue || ''
    const lower = text.toLowerCase()
    const frag = document.createDocumentFragment()
    let last = 0
    let idx = lower.indexOf(needle)
    while (idx !== -1) {
      if (idx > last) frag.appendChild(document.createTextNode(text.slice(last, idx)))
      const mark = document.createElement('mark')
      mark.className = k === matchIndex.value ? 'find-hit find-current' : 'find-hit'
      mark.textContent = text.slice(idx, idx + needle.length)
      frag.appendChild(mark)
      k++
      last = idx + needle.length
      idx = lower.indexOf(needle, last)
    }
    if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)))
    node.parentNode?.replaceChild(frag, node)
  }
}

function markCurrentHit() {
  const marks = previewEl.value?.querySelectorAll('mark.find-hit')
  if (!marks || !marks.length) return
  marks.forEach((m, i) => m.classList.toggle('find-current', i === matchIndex.value))
  const idx = Math.min(matchIndex.value, marks.length - 1)
  marks[idx]?.scrollIntoView({ block: 'center' })
}

// ---- base name / title / dirty ----
function baseName(p: string): string {
  return p.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || p
}

function updateTitle() {
  const name = filePath.value ? baseName(filePath.value) : '无标题'
  // Dirty marker goes BEFORE the filename: OS title bars truncate long titles
  // at the end, so a trailing `*` disappears exactly when names are longest.
  SetTitle(`simplify2md — ${dirty.value ? '● ' : ''}${name}`).catch(() => {})
}

function markDirty() {
  dirty.value = true
  SetDirty(true).catch(() => {})
  updateTitle()
}

function markClean() {
  dirty.value = false
  SetDirty(false).catch(() => {})
  updateTitle()
}

function applyLoaded(r: { path: string; content: string; encoding?: string; newline?: string }) {
  loadingFile = true
  filePath.value = r.path
  // Feed the doc through the editor so its DOM/undo state stays authoritative;
  // the update listener mirrors it back into `source`. loadingFile holds the
  // source watcher off (watchers flush before the setTimeout below runs), so a
  // freshly opened file is not marked dirty.
  if (editorView) replaceEditorDoc(editorView, r.content)
  if (source.value !== r.content) source.value = r.content
  fileEnc.value = r.encoding || 'utf-8'
  fileNewline.value = r.newline || 'lf'
  imageRoot.value = extractImageRoot(r.content)
  imageCache.clear()
  markClean()
  refreshRecents()
  render()
  setTimeout(() => { loadingFile = false }, 0)
}

async function loadPath(path: string): Promise<boolean> {
  try {
    applyLoaded(await ReadFileAt(path))
    return true
  } catch (e: any) {
    status.value = String(e)
    return false
  }
}

async function open() {
  try {
    applyLoaded(await OpenFile())
  } catch (e: any) {
    // Dialog cancel is a normal user action, not an error.
    if (!/cancelled/i.test(String(e))) status.value = String(e)
  }
}

async function save() {
  if (!filePath.value) return
  try {
    await SaveFile(filePath.value, source.value, fileEnc.value, fileNewline.value)
    markClean()
    status.value = '已保存 ' + new Date().toLocaleTimeString()
  } catch (e: any) {
    status.value = String(e)
  }
}

function confirmExit() {
  ConfirmExit()
}

async function saveAndExit() {
  await save()
  if (!dirty.value) ConfirmExit()
}

async function reloadFromDisk() {
  fileChangedVisible.value = false
  if (filePath.value) await loadPath(filePath.value)
}

async function refreshRecents() {
  try {
    recents.value = (await GetRecents()) || []
  } catch {
    /* ignore */
  }
}

function onRecentChange(e: Event) {
  const sel = e.target as HTMLSelectElement
  const p = sel.value
  sel.selectedIndex = 0
  if (p) loadPath(p)
}

// Route link clicks in the preview. The WebView itself must never navigate
// away: external links go to the system browser, in-page anchors scroll the
// preview, relative .md links open in-app, everything else is blocked.
function onPreviewClick(e: MouseEvent) {
  const a = (e.target as HTMLElement).closest('a')
  if (!a) return
  const href = a.getAttribute('href')
  if (!href) return
  if (href.startsWith('#')) {
    e.preventDefault()
    let id = href.slice(1)
    try { id = decodeURIComponent(id) } catch { /* keep raw */ }
    const el = previewEl.value?.querySelector('[id="' + CSS.escape(id) + '"]')
    el?.scrollIntoView({ behavior: 'smooth' })
    return
  }
  if (/^(https?|mailto):/i.test(href)) {
    e.preventDefault()
    BrowserOpenURL(href).catch(() => {})
    return
  }
  e.preventDefault()
  if (/\.md\b/i.test(href) && filePath.value) {
    const base = filePath.value.replace(/[\\/][^\\/]*$/, '')
    const abs = /^[a-zA-Z]:[\\/]/.test(href)
      ? href
      : base + '\\' + href.replace(/\//g, '\\')
    loadPath(abs)
  }
}

// Render markdown -> HTML, preserving preview scroll across re-renders,
// then load local images via Go (with a cache so typing stays cheap).
function render() {
  const el = previewEl.value
  const st = el ? el.scrollTop : 0
  previewHtml.value = renderMarkdown(source.value)
  outline.value = extractOutline(source.value)
  computeStats(source.value)
  nextTick(() => {
    if (el) el.scrollTop = st
    resolveImages()
    if (findVisible.value && findText.value) {
      applyFindHighlights()
      const marks = previewEl.value?.querySelectorAll('mark.find-hit')
      marks?.forEach((m, i) => m.classList.toggle('find-current', i === matchIndex.value))
    }
  })
}

function computeStats(s: string) {
  const cjkRe = /[一-鿿ぁ-ヿ가-힯]/g
  const cjk = (s.match(cjkRe) || []).length
  const words = cjk + (s.replace(cjkRe, ' ').match(/[A-Za-z0-9_]+/g) || []).length
  stats.value = { words, chars: s.length }
}

async function resolveImages() {
  const root = previewEl.value
  if (!root) return
  for (const img of Array.from(root.querySelectorAll<HTMLImageElement>('img'))) {
    const src = img.getAttribute('src') || ''
    // Leave remote / data / blob URLs alone.
    if (!src || /^(https?:|data:|blob:)/i.test(src)) continue
    if (img.dataset.loaded === '1') continue
    img.dataset.loaded = '1'
    const key = filePath.value + '|' + src
    const cached = imageCache.get(key)
    if (cached) {
      img.src = cached
      continue
    }
    try {
      const r = await LoadImageForSrc(src, filePath.value, imageRoot.value || '')
      if (r.b64) {
        const url = `data:${r.mime};base64,${r.b64}`
        imageCache.set(key, url)
        img.src = url
      }
    } catch {
      // leave broken-image placeholder
    }
  }
}

// Scroll the preview so the given 1-indexed source line's block is at top.
function scrollPreviewToLine(line: number) {
  const pv = previewEl.value
  if (!pv) return
  const blocks = pv.querySelectorAll<HTMLElement>('[data-source-line]')
  let target: HTMLElement | null = null
  for (const b of Array.from(blocks)) {
    const l = parseInt(b.dataset.sourceLine || '0', 10)
    if (l <= line) target = b
    else break
  }
  if (!target && blocks.length) target = blocks[0]
  if (!target) return
  const pvRect = pv.getBoundingClientRect()
  const tRect = target.getBoundingClientRect()
  pv.scrollTop += tRect.top - pvRect.top - 8
}

// Split-pane scroll sync, mapped through the data-source-line anchors the
// markdown pipeline stamps on every block element.
function onEditorScroll() {
  if (viewMode.value !== 'split') return
  if (Date.now() < scrollLockUntil) return
  const view = editorView
  const pv = previewEl.value
  if (!view || !pv) return
  scrollLockUntil = Date.now() + 60
  // The source line visible at the editor viewport's top edge drives the
  // preview — posAtCoords handles wrapped lines and variable line heights.
  const rect = view.scrollDOM.getBoundingClientRect()
  const pos = Math.min(
    view.posAtCoords({ x: rect.left + 10, y: rect.top + 4 }, false),
    view.state.doc.length,
  )
  scrollPreviewToLine(view.state.doc.lineAt(Math.max(0, pos)).number)
}

function onPreviewScroll() {
  if (viewMode.value !== 'split') return
  if (Date.now() < scrollLockUntil) return
  const view = editorView
  const pv = previewEl.value
  if (!view || !pv) return
  const pvRect = pv.getBoundingClientRect()
  const blocks = pv.querySelectorAll<HTMLElement>('[data-source-line]')
  let line = 1
  for (const b of Array.from(blocks)) {
    const r = b.getBoundingClientRect()
    if (r.top - pvRect.top <= 12) line = parseInt(b.dataset.sourceLine || '1', 10) || 1
    else break
  }
  scrollLockUntil = Date.now() + 60
  const pos = view.state.doc.line(Math.min(line, view.state.doc.lines)).from
  view.dispatch({ effects: EditorView.scrollIntoView(pos, { y: 'start', yMargin: 0 }) })
}

function gotoOutline(o: OutlineItem) {
  const el = previewEl.value?.querySelector('[id="' + CSS.escape(o.slug) + '"]')
  if (el) {
    el.scrollIntoView({ behavior: 'smooth' })
    return
  }
  const view = editorView
  if (view && o.line >= 1 && o.line <= view.state.doc.lines) {
    const pos = view.state.doc.line(o.line).from
    view.dispatch({ effects: EditorView.scrollIntoView(pos, { y: 'start', yMargin: 8 }) })
  }
}

watch(source, () => {
  if (loadingFile) return
  if (!dirty.value) markDirty()
  if (renderTimer) clearTimeout(renderTimer)
  renderTimer = setTimeout(render, 200)
})

function onKeydown(e: KeyboardEvent) {
  const k = e.key.toLowerCase()
  if (e.ctrlKey && !e.shiftKey && !e.altKey) {
    if (k === 's') {
      e.preventDefault()
      save()
      return
    }
    if (k === 'o') {
      e.preventDefault()
      open()
      return
    }
    if (k === 'f') {
      e.preventDefault()
      openFind()
      return
    }
  }
  if (k === 'f3') {
    e.preventDefault()
    if (!findVisible.value) openFind()
    else findNext(e.shiftKey)
    return
  }
  if (k === 'escape') {
    if (findVisible.value) {
      e.preventDefault()
      closeFind()
    } else if (exitConfirmVisible.value) {
      exitConfirmVisible.value = false
    }
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  if (editorHost.value) {
    editorView = createMarkdownEditor(editorHost.value, source.value, theme.value === 'dark', (t) => {
      source.value = t
    })
    editorView.scrollDOM.addEventListener('scroll', onEditorScroll)
  }
  EventsOn('mdview:confirm-exit', () => { exitConfirmVisible.value = true })
  EventsOn('mdview:file-changed', () => {
    if (loadingFile || !filePath.value) return
    if (!dirty.value) {
      // Not modified locally — follow the disk silently.
      loadPath(filePath.value)
      status.value = '文件已在磁盘上更新，已重新加载'
    } else {
      fileChangedVisible.value = true
    }
  })
  updateTitle()
  await refreshRecents()
  // Startup restore: reopen the most recent file; if it vanished from disk,
  // drop it from the list instead of failing on every launch.
  if (recents.value.length > 0) {
    const first = recents.value[0]
    if (!(await loadPath(first))) {
      await RemoveRecent(first)
      recents.value = recents.value.filter((p) => p !== first)
      status.value = `上次打开的文件已失效：${baseName(first)}`
    }
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (editorView) {
    editorView.scrollDOM.removeEventListener('scroll', onEditorScroll)
    editorView.destroy()
    editorView = null
  }
})
</script>

<template>
  <div class="app" :class="{ dark: theme === 'dark' }">
    <div class="toolbar">
      <button @click="open">打开</button>
      <button @click="save" :disabled="!filePath">保存</button>
      <span class="seg">
        <button :class="{ active: viewMode === 'edit' }" @click="viewMode = 'edit'">编辑</button>
        <button :class="{ active: viewMode === 'split' }" @click="viewMode = 'split'">分屏</button>
        <button :class="{ active: viewMode === 'preview' }" @click="viewMode = 'preview'">预览</button>
      </span>
      <button :class="{ active: outlineVisible }" @click="outlineVisible = !outlineVisible">大纲</button>
      <button @click="theme = theme === 'dark' ? 'light' : 'dark'">
        {{ theme === 'dark' ? '亮色' : '暗色' }}
      </button>
      <select v-if="recents.length" class="recents" @change="onRecentChange">
        <option disabled selected>最近文件</option>
        <option v-for="p in recents" :key="p" :value="p">{{ baseName(p) }}</option>
      </select>
      <span class="enc">{{ fileEnc }}</span>
      <span class="path"><span v-if="dirty" class="dirty-dot">● </span>{{ filePath }}</span>
      <span class="stats">{{ stats.words }} 字 · {{ stats.chars }} 字符</span>
      <span class="status">{{ status }}</span>
    </div>
    <div class="main" :class="'mode-' + viewMode">
      <div class="editor" ref="editorHost"></div>
      <div
        class="preview"
        ref="previewEl"
        v-html="previewHtml"
        @click="onPreviewClick"
        @scroll="onPreviewScroll"
      ></div>
      <div v-if="outlineVisible" class="outline">
        <div class="outline-title">大纲</div>
        <div v-if="!outline.length" class="outline-empty">（无标题）</div>
        <div
          v-for="(o, i) in outline"
          :key="i"
          class="outline-item"
          :class="'lv' + o.level"
          :style="{ paddingLeft: (o.level - 1) * 14 + 8 + 'px' }"
          @click="gotoOutline(o)"
        >{{ o.text }}</div>
      </div>
    </div>
    <div v-if="findVisible" class="findbar">
      <input
        ref="findInputEl"
        v-model="findText"
        placeholder="查找..."
        spellcheck="false"
        @keydown.enter.prevent="findNext($event.shiftKey)"
        @keydown.esc.stop="closeFind"
      />
      <span class="find-count">
        {{ findText ? (matchPositions.length ? (matchIndex + 1) + '/' + matchPositions.length : '无结果') : '' }}
      </span>
      <button title="上一个 (Shift+F3)" @click="findNext(true)">↑</button>
      <button title="下一个 (F3 / Enter)" @click="findNext(false)">↓</button>
      <button title="关闭 (Esc)" @click="closeFind">✕</button>
    </div>
    <div v-if="exitConfirmVisible" class="modal-mask">
      <div class="modal">
        <div class="modal-title">未保存的修改</div>
        <div class="modal-body">文档有未保存的修改。要怎么处理？</div>
        <div class="modal-actions">
          <button @click="saveAndExit">保存并退出</button>
          <button @click="confirmExit">不保存退出</button>
          <button @click="exitConfirmVisible = false">取消</button>
        </div>
      </div>
    </div>
    <div v-if="fileChangedVisible" class="modal-mask">
      <div class="modal">
        <div class="modal-title">文件已在磁盘上被修改</div>
        <div class="modal-body">
          “{{ baseName(filePath) }}”在应用外被修改了。{{ dirty ? '当前有未保存的修改，重新加载将放弃这些修改。' : '' }}
        </div>
        <div class="modal-actions">
          <button @click="reloadFromDisk">重新加载</button>
          <button @click="fileChangedVisible = false">忽略</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.app {
  --bg: #ffffff;
  --fg: #222222;
  --muted: #666666;
  --faint: #888888;
  --panel: #f3f3f3;
  --border: #dddddd;
  --hover: #e8f1ff;
  --accent: #d5e5ff;
  --accent-border: #9cc2f0;
  --code-bg: #f6f8fa;
  --input-bg: #ffffff;
  --ok: #0a8f3c;

  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg);
  color: var(--fg);
  position: relative;
}
.app.dark {
  --bg: #1e1e1e;
  --fg: #d4d4d4;
  --muted: #a0a0a0;
  --faint: #858585;
  --panel: #252526;
  --border: #3c3c3c;
  --hover: #2a2d2e;
  --accent: #094771;
  --accent-border: #0e639c;
  --code-bg: #1b1b1b;
  --input-bg: #1e1e1e;
  --ok: #4ec98a;
}
* { box-sizing: border-box; }
html, body, #app { height: 100%; margin: 0; }
body { font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
.toolbar { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--panel); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.toolbar button { padding: 4px 12px; cursor: pointer; background: var(--input-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 3px; }
.toolbar button.active { background: var(--accent); border-color: var(--accent-border); }
.seg { display: inline-flex; }
.seg button { border-radius: 0; }
.seg button:first-child { border-radius: 3px 0 0 3px; }
.seg button:last-child { border-radius: 0 3px 3px 0; }
.toolbar .recents { max-width: 200px; padding: 3px 6px; background: var(--input-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 3px; }
.toolbar .enc { font-size: 12px; color: var(--faint); border: 1px solid var(--border); border-radius: 3px; padding: 1px 6px; }
.toolbar .path { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 26%; }
.toolbar .dirty-dot { color: #e8a13c; }
.toolbar .stats { font-size: 12px; color: var(--faint); }
.toolbar .status { margin-left: auto; font-size: 12px; color: var(--ok); }
.main { flex: 1; display: flex; overflow: hidden; }
.editor { flex: 1 1 0; min-width: 0; height: 100%; border: none; border-right: 1px solid var(--border); outline: none; overflow: hidden; background: var(--bg); color: var(--fg); }
.editor .cm-editor { height: 100%; }
.preview { flex: 1 1 0; min-width: 0; height: 100%; overflow: auto; padding: 20px 28px; }
.mode-edit .preview { display: none; }
.mode-edit .editor { border-right: none; }
.mode-preview .editor { display: none; }
.outline { flex: 0 0 220px; border-left: 1px solid var(--border); overflow: auto; padding: 8px 0 20px; font-size: 13px; background: var(--panel); }
.outline-title { font-weight: 600; padding: 4px 10px 8px; color: var(--muted); }
.outline-item { padding: 3px 8px; cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--fg); }
.outline-item:hover { background: var(--hover); }
.outline-item.lv1 { font-weight: 600; }
.outline-empty { padding: 6px 10px; color: var(--faint); font-size: 12px; }
.findbar { position: absolute; top: 44px; right: 16px; display: flex; align-items: center; gap: 6px; padding: 6px 8px; background: var(--panel); border: 1px solid var(--border); border-radius: 6px; box-shadow: 0 4px 16px rgba(0,0,0,.18); z-index: 60; }
.findbar input { width: 180px; padding: 3px 8px; border: 1px solid var(--border); border-radius: 3px; background: var(--input-bg); color: var(--fg); outline: none; }
.findbar .find-count { font-size: 12px; color: var(--muted); min-width: 44px; text-align: center; }
.findbar button { padding: 2px 8px; cursor: pointer; background: var(--input-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 3px; }
.preview h1, .preview h2, .preview h3 { line-height: 1.3; }
.preview img { max-width: 100%; height: auto; }
.preview pre { background: var(--code-bg); padding: 12px; overflow: auto; border-radius: 4px; }
.preview code { font-family: "Cascadia Code", Consolas, monospace; font-size: 13px; }
.preview table { border-collapse: collapse; }
.preview th, .preview td { border: 1px solid var(--border); padding: 6px 10px; }
.preview blockquote { border-left: 4px solid var(--border); margin: 0; padding-left: 14px; color: var(--muted); }
.preview mark.find-hit { background: #ffe066; color: #222; padding: 0 1px; border-radius: 2px; }
.preview mark.find-hit.find-current { background: #ff9f2e; color: #222; }
.app.dark .preview mark.find-hit { background: #77641f; color: #fff; }
.app.dark .preview mark.find-hit.find-current { background: #c07f1c; color: #1e1e1e; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--bg); color: var(--fg); border: 1px solid var(--border); border-radius: 8px; padding: 20px 24px; width: 380px; box-shadow: 0 8px 30px rgba(0,0,0,.25); }
.modal-title { font-weight: 600; font-size: 15px; margin-bottom: 8px; }
.modal-body { font-size: 13px; color: var(--muted); margin-bottom: 16px; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; }
.modal-actions button { padding: 5px 14px; cursor: pointer; background: var(--input-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 3px; }
</style>
