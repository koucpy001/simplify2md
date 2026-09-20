// Bridge event registration + startup-ready gate tests (plan todo 17).
//
// Uses a FAKE EventsOn — grep is not evidence; the assertions below actually
// invoke the registered handlers and the gate.
//
// Run: npx tsx test-bridge-events.ts

import assert from 'node:assert/strict'
import {
  registerBridgeEvents,
  createStartupReadyGate,
  imeInsetPx,
  openTextPayload,
  IME_HEIGHT_CSS_VAR,
} from './src/lib/bridge-events'

// ---- fake EventsOn capturing the registration set ----------------------------
const registered = new Map<string, (...data: any[]) => void>()
const fakeEventsOn = (name: string, cb: (...data: any[]) => void): (() => void) => {
  registered.set(name, cb)
  return () => {}
}

// ---- fake handlers recording invocations -------------------------------------
const calls = {
  confirmExit: 0,
  openPath: [] as string[],
  fileChanged: 0,
  imeHeight: [] as string[],
  openText: [] as ReturnType<typeof openTextPayload>[],
}
registerBridgeEvents(fakeEventsOn, {
  confirmExit: () => { calls.confirmExit += 1 },
  openPath: (p) => { calls.openPath.push(p) },
  fileChanged: () => { calls.fileChanged += 1 },
  imeHeight: (cssValue) => { calls.imeHeight.push(cssValue) },
  openText: (payload) => { calls.openText.push(payload) },
})

// ---- 1. all FIVE handlers registered -----------------------------------------
const EXPECTED = [
  'mdview:confirm-exit',
  'mdview:open-path',
  'mdview:file-changed',
  'mdview:ime',
  'mdview:open-text',
]
for (const name of EXPECTED) {
  assert.ok(registered.has(name), `handler not registered: ${name}`)
}
assert.equal(registered.size, 5, `expected exactly 5 registrations, got ${registered.size}`)
console.log('registered handlers  ->', [...registered.keys()].join(', '))

// ---- 2. each handler actually dispatches to its callback ---------------------
registered.get('mdview:confirm-exit')!()
assert.equal(calls.confirmExit, 1, 'confirm-exit handler must invoke its callback')

registered.get('mdview:open-path')!('content://doc/a.md')
registered.get('mdview:open-path')!('') // empty path must be dropped (desktop parity)
assert.deepEqual(calls.openPath, ['content://doc/a.md'])

registered.get('mdview:file-changed')!()
assert.equal(calls.fileChanged, 1)

// mdview:ime: payload height -> validated CSS value; broken payloads -> '0px'
registered.get('mdview:ime')!({ height: 120 })
registered.get('mdview:ime')!({ height: 0 })
registered.get('mdview:ime')!({ height: -50 })
registered.get('mdview:ime')!({ height: Number.NaN })
registered.get('mdview:ime')!({}) // missing height
registered.get('mdview:ime')!(undefined) // no payload at all
assert.deepEqual(
  calls.imeHeight,
  ['120px', '0px', '0px', '0px', '0px', '0px'],
  'ime handler must sanitize every payload shape',
)

// mdview:open-text: shared text -> unnamed dirty document payload
registered.get('mdview:open-text')!('# hello')
assert.deepEqual(calls.openText, [{ filePath: '', content: '# hello', dirty: true }])
console.log('dispatch             -> all five callbacks invoked with correct payloads')

// ---- 3. ready-gate timing contract -------------------------------------------
{
  let readyCalls = 0
  const gate = createStartupReadyGate(() => { readyCalls += 1 })
  // Pending draft-recovery modal: hold open -> NO ready.
  gate.hold()
  assert.equal(readyCalls, 0, 'pending draft modal must NOT send ready')
  assert.equal(gate.isReady(), false)
  // Modal settles -> exactly one ready.
  gate.release()
  assert.equal(readyCalls, 1, 'exactly one ready after the modal settles')
  assert.equal(gate.isReady(), true)
  // Second onMounted exit path (hold/release again) must be a no-op.
  gate.hold()
  gate.release()
  assert.equal(readyCalls, 1, 'second exit path must not send ready again')
  // Stray release without a hold must not fire anything.
  gate.release()
  assert.equal(readyCalls, 1)
  console.log('ready gate           -> pending modal blocks ready; exactly one ready per exit')
}

// ---- 3b. BOTH onMounted exit paths reach ready exactly once (todo 18g) --------
{
  let readyCalls = 0
  const gate = createStartupReadyGate(() => { readyCalls += 1 })
  // Branch A: startup-file early return — postStartup hold, no modal, release.
  gate.hold()
  gate.release()
  assert.equal(readyCalls, 1, 'branch A (startup-file) must reach ready exactly once')
  // Branch B: recents-restore tail — hold, draft modal pending, modal settles.
  gate.hold()
  assert.equal(readyCalls, 1, 'branch B pending modal must not fire ready again')
  gate.release()
  assert.equal(readyCalls, 1, 'branch B must not fire ready a second time (once per lifetime)')
  console.log('both onMounted branches -> each reaches ready; exactly one ready total')
}

// ---- 4. pure helpers re-exported sanity (full boundaries in test-ime-helper) --
assert.equal(imeInsetPx(120), '120px')
assert.equal(IME_HEIGHT_CSS_VAR, '--mdview-ime-height')
console.log('ALL BRIDGE-EVENTS TESTS PASSED')
