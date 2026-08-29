// Zero-dependency PNG round-corner alpha tool.
// Browser screenshots are opaque, so the icon's rounded corners carry the
// page background. This decodes a PNG, sets alpha by the signed distance to
// the rounded-rect outline (smooth 1px anti-aliased edge), and re-encodes.
// Usage: node round-alpha.mjs <in.png> <out.png> [radiusRatio=0.205]
import { readFileSync, writeFileSync } from 'node:fs'
import { inflateSync, deflateSync } from 'node:zlib'

function decodePNG(buf) {
  let off = 8
  const idat = []
  let w, h, colorType
  while (off < buf.length) {
    const len = buf.readUInt32BE(off)
    const type = buf.toString('ascii', off + 4, off + 8)
    const data = buf.subarray(off + 8, off + 8 + len)
    if (type === 'IHDR') {
      w = data.readUInt32BE(0)
      h = data.readUInt32BE(4)
      colorType = data[9]
      if (data[8] !== 8 || (colorType !== 2 && colorType !== 6))
        throw new Error(`unsupported PNG: depth=${data[8]} color=${colorType}`)
    } else if (type === 'IDAT') idat.push(data)
    off += 12 + len
  }
  const raw = inflateSync(Buffer.concat(idat))
  const bpp = colorType === 6 ? 4 : 3
  const stride = w * bpp
  const out = Buffer.alloc(w * h * 4)
  let prev = Buffer.alloc(stride)
  for (let y = 0; y < h; y++) {
    const filter = raw[y * (stride + 1)]
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1))
    const cur = Buffer.alloc(stride)
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? cur[i - bpp] : 0
      const b = prev[i]
      const c = i >= bpp ? prev[i - bpp] : 0
      let v = line[i]
      if (filter === 1) v += a
      else if (filter === 2) v += b
      else if (filter === 3) v += (a + b) >> 1
      else if (filter === 4) {
        const p = a + b - c
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c)
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c
      }
      cur[i] = v & 0xff
    }
    for (let x = 0; x < w; x++) {
      out[(y * w + x) * 4] = cur[x * bpp]
      out[(y * w + x) * 4 + 1] = cur[x * bpp + 1]
      out[(y * w + x) * 4 + 2] = cur[x * bpp + 2]
      out[(y * w + x) * 4 + 3] = colorType === 6 ? cur[x * bpp + 3] : 255
    }
    prev = cur
  }
  return { w, h, data: out }
}

const crcTable = new Int32Array(256).map((_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c
})
function crc32(buf) {
  let crc = -1
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ crcTable[(crc ^ buf[i]) & 0xff]
  return (crc ^ -1) >>> 0
}
function chunk(type, data) {
  const b = Buffer.alloc(data.length + 12)
  b.writeUInt32BE(data.length, 0)
  b.write(type, 4, 'ascii')
  data.copy(b, 8)
  b.writeUInt32BE(crc32(b.subarray(4, 8 + data.length)), 8 + data.length)
  return b
}
function encodePNG(w, h, rgba) {
  const stride = w * 4
  const raw = Buffer.alloc((stride + 1) * h)
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8
  ihdr[9] = 6
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

const [input, output, radiusArg, bgHex, marginArg] = process.argv.slice(2)
if (!input || !output) throw new Error('usage: node round-alpha.mjs <in> <out> [radiusRatio] [bgHex] [marginRatio]')
const img = decodePNG(readFileSync(input))
const minSide = Math.min(img.w, img.h)
const radius = parseFloat(radiusArg ?? '0.205') * minSide
const margin = parseFloat(marginArg ?? '0') * minSide
const cx = (img.w - 1) / 2
const cy = (img.h - 1) / 2
// Expand the outline by half a pixel so edge pixels are fully opaque —
// a half-transparent outermost ring reads as a faint square frame on
// checkered/dark backgrounds and blurs the corner silhouette.
const hw = img.w / 2 - margin + 0.5 - radius
const hh = img.h / 2 - margin + 0.5 - radius
// The capture background (page color behind the rounded rect). Edge pixels
// stored fg blended with it, so once the geometric alpha is known the true
// foreground is un-blended: F = (C - B*(1-a)) / a. Interior (a=1) untouched.
let bg = null
if (bgHex) {
  const h = bgHex.replace('#', '')
  bg = [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
for (let y = 0; y < img.h; y++) {
  for (let x = 0; x < img.w; x++) {
    const i = (y * img.w + x) * 4
    const px = Math.abs(x - cx) - hw
    const py = Math.abs(y - cy) - hh
    const dx = Math.max(px, 0)
    const dy = Math.max(py, 0)
    const d = Math.sqrt(dx * dx + dy * dy) + Math.min(Math.max(px, py), 0) - radius
    const a = Math.max(0, Math.min(1, 0.5 - d))
    if (bg && a > 0 && a < 1) {
      for (let k = 0; k < 3; k++) {
        img.data[i + k] = Math.max(0, Math.min(255, Math.round((img.data[i + k] - bg[k] * (1 - a)) / a)))
      }
    }
    img.data[i + 3] = Math.round(a * 255)
  }
}
writeFileSync(output, encodePNG(img.w, img.h, img.data))
console.log(`${output}: ${img.w}x${img.h}, radius ${Math.round(radius)}px${bg ? ', bg un-blend ' + bgHex : ''}`)
