// DOM storage / secure-context probe gates (plan todo 20b).
import { probeDomEnvironment } from './src/lib/web-env'

let failures = 0
function check(name: string, cond: boolean, detail?: unknown) {
  if (cond) console.log('PASS', name)
  else { failures++; console.error('FAIL', name, detail ?? '') }
}

function fakeStorage(throwOnAccess = false): Storage | null {
  if (throwOnAccess) {
    return new Proxy({}, {
      get() { throw new DOMException('denied', 'SecurityError') },
      set() { throw new DOMException('denied', 'SecurityError') },
    }) as unknown as Storage
  }
  const map = new Map<string, string>()
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => { map.set(k, String(v)) },
    removeItem: (k) => { map.delete(k) },
    clear: () => map.clear(),
    key: () => null,
    get length() { return map.size },
  } as Storage
}

check('healthy env -> null', probeDomEnvironment({ isSecureContext: true, localStorage: fakeStorage() }) === null)
const noSecure = probeDomEnvironment({ isSecureContext: false, localStorage: fakeStorage() })
check('insecure -> message', noSecure !== null && noSecure.includes('isSecureContext'), noSecure)
const noStorage = probeDomEnvironment({ isSecureContext: true, localStorage: null })
check('missing localStorage -> message', noStorage !== null && noStorage.includes('localStorage'), noStorage)
const denied = probeDomEnvironment({ isSecureContext: true, localStorage: fakeStorage(true) })
check('throwing localStorage -> message', denied !== null && denied.includes('拒绝'), denied)

if (failures > 0) { console.error(`${failures} failure(s)`); process.exit(1) }
console.log('ALL PASS')
