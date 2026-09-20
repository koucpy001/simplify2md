// Explicit relative `.md` link resolution (plan todo 20c).
//
// Desktop keeps the historical behaviour: join the link against the document's
// directory (paths.ts dirOf) and open the resulting filesystem path.
//
// Android addresses documents by SAF URI, so directory concatenation is
// meaningless there. Instead the child document URI is constructed the SAME way
// todo 13 builds relative-image URIs: `DocumentsContract.buildDocumentUriUsingTree`
// shape — `content://<authority>/tree/<treeDocId>/document/<childDocId>` — where
// `<childDocId>` is the current documentId's parent directory joined with the
// decoded relative segments. This only works when the CURRENT document URI is
// itself tree-formed (i.e. the app is operating inside a granted tree); a plain
// `content://…/document/<id>` URI (the normal ACTION_OPEN_DOCUMENT result)
// carries no tree, and the frontend cannot learn the granted tree through the
// frozen 20-symbol bridge — so the resolver answers with a status-bar prompt
// guiding the user to authorize the folder instead of silently doing nothing.
//
// Security: a hostile link (`../../`, encoded escapes, absolute paths) can never
// leave the granted tree — the resolved childDocId must remain under treeDocId
// (segment-wise), and the only sink is ReadFileAt on a SAF URI, which the
// provider enforces against the user's own grants. The WebView never navigates.

export type RelativeLinkResolution =
  | { action: 'open'; target: string }
  | { action: 'prompt'; message: string }

const AUTHORIZATION_PROMPT =
  '相对链接需要先授权文档所在文件夹：滚动到文档内的相对图片时会弹出文件夹授权，授权后即可打开相对链接'

/** Percent-decodes a URI segment; returns the raw value when malformed. */
function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

/** Percent-encodes a documentId for use inside a SAF URI path segment. */
function encodeDocumentId(documentId: string): string {
  return encodeURIComponent(documentId)
}

/** Join relative segments against a base directory, rejecting tree escapes. */
function joinRelative(baseSegments: string[], relative: string): string[] | null {
  const segments = [...baseSegments]
  for (const raw of relative.split('/')) {
    if (raw === '' || raw === '.') continue
    if (raw === '..') {
      if (segments.length === 0) return null // escapes above the tree root
      segments.pop()
      continue
    }
    segments.push(raw)
  }
  return segments
}

export function isContentUri(id: string): boolean {
  return id.startsWith('content://')
}

/**
 * Extract `{ authority, treeDocId?, documentId }` from a SAF document URI.
 * Both `content://a/document/<id>` and `content://a/tree/<t>/document/<id>`
 * forms are understood; the documentId is the percent-decoded last
 * `/document/` segment.
 */
export function parseDocumentUri(uri: string): {
  authority: string
  treeDocId: string | null
  documentId: string | null
} | null {
  if (!uri.startsWith('content://')) return null
  const rest = uri.slice('content://'.length)
  const slash = rest.indexOf('/')
  if (slash <= 0) return null
  const authority = rest.slice(0, slash)
  const path = rest.slice(slash + 1)
  const docMatch = /(?:^|\/)document\/([^/]+)$/.exec(path)
  if (!docMatch) return { authority, treeDocId: null, documentId: null }
  const before = path.slice(0, docMatch.index)
  const documentId = decodeSegment(docMatch[1])
  const treeMatch = /(?:^|\/)tree\/([^/]+)(?:\/|$)/.exec(before)
  const treeDocId = treeMatch ? decodeSegment(treeMatch[1]) : null
  return { authority, treeDocId, documentId }
}

/**
 * Resolve a stripped, decoded relative `.md` target against the current
 * document id. Desktop (non-content) ids keep the directory-join behaviour;
 * Android content URIs go through the tree-formed child-URI construction, or
 * answer with a guidance prompt when no tree context is available.
 */
export function resolveRelativeMdLink(relative: string, filePath: string): RelativeLinkResolution {
  if (!filePath.startsWith('content://')) {
    // Desktop: unchanged directory concatenation (Windows paths included).
    const trimmed = filePath.replace(/[\\/]+$/, '')
    const idx = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    const base = idx <= 0 ? '' : trimmed.slice(0, idx)
    const isAbsolute = /^[a-zA-Z]:[\\/]/.test(relative) || relative.startsWith('/')
    const target = isAbsolute ? relative : base ? base + '/' + relative : relative
    return { action: 'open', target }
  }

  const parsed = parseDocumentUri(filePath)
  if (!parsed || !parsed.documentId) {
    return { action: 'prompt', message: AUTHORIZATION_PROMPT }
  }
  // Absolute filesystem paths and file: URLs are never resolvable through SAF.
  if (/^(?:file|[a-zA-Z]):/.test(relative) || relative.startsWith('/') || relative.startsWith('\\\\')) {
    return { action: 'prompt', message: '该链接指向文件系统路径，Android 上无法通过 SAF 打开' }
  }
  const docSegments = parsed.documentId.split('/')
  if (docSegments.length < 2) {
    // Root-level or opaque documentId: no parent directory to resolve against.
    return { action: 'prompt', message: AUTHORIZATION_PROMPT }
  }
  const childSegments = joinRelative(docSegments.slice(0, -1), relative)
  if (!childSegments) {
    return { action: 'prompt', message: '该链接逃逸出所在文件夹，无法打开' }
  }
  const childDocId = childSegments.join('/')
  // Tree-formed current URI: same construction as todo 13's buildChildUri.
  if (parsed.treeDocId) {
    if (childDocId !== parsed.treeDocId && !childDocId.startsWith(parsed.treeDocId + '/')) {
      return { action: 'prompt', message: '该链接不在已授权的文件夹内，无法打开' }
    }
    const target =
      `content://${parsed.authority}/tree/${encodeDocumentId(parsed.treeDocId)}` +
      `/document/${encodeDocumentId(childDocId)}`
    return { action: 'open', target }
  }
  // Plain document URI: the granted tree (if any) is not visible to the
  // frontend through the frozen bridge — guide the user instead of guessing.
  return { action: 'prompt', message: AUTHORIZATION_PROMPT }
}
