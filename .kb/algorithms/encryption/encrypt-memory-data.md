---
title: "encrypt-memory-data"
type: feature-footprint
domains: ["security", "data-integrity", "encryption"]
constructs: ["EncryptionSpec", "EncryptionKeyHolder", "AesGcmEncryptor", "AesSivEncryptor", "BoldyrevaOpeEncryptor", "DcpeSapEncryptor", "FieldEncryptionDispatch", "SseEncryptedIndex", "PositionalPostingCodec"]
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/*.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldEncryptionDispatch.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/SseEncryptedIndex.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/PositionalPostingCodec.java"
research_status: stable
last_researched: "2026-03-25"
---
# encrypt-memory-data

## What it built
Opt-in field-level encryption for in-memory data in jlsm-table. Five encryption
schemes (None, Deterministic/AES-SIV, OrderPreserving/Boldyreva OPE,
DistancePreserving/DCPE-SAP, Opaque/AES-GCM) mapped via a sealed EncryptionSpec
interface. Per-field encryption dispatch integrated into DocumentSerializer.
SSE encrypted index for searchable encrypted full-text fields.

## Key constructs
- `EncryptionSpec` — sealed interface defining encryption capabilities per variant
- `EncryptionKeyHolder` — Arena-backed off-heap key storage with zeroing
- `AesGcmEncryptor` — opaque AES-GCM encryption with random IV
- `AesSivEncryptor` — deterministic AES-SIV for equality-searchable encryption
- `BoldyrevaOpeEncryptor` — order-preserving encryption via hypergeometric sampling
- `DcpeSapEncryptor` — distance-preserving vector encryption (Scale-And-Perturb)
- `FieldEncryptionDispatch` — per-field encryptor/decryptor dispatch table
- `SseEncryptedIndex` — Curtmola SSE-2 encrypted inverted index
- `PositionalPostingCodec` — OPE-encrypted positional postings for phrase queries

## Adversarial findings
- OPE-width-truncation: OPE capped to 2 bytes caused silent data corruption for wider types → [KB entry](ope-width-truncation.md)
- Key-bytes-on-heap: Multiple encryptors leave key material on the Java heap → [KB entry](key-bytes-on-heap.md)
- Mutable-array-in-record: EncryptedVector and DecodedPosting exposed mutable arrays → [KB entry](../../data-structures/mutable-array-in-record.md)

## Cross-references
- ADR: .decisions/field-encryption-api-design/adr.md
- ADR: .decisions/encrypted-index-strategy/adr.md
- KB: .kb/algorithms/encryption/searchable-encryption-schemes.md
- KB: .kb/algorithms/encryption/vector-encryption-approaches.md
- KB: .kb/systems/security/jvm-key-handling-patterns.md
