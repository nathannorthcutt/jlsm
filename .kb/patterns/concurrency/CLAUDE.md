# patterns / concurrency

> Anti-patterns and fix patterns for concurrent code.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| non-atomic-lifecycle-flags | [non-atomic-lifecycle-flags.md](non-atomic-lifecycle-flags.md) | volatile boolean check-then-act races on start/close; fix with AtomicBoolean.compareAndSet | 2026-04-06 |
| lock-held-side-effects | [lock-held-side-effects.md](lock-held-side-effects.md) | Network I/O and callbacks executed under protocol locks; fix with snapshot-then-release | 2026-04-06 |
| read-method-missing-close-guard | [read-method-missing-close-guard.md](read-method-missing-close-guard.md) | Read-only methods lack lock acquisition, creating TOCTOU race with close() | 2026-04-06 |
| phantom-registration-after-lifecycle-transition | [phantom-registration-after-lifecycle-transition.md](phantom-registration-after-lifecycle-transition.md) | Registration after close/invalidate creates orphaned entities tracked by nobody | 2026-04-07 |
| unsafe-this-escape-via-listener-registration | [unsafe-this-escape-via-listener-registration.md](unsafe-this-escape-via-listener-registration.md) | Ctor registers listener capturing `this` before final fields are assigned; fix by registering last and rolling back on ctor failure | 2026-04-20 |
| timeout-wrapper-does-not-cancel-source-future | [timeout-wrapper-does-not-cancel-source-future.md](timeout-wrapper-does-not-cancel-source-future.md) | `orTimeout` completes a wrapper but leaves source uncancelled, leaking server-side state; fix by scheduling explicit `source.cancel(true)` and tracking blocked threads | 2026-04-20 |
| torn-volatile-publish-multi-field | [torn-volatile-publish-multi-field.md](torn-volatile-publish-multi-field.md) | Two independent volatile writes publish multi-field state; observer sees one set and the other null; fix with combined reference or ordered publish + sentinel render | 2026-04-22 |
| check-then-act-across-paired-acquire-release | [check-then-act-across-paired-acquire-release.md](check-then-act-across-paired-acquire-release.md) | Reader-slot vs recovery-scan predicates read unlocked; straddle window violates mutex; fix with single-mutex serialization of check-and-modify | 2026-04-22 |
| shared-rwlock-bracketing-facade-close-atomicity | [shared-rwlock-bracketing-facade-close-atomicity.md](shared-rwlock-bracketing-facade-close-atomicity.md) | Facade with off-heap state races close() against in-flight derives; AtomicBoolean gate alone is insufficient; fix with `ReentrantReadWriteLock` — read lock on every derive (incl. external I/O and publish), write lock on close() to drain readers before zeroize/release | 2026-04-23 |
