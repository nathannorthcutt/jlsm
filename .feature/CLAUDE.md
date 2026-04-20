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
| WD-06 Engine API & Catalog | decisions-backlog--wd-06 | 2026-04-14 | spec-authoring complete | 7 decisions specified across 4 specs (F34-F37); F37 resolves OB-F33-01 |
| WD-07 Partitioning & Rebalancing | decisions-backlog--wd-07 | 2026-04-14 | spec-authoring complete | 13 decisions specified across 7 specs (F27-F33); OB-F33-01 resolved by F37 |
| WD-08 Query Execution | decisions-backlog--wd-08 | 2026-04-14 | spec-authoring complete | 3 decisions confirmed across 3 specs (F38-F40) |
| WD-09 Encryption & Security | decisions-backlog--wd-09 | 2026-04-14 | spec-authoring complete | 11 decisions (6 confirmed, 4 re-deferred, 1 closed); F41-F42 specs |
| WD-11 Partition Optimization | decisions-backlog--wd-11 | 2026-04-15 | spec-authoring complete | 3 decisions (3 accepted); F43 spec + F30 R20-R63 |
| WD-10 Rebalancing Safety & Recovery | decisions-backlog--wd-10 | 2026-04-15 | spec-authoring complete | 5 decisions (5 accepted); F48 spec + F27/F29/F32 |
| WD-13 Catalog & Scan Lifecycle | decisions-backlog--wd-13 | 2026-04-15 | spec-authoring complete | 4 decisions (4 accepted); F44 spec + F39/F37/F33 |
| WD-12 Encrypted Query Capabilities | decisions-backlog--wd-12 | 2026-04-15 | spec-authoring complete | 4 decisions (3 accepted, 1 re-deferred); F45-F47 specs |
| Protocol Infrastructure (F04 R39 + R53) | f04-obligation-resolution--wd-01 | 2026-04-19 | planning complete | work-plan.md written, 2 stubs created, balanced split WU-1/WU-2 |
| Engine Lifecycle and Local Routing (F04 R56+R57+R58+R60+R79) | f04-obligation-resolution--wd-02 | 2026-04-19 | planning complete | work-plan.md written; 2 extension markers; single unit (no new files) |
| RAPID Consensus Protocol (F04 R34-R38) | f04-obligation-resolution--wd-04 | 2026-04-19 | planning complete | 6 stubs; 4-unit balanced split {WU-1,WU-2}→WU-3→WU-4; KB rapid-consensus.md + incarnation-refutation.md |

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
