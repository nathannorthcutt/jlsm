---
problem: "encrypted-index-strategy"
date: "2026-03-18"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldIndex.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/VectorFieldIndex.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/QueryExecutor.java"
  - "modules/jlsm-indexing/src/main/java/jlsm/indexing/LsmInvertedIndex.java"
---

# ADR — Encrypted Index Strategy

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |
| Related ADR | [field-encryption-api-design](../field-encryption-api-design/adr.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Searchable Encryption Schemes | DET/OPE index properties, SSE tiered full-text approach | [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md) |
| Vector Encryption Approaches | DCPE distance-preserving ANN support | [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md) |

---

## Files Constrained by This Decision

- `FieldIndex.java` — operates on DET/OPE ciphertexts for equality/range queries
- `VectorFieldIndex.java` — operates on DCPE encrypted vectors for ANN
- `IndexRegistry.java` — validates EncryptionSpec × index compatibility at construction
- `QueryExecutor.java` — checks capability matrix before executing query operations
- `LsmInvertedIndex.java` — encrypted term indexing (T1/T2), SSE index (T3)

## Problem
How should secondary indices and queries adapt when field values are encrypted — which index types work with which EncryptionSpec variants, and what query operations are supported?

## Constraints That Drove This Decision
- **Correctness over capability**: better to prohibit an index than to allow one that returns wrong results — incompatible combinations must fail at schema construction, not at query time
- **EncryptionSpec determines capability**: the sealed interface categorizes encryption families; index capability maps directly from scheme properties
- **Fit with existing index infrastructure**: FieldIndex, VectorFieldIndex, IndexRegistry, QueryExecutor must adapt without restructuring

## Decision
**Chosen approach: Static Capability Matrix with Tiered Full-Text Search**

Each `EncryptionSpec` variant exposes capability methods (`supportsEquality()`, `supportsRange()`, `supportsKeywordSearch()`, `supportsPhraseSearch()`, `supportsSseSearch()`, `supportsANN()`). `IndexRegistry` validates field encryption × index compatibility at schema construction time — incompatible combinations throw `IllegalArgumentException`. Existing indices operate directly on encrypted values (DET ciphertexts for equality, OPE ciphertexts for range, DCPE vectors for ANN). Full-text search on encrypted fields is supported through three tiers, all included in scope.

### Capability Matrix

| EncryptionSpec     | Equality | Range | Keyword | Phrase | SSE Boolean | ANN   |
|--------------------|----------|-------|---------|--------|-------------|-------|
| None               |    ✓     |   ✓   |    ✓    |   ✓    |      ✓      |   ✓   |
| Deterministic      |    ✓     |   ✗   |  T1 ✓   |  T2 ✓  |    T3 ✓     |   ✗   |
| OrderPreserving    |    ✓     |   ✓   |    ✗    |   ✗    |      ✗      |   ✗   |
| DistancePreserving |    ✗     |   ✗   |    ✗    |   ✗    |      ✗      | ✓(≈)  |
| Opaque             |    ✗     |   ✗   |    ✗    |   ✗    |      ✗      |   ✗   |

### Full-Text Search Tiers

**Tier 1 — DET keyword match (~50 lines)**
Tokenize text, encrypt each term with AES-SIV (deterministic), store encrypted terms in the existing `LsmInvertedIndex` composite key format. Search: encrypt query term with same key, standard index lookup. Supports exact keyword match and boolean AND/OR.

**Tier 2 — DET + OPE positions (~200 lines)**
Extend inverted index postings to include OPE-encrypted term positions. Phrase query: encrypt query terms (DET), look up postings, check that OPE-encrypted positions are consecutive. Proximity: check position difference ≤ threshold on OPE ciphertexts. OPE is only used for the small position domain (integers), minimal security tradeoff.

**Tier 3 — SSE encrypted inverted index (~500-800 lines)**
Separate encrypted index structure per Curtmola SSE-2 / Dyn2Lev scheme. For each term, derive search token `Tw = PRF(K, term)`, encrypt document ID list under `Tw`, store in dictionary keyed by `hash(Tw)`. Minimal access-pattern leakage. Dynamic SSE supports add/delete with forward privacy. Supports keyword and boolean queries with stronger security than T1/T2.

## Rationale

### Why Static Capability Matrix
- **Correctness**: fail-fast at schema construction — impossible to create an incompatible index/encryption combination that silently returns wrong results
- **Scale**: zero runtime overhead — existing indices operate on encrypted values directly, the encryption scheme guarantees the index operation works on ciphertext
- **Fit**: capability methods on the sealed EncryptionSpec interface — no new classes needed for FieldIndex or VectorFieldIndex. IndexRegistry checks before building.

### Why not Encrypted Index Adapters
- **Fit**: SecondaryIndex is sealed — adapters break the hierarchy or require delegation wrappers. N×M adapter classes (encryption specs × index types) to maintain. Validation at query time instead of construction.

### Why not No Index on Encrypted Fields
- **Disqualified**: contradicts acceptance criterion #4 ("at least one field type supports indexed search while encrypted"). Full scan at millions of QPS is prohibitive.

## Implementation Guidance

### EncryptionSpec capability methods
From [`searchable-encryption-schemes.md#implementation-notes`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md#implementation-notes):

```java
public sealed interface EncryptionSpec {
    default boolean supportsEquality()      { return false; }
    default boolean supportsRange()         { return false; }
    default boolean supportsKeywordSearch() { return false; }
    default boolean supportsPhraseSearch()  { return false; }
    default boolean supportsSseSearch()     { return false; }
    default boolean supportsANN()           { return false; }

    record None() implements EncryptionSpec {
        @Override public boolean supportsEquality()      { return true; }
        @Override public boolean supportsRange()         { return true; }
        @Override public boolean supportsKeywordSearch() { return true; }
        @Override public boolean supportsPhraseSearch()  { return true; }
        @Override public boolean supportsSseSearch()     { return true; }
        @Override public boolean supportsANN()           { return true; }
    }
    record Deterministic() implements EncryptionSpec {
        @Override public boolean supportsEquality()      { return true; }
        @Override public boolean supportsKeywordSearch() { return true; }
        @Override public boolean supportsPhraseSearch()  { return true; }
        @Override public boolean supportsSseSearch()     { return true; }
    }
    record OrderPreserving() implements EncryptionSpec {
        @Override public boolean supportsEquality()      { return true; }
        @Override public boolean supportsRange()         { return true; }
    }
    record DistancePreserving() implements EncryptionSpec {
        @Override public boolean supportsANN()           { return true; }
    }
    record Opaque() implements EncryptionSpec {}
}
```

### IndexRegistry validation
```java
// In IndexRegistry, at index creation time:
if (indexDef.type() == IndexType.SECONDARY && !encryption.supportsEquality()) {
    throw new IllegalArgumentException("Field '" + field + "' with " +
        encryption + " encryption does not support secondary index");
}
```

### How indices work on encrypted values
- **DET + FieldIndex**: same ciphertext for same plaintext → standard B-tree/skip-list equality works on ciphertext bytes
- **OPE + FieldIndex**: ciphertext ordering preserved → range scan on ciphertext bytes works correctly
- **DCPE + VectorFieldIndex**: approximate distance preserved → HNSW/IVF graph search on encrypted float[] works with degraded recall
- **DET + LsmInvertedIndex (T1)**: encrypt terms before indexing, encrypt query term before lookup — standard inverted index mechanics
- **DET+OPE + positional index (T2)**: terms encrypted with DET, positions with OPE — phrase check via position comparison on OPE ciphertexts
- **SSE index (T3)**: separate structure — encrypted posting lists keyed by PRF-derived tokens

Known edge cases from [`searchable-encryption-schemes.md#edge-cases-and-gotchas`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md#edge-cases-and-gotchas):
- DET leaks frequency through index — document as security tradeoff
- OPE on positions leaks relative ordering — acceptable for small position domain
- DCPE recall degrades with perturbation strength — tunable parameter

## What This Decision Does NOT Solve
- Prefix/wildcard queries on encrypted text (would need character-level encryption)
- Fuzzy matching on encrypted text (edit distance doesn't work on ciphertexts)
- Cross-field joins on encrypted values from different tables
- Access-pattern leakage in T1/T2 (T3 provides stronger protection)

## Conditions for Revision
This ADR should be re-evaluated if:
- A new EncryptionSpec variant is added that doesn't fit the capability matrix pattern
- Prefix/wildcard support becomes a requirement (would need new encryption family)
- T3 SSE performance is insufficient at scale (may need algorithmic optimization)
- Forward/backward privacy requirements tighten beyond what Dyn2Lev provides

---
*Confirmed by: user deliberation | Date: 2026-03-18*
*Full scoring: [evaluation.md](evaluation.md)*
