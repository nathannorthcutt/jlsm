# Architecture Decisions — History

> Full history of accepted decisions. Most recent 5 are in [CLAUDE.md](CLAUDE.md).

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| Stripe Hash Function | stripe-hash-function | 2026-03-17 | Stafford variant 13 (splitmix64) — zero-allocation, sub-nanosecond |
| Compression Codec API Design | compression-codec-api-design | 2026-03-17 | Open interface + explicit codec list — non-sealed, reader takes varargs codecs |
| Cross-Stripe Eviction | cross-stripe-eviction | 2026-03-17 | Sequential loop — iterate stripes, call evict on each |
| SSTable Block Compression Format | sstable-block-compression-format | 2026-03-17 | Compression Offset Map — separate file section with per-block metadata array |
| VectorType Serialization Encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
