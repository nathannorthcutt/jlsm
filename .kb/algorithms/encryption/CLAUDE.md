# Encryption — Category Index
*Topic: algorithms*

Encryption schemes relevant to database and storage systems — field-level
encryption, searchable encryption, and key management patterns. Focus is on
schemes implementable in pure Java for use in jlsm's document model.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [searchable-encryption-schemes.md](searchable-encryption-schemes.md) | Searchable Encryption Schemes | active | DET: O(n) enc, +16B expansion | Field-level encryption with query support |
| [vector-encryption-approaches.md](vector-encryption-approaches.md) | Vector Encryption Approaches | active | DCPE: ~1x plaintext query perf | Encrypted ANN search |
| [deterministic-encryption-performance.md](deterministic-encryption-performance.md) | Deterministic Encryption Performance | active | AES-SIV: 66K->~130K ops/s optimized | Narrowing DET vs AEAD perf gap |
| [ope-width-truncation.md](ope-width-truncation.md) | OPE width truncation | active | critical | adversarial-finding: OPE cap causes data truncation |
| [key-bytes-on-heap.md](key-bytes-on-heap.md) | Key bytes on heap | active | tendency | adversarial-finding: key material persists on heap |
| [encrypt-memory-data.md](encrypt-memory-data.md) | encrypt-memory-data footprint | stable | — | feature-footprint: field-level encryption audit |
| [prefix-fuzzy-searchable-encryption.md](prefix-fuzzy-searchable-encryption.md) | Searchable Encryption for Prefix and Fuzzy Queries | active | Prefix tokenization + DET | Encrypted LIKE and fuzzy matching |
| [index-access-pattern-leakage.md](index-access-pattern-leakage.md) | Index Access Pattern Leakage and Mitigations | active | HKDF per-field keys (low cost) | Understanding and mitigating encrypted index leakage |
| [encrypted-cross-field-joins.md](encrypted-cross-field-joins.md) | Encrypted Cross-Field Join Strategies | active | Same-key DET: zero cost | Joining on encrypted fields, leakage amplification risks |

## Comparison Summary

Two complementary encryption strategies for different field types:
- **Scalar/text fields**: use deterministic encryption (AES-SIV) for equality
  queries or OPE for range queries. Well-understood tradeoffs.
- **Vector fields**: use DCPE (Scale-And-Perturb) for approximate distance
  search, or AES-GCM fallback for no-search strong encryption. PHE/FHE are
  theoretically viable but 1000x+ too slow for practical use.

Both are implementable in pure Java with zero external dependencies.

## Recommended Reading Order
1. Start: [searchable-encryption-schemes.md](searchable-encryption-schemes.md) — DET, OPE, SSE for text/scalar
2. Then: [vector-encryption-approaches.md](vector-encryption-approaches.md) — DCPE and alternatives for vectors

## Recommended Reading Order
1. Start: [searchable-encryption-schemes.md](searchable-encryption-schemes.md) — DET, OPE, SSE for text/scalar
2. Then: [vector-encryption-approaches.md](vector-encryption-approaches.md) — DCPE and alternatives for vectors
3. Then: [prefix-fuzzy-searchable-encryption.md](prefix-fuzzy-searchable-encryption.md) — prefix tokenization, LSH fuzzy
4. Then: [index-access-pattern-leakage.md](index-access-pattern-leakage.md) — leakage taxonomy, mitigations

## Research Gaps
- Homomorphic encryption practical feasibility assessment (likely won't-use but worth documenting)
