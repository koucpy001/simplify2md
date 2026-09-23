// Mobile-UI decision policy gate (plan android-gui-mobile, deliverable 2).
//
// Covers the pure policies extracted from App.vue:
//   - initialViewMode / coerceViewModeForPhone (D1 + D4)
//   - initialTheme (D5)
//
// Run: npx tsx test-mobile-ui.ts

import assert from 'node:assert/strict'
import {
  initialViewMode,
  coerceViewModeForPhone,
  initialTheme,
} from './src/lib/mobile-ui'

// ---- initialViewMode (D1 + D4) ----------------------------------------------

// Phone, no stored choice -> preview (reading-first default).
assert.strictEqual(
  initialViewMode({ isPhoneLayout: true, stored: null }),
  'preview',
  'phone with no stored choice must default to preview',
)
console.log('case 1 OK: phone, no stored choice      -> preview')

// Phone with a stored 'split' preference -> forced to preview (D4: a stored
// split preference must never produce a broken two-column phone layout).
assert.strictEqual(
  initialViewMode({ isPhoneLayout: true, stored: 'split' }),
  'preview',
  "phone with stored 'split' must fall back to preview",
)
console.log("case 2 OK: phone, stored 'split'        -> preview")

// Phone with an explicit stored 'edit' choice -> honoured (user choice wins).
assert.strictEqual(
  initialViewMode({ isPhoneLayout: true, stored: 'edit' }),
  'edit',
  "phone with stored 'edit' must keep edit",
)
console.log("case 3 OK: phone, stored 'edit'         -> edit")

// Phone with an explicit stored 'preview' choice -> honoured.
assert.strictEqual(
  initialViewMode({ isPhoneLayout: true, stored: 'preview' }),
  'preview',
  "phone with stored 'preview' must keep preview",
)
console.log("case 4 OK: phone, stored 'preview'      -> preview")

// Desktop, no stored choice -> historical 'split' default (zero regression).
assert.strictEqual(
  initialViewMode({ isPhoneLayout: false, stored: null }),
  'split',
  'desktop with no stored choice must keep the split default',
)
console.log("case 5 OK: desktop, no stored choice    -> split")

// Desktop with a stored choice -> honoured as before.
assert.strictEqual(
  initialViewMode({ isPhoneLayout: false, stored: 'edit' }),
  'edit',
  "desktop with stored 'edit' must keep edit",
)
assert.strictEqual(
  initialViewMode({ isPhoneLayout: false, stored: 'split' }),
  'split',
  "desktop with stored 'split' must keep split",
)
console.log("case 6 OK: desktop, stored choices      -> honoured")

// Garbage stored value behaves like no preference on both platforms.
assert.strictEqual(
  initialViewMode({ isPhoneLayout: true, stored: 'bogus' }),
  'preview',
  'phone with a garbage stored value must default to preview',
)
assert.strictEqual(
  initialViewMode({ isPhoneLayout: false, stored: 'bogus' }),
  'split',
  'desktop with a garbage stored value must default to split',
)
console.log('case 7 OK: garbage stored values        -> defaults')

// ---- coerceViewModeForPhone (D4 live guard) ----------------------------------

// A live 'split' on a phone is coerced to preview...
assert.strictEqual(
  coerceViewModeForPhone('split'),
  'preview',
  "live 'split' on a phone must coerce to preview",
)
// ...while explicit user choices pass through untouched.
assert.strictEqual(coerceViewModeForPhone('edit'), 'edit', "'edit' must pass through")
assert.strictEqual(coerceViewModeForPhone('preview'), 'preview', "'preview' must pass through")
console.log('case 8 OK: coerceViewModeForPhone       -> split coerced, others pass')

// ---- initialTheme (D5) --------------------------------------------------------

// Stored choice always wins, even against the system preference.
assert.strictEqual(
  initialTheme({ stored: 'dark', systemPrefersDark: false }),
  'dark',
  "stored 'dark' must win over a light system",
)
assert.strictEqual(
  initialTheme({ stored: 'light', systemPrefersDark: true }),
  'light',
  "stored 'light' must win over a dark system",
)
console.log('case 9 OK: stored theme wins           -> dark/light honoured')

// No stored choice: follow the system (D5).
assert.strictEqual(
  initialTheme({ stored: null, systemPrefersDark: true }),
  'dark',
  'no stored choice + dark system must give dark',
)
assert.strictEqual(
  initialTheme({ stored: null, systemPrefersDark: false }),
  'light',
  'no stored choice + light system must give light',
)
console.log('case 10 OK: no stored choice            -> follows system')

// No stored choice AND matchMedia unavailable -> historical 'light' fallback.
assert.strictEqual(
  initialTheme({ stored: null, systemPrefersDark: null }),
  'light',
  'unavailable system preference must fall back to light',
)
console.log('case 11 OK: system pref unavailable     -> light fallback')

console.log('')
console.log('MOBILE UI OK: 11/11 cases hold.')
