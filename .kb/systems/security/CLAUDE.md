# Security — Category Index
*Topic: systems*

Security patterns for JVM-based storage systems — key management, encryption
integration, and in-memory data protection.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) | JVM Key Handling Patterns | stable | Off-heap Arena key storage | Caller-provided key lifecycle management |
| [encryption-key-rotation-patterns.md](encryption-key-rotation-patterns.md) | Encryption Key Rotation Patterns | active | Compaction-driven re-encryption | LSM-tree key rotation without downtime |
| [wal-encryption-approaches.md](wal-encryption-approaches.md) | WAL Encryption Approaches | active | AES-GCM per-record, seq# as nonce | WAL data-at-rest encryption |

## Comparison Summary

Three complementary security concerns:
- **Key handling** covers how keys are stored and managed in the JVM
- **Key rotation** covers how to change keys without downtime, leveraging LSM compaction
- **WAL encryption** covers encrypting the write-ahead log with per-record AES-GCM

## Recommended Reading Order
1. Start: [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) — key lifecycle, KMS integration, Arena-based storage
2. Then: [encryption-key-rotation-patterns.md](encryption-key-rotation-patterns.md) — envelope encryption, compaction-driven rotation
3. Then: [wal-encryption-approaches.md](wal-encryption-approaches.md) — record-level vs segment-level, compression ordering

## Research Gaps
- Threat modeling for encrypted LSM-tree components (SSTable, MemTable)
- Java Security Manager alternatives post-deprecation
