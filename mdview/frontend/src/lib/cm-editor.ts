// CodeMirror 6 editor for mdview — lean on purpose.
// Only base extensions: markdown (GFM) syntax highlighting, line numbers,
// soft wrap, history. No autocompletion / block widgets / built-in search
// panel: soloMD's Windows IME work (issue #108) shows custom ViewPlugins are
// what break CJK composition in WebView2; their Windows builds ship this same
// base-highlighting path for vim users.
import { EditorState, Compartment } from '@codemirror/state'
import {
  EditorView,
  keymap,
  lineNumbers,
  highlightActiveLine,
  highlightActiveLineGutter,
  drawSelection,
  dropCursor,
  rectangularSelection,
  crosshairCursor,
  placeholder,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab, isolateHistory } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import {
  syntaxHighlighting,
  defaultHighlightStyle,
  bracketMatching,
  indentOnInput,
} from '@codemirror/language'
import { oneDarkHighlightStyle } from '@codemirror/theme-one-dark'

// Chrome reads the app's CSS variables, so light/dark follows .app.dark
// without a second theme definition.
const mdviewTheme = EditorView.theme({
  '&': {
    height: '100%',
    color: 'var(--fg)',
    backgroundColor: 'var(--bg)',
    fontSize: '14px',
  },
  '.cm-scroller': {
    fontFamily: '"Cascadia Code", Consolas, monospace',
    lineHeight: '1.6',
    overflow: 'auto',
  },
  '.cm-content': { padding: '12px 0', caretColor: 'var(--fg)' },
  '&.cm-focused': { outline: 'none' },
  '.cm-gutters': {
    backgroundColor: 'var(--panel)',
    color: 'var(--faint)',
    border: 'none',
    borderRight: '1px solid var(--border)',
  },
  '.cm-activeLine': { backgroundColor: 'var(--hover)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--hover)' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--fg)' },
  '.cm-selectionBackground': { backgroundColor: 'var(--accent) !important' },
  '.cm-placeholder': { color: 'var(--faint)' },
})

const hlCompartment = new Compartment()

function highlightForTheme(dark: boolean) {
  return syntaxHighlighting(dark ? oneDarkHighlightStyle : defaultHighlightStyle)
}

export function createMarkdownEditor(
  host: HTMLElement,
  doc: string,
  dark: boolean,
  onDocChanged: (text: string) => void,
): EditorView {
  return new EditorView({
    state: EditorState.create({
      doc,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        history(),
        drawSelection(),
        dropCursor(),
        rectangularSelection(),
        crosshairCursor(),
        indentOnInput(),
        bracketMatching(),
        EditorView.lineWrapping,
        placeholder('打开或输入 Markdown...'),
        mdviewTheme,
        hlCompartment.of(highlightForTheme(dark)),
        markdown(),
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        EditorView.updateListener.of((u) => {
          // Skip while an IME composition is running — the committed text
          // arrives in the final non-composing docChanged at compositionend
          // (same pattern soloMD uses).
          if (u.docChanged && !u.view.composing) onDocChanged(u.state.doc.toString())
        }),
      ],
    }),
    parent: host,
  })
}

export function setEditorHighlight(view: EditorView, dark: boolean) {
  view.dispatch({ effects: hlCompartment.reconfigure(highlightForTheme(dark)) })
}

export function replaceEditorDoc(view: EditorView, text: string) {
  if (view.state.doc.toString() === text) return
  // Replace the whole document as an isolated history event so a subsequent
  // Ctrl+Z restores nothing from the previous file (otherwise opening a new file
  // then undoing would resurrect the old file's content and risk writing it to
  // the new path).
  view.dispatch({
    changes: { from: 0, to: view.state.doc.length, insert: text },
    annotations: isolateHistory.of('full'),
  })
}
