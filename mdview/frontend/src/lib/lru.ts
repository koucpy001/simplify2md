// LRU cache for base64 image data with a dual budget: item count AND total
// decoded size. Zero DOM dependency so test-pipeline.ts can import it in plain
// Node. Failed loads are stored as '' and count toward capacity, so a missing
// file is never re-read from disk on every keystroke's re-render — but a cache
// hit on a failed entry is skipped, not retried.

const MAX_ITEMS = 64
const MAX_TOTAL_BYTES = 256 * 1024 * 1024 // 256MB of base64 bytes

export class LruCache {
  // Map iterates in insertion order; re-setting an existing key moves it to
  // the newest position, which gives us get-promotes for free.
  private map = new Map<string, string>()

  constructor(
    private maxItems = MAX_ITEMS,
    private maxBytes = MAX_TOTAL_BYTES,
  ) {}

  get size(): number {
    return this.map.size
  }

  // Total base64 bytes currently held (a data URL is stored without the
  // `data:...;base64,` prefix, so the stored string IS the base64 payload).
  private totalBytes(): number {
    let n = 0
    for (const v of this.map.values()) n += v.length
    return n
  }

  get(key: string): string | undefined {
    const v = this.map.get(key)
    if (v === undefined) return undefined
    // Re-set to move the key to the newest position (get-promotes).
    this.map.delete(key)
    this.map.set(key, v)
    return v
  }

  set(key: string, value: string): void {
    // Overwrite in place: delete first so insertion order reflects recency.
    if (this.map.has(key)) this.map.delete(key)
    this.map.set(key, value)
    this.evict()
  }

  has(key: string): boolean {
    return this.map.has(key)
  }

  clear(): void {
    this.map.clear()
  }

  private evict(): void {
    // Cap item count first (oldest entries are at map start).
    while (this.map.size > this.maxItems) {
      const oldest = this.map.keys().next().value
      if (oldest === undefined) break
      this.map.delete(oldest)
    }
    // Then cap total bytes.
    while (this.totalBytes() > this.maxBytes && this.map.size > 0) {
      const oldest = this.map.keys().next().value
      if (oldest === undefined) break
      this.map.delete(oldest)
    }
  }
}
