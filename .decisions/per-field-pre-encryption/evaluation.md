# Evaluation — Per-Field Pre-Encryption

## Candidates

### A. Bitset Flag (boolean → bitset in JlsmDocument)
Replace the `boolean preEncrypted` field with a `long preEncryptedBitset` where each bit corresponds to a field index. A new factory method accepts a `Set<String>` of pre-encrypted field names.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 9 | Bitwise check per field — O(1). Long supports up to 64 fields | — |
| Resources | 9 | long field vs boolean — 8 bytes vs 1 byte; fits in alignment padding on 64-bit JVMs | — |
| Complexity | 8 | Simple bitset logic. Factory validates field names against schema | — |
| Accuracy | 9 | Precise per-field control; only marked fields skip encryption | parent ADR: pre-encrypted-document-signaling |
| Operational | 9 | Backward compatible — `preEncrypted(schema, ...)` sets all bits; new `preEncrypted(schema, fieldNames, ...)` sets subset | — |
| Fit | 9 | JlsmDocument stays final; DocumentSerializer checks bit per field in dispatch | — |
| **Total** | **53/60** | | |

### B. Per-Field Wrapper Type (encrypted fields carry PreEncryptedValue)
Introduce a `PreEncryptedValue` record wrapping `byte[]`. The serializer detects this type via instanceof and skips encryption.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 7 | instanceof check per field — slightly slower than bitset | — |
| Resources | 5 | Additional object allocation per pre-encrypted field value | — |
| Complexity | 6 | New public type in the API; users must wrap values | — |
| Accuracy | 8 | Type system enforces pre-encrypted marking | — |
| Operational | 6 | Breaking API change — pre-encrypted values must be wrapped | — |
| Fit | 5 | Changes the Object[] value array contract; downstream consumers must handle PreEncryptedValue | parent ADR: pre-encrypted-document-signaling |
| **Total** | **37/60** | | |

### C. Per-Field Metadata Map
Add a `Map<String, FieldMetadata>` to JlsmDocument carrying per-field metadata including a pre-encrypted flag.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 6 | Map lookup per field — O(1) amortized but higher constant than bitset | — |
| Resources | 4 | Map allocation per document; entry overhead | — |
| Complexity | 5 | Generic escape hatch invites scope creep | parent ADR: pre-encrypted-document-signaling (explicitly rejected metadata map) |
| Accuracy | 7 | Correct but untyped | — |
| Operational | 5 | New API surface with untyped metadata | — |
| Fit | 4 | Parent ADR rejected this pattern as "architecturally inconsistent" | parent ADR: pre-encrypted-document-signaling |
| **Total** | **31/60** | | |

## Recommendation
**Candidate A — Bitset Flag**. Minimal change, backward compatible, zero additional allocation, precise per-field control. The `long` bitset supports up to 64 fields, which exceeds any reasonable schema width for a document store.

## Falsification Check
- **What if schemas exceed 64 fields?** The bitset can be extended to `long[]` or `BitSet` if needed. Current JlsmSchema has no practical use case for >64 fields, and this is a future-compatible extension, not a design flaw.
- **What if the bitset makes the API harder to use?** The `Set<String>` factory method translates field names to bit positions internally — callers never see the bitset.
- **What if partial pre-encryption breaks index consistency?** Pre-encrypted fields are already type-validated for ciphertext structure. The index sees the same ciphertext regardless of who encrypted it. No consistency risk.
