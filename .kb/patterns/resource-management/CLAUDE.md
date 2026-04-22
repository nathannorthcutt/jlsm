# patterns / resource-management

> Anti-patterns and fix patterns for resource lifecycle and memory management.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| unbounded-collection-growth | [unbounded-collection-growth.md](unbounded-collection-growth.md) | Maps/caches that grow with input without eviction; fix with bounded LRU or epoch scoping | 2026-04-06 |
| partial-init-no-rollback | [partial-init-no-rollback.md](partial-init-no-rollback.md) | Multi-step init with no rollback on failure; fix with compensating actions or rollback stack | 2026-04-06 |
| mutation-outside-rollback-scope | [mutation-outside-rollback-scope.md](mutation-outside-rollback-scope.md) | Mutation steps placed outside try/catch rollback leave inconsistent state on failure | 2026-04-06 |
| destructive-error-recovery | [destructive-error-recovery.md](destructive-error-recovery.md) | Cleanup paths that delete data on transient errors, converting recoverable failures to data loss | 2026-04-07 |
| eviction-scope-mismatch | [eviction-scope-mismatch.md](eviction-scope-mismatch.md) | Eviction targets wrong entity or uses stale state in bounded-resource pools | 2026-04-07 |
| fan-out-iterator-leak | [fan-out-iterator-leak.md](fan-out-iterator-leak.md) | Fan-out query collects iterators from partitions; partial failure or abandonment leaks unclosed sources | 2026-04-07 |
| non-idempotent-close | [non-idempotent-close.md](non-idempotent-close.md) | close() re-delegates without tracking prior invocation; causes double-free or redundant I/O | 2026-04-07 |
| multi-resource-close-ordering | [multi-resource-close-ordering.md](multi-resource-close-ordering.md) | Wrapper components creating internal closeables without tracking for close | 2026-04-12 |
| fan-out-dispatch-deferred-exception-pattern | [fan-out-dispatch-deferred-exception-pattern.md](fan-out-dispatch-deferred-exception-pattern.md) | Fan-out operations short-circuit on first failure; must iterate all siblings with deferred exceptions | 2026-04-21 |
