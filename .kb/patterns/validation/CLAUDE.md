# patterns / validation

> Anti-patterns and fix patterns for input validation and dispatch correctness.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| assert-only-public-api-validation | [assert-only-public-api-validation.md](assert-only-public-api-validation.md) | assert used as sole enforcement in public API boundaries; disabled in production | 2026-04-06 |
| non-finite-float-bypass | [non-finite-float-bypass.md](non-finite-float-bypass.md) | NaN/Infinity enters via internal construction paths that bypass public validation | 2026-04-06 |
| else-branch-assumes-last-variant | [else-branch-assumes-last-variant.md](else-branch-assumes-last-variant.md) | two-branch if/else silently mishandles future third variant | 2026-04-06 |
| incomplete-serialization-round-trip | [incomplete-serialization-round-trip.md](incomplete-serialization-round-trip.md) | Persist/recover cycles that silently omit fields, producing valid but incomplete objects | 2026-04-07 |
| mutable-state-escaping-builder | [mutable-state-escaping-builder.md](mutable-state-escaping-builder.md) | Builder passes mutable collections or silently discards configured values during construction | 2026-04-07 |
| untrusted-storage-byte-length | [untrusted-storage-byte-length.md](untrusted-storage-byte-length.md) | Decode routines that trust persisted byte array lengths without runtime bounds checks | 2026-04-08 |
