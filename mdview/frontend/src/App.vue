<script lang="ts" setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { renderMarkdown, extractImageRoot, extractOutline } from './lib/markdown'
import type { OutlineItem } from './lib/markdown'
import {
  OpenFile,
  SaveFile,
  PickSavePath,
  LoadImageForSrc,
  ReadFileAt,
  GetRecents,
  GetStartupFile,
  RemoveRecent,
  SetDirty,
  SetTitle,
  ConfirmExit,
  CheckForUpdate,
  SaveDraft,
  LoadDraft,
  ListDrafts,
  ClearDraft,
  ClearRecents,
} from '../wailsjs/go/main/App'
import { EventsOn, BrowserOpenURL } from '../wailsjs/runtime/runtime'
import { EditorView } from '@codemirror/view'
import { createMarkdownEditor, setEditorHighlight, replaceEditorDoc } from './lib/cm-editor'
import { LruCache } from './lib/lru'

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
// Image cache: LRU with a dual budget (64 items AND 256MB total base64 bytes).
// '' means a known-failed load — the entry counts toward the budget and is
// never retried, so a missing image isn't re-read from disk on every re-render.
const imageCache = new LruCache()
const imageInFlight = new Map<string, Promise<void>>()
let renderTimer: ReturnType<typeof setTimeout> | null = null
let loadingFile = false
const exitConfirmVisible = ref(false)
const fileChangedVisible = ref(false)
const switchConfirmVisible = ref(false)
const pendingSwitchAction = ref<(() => void) | null>(null)
const stats = ref({ words: 0, chars: 0 })

// ---- update check (todo 4) ----
const updateTipVisible = ref(false)
const updateTipText = ref('')
const updateTipUrl = ref('')
const checkingUpdate = ref(false)

// Startup update check + draft recovery. MUST run on BOTH startup paths (the
// GetStartupFile branch returns early, so the recents-restore tail never runs
// in that case — ORACLE-M1): called once before that early return and once at
// the end of the recents branch. Auto-path failures are silent; the manual
// toolbar button surfaces them in the status bar.
async function postStartup() {
  // Startup draft recovery (todo 10): only AFTER the startup load has settled
  // (resolve or reject) so we never prompt over a still-loading document.
  await recoverLatestDraft()
  // Fire-and-forget update check; never blocks or fails the startup chain.
  try {
    const info = await CheckForUpdate()
    if (info && info.hasUpdate && info.htmlURL) {
      updateTipText.value = `新版本 ${info.latestTag} 可用，点击下载`
      updateTipUrl.value = info.htmlURL
      updateTipVisible.value = true
    }
  } catch {
    // Silent: automatic check failures give no feedback (ORACLE-M1).
  }
}

// Manual "检查更新" toolbar action: loading state + all three outcomes shown
// explicitly (has-update tip bar / up-to-date / check failed in status bar).
async function manualCheckUpdate() {
  if (checkingUpdate.value) return
  checkingUpdate.value = true
  try {
    const info = await CheckForUpdate()
    if (info && info.hasUpdate && info.htmlURL) {
      updateTipText.value = `新版本 ${info.latestTag} 可用，点击下载`
      updateTipUrl.value = info.htmlURL
      updateTipVisible.value = true
      status.value = `发现新版本 ${info.latestTag}`
    } else {
      status.value = '已是最新版本'
    }
  } catch (e: unknown) {
    // Manual path failures MUST be visible (METIS#8).
    status.value = '检查更新失败: ' + (e instanceof Error ? e.message : String(e))
  } finally {
    checkingUpdate.value = false
  }
}

function openUpdateUrl() {
  if (!updateTipUrl.value) return
  // wailsjs types BrowserOpenURL as void but it returns a promise at runtime.
  Promise.resolve(BrowserOpenURL(updateTipUrl.value)).catch(() => {})
}

function dismissUpdateTip() {
  updateTipVisible.value = false
}

// ---- draft recovery (todo 10) ----
// Only the LATEST draft is prompted (older drafts stay on disk, unpruned).
async function recoverLatestDraft() {
  try {
    const drafts = (await ListDrafts()) || []
    if (!drafts.length) return
    const latest = drafts[0] // Go side returns them newest-first
    const content = await LoadDraft(latest.key)
    if (!content) return
    draftRecoverInfo.value = {
      key: latest.key,
      content,
      name: await draftDisplayName(latest.key),
    }
    draftRecoverVisible.value = true
  } catch {
    // No drafts / binding failure — nothing to recover, stay silent.
  }
}

// The key is sha1(path) — not reversible. Best effort: match it against the
// recents list; untitled has the literal key.
async function draftDisplayName(key: string): Promise<string> {
  if (key === 'untitled') return '无标题'
  for (const p of recents.value) {
    if ((await sha1Hex(p)) === key) return baseName(p)
  }
  return '未知文档'
}

function confirmRecoverDraft() {
  const info = draftRecoverInfo.value
  draftRecoverVisible.value = false
  if (!info) return
  loadingFile = true
  if (editorView) replaceEditorDoc(editorView, info.content)
  if (source.value !== info.content) source.value = info.content
  // File open → overwrite current content; not open → becomes the untitled
  // doc (filePath stays ''). Either way the recovered edits stay dirty so a
  // follow-up autosave re-persists them under the current key.
  markDirty()
  imageRoot.value = extractImageRoot(info.content)
  imageCache.clear()
  render()
  setTimeout(() => { loadingFile = false }, 0)
  draftRecoverInfo.value = null
  status.value = '已恢复未保存的草稿'
}

function rejectRecoverDraft() {
  // Keep the draft on disk — the user may recover later (todo 10d).
  draftRecoverVisible.value = false
  draftRecoverInfo.value = null
}

// Autosave drafts (todo 10): ≤30s throttle while dirty + ≥30s idle final flush.
const AUTOSAVE_INTERVAL_MS = 30_000
let draftSaveTimer: ReturnType<typeof setTimeout> | null = null
let draftIdleTimer: ReturnType<typeof setTimeout> | null = null
let draftSnapshot: { path: string; content: string } | null = null
let draftLastWriteAt = 0
let draftInFlight = false
let draftSaveFailed = false // one silent failure stops retrying until next dirty cycle
// Bumped on every abandon (switch/exit/reload/save) so an ALREADY-IN-FLIGHT
// SaveDraft can detect that its target draft was cleared while it was awaiting
// the bridge — and delete the file its write just recreated (ORACLE-M3).
let draftAbandonToken = 0
// Outline collapse (todo 7): folding LEVEL N hides every same-level sub-tree.
const collapsedLevels = ref<Set<number>>(new Set())
// Reading progress (todo 7): 0..100, shown in preview/split modes.
const progressPct = ref(0)
// Lightbox (todo 6)
const lightboxVisible = ref(false)
const lightboxSrc = ref('')
// Draft recovery prompt (todo 10) — reuses the switchConfirm modal styling.
const draftRecoverVisible = ref(false)
const draftRecoverInfo = ref<{ key: string; content: string; name?: string } | null>(null)

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
const findCapped = ref(false)
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
  if (!findVisible.value || !q) {
    findCapped.value = false
    return
  }
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
  // Cap the number of wrapped <mark> elements so a huge document can't freeze
  // the UI building thousands of nodes. Once we hit the cap we stop wrapping and
  // surface "500+" in the find counter.
  const MAX_MARKS = 500
  let k = 0
  let capped = false
  for (const node of targets) {
    const text = node.nodeValue || ''
    const lower = text.toLowerCase()
    const frag = document.createDocumentFragment()
    let last = 0
    let idx = lower.indexOf(needle)
    while (idx !== -1) {
      if (k >= MAX_MARKS) {
        capped = true
        break
      }
      if (idx > last) frag.appendChild(document.createTextNode(text.slice(last, idx)))
      const mark = document.createElement('mark')
      mark.className = k === matchIndex.value ? 'find-hit find-current' : 'find-hit'
      mark.textContent = text.slice(idx, idx + needle.length)
      frag.appendChild(mark)
      k++
      last = idx + needle.length
      idx = lower.indexOf(needle, last)
    }
    if (capped) {
      // Flush the remainder of this node as plain text, then stop entirely.
      frag.appendChild(document.createTextNode(text.slice(last)))
      node.parentNode?.replaceChild(frag, node)
      break
    }
    if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)))
    node.parentNode?.replaceChild(frag, node)
  }
  findCapped.value = capped
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
  const wasClean = !dirty.value
  dirty.value = true
  SetDirty(true).catch(() => {})
  updateTitle()
  if (wasClean) draftSaveFailed = false // fresh edit session: retry drafts again
  scheduleDraftSave()
}

function markClean() {
  dirty.value = false
  SetDirty(false).catch(() => {})
  updateTitle()
}

// ---- autosave drafts (todo 10) ----
// Never write on the keystroke hot path: a throttled timer (≤30s while dirty)
// plus an idle flush (≥30s after editing stops). Each scheduling round captures
// a {path, content} snapshot; the write only lands after a triple check so a
// stale timer can't resurrect abandoned content.

async function draftKey(path: string): Promise<string> {
  return path ? sha1Hex(path) : 'untitled'
}

async function sha1Hex(s: string): Promise<string> {
  const data = new TextEncoder().encode(s)
  const buf = await crypto.subtle.digest('SHA-1', data)
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

function scheduleDraftSave() {
  if (!dirty.value) return
  // Refresh the snapshot on every edit so the guarded write always sees the
  // newest content; timers only — never disk IO on the keystroke hot path.
  draftSnapshot = { path: filePath.value, content: source.value }
  // Final flush: fires once, 30s after the LAST edit.
  if (draftIdleTimer) clearTimeout(draftIdleTimer)
  draftIdleTimer = setTimeout(() => {
    draftIdleTimer = null
    void writeDraftSnapshot()
  }, AUTOSAVE_INTERVAL_MS)
  // Throttle: while editing keeps going, write at most every 30s.
  if (draftSaveTimer) return
  const due = draftLastWriteAt + AUTOSAVE_INTERVAL_MS - Date.now()
  draftSaveTimer = setTimeout(() => {
    draftSaveTimer = null
    void writeDraftSnapshot()
  }, Math.max(0, due))
}

async function writeDraftSnapshot() {
  const snap = draftSnapshot
  if (!snap) return
  // Triple guard (ORACLE-M3): same path, still dirty, content unchanged.
  // The dirty check blocks reloadFromDisk's same-path stale save; the content
  // check blocks a timer from a superseded editing session.
  const shouldWrite =
    filePath.value === snap.path && dirty.value === true && snap.content === source.value
  if (!shouldWrite) return
  if (draftInFlight || draftSaveFailed) return
  draftInFlight = true
  // Open the throttle window at write START so keystrokes landing during the
  // async SaveDraft schedule the next write at +30s instead of immediately
  // hitting the in-flight guard and stalling the cadence.
  draftLastWriteAt = Date.now()
  const myToken = draftAbandonToken
  try {
    await SaveDraft(await draftKey(snap.path), snap.content)
    if (myToken !== draftAbandonToken) {
      // The draft was abandoned while the write was in flight: delete the
      // file this write just recreated so abandoned content cannot resurrect.
      await ClearDraft(await draftKey(snap.path)).catch(() => {})
      return
    }
  } catch {
    // Silent once; stop retrying this session until the doc goes clean→dirty.
    draftSaveFailed = true
  } finally {
    draftInFlight = false
  }
}

// Abandoning content on `path`: clear the stored draft AND cancel this key's
// pending timers / in-flight write so a late SaveDraft can't recreate it
// (ORACLE-M3: ClearDraft-then-inflight-WriteBack resurrection).
function abandonDraftFor(path: string) {
  // Bump FIRST: any in-flight write that started before this abandon will see
  // a token mismatch when it resolves and clean up after itself.
  draftAbandonToken++
  if (draftSaveTimer) {
    clearTimeout(draftSaveTimer)
    draftSaveTimer = null
  }
  if (draftIdleTimer) {
    clearTimeout(draftIdleTimer)
    draftIdleTimer = null
  }
  // The pending snapshot belongs to the abandoned session; drop it.
  draftSnapshot = null
  const key = path ? sha1Hex(path) : Promise.resolve('untitled')
  key
    .then((k) => ClearDraft(k))
    .catch(() => {})
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

async function openFileDialog() {
  try {
    applyLoaded(await OpenFile())
  } catch (e: any) {
    // Dialog cancel is a normal user action, not an error.
    if (!/cancelled/i.test(String(e))) status.value = String(e)
  }
}

// A document switch (recent dropdown, second-instance open-path, preview .md
// link, or the open dialog) must not silently discard unsaved edits. When the
// current doc is dirty we ask for confirmation and defer the switch; the startup
// restore path and reloadFromDisk manage their own modals and skip this guard.
function requestSwitch(action: () => void) {
  if (!dirty.value) {
    action()
    return
  }
  pendingSwitchAction.value = action
  switchConfirmVisible.value = true
}

function confirmSwitch() {
  const action = pendingSwitchAction.value
  pendingSwitchAction.value = null
  switchConfirmVisible.value = false
  if (!action) return
  // Proceeding abandons the current doc's unsaved content: drop its draft
  // first so no pending autosave can resurrect it (todo 10c).
  abandonDraftFor(filePath.value)
  if (dirty.value) markClean() // content is being discarded; cancel pending timers via markDirty path later
  action()
}

function cancelSwitch() {
  pendingSwitchAction.value = null
  switchConfirmVisible.value = false
}

async function open() {
  requestSwitch(() => { void openFileDialog() })
}

async function save() {
  if (!filePath.value) {
    await saveAs()
    return
  }
  try {
    await SaveFile(filePath.value, source.value, fileEnc.value, fileNewline.value)
    markClean()
    // Successful manual save supersedes the draft: abandonDraftFor clears the
    // stored file, cancels pending timers and invalidates any in-flight write
    // (token bump) — the same resurrection guard as the abandon paths.
    abandonDraftFor(filePath.value)
    status.value = '已保存 ' + new Date().toLocaleTimeString()
  } catch (e: any) {
    status.value = String(e)
  }
}

// 另存为：弹原生保存对话框选路径。无路径文档的首次保存也走这里——没有它，
// "保存并退出"对无标题文档是死路（无处可写，退出弹窗关不掉）。
async function saveAs() {
  try {
    const p = await PickSavePath(filePath.value ? baseName(filePath.value) : '未标题.md')
    if (!p) return
    await SaveFile(p, source.value, fileEnc.value, fileNewline.value)
    // The untitled draft (if any) is now superseded by the real file.
    const oldPath = filePath.value
    filePath.value = p
    markClean()
    abandonDraftFor(p)
    if (oldPath) abandonDraftFor(oldPath)
    else abandonDraftFor('')
    status.value = '已保存 ' + new Date().toLocaleTimeString()
    await refreshRecents()
  } catch (e: any) {
    status.value = String(e)
  }
}

function confirmExit() {
  // "不保存退出" abandons the current doc's content: clear its draft first
  // (todo 10c). Note the button semantics: the exit modal's 不保存退出 calls
  // this, so abandon before handing control to the Go side.
  abandonDraftFor(filePath.value)
  ConfirmExit()
}

async function saveAndExit() {
  await save()
  if (!dirty.value) ConfirmExit()
}

async function reloadFromDisk() {
  fileChangedVisible.value = false
  if (filePath.value) {
    // Reloading abandons local edits: clear the draft + pending timers first
    // (todo 10c) so the on-disk content can't be overwritten by a stale save
    // (ORACLE-M3: also blocked by the dirty===true triple guard).
    abandonDraftFor(filePath.value)
    await loadPath(filePath.value)
  }
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
  // Sentinel option "清空记录" (todo 11): wipe the whole list, no confirm dialog
  // (low-value data). After clearing, the v-if hides the select — the status
  // bar is the only feedback. Must be handled BEFORE requestSwitch.
  if (p === '__clear__') {
    ClearRecents()
      .then(() => {
        recents.value = []
        status.value = '已清空最近记录'
      })
      .catch((err: unknown) => {
        status.value = String(err)
      })
    return
  }
  if (p) requestSwitch(() => { void loadPath(p) })
}

// Route clicks inside the preview via delegation. Priority order:
// 1. copy button (injected into code blocks),
// 2. image → lightbox (an <a>-wrapped image intentionally opens the lightbox
//    instead of the link — image-first is the product decision for v0.2.0),
// 3. links (external → system browser, anchors → scroll, relative .md → open).
// The WebView itself must never navigate away.
function onPreviewClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  // (1) Copy-code button.
  const btn = target.closest('.copy-btn')
  if (btn) {
    const pre = btn.closest('pre')
    const code = pre?.querySelector('code')
    if (code) {
      navigator.clipboard
        .writeText(code.textContent || '')
        .then(() => {
          btn.textContent = '已复制'
          setTimeout(() => { btn.textContent = '复制' }, 1500)
        })
        .catch(() => {
          btn.textContent = '复制失败'
          setTimeout(() => { btn.textContent = '复制' }, 1500)
        })
    }
    return
  }
  // (2) Image → lightbox. Read the clicked node's src directly (works for
  // data:/https: alike, no cache lookup). Skip lazy placeholders or empty
  // src so a 1×1 transparent placeholder never opens a blank lightbox.
  const img = target.closest('img')
  if (img) {
    if (img.dataset.lazy || !img.getAttribute('src')) return
    e.preventDefault()
    lightboxSrc.value = img.src
    lightboxVisible.value = true
    return
  }
  // (3) Links.
  const a = target.closest('a')
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
    // wailsjs types BrowserOpenURL as void but it returns a promise at runtime.
    Promise.resolve(BrowserOpenURL(href)).catch(() => {})
    return
  }
  e.preventDefault()
  if (/\.md(?:ark)?\b/i.test(href) && filePath.value) {
    // Relative links may carry a #fragment and URL-encoded chars; strip the
    // fragment and decode so `子目录/文件%20名.md#节` resolves on disk.
    const raw = href.split('#')[0]
    let rel = raw
    try { rel = decodeURIComponent(raw) } catch { /* keep raw */ }
    const base = filePath.value.replace(/[\\/][^\\/]*$/, '')
    const abs = /^[a-zA-Z]:[\\/]/.test(rel)
      ? rel
      : base + '\\' + rel.replace(/\//g, '\\')
    requestSwitch(() => { void loadPath(abs) })
  }
}

function closeLightbox() {
  lightboxVisible.value = false
  lightboxSrc.value = ''
}

// Render markdown -> HTML, preserving preview scroll across re-renders,
// then load local images via Go (with a cache so typing stays cheap).
function render() {
  const el = previewEl.value
  const st = el ? el.scrollTop : 0
  try {
    previewHtml.value = renderMarkdown(source.value)
    outline.value = extractOutline(source.value)
    computeStats(source.value)
  } catch (e: unknown) {
    // Keep the previous preview intact so the user doesn't lose context; just
    // surface a short, non-fatal error in the status bar.
    status.value = '渲染出错: ' + (e instanceof Error ? e.message : String(e))
    return
  }
  nextTick(() => {
    if (el) el.scrollTop = st
    // Order matters: mermaid replaces <pre> blocks, so it MUST run (awaited)
    // before copy buttons are injected, and both before images resolve.
    void (async () => {
      await renderMermaidDiagrams()
      injectCopyButtons()
      resolveImages()
    })()
    if (findVisible.value && findText.value) {
      applyFindHighlights()
      const marks = previewEl.value?.querySelectorAll('mark.find-hit')
      marks?.forEach((m, i) => m.classList.toggle('find-current', i === matchIndex.value))
    }
  })
}

// ---- mermaid (todo 5) ----
// Diagram source lives in fenced code blocks with the language-mermaid class
// (markdown.ts escapes them as plain code — that stays the integration marker).
// Rendering is on-demand: mermaid is dynamically imported only when a block is
// actually present, and every injected SVG is DOMPurify-sanitized first because
// diagram text is untrusted input.
type MermaidApi = {
  initialize: (opts: Record<string, unknown>) => void
  render: (id: string, code: string) => Promise<{ svg: string }>
}
let mermaidApi: MermaidApi | null = null
let mermaidReady = false // initialize() already ran for the current theme

async function renderMermaidDiagrams() {
  const root = previewEl.value
  if (!root) return
  const blocks = root.querySelectorAll<HTMLElement>('pre > code.language-mermaid')
  if (!blocks.length) return // early return BEFORE the dynamic import: zero load cost
  try {
    if (!mermaidApi) {
      mermaidApi = ((await import('mermaid')) as { default: MermaidApi }).default
    }
    if (!mermaidReady) {
      mermaidApi.initialize({
        startOnLoad: false,
        theme: theme.value === 'dark' ? 'dark' : 'default',
        fontFamily: 'inherit',
      })
      mermaidReady = true
    }
    const DOMPurify = (await import('dompurify')).default
    for (let i = 0; i < blocks.length; i++) {
      const code = blocks[i]
      const pre = code.parentElement as HTMLPreElement
      if (!pre || pre.dataset.mmdDone === '1') continue
      try {
        const src = code.textContent || ''
        // Timestamped id prevents cross-re-render id collisions.
        const { svg } = await mermaidApi.render('mmd-' + Date.now() + '-' + i, src)
        // Diagram text is untrusted: sanitize with the SVG profile before injecting.
        const safe = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true } })
        const div = document.createElement('div')
        div.className = 'md-mermaid'
        // The diagram source is stored (encoded) for theme re-renders — after
        // replacement the original <pre> is gone.
        div.dataset.mmdSrc = encodeURIComponent(src)
        div.dataset.mmdDone = '1'
        div.innerHTML = safe
        pre.replaceWith(div)
      } catch {
        // Single-block failure keeps the original escaped <pre>.
      }
    }
  } catch {
    // Import/initialize failure: leave all blocks as plain code.
  }
}

// Theme switch: re-initialize with the new theme and re-render every existing
// diagram from its stored source (ORACLE-M4).
watch(theme, async (v) => {
  const root = previewEl.value
  if (!root) return
  const containers = root.querySelectorAll<HTMLElement>('.md-mermaid[data-mmd-src]')
  if (!containers.length) return
  try {
    if (!mermaidApi) {
      mermaidApi = ((await import('mermaid')) as { default: MermaidApi }).default
    }
    mermaidApi.initialize({
      startOnLoad: false,
      theme: v === 'dark' ? 'dark' : 'default',
      fontFamily: 'inherit',
    })
    mermaidReady = true
    const DOMPurify = (await import('dompurify')).default
    containers.forEach((div, i) => {
      const src = decodeURIComponent(div.dataset.mmdSrc || '')
      mermaidApi!
        .render('mmd-' + Date.now() + '-t' + i, src)
        .then(({ svg }) => {
          div.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true } })
        })
        .catch(() => {
          /* keep previous svg on failure */
        })
    })
  } catch {
    /* ignore */
  }
})

// ---- copy-code buttons (todo 6) ----
// Injected AFTER mermaid replacement so a button never lands inside a <pre>
// that is about to be swapped for a diagram (ORACLE interaction review).
function injectCopyButtons() {
  const root = previewEl.value
  if (!root) return
  // Double filter: skip diagram containers and any pre still holding a
  // mermaid code block (covers the render-failed-keep-pre fallback).
  const pres = root.querySelectorAll<HTMLElement>(
    'pre:not(.md-mermaid):not(:has(> code.language-mermaid))',
  )
  for (const pre of Array.from(pres)) {
    if (pre.querySelector('button.copy-btn')) continue // idempotent
    const btn = document.createElement('button')
    btn.className = 'copy-btn'
    btn.textContent = '复制'
    btn.type = 'button'
    pre.appendChild(btn)
  }
}

function computeStats(s: string) {
  const cjkRe = /[一-鿿ぁ-ヿ가-힯]/g
  const cjk = (s.match(cjkRe) || []).length
  const words = cjk + (s.replace(cjkRe, ' ').match(/[A-Za-z0-9_]+/g) || []).length
  stats.value = { words, chars: s.length }
}

// Local images load through Go (LoadImageForSrc → base64), so they are lazy:
// every render registers viewport-below images with an IntersectionObserver,
// swaps in a fixed 1×1 transparent placeholder, and hydrates on intersect.
// Remote / data / blob URLs load themselves and are left alone.
const LAZY_PLACEHOLDER =
  'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
let imageObserver: IntersectionObserver | null = null
let lazyImageSeq = 0

function resolveImages() {
  const root = previewEl.value
  if (!root) return
  // v-html rebuilds the preview nodes on re-render, so the previous observer's
  // targets would be dead nodes — disconnect and re-register every render
  // (METIS#18).
  imageObserver?.disconnect()
  if (!imageObserver) {
    imageObserver = new IntersectionObserver(onImageIntersect, { rootMargin: '200px' })
  }
  const pending: HTMLImageElement[] = []
  for (const img of Array.from(root.querySelectorAll<HTMLImageElement>('img'))) {
    // Placeholder image from an earlier pass on the SAME dom (Vue keeps nodes
    // when previewHtml is unchanged): re-observe, nothing else to do.
    if (img.dataset.lazy && img.dataset.lazySrc) {
      if (img.getAttribute('src') === LAZY_PLACEHOLDER) pending.push(img)
      continue
    }
    const src = img.getAttribute('src') || ''
    // Leave remote / data / blob URLs alone.
    if (!src || /^(https?:|data:|blob:)/i.test(src)) continue
    // The cache also holds '' for known-failed loads, so a missing image file
    // isn't re-read from disk on every keystroke's re-render.
    const cached = imageCache.get(filePath.value + '|' + src)
    if (cached !== undefined) {
      if (cached) img.src = cached
      continue
    }
    pending.push(img)
  }
  // Register (or re-observe — disconnect() above dropped every previous
  // registration) all viewport-below images. The re-observe case matters when
  // Vue kept the same DOM across a re-render: without it those placeholders
  // would never hydrate. Already-registered images keep their id/lazySrc and
  // are only re-observed.
  for (const img of pending) {
    if (!img.dataset.lazy) {
      lazyImageSeq++
      img.dataset.lazy = String(lazyImageSeq)
      // The original relative path survives the placeholder swap here.
      img.dataset.lazySrc = img.getAttribute('src') || ''
      img.src = LAZY_PLACEHOLDER
    }
    imageObserver.observe(img)
  }
}

function onImageIntersect(entries: IntersectionObserverEntry[]) {
  for (const entry of entries) {
    const observed = entry.target as HTMLImageElement
    imageObserver?.unobserve(observed)
    if (!entry.isIntersecting) continue
    const id = observed.dataset.lazy
    if (!id) continue
    // Re-query the CURRENT dom instead of trusting the observed node: a
    // re-render may have replaced it with a fresh node (v-html rebuilds).
    const img = previewEl.value?.querySelector<HTMLImageElement>(`img[data-lazy="${id}"]`)
    if (!img) continue // stale registration; the next render re-registers it
    void hydrateImage(img)
  }
}

async function hydrateImage(img: HTMLImageElement) {
  const src = img.dataset.lazySrc || ''
  if (!src) return
  const key = filePath.value + '|' + src
  // Already cached (e.g. loaded for a sibling node): skip the bridge entirely.
  if (imageCache.get(key) !== undefined) {
    applyImageResult(img, key, src)
    return
  }
  // Same-src images hydrate concurrently; dedupe the BRIDGE CALL but not the
  // node fill-in: waiters wait for the shared fetch, then every caller fills
  // its own node. (A plain early-return here left the 2nd..nth same-src image
  // stuck on the 1×1 placeholder forever — the visible "images don't show"
  // regression.)
  let fetchPromise: Promise<void>
  if (imageInFlight.has(key)) {
    fetchPromise = imageInFlight.get(key)!
  } else {
    fetchPromise = (async () => {
      try {
        const r = await LoadImageForSrc(src, filePath.value, imageRoot.value || '')
        if (r.b64) {
          imageCache.set(key, `data:${r.mime};base64,${r.b64}`)
        } else {
          imageCache.set(key, '')
        }
      } catch {
        imageCache.set(key, '')
      }
    })()
    imageInFlight.set(key, fetchPromise)
  }
  try {
    await fetchPromise
  } finally {
    // Only the caller that started the fetch removes the map entry; waiters
    // must not clear it while the fetch is still delivering.
    if (imageInFlight.get(key) === fetchPromise) imageInFlight.delete(key)
  }
  const url = imageCache.get(key) || ''
  if (!url) return // known-failed load; keep the placeholder
  applyImageResult(img, key, src)
}

// Fill the hydrated data URL into the registered node, tolerating a re-render
// that replaced the awaited node (re-query by registration id; a detached
// assignment is lost, but the cache hit restores it on the next render).
function applyImageResult(img: HTMLImageElement, key: string, src: string) {
  const url = imageCache.get(key) || ''
  if (!url) return
  const cur = previewEl.value?.querySelector<HTMLImageElement>(
    `img[data-lazy="${img.dataset.lazy}"]`,
  )
  if (cur && cur.dataset.lazySrc === src) {
    delete cur.dataset.lazy
    delete cur.dataset.lazySrc
    cur.src = url
  }
}

// Scroll the preview so the given 1-indexed source line's block is at top.
function scrollPreviewToLine(line: number) {
  const pv = previewEl.value
  if (!pv) return
  updateActiveOutline(line)
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

// Highlight the outline entry that contains the given 1-indexed source line,
// so the outline doubles as a reading-position indicator.
const activeOutlineLine = ref(0)
function updateActiveOutline(line: number) {
  let cur = 0
  for (const o of outline.value) {
    if (o.line <= line) cur = o.line
    else break
  }
  if (cur !== activeOutlineLine.value) activeOutlineLine.value = cur
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
  // Progress bar first, BEFORE any guard (METIS#4): in preview-only mode the
  // split-sync guards below would return early and the bar would freeze.
  const pvProgress = previewEl.value
  if (pvProgress) {
    const max = pvProgress.scrollHeight - pvProgress.clientHeight
    progressPct.value = max > 0 ? Math.min(100, (pvProgress.scrollTop / max) * 100) : 0
  }
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
  updateActiveOutline(line)
  scrollLockUntil = Date.now() + 60
  const pos = view.state.doc.line(Math.min(line, view.state.doc.lines)).from
  view.dispatch({ effects: EditorView.scrollIntoView(pos, { y: 'start', yMargin: 0 }) })
}

// ---- outline collapse (todo 7) ----
// Folding is BY LEVEL (intentional product decision, plan sanity-check ②):
// collapsing level N hides the sub-trees of ALL headings at that level — the
// outline is a global level tree, not a per-node tree. State is session-only.
const visibleOutline = computed<{ item: OutlineItem; idx: number }[]>(() => {
  const all = outline.value
  const out: { item: OutlineItem; idx: number }[] = []
  if (!collapsedLevels.value.size) {
    for (let i = 0; i < all.length; i++) out.push({ item: all[i], idx: i })
    return out
  }
  // `stack` holds the ancestor levels of the current item (strictly
  // increasing). An item is hidden when ANY ancestor level is collapsed.
  const stack: number[] = []
  for (let i = 0; i < all.length; i++) {
    const lvl = all[i].level
    while (stack.length && stack[stack.length - 1] >= lvl) stack.pop()
    const hidden = stack.some((l) => collapsedLevels.value.has(l))
    if (!hidden) out.push({ item: all[i], idx: i })
    stack.push(lvl)
  }
  return out
})

function outlineHasChildren(idx: number): boolean {
  const all = outline.value
  const lvl = all[idx]?.level
  if (lvl === undefined) return false
  for (let i = idx + 1; i < all.length; i++) {
    if (all[i].level > lvl) return true
    if (all[i].level <= lvl) return false
  }
  return false
}

// Arrow click: toggle this level's fold WITHOUT triggering gotoOutline.
function toggleOutlineLevel(level: number, e: MouseEvent) {
  e.stopPropagation()
  const next = new Set(collapsedLevels.value)
  if (next.has(level)) next.delete(level)
  else next.add(level)
  collapsedLevels.value = next
}

function gotoOutline(o: OutlineItem) {
  // If any ancestor level of this item is collapsed, expand the chain first
  // (todo 7) so the jump target is visible in the outline.
  const idx = outline.value.indexOf(o)
  if (idx >= 0 && collapsedLevels.value.size) {
    const toExpand = new Set(collapsedLevels.value)
    for (let i = 0; i < idx; i++) {
      const lvl = outline.value[i].level
      if (lvl < o.level) toExpand.delete(lvl)
    }
    if (toExpand.size !== collapsedLevels.value.size) collapsedLevels.value = toExpand
  }
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
  else scheduleDraftSave() // refresh snapshot + re-arm idle flush on every edit
  if (renderTimer) clearTimeout(renderTimer)
  renderTimer = setTimeout(render, 200)
})

function onKeydown(e: KeyboardEvent) {
  const k = e.key.toLowerCase()
  if (e.ctrlKey && !e.altKey) {
    if (k === 's' && e.shiftKey) {
      e.preventDefault()
      saveAs()
      return
    }
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
    if (lightboxVisible.value) {
      e.preventDefault()
      closeLightbox()
    } else if (findVisible.value) {
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
  // A second launch of the exe (another double-clicked file) is routed here
  // by the single-instance lock in main.go.
  EventsOn('mdview:open-path', (p: string) => { if (p) requestSwitch(() => { void loadPath(p) }) })
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
  // A file passed on the command line (file association / "打开方式") wins
  // over the last-session restore — opening file A must never show file B.
  const startupFile = await GetStartupFile().catch(() => '')
  if (startupFile) {
    if (!(await loadPath(startupFile))) {
      status.value = `无法打开：${baseName(startupFile)}`
    }
    // Startup path A: file-association launch returns early here, so the
    // update check + draft recovery must be invoked BEFORE the return
    // (ORACLE-M1) — never only at the end of onMounted.
    void postStartup()
    return
  }
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
  // Startup path B: normal launch (restored recents or empty state).
  void postStartup()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  imageObserver?.disconnect()
  imageObserver = null
  if (editorView) {
    editorView.scrollDOM.removeEventListener('scroll', onEditorScroll)
    editorView.destroy()
    editorView = null
  }
})
</script>

<template>
  <div class="app" :class="{ dark: theme === 'dark' }">
    <div v-if="updateTipVisible" class="update-tip" @click="openUpdateUrl">
      <span>{{ updateTipText }}</span>
      <button class="update-tip-close" title="关闭" @click.stop="dismissUpdateTip">✕</button>
    </div>
    <div class="toolbar">
      <button @click="open">打开</button>
      <button @click="save">保存</button>
      <button @click="saveAs">另存为</button>
      <span class="seg">
        <button :class="{ active: viewMode === 'edit' }" @click="viewMode = 'edit'">编辑</button>
        <button :class="{ active: viewMode === 'split' }" @click="viewMode = 'split'">分屏</button>
        <button :class="{ active: viewMode === 'preview' }" @click="viewMode = 'preview'">预览</button>
      </span>
      <button :class="{ active: outlineVisible }" @click="outlineVisible = !outlineVisible">大纲</button>
      <button @click="theme = theme === 'dark' ? 'light' : 'dark'">
        {{ theme === 'dark' ? '亮色' : '暗色' }}
      </button>
      <button :disabled="checkingUpdate" @click="manualCheckUpdate">
        {{ checkingUpdate ? '检查中…' : '检查更新' }}
      </button>
      <select v-if="recents.length" class="recents" @change="onRecentChange">
        <option disabled selected>最近文件</option>
        <option v-for="p in recents" :key="p" :value="p">{{ baseName(p) }}</option>
        <option value="__clear__">清空记录</option>
      </select>
      <span class="enc">{{ fileEnc }}</span>
      <span class="path"><span v-if="dirty" class="dirty-dot">● </span>{{ filePath }}</span>
      <span class="stats">{{ stats.words }} 字 · {{ stats.chars }} 字符</span>
      <span class="status">{{ status }}</span>
      <!-- Reading progress: hidden in edit mode (no preview scrolling there). -->
      <div v-if="viewMode !== 'edit'" class="progress-track">
        <div class="progress-bar" :style="{ width: progressPct + '%' }"></div>
      </div>
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
          v-for="vo in visibleOutline"
          :key="vo.idx"
          class="outline-item"
          :class="['lv' + vo.item.level, { active: vo.item.line === activeOutlineLine }]"
          :style="{ paddingLeft: (vo.item.level - 1) * 14 + 8 + 'px' }"
          @click="gotoOutline(vo.item)"
        ><span
            v-if="outlineHasChildren(vo.idx)"
            class="outline-arrow"
            :class="{ collapsed: collapsedLevels.has(vo.item.level) }"
            title="折叠/展开该层级"
            @click="toggleOutlineLevel(vo.item.level, $event)"
          >▾</span>{{ vo.item.text }}</div>
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
        {{ findText ? (matchPositions.length ? (findCapped ? '500+' : (matchIndex + 1) + '/' + matchPositions.length) : '无结果') : '' }}
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
    <div v-if="switchConfirmVisible" class="modal-mask">
      <div class="modal">
        <div class="modal-title">未保存的修改</div>
        <div class="modal-body">当前文档有未保存的修改，打开其他文件将放弃这些修改。</div>
        <div class="modal-actions">
          <button @click="confirmSwitch">继续打开</button>
          <button @click="cancelSwitch">取消</button>
        </div>
      </div>
    </div>
    <div v-if="draftRecoverVisible" class="modal-mask">
      <div class="modal">
        <div class="modal-title">未保存的草稿</div>
        <div class="modal-body">检测到未保存的草稿：{{ draftRecoverInfo?.name }}，是否恢复？</div>
        <div class="modal-actions">
          <button @click="confirmRecoverDraft">恢复</button>
          <button @click="rejectRecoverDraft">暂不</button>
        </div>
      </div>
    </div>
    <div v-if="lightboxVisible" class="img-lightbox" @click="closeLightbox">
      <img class="img-lightbox-img" :src="lightboxSrc" alt="" @click.stop />
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
.toolbar { position: relative; display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--panel); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.toolbar button { padding: 4px 12px; cursor: pointer; background: var(--input-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 3px; }
.toolbar button.active { background: var(--accent); border-color: var(--accent-border); }
.toolbar button:disabled { opacity: .6; cursor: default; }
/* Reading progress: 2px bar pinned to the toolbar's bottom edge (todo 7). */
.progress-track { position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: transparent; pointer-events: none; }
.progress-bar { height: 100%; width: 0; background: var(--accent); }
/* Update tip bar (todo 4): reuse toolbar/modal variables, never persisted. */
.update-tip { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 5px 12px; background: var(--accent); border-bottom: 1px solid var(--accent-border); font-size: 13px; cursor: pointer; user-select: none; }
.update-tip-close { padding: 0 6px; cursor: pointer; background: transparent; color: inherit; border: none; font-size: 12px; }
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
.outline-item:hover, .outline-item.active { background: var(--hover); }
.outline-item.lv1 { font-weight: 600; }
/* Expand/collapse arrow: its own click target, never triggers gotoOutline. */
.outline-arrow { display: inline-block; width: 14px; text-align: center; color: var(--faint); cursor: pointer; transition: transform .12s ease; }
.outline-arrow.collapsed { transform: rotate(-90deg); }
.outline-empty { padding: 6px 10px; color: var(--faint); font-size: 12px; }
.findbar { position: absolute; top: 44px; right: 16px; display: flex; align-items: center; gap: 6px; padding: 6px 8px; background: var(--panel); border: 1px solid var(--border); border-radius: 6px; box-shadow: 0 4px 16px rgba(0,0,0,.18); z-index: 60; }
.findbar input { width: 180px; padding: 3px 8px; border: 1px solid var(--border); border-radius: 3px; background: var(--input-bg); color: var(--fg); outline: none; }
.findbar .find-count { font-size: 12px; color: var(--muted); min-width: 44px; text-align: center; }
.findbar button { padding: 2px 8px; cursor: pointer; background: var(--input-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 3px; }
.preview h1, .preview h2, .preview h3 { line-height: 1.3; }
.preview img { max-width: 100%; height: auto; }
.preview pre { background: var(--code-bg); padding: 12px; overflow: auto; border-radius: 4px; }
.preview pre { position: relative; }
/* Copy-code button (todo 6): DOM-injected after mermaid replacement, so it
   never lands inside a diagram container. */
.preview .copy-btn { position: absolute; top: 6px; right: 6px; padding: 2px 8px; font-size: 12px; cursor: pointer; background: var(--input-bg); color: var(--muted); border: 1px solid var(--border); border-radius: 3px; opacity: 0; }
.preview pre:hover .copy-btn { opacity: 1; }
.preview .copy-btn:hover { color: var(--fg); border-color: var(--accent-border); }
/* Mermaid diagram container (todo 5): sanitized SVG injected here. */
.preview .md-mermaid { background: var(--code-bg); padding: 12px; border-radius: 4px; overflow-x: auto; text-align: center; }
.preview .md-mermaid svg { max-width: 100%; height: auto; }
.preview code { font-family: "Cascadia Code", Consolas, monospace; font-size: 13px; }
.preview table { border-collapse: collapse; }
.preview th, .preview td { border: 1px solid var(--border); padding: 6px 10px; }
.preview blockquote { border-left: 4px solid var(--border); margin: 0; padding-left: 14px; color: var(--muted); }
/* GitHub-style callouts ([!NOTE] etc.). markdown.ts strips the marker line and
   tags the blockquote with .md-callout--<kind>; the label row is CSS-only. */
.preview .md-callout {
  border-left: 4px solid var(--co);
  background: var(--cb);
  margin: 12px 0;
  padding: 10px 14px 10px 12px;
  border-radius: 4px;
  color: var(--fg);
}
.preview .md-callout > :first-child { margin-top: 0; }
.preview .md-callout > :last-child { margin-bottom: 0; }
.preview .md-callout::before {
  content: var(--label);
  display: block;
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: var(--co);
  margin-bottom: 4px;
}
.preview .md-callout--note      { --co: #0969da; --cb: rgba(9, 105, 218, 0.08);  --label: "Note"; }
.preview .md-callout--tip       { --co: #1a7f37; --cb: rgba(26, 127, 55, 0.08);  --label: "Tip"; }
.preview .md-callout--important { --co: #8250df; --cb: rgba(130, 80, 223, 0.08); --label: "Important"; }
.preview .md-callout--warning   { --co: #9a6700; --cb: rgba(154, 103, 0, 0.10);  --label: "Warning"; }
.preview .md-callout--caution   { --co: #cf222e; --cb: rgba(207, 34, 46, 0.08);  --label: "Caution"; }
.app.dark .preview .md-callout--note      { --co: #4493f8; --cb: rgba(68, 147, 248, 0.12); }
.app.dark .preview .md-callout--tip       { --co: #3fb950; --cb: rgba(63, 185, 80, 0.12); }
.app.dark .preview .md-callout--important { --co: #ab7df8; --cb: rgba(171, 125, 248, 0.12); }
.app.dark .preview .md-callout--warning   { --co: #d29922; --cb: rgba(210, 153, 34, 0.14); }
.app.dark .preview .md-callout--caution   { --co: #f85149; --cb: rgba(248, 81, 73, 0.12); }
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
/* Image lightbox (todo 6): above findbar (60) and modals (100). */
.img-lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.75); display: flex; align-items: center; justify-content: center; z-index: 1000; cursor: zoom-out; }
.img-lightbox-img { max-width: 92vw; max-height: 92vh; border-radius: 4px; box-shadow: 0 8px 40px rgba(0,0,0,.5); cursor: default; }
</style>
