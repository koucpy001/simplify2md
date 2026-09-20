// IME height -> CSS variable helper tests (plan todo 17).
//
// Pure function boundaries: 0 / negative / NaN / missing / fractional /
// absurdly large keyboard heights.
//
// Run: npx tsx test-ime-helper.ts

import assert from 'node:assert/strict'
import { imeInsetPx, IME_HEIGHT_CSS_VAR, MAX_IME_HEIGHT_PX } from './src/lib/bridge-events'

// ---- normal heights ----------------------------------------------------------
assert.equal(imeInsetPx(120), '120px', 'typical keyboard height')
assert.equal(imeInsetPx(1), '1px', 'minimum positive height')
assert.equal(imeInsetPx(345.7), '345px', 'fractional height is floored')
console.log('normal heights       -> 120px / 1px / 345px')

// ---- zero / negative / non-finite / missing ----------------------------------
assert.equal(imeInsetPx(0), '0px', 'zero height = keyboard closed')
assert.equal(imeInsetPx(-1), '0px', 'negative height clamps to 0px')
assert.equal(imeInsetPx(-1e9), '0px', 'very negative clamps to 0px')
assert.equal(imeInsetPx(Number.NaN), '0px', 'NaN height -> 0px')
assert.equal(imeInsetPx(Number.POSITIVE_INFINITY), '0px', 'Infinity -> 0px (non-finite)')
assert.equal(imeInsetPx(undefined), '0px', 'missing height -> 0px')
assert.equal(imeInsetPx(null), '0px', 'null height -> 0px')
assert.equal(imeInsetPx('120'), '0px', 'string height is rejected (not a number)')
console.log('boundaries           -> 0 / negative / NaN / Infinity / missing all -> 0px')

// ---- absurdly large ----------------------------------------------------------
assert.equal(imeInsetPx(1e12), `${MAX_IME_HEIGHT_PX}px`, 'absurd height clamps to the ceiling')
assert.equal(imeInsetPx(MAX_IME_HEIGHT_PX + 1), `${MAX_IME_HEIGHT_PX}px`)
assert.equal(imeInsetPx(MAX_IME_HEIGHT_PX), `${MAX_IME_HEIGHT_PX}px`, 'ceiling itself passes through')
console.log(`absurdly large       -> clamped to ${MAX_IME_HEIGHT_PX}px`)

// ---- CSS variable name contract ----------------------------------------------
assert.equal(IME_HEIGHT_CSS_VAR, '--mdview-ime-height')
console.log('ALL IME-HELPER TESTS PASSED')
