# Architecture Decisions — History

> Full history of accepted decisions. Most recent 5 are in [CLAUDE.md](CLAUDE.md).

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|
| Field Encryption API Design | field-encryption-api-design | 2026-03-18 | Schema Annotation — FieldDefinition carries sealed EncryptionSpec, keys in Arena-backed holder |
| Index Definition API Simplification | index-definition-api-simplification | 2026-03-17 | Derive dimensions from schema VectorType — remove vectorDimensions from record |
| Stripe Hash Function | stripe-hash-function | 2026-03-17 | Stafford variant 13 (splitmix64) — zero-allocation, sub-nanosecond |
| Compression Codec API Design | compression-codec-api-design | 2026-03-17 | Open interface + explicit codec list — non-sealed, reader takes varargs codecs |
| Cross-Stripe Eviction | cross-stripe-eviction | 2026-03-17 | Sequential loop — iterate stripes, call evict on each |
| SSTable Block Compression Format | sstable-block-compression-format | 2026-03-17 | Compression Offset Map — separate file section with per-block metadata array |
| VectorType Serialization Encoding | vector-type-serialization-encoding | 2026-03-17 | Flat Vector Encoding — contiguous d×sizeof(T) bytes, no per-vector metadata |
| Table Partitioning | table-partitioning | 2026-03-16 | Range partitioning with per-partition co-located indices |
| Encrypted Index Strategy | encrypted-index-strategy | 2026-03-18 | Static Capability Matrix with 3-tier full-text search (keyword, phrase, SSE) |
| Engine API Surface Design | engine-api-surface-design | 2026-03-19 | Interface-Based Handle Pattern with Tracked Lifecycle and Lease Eviction |
| Table Catalog Persistence | table-catalog-persistence | 2026-03-19 | Per-Table Metadata Directories — lazy recovery, per-table failure isolation |
| BoundedString Field Type Design | bounded-string-field-type | 2026-03-19 | BoundedString record as 5th sealed permit with STRING-delegating switch arms |
| Pre-Encrypted Document Signaling | pre-encrypted-document-signaling | 2026-03-19 | Factory method with boolean field — `JlsmDocument.preEncrypted(schema, ...)` |
| Cluster Membership Protocol | cluster-membership-protocol | 2026-03-20 | Rapid + Phi Accrual Composite — leaderless consistent membership with adaptive failure detection |
| Transport Abstraction Design | transport-abstraction-design | 2026-03-20 | Message-Oriented Transport — send + request with type dispatch, virtual/platform thread split |
| Discovery SPI Design | discovery-spi-design | 2026-03-20 | Minimal Seed Provider with Optional Registration — discoverSeeds + default register/deregister |
| Scatter-Gather Query Execution | scatter-gather-query-execution | 2026-03-20 | Partition-Aware Proxy Table — transparent Table interface, k-way merge, partition pruning |
| Rebalancing & Grace Period Strategy | rebalancing-grace-period-strategy | 2026-03-20 | Eager Reassignment with Deferred Cleanup — immediate HRW recompute, grace controls cleanup |
| Partition-to-Node Ownership | partition-to-node-ownership | 2026-03-20 | Rendezvous Hashing (HRW) — stateless pure function, O(K/N) minimal movement |
| Power-of-Two Stripe Optimization | power-of-two-stripe-optimization | 2026-04-10 | Enforce power-of-2 stripe counts, use bitmask instead of modulo |
| Codec Thread Safety | codec-thread-safety | 2026-04-10 | Stateless and thread-safe contract on CompressionCodec interface |
| Max Compressed Length | max-compressed-length | 2026-04-10 | Add maxCompressedLength(int) default method to CompressionCodec |
| Per-Block Checksums | per-block-checksums | 2026-04-10 | CRC32C per-block checksum in CompressionMap.Entry |
