# patterns / transactions

> Patterns for multi-resource transactional consistency and rollback strategies.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| multi-index-partial-failure-rollback | [multi-index-partial-failure-rollback.md](multi-index-partial-failure-rollback.md) | Partial failure during fan-out across N indices leaves inconsistent state without compensating rollback | 2026-04-11 |
| stage-then-publish-disk-before-memory | [stage-then-publish-disk-before-memory.md](stage-then-publish-disk-before-memory.md) | Two-layer (memory + disk) state where in-memory publish precedes disk write produces TOCTOU windows and crash holes; fix with staged-placeholder, snapshot-then-promote, paired-mutation rollback, or deferred-rescan shapes | 2026-04-25 |
