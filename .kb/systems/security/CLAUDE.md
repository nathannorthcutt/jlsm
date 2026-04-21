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

## Comparison Summary

Four complementary security concerns:
- **Key handling** covers how keys are stored and managed in the JVM
- **Key hierarchy** covers the 3-level structure (root KEK → domain KEK → DEK), HKDF context binding, and wrap-primitive selection
- **Key rotation** covers how to change keys without downtime, leveraging LSM compaction
- **WAL encryption** covers encrypting the write-ahead log with per-record AES-GCM

## Recommended Reading Order
1. Start: [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) — key lifecycle, KMS integration, Arena-based storage
2. Then: [three-level-key-hierarchy.md](three-level-key-hierarchy.md) — root/domain/DEK structure, HKDF derivation, wrap primitives
3. Then: [encryption-key-rotation-patterns.md](encryption-key-rotation-patterns.md) — envelope encryption, compaction-driven rotation
4. Then: [wal-encryption-approaches.md](wal-encryption-approaches.md) — record-level vs segment-level, compression ordering
5. Then: [client-side-encryption-patterns.md](client-side-encryption-patterns.md) — CSFLE, SDK design, pre-encrypted document handling

## Research Gaps
- Threat modeling for encrypted LSM-tree components (SSTable, MemTable)
- Java Security Manager alternatives post-deprecation
