// Parity gate for the `@bridge` shim (plan todo 6 / Verification strategy).
//
// The desktop and Android shims MUST expose exactly the same runtime value
// exports: the fixed 20-symbol contract
//   - 17 App functions
//   - EventsOn
//   - BrowserOpenURL
//   - notifyBridgeReady
//
// `export type` declarations erase at build time and are NOT counted; only
// runtime values matter for the bundle (and a type-only divergence is already
// caught by `vue-tsc --noEmit`, which typechecks BOTH files).
//
// Run: npx tsx test-bridge-parity.ts   (deterministic: sorted comparison only)

import assert from 'node:assert/strict'
import * as wailsBridge from './src/lib/bridge.wails'
import * as androidBridge from './src/lib/bridge.android'

const EXPECTED: string[] = [
  'BrowserOpenURL',
  'CheckForUpdate',
  'ClearDraft',
  'ClearRecents',
  'ConfirmExit',
  'EventsOn',
  'GetRecents',
  'GetStartupFile',
  'ListDrafts',
  'LoadDraft',
  'LoadImageForSrc',
  'OpenFile',
  'PickSavePath',
  'ReadFileAt',
  'RemoveRecent',
  'SaveDraft',
  'SaveFile',
  'SetDirty',
  'SetTitle',
  'notifyBridgeReady',
].sort()

function runtimeExports(mod: object): string[] {
  return Object.keys(mod)
    .filter((key) => key !== 'default' && key !== '__esModule')
    .sort()
}

const wails = runtimeExports(wailsBridge)
const android = runtimeExports(androidBridge)

console.log('expected (20): ' + EXPECTED.join(', '))
console.log('wails   (' + wails.length + '): ' + wails.join(', '))
console.log('android (' + android.length + '): ' + android.join(', '))

assert.deepStrictEqual(wails, EXPECTED, 'bridge.wails.ts runtime exports must equal the 20-symbol contract')
assert.deepStrictEqual(android, EXPECTED, 'bridge.android.ts runtime exports must equal the 20-symbol contract')
assert.deepStrictEqual(wails, android, 'both @bridge shims must export the identical runtime set')

for (const [label, mod] of [['wails', wailsBridge], ['android', androidBridge]] as const) {
  for (const key of EXPECTED) {
    const value = (mod as Record<string, unknown>)[key]
    assert.strictEqual(typeof value, 'function', `${label}.${key} must be a runtime function`)
  }
}

console.log('')
console.log('PARITY OK: both @bridge shims export the identical 20-symbol runtime set.')
