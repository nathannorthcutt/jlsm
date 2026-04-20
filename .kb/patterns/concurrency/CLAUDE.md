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
