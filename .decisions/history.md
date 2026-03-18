# Architecture Decisions — History

> Full history of accepted decisions. Most recent 5 are in [CLAUDE.md](CLAUDE.md).

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| VectorType Serialization Encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
