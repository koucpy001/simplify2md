import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

// KaTeX's CSS ships every font in woff2 + woff + ttf. WebView2 is Chromium
// and renders woff2, so the other two formats are dead weight in the single
// binary — strip them from the CSS and drop the emitted assets.
function trimKatexFonts() {
  const strip = (css: string): string =>
    css
      .replace(/,\s*url\([^)]*\.woff\)\s*format\(["']?woff["']?\)/g, '')
      .replace(/,\s*url\([^)]*\.ttf\)\s*format\(["']?truetype["']?\)/g, '')
  return {
    name: 'trim-katex-fonts',
    generateBundle(_opts: unknown, bundle: Record<string, {type: string; fileName: string; source?: unknown}>) {
      for (const file of Object.values(bundle)) {
        if (file.type === 'asset' && file.fileName.endsWith('.css')) {
          file.source = strip(String(file.source))
        }
      }
      for (const name of Object.keys(bundle)) {
        if (/KaTeX_[\w-]+\.(woff|ttf)$/.test(name)) {
          delete bundle[name]
        }
      }
    },
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), trimKatexFonts()],
})
