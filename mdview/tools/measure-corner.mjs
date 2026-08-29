// Measure the actual silhouette of the top-left corner: for each row, find
// the first pixel whose alpha >= 128, and compare with the ideal SVG arc
// (r=210 centered at 210.5,210.5).
import { readFileSync } from 'node:fs'
import { inflateSync } from 'node:zlib'

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

const img = decodePNG(readFileSync(process.argv[2]))
const r = 210
const cc = 210.5 // corner arc center
for (const y of [0, 5, 20, 40, 60, 80, 100, 150, 210]) {
  let first = -1
  for (let x = 0; x < 500; x++) {
    if (img.data[(y * img.w + x) * 4 + 3] >= 128) { first = x; break }
  }
  const dy = Math.max(cc - y, 0)
  const ideal = dy >= r ? 0 : cc - Math.sqrt(r * r - dy * dy) - (y > cc ? r : 0)
  console.log(`y=${String(y).padStart(3)}  firstOpaque x=${String(first).padStart(3)}  ideal≈${ideal.toFixed(1)}`)
}
// edge midpoint alpha (the faint-frame check)
const midY = Math.floor(img.h / 2)
console.log('edge pixel alpha: (0,mid)=', img.data[(midY * img.w + 0) * 4 + 3], ' (1,mid)=', img.data[(midY * img.w + 1) * 4 + 3])
