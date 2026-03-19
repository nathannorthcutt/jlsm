---
title: "Vector Encryption Approaches"
aliases: ["DCPE", "encrypted ANN", "privacy-preserving vector search", "SAP"]
topic: "algorithms"
category: "encryption"
tags: ["encryption", "vector", "ANN", "nearest-neighbor", "privacy"]
complexity:
  time_build: "O(n*d) — encrypt n vectors of d dimensions"
  time_query: "O(n*d) or O(log n * d) with index — same as plaintext ANN"
  space: "O(n*d) — same dimensionality as plaintext"
research_status: "active"
last_researched: "2026-03-18"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
sources:
  - url: "https://eprint.iacr.org/2021/1666.pdf"
    title: "Approximate Distance-Comparison-Preserving Symmetric Encryption (Bossuat et al.)"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://arxiv.org/html/2503.05850v1"
    title: "Encrypted Vector Similarity Using PHE (March 2025)"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://arxiv.org/pdf/2508.10373"
    title: "Privacy-Preserving Approximate Nearest Neighbor Search on High-Dimensional Data"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://eprint.iacr.org/2024/1600.pdf"
    title: "Pacmann: Efficient Private Approximate Nearest Neighbor Search"
    accessed: "2026-03-18"
    type: "paper"
---

# Vector Encryption Approaches

## summary

Encrypting vectors while preserving similarity search capability is an active
research problem with three practical approaches: (1) **DCPE (Scale-And-Perturb)**
— lightweight symmetric encryption that approximately preserves distance
comparisons, enabling encrypted ANN with standard index structures (HNSW, IVF);
(2) **Partially Homomorphic Encryption (PHE)** — computes inner products on
encrypted vectors server-side but is extremely slow (1-8 ops/sec for 128-512d);
(3) **Decrypt-then-search** — standard AES encryption with no search capability,
requiring full decryption before any distance computation. For a pure Java
library, DCPE (SAP) is the only practical option that preserves search — it's
implementable in ~150 lines with no external dependencies, maintains the same
dimensionality, and allows existing index structures to work on encrypted vectors.

## how-it-works

### approach-comparison

| Approach | Search on Encrypted? | Performance | Security | Pure Java? |
|----------|---------------------|-------------|----------|------------|
| DCPE (SAP) | Yes (approximate) | ~1x plaintext | Approximate distances leak | Yes |
| PHE (Paillier) | Yes (exact inner product) | ~1000x slower | Strong (semantic) | Possible but very slow |
| FHE (CKKS/BFV) | Yes (arbitrary) | ~10000x slower | Strongest | Not practical |
| AES + decrypt | No — must decrypt first | Decrypt: O(d), then search | Strongest for at-rest | Yes (javax.crypto) |

### dcpe-scale-and-perturb (recommended for jlsm)

The SAP scheme encrypts a d-dimensional vector by scaling it uniformly and
adding a random perturbation vector. The key insight: if the scaling factor is
large relative to the perturbation, distance comparisons between encrypted
vectors approximate distance comparisons between plaintext vectors.

**Encryption**: `Enc(p) = s * p + λ_p`
- `s` — secret scaling factor (real number, part of the key)
- `λ_p` — random perturbation vector sampled from a sphere of radius β
- `β` — perturbation bound (controls security/accuracy tradeoff)

**Properties**:
- Encrypted vector has the same dimensionality d as plaintext
- Distance computation on encrypted vectors costs the same as on plaintext
- Existing index structures (HNSW, IVF) work directly on encrypted vectors
- Approximate distance comparisons: `d(Enc(a), Enc(b)) ≈ s * d(a, b)` with
  error bounded by β

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| s (scale) | Secret scaling factor | random in [100, 10000] | Larger = more accurate comparisons |
| β (perturb) | Perturbation sphere radius | [√M, 2M√d] where M = max coord | Larger = more secure, less accurate |
| d (dimensions) | Vector dimensionality | 64–4096 | Higher d = perturbation has less relative effect |

## algorithm-steps

### dcpe-sap-encryption

1. **Key generation**: sample random `s > 0` and perturbation bound `β`
2. **Encrypt vector p**:
   a. Sample random unit vector `u ~ N(0, I_d)`, normalize to `u/||u||`
   b. Sample random radius `r ~ U(0, β)`
   c. Compute perturbation `λ = r * u` (random point in d-ball of radius β)
   d. Output `c = s * p + λ`
3. **Decrypt ciphertext c**: `p = (c - λ) / s`
   Note: decryption requires storing or re-deriving `λ` per vector. In
   practice, store the random seed used to generate `λ` alongside the
   ciphertext (16 bytes overhead per vector).

### dcpe-sap-search

1. **Encrypt query q**: same as encryption above (fresh random perturbation)
2. **Search**: use existing ANN index (HNSW/IVF) with encrypted query against
   encrypted database vectors. Distance computations are approximate.
3. **Post-filter (optional)**: decrypt top-k candidates and recompute exact
   distances for precise ranking.

### decrypt-then-search (fallback)

1. **Encrypt**: AES-GCM on serialized float[] bytes (standard symmetric enc)
2. **Search**: decrypt all candidate vectors, compute distances on plaintext
3. **Use when**: security requirements preclude any distance leakage

## implementation-notes

### pure-java-feasibility

| Approach | Deps | Lines of Code | Query Perf vs Plaintext |
|----------|------|---------------|------------------------|
| DCPE (SAP) | None — java.util.Random, float math | ~150 | ~1x (same distance ops) |
| AES-GCM fallback | javax.crypto | ~50 | Decrypt + compute (no index) |
| PHE (Paillier) | BigInteger math | ~500 | ~1000x slower |

**DCPE is the clear recommendation for jlsm**: zero external deps, trivial
to implement, preserves existing IvfFlat/Hnsw index functionality, same query
performance. The tradeoff is that approximate distance relationships leak —
an attacker observing encrypted vectors can learn which vectors are near each
other, but not the actual coordinates.

### data-structure-requirements

- DCPE: same float[] (or short[] for float16) dimensions. Encrypted vector is
  stored as float[] of same length. Per-vector overhead: 16 bytes (random seed
  for perturbation re-derivation during decrypt).
- AES-GCM: encrypted bytes are opaque. Vector field becomes a byte blob. No
  index compatibility. Size: original + 12 (IV) + 16 (tag) bytes.

### edge-cases-and-gotchas

- **DCPE perturbation vs recall**: small β gives high recall but weak security;
  large β gives strong security but ANN recall degrades. Need to tune per
  use case and document the tradeoff.
- **DCPE key reuse**: all vectors must be encrypted with the same key for
  distance comparisons to be meaningful. Key rotation requires re-encrypting
  all vectors.
- **Float16 vectors**: DCPE works on float[] — float16 vectors must be decoded
  to float32 before encryption, or the SAP scheme must operate in float16
  precision (losing accuracy in the perturbation).
- **Cosine similarity**: SAP preserves Euclidean distance comparisons. For
  cosine similarity, normalize vectors to unit length before encryption.
  The perturbation then operates on the unit sphere.

## complexity-analysis

### encryption

- DCPE: O(d) per vector — scale + add perturbation
- AES-GCM: O(d * sizeof(float)) — encrypt serialized bytes

### query

- DCPE: same as plaintext ANN — O(log n * d) for HNSW, O(√n * d) for IVF
- AES-GCM: O(k * d) for brute-force decrypt-top-k, no index acceleration

### memory-footprint

- DCPE: same as plaintext + 16 bytes per vector (perturbation seed)
- AES-GCM: same as plaintext + 28 bytes per vector (IV + tag)

## tradeoffs

### strengths

- **DCPE**: zero-cost search on encrypted vectors with existing index
  structures. No external deps. Simple implementation. Same dimensionality.
- **AES-GCM fallback**: strongest security — ciphertext reveals nothing about
  the vector. Standard, well-understood primitive.

### weaknesses

- **DCPE**: leaks approximate distances between vectors. Not IND-CPA secure.
  All vectors must share a key. Recall degrades with stronger perturbation.
- **AES-GCM**: no search capability at all. Every query requires decrypting
  candidates. Defeats the purpose of vector indexing.
- **PHE/FHE**: too slow for practical use in a database library (1-8 ops/sec
  for PHE, worse for FHE). Massive ciphertext expansion (1-450MB keys for FHE).

### compared-to-alternatives

- See [searchable-encryption-schemes.md](searchable-encryption-schemes.md) for
  text/scalar field encryption approaches
- PHE/FHE are theoretically superior but 1000-10000x slower — not viable for
  jlsm's performance expectations

## practical-usage

### when-to-use

- **DCPE (SAP)**: when approximate distance leakage is acceptable and vector
  search must work on encrypted data. Suitable for: recommendation engines,
  image similarity, document embeddings where the exact coordinates are
  sensitive but the neighborhood structure is not.
- **AES-GCM fallback**: when vectors contain highly sensitive data (biometric
  templates, medical embeddings) and no distance leakage is tolerable. Search
  requires decrypting candidates — use with small candidate sets or accept
  brute-force cost.

### when-not-to-use

- DCPE on biometric templates where distance relationships themselves are
  sensitive (face embeddings in adversarial settings)
- AES-GCM when vector search is a core requirement — it eliminates index utility

## code-skeleton

```java
// DCPE Scale-And-Perturb for encrypted vector search
class DcpeSapEncryptor {
    private final double scale;       // secret scaling factor
    private final double perturbBound; // β — perturbation radius
    private final SecureRandom rng;

    DcpeSapEncryptor(byte[] key, double perturbBound) {
        this.rng = new SecureRandom(key);
        this.scale = 100.0 + rng.nextDouble() * 9900.0; // s in [100, 10000]
        this.perturbBound = perturbBound;
    }

    float[] encrypt(float[] vector) {
        int d = vector.length;
        float[] encrypted = new float[d];
        // Sample random perturbation in d-ball of radius β
        float[] perturb = randomInBall(d, perturbBound);
        for (int i = 0; i < d; i++) {
            encrypted[i] = (float)(scale * vector[i] + perturb[i]);
        }
        return encrypted;
    }

    // Search: use existing HNSW/IVF index on encrypted float[]
    // Decrypt: requires storing perturbation seed per vector
}
```

## sources

1. [Bossuat et al. — DCPE (2021)](https://eprint.iacr.org/2021/1666.pdf) — foundational DCPE paper defining SAP scheme and RoR security
2. [PHE for Vector Similarity (March 2025)](https://arxiv.org/html/2503.05850v1) — Paillier/Damgård-Jurik/OU evaluation, 1-8 ops/sec at 128-512d
3. [PP-ANNS on High-Dimensional Data](https://arxiv.org/pdf/2508.10373) — DCE + HNSW construction on encrypted vectors, 1000x faster than prior PP-ANNS
4. [Pacmann (2024)](https://eprint.iacr.org/2024/1600.pdf) — efficient private ANN search combining DCPE with graph-based indexes

---
*Researched: 2026-03-18 | Next review: 2026-09-18*
