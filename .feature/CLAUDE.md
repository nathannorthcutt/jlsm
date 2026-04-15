# Feature Work Index

> Pull model — do not scan proactively.
> Each feature lives at .feature/<slug>/
> Project configuration: .feature/project-config.md
> Resume any feature: /feature-resume "<slug>"

## Active Features

| Feature | Slug | Started | Stage | Last Checkpoint |
|---------|------|---------|-------|-----------------|
| In-process database engine | in-process-database-engine | 2026-03-19 | planning complete | work-plan.md written, 13 stubs created |
| Engine clustering | engine-clustering | 2026-03-20 | planning complete | work-plan.md written, 22 stubs created |
| JSON-only SIMD on-demand + JSONL streaming | json-only-simd-jsonl | 2026-04-10 | planning complete | work-plan.md written, 16 stubs created |
| SSTable v3 format upgrade | sstable-v3-format-upgrade | 2026-04-11 | planning complete | work-plan.md written, 1 stub created |
| WD-02 Storage & Compression | decisions-backlog--wd-02 | 2026-04-14 | spec-authoring complete | 10 decisions resolved (3 confirmed, 1 closed, 1 re-deferred, 5 perf-gated re-deferred); F24-F26 specs |
| WD-05 Cluster Networking & Discovery | decisions-backlog--wd-05 | 2026-04-13 | spec-authoring complete | 12 decisions resolved (7 confirmed, 4 closed, 1 re-deferred); F19-F23 specs |

## Completed / Archived

| Feature | Slug | Completed | Archive |
|---------|------|-----------|---------|
| ZSTD dictionary compression + per-level codec policy | zstd-dictionary-compression-per-level-codec-policy | 2026-04-12 | .feature/_archive/zstd-dictionary-compression-per-level-codec-policy/ |
| WAL compression + MemorySegment codec API | wal-compression-codec-api | 2026-04-12 | .feature/_archive/wal-compression-codec-api/ |
| Field-level in-memory encryption | encrypt-memory-data | 2026-03-19 | .feature/_archive/encrypt-memory-data/ |
| Extract core encryption primitives | extract-core-encryption | 2026-03-19 | .feature/_archive/extract-core-encryption/ |
| Fix encryption performance | fix-encryption-performance | 2026-03-19 | .feature/_archive/fix-encryption-performance/ |
| OPE type-aware bounds + BoundedString + docs | ope-type-aware-bounds | 2026-03-19 | .feature/_archive/ope-type-aware-bounds/ |
| Optimize DocumentSerializer | optimize-document-serializer | 2026-03-18 | .feature/_archive/optimize-document-serializer/ |
| Streaming block decompression | streaming-block-decompression | 2026-03-18 | .feature/_archive/streaming-block-decompression/ |
| Block-level SSTable compression | block-compression | 2026-03-18 | .feature/_archive/block-compression/ |
| Float16 vector support | float16-vector-support | 2026-03-16 | .feature/_archive/float16-vector-support/ |
| SQL query support | sql-query-support | 2026-03-17 | .feature/_archive/sql-query-support/ |
| Table indices and queries | table-indices-and-queries | 2026-03-16 | .feature/_archive/table-indices-and-queries/ |
| Striped block cache | striped-block-cache | 2026-03-17 | .feature/_archive/striped-block-cache/ |
| Table partitioning | table-partitioning | 2026-03-17 | .feature/_archive/table-partitioning/ |
| Vector field type | vector-field-type | 2026-03-17 | .feature/_archive/vector-field-type/ |
