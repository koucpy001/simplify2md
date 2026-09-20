import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath} from 'node:url'

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
export default defineConfig(({mode}) => ({
  plugins: [vue(), trimKatexFonts()],
  resolve: {
    // The platform seam: --mode android bundles the native WebView bridge
    // (which defines __bridgeCall, asserted by android-ci.yml), every other
    // mode bundles the Wails desktop binding.
    alias: {
      '@bridge': fileURLToPath(
        new URL(
          mode === 'android' ? './src/lib/bridge.android.ts' : './src/lib/bridge.wails.ts',
          import.meta.url,
        ),
      ),
    },
  },
}))
