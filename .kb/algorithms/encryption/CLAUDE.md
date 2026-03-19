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

## Research Gaps
- In-memory key handling patterns for JVM
- Homomorphic encryption practical feasibility assessment (likely won't-use but worth documenting)
