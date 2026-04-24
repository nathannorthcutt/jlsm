# Security — Category Index
*Topic: systems*

Security patterns for JVM-based storage systems — key management, encryption
integration, and in-memory data protection.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) | JVM Key Handling Patterns | stable | Off-heap Arena key storage | Caller-provided key lifecycle management |
| [encryption-key-rotation-patterns.md](encryption-key-rotation-patterns.md) | Encryption Key Rotation Patterns | active | Compaction-driven re-encryption | LSM-tree key rotation without downtime |
| [three-level-key-hierarchy.md](three-level-key-hierarchy.md) | Three-Level Key Hierarchy (Root KEK → Domain KEK → DEK) | active | O(keys) rotation per level; HKDF context binding | Multi-tenant key separation, envelope wrapping design |
| [wal-encryption-approaches.md](wal-encryption-approaches.md) | WAL Encryption Approaches | active | AES-GCM per-record, seq# as nonce | WAL data-at-rest encryption |
| [client-side-encryption-patterns.md](client-side-encryption-patterns.md) | Client-Side Encryption Patterns and SDK Design | active | Schema-driven per-field encryption | CSFLE SDK, pre-encrypted documents, key distribution |
| [sstable-block-level-ciphertext-envelope.md](sstable-block-level-ciphertext-envelope.md) | SSTable Block-Level Ciphertext Envelope and Key-ID Signalling | active | Per-block AES-GCM + per-file footer key-id | SSTable encryption format (WD-02) |
| [dek-revocation-vs-rotation.md](dek-revocation-vs-rotation.md) | DEK Revocation Semantics vs Rotation | active | KEK-destruction → tenant-scoped shredding | DEK lifecycle and revocation (WD-03) |
| [dek-caching-policies-multi-tenant.md](dek-caching-policies-multi-tenant.md) | DEK Caching Policies for Multi-Tenant LSM | active | TTL + max-bytes + per-tenant capacity | Key cache design (WD-05) |

## Comparison Summary

Seven complementary security concerns:
- **Key handling** covers how keys are stored and managed in the JVM
- **Key hierarchy** covers the 3-level structure (root KEK → domain KEK → DEK), HKDF context binding, and wrap-primitive selection
- **Key rotation** covers how to change keys without downtime, leveraging LSM compaction
- **WAL encryption** covers encrypting the write-ahead log with per-record AES-GCM
- **SSTable envelope** covers how blocks inside an SSTable are framed with ciphertext + nonce + tag, and how the reader locates the DEK
- **DEK revocation vs rotation** covers the distinction between rotation (additive), revocation (reversible), and crypto-shredding (irreversible), and how the key hierarchy makes tenant-scoped shredding a single KEK destruction
- **DEK caching policies** covers the TTL/usage/capacity bounds and multi-tenant partitioning of an in-memory DEK cache — revocation lag, noisy-neighbor isolation, zeroisation-on-eviction

## Recommended Reading Order
1. Start: [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) — key lifecycle, KMS integration, Arena-based storage
2. Then: [three-level-key-hierarchy.md](three-level-key-hierarchy.md) — root/domain/DEK structure, HKDF derivation, wrap primitives
3. Then: [encryption-key-rotation-patterns.md](encryption-key-rotation-patterns.md) — envelope encryption, compaction-driven rotation
4. Then: [wal-encryption-approaches.md](wal-encryption-approaches.md) — record-level vs segment-level, compression ordering
5. Then: [sstable-block-level-ciphertext-envelope.md](sstable-block-level-ciphertext-envelope.md) — SSTable per-block envelope, key-id signalling, GCM AAD binding
6. Then: [dek-revocation-vs-rotation.md](dek-revocation-vs-rotation.md) — rotation vs disable vs destroy vs forced re-encryption
7. Then: [dek-caching-policies-multi-tenant.md](dek-caching-policies-multi-tenant.md) — TTL, usage, capacity bounds; per-tenant partitioning; zeroisation on eviction
8. Then: [client-side-encryption-patterns.md](client-side-encryption-patterns.md) — CSFLE, SDK design, pre-encrypted document handling

## Research Gaps
- Threat modeling for encrypted LSM-tree components (SSTable, MemTable)
- Java Security Manager alternatives post-deprecation
