# Project Capabilities

> Managed by vallorcine agents. Use /capabilities to query.
> Pull model. Navigate: domain → capability file.
> Do not scan this directory recursively.
> Structure: .capabilities/<domain>/<capability>.md

## Domain Map

| Domain | Path | Capabilities | Last Updated |
|--------|------|-------------|--------------|
| data-management | [data-management/](data-management/CLAUDE.md) | 4 | 2026-04-11 |
| security | [security/](security/CLAUDE.md) | 3 | 2026-04-23 |
| query | [query/](query/CLAUDE.md) | 3 | 2026-04-07 |
| distribution | [distribution/](distribution/CLAUDE.md) | 3 | 2026-04-20 |

## Recently Updated (last 5)

| Date | Domain | Capability | Change |
|------|--------|-----------|--------|
| 2026-04-23 | security | per-tenant-encryption-key-hierarchy | New: Core — per-tenant three-tier key hierarchy (Tenant KEK -> Domain KEK -> DEK), wait-free sharded registry, HKDF-SHA256 derivation, AES-KWP + AES-GCM wrapping, KmsClient SPI (WD-01) |
| 2026-04-22 | data-management | compressed-blocks | Extends: pool-aware block size (Builder.pool(ArenaBufferPool)); audit-hardened ArenaBufferPool lifecycle |
| 2026-04-21 | data-management | block-cache | Extends: byte-budget LRU displacing entry-count; non-linear splitmix64 pre-avalanche |
| 2026-04-20 | distribution | engine-clustering | Core: remote dispatch payload format + parallel scatter (F04 R68+R77) |
| 2026-04-12 | data-management | compressed-blocks | Extends: ZSTD dictionary compression, per-level codec policy, SSTable v4 format |
