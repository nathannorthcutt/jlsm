# Project Capabilities

> Managed by vallorcine agents. Use /capabilities to query.
> Pull model. Navigate: domain → capability file.
> Do not scan this directory recursively.
> Structure: .capabilities/<domain>/<capability>.md

## Domain Map

| Domain | Path | Capabilities | Last Updated |
|--------|------|-------------|--------------|
| data-management | [data-management/](data-management/CLAUDE.md) | 4 | 2026-04-11 |
| security | [security/](security/CLAUDE.md) | 2 | 2026-04-07 |
| query | [query/](query/CLAUDE.md) | 3 | 2026-04-07 |
| distribution | [distribution/](distribution/CLAUDE.md) | 3 | 2026-04-20 |

## Recently Updated (last 5)

| Date | Domain | Capability | Change |
|------|--------|-----------|--------|
| 2026-04-20 | distribution | engine-clustering | Core: remote dispatch payload format + parallel scatter (F04 R68+R77) |
| 2026-04-12 | data-management | compressed-blocks | Extends: ZSTD dictionary compression, per-level codec policy, SSTable v4 format |
| 2026-04-12 | data-management | compressed-blocks | Extends: MemorySegment codec API, zero-copy Deflate, per-record WAL compression |
| 2026-04-11 | data-management | compressed-blocks | Extends: v3 format — CRC32C checksums, configurable block size |
| 2026-04-10 | data-management | json-processing | New: JSON value types, SIMD parser, JSONL streaming |
