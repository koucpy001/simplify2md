// Stitch the four 512px quadrant captures into the 1024 appicon, then verify
// the stitched result visually is the caller's job — this only asserts sizes.
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
    const f = raw[y * (stride + 1)]
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1))
    const cur = Buffer.alloc(stride)
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? cur[i - bpp] : 0
      const b = prev[i]
      const c = i >= bpp ? prev[i - bpp] : 0
      let v = line[i]
      if (f === 1) v += a
      else if (f === 2) v += b
      else if (f === 3) v += (a + b) >> 1
      else if (f === 4) {
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

const dir = 'build/icon-src/'
const tl = decodePNG(readFileSync(dir + 'appicon-quad-tl.png'))
const tr = decodePNG(readFileSync(dir + 'appicon-quad-tr.png'))
const bl = decodePNG(readFileSync(dir + 'appicon-quad-bl.png'))
const br = decodePNG(readFileSync(dir + 'appicon-quad-br.png'))
for (const q of [tl, tr, bl, br]) {
  if (q.w !== 512 || q.h !== 512) throw new Error(`quad size ${q.w}x${q.h} != 512x512`)
}
const W = 1024, H = 1024
const out = Buffer.alloc(W * H * 4)
const put = (q, ox, oy) => {
  for (let y = 0; y < 512; y++) {
    q.data.copy(out, ((oy + y) * W + ox) * 4, y * 512 * 4, (y + 1) * 512 * 4)
  }
}
put(tl, 0, 0)
put(tr, 512, 0)
put(bl, 0, 512)
put(br, 512, 512)

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
const stride = W * 4
const raw = Buffer.alloc((stride + 1) * H)
for (let y = 0; y < H; y++) {
  raw[y * (stride + 1)] = 0
  out.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(W, 0)
ihdr.writeUInt32BE(H, 4)
ihdr[8] = 8
ihdr[9] = 6
writeFileSync(
  dir + 'appicon-stitched.png',
  Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]),
)
console.log('stitched appicon-stitched.png 1024x1024')
