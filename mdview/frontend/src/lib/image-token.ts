// Pure mapping from relative-image rejection tokens to user-facing status hints
// (plan todo 13f). The Kotlin resolver rejects with one of these stable tokens;
// the mapping is informational only — it never offers a retry action, because
// recovery is driven by the tree-grant suspend/resume flow (the bridge export
// set is frozen and no retry UI exists).
//
// App.vue's hydrateImage catch maps a rejection message through imageStatusHint;
// test-token-mapping.ts asserts the full token set and the mapping.

export const IMAGE_TOKENS = {
  NOT_IN_TREE: 'image-not-in-tree',
  UNSUPPORTED_PROVIDER: 'image-unsupported-provider',
  TOO_LARGE: 'image-too-large',
  DENIED: 'image-denied',
  TREE_CANCELLED: 'image-tree-cancelled',
} as const

export type ImageToken = (typeof IMAGE_TOKENS)[keyof typeof IMAGE_TOKENS]

/** The stable token carried by a rejection message, or null when none matches. */
export function imageTokenOf(message: unknown): ImageToken | null {
  if (typeof message !== 'string') return null
  for (const token of Object.values(IMAGE_TOKENS)) {
    if (message.includes(token)) return token
  }
  return null
}

/** Chinese status-bar hint for a rejection; '' when the message is not an image token. */
export function imageStatusHint(message: unknown): string {
  switch (imageTokenOf(message)) {
    case IMAGE_TOKENS.NOT_IN_TREE:
      return '图片不在已授权文件夹内'
    case IMAGE_TOKENS.UNSUPPORTED_PROVIDER:
      return '该来源不支持相对图片解析'
    case IMAGE_TOKENS.TOO_LARGE:
      return '图片超过 6MB，已跳过'
    case IMAGE_TOKENS.DENIED:
      return '该目录无法授权访问'
    case IMAGE_TOKENS.TREE_CANCELLED:
      return '已取消文件夹授权，图片暂不显示'
    default:
      return ''
  }
}