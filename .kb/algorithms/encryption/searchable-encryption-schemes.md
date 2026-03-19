---
title: "Searchable Encryption Schemes"
aliases: ["SSE", "searchable symmetric encryption", "deterministic encryption", "OPE"]
topic: "algorithms"
category: "encryption"
tags: ["encryption", "search", "database", "field-level", "privacy"]
complexity:
  time_build: "O(n) — index construction over n documents"
  time_query: "O(r) to O(r + log n) — r = result set size"
  space: "O(n) — encrypted index proportional to corpus"
research_status: "active"
last_researched: "2026-03-18"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmSchema.java"
sources:
  - url: "https://eprint.iacr.org/2006/210.pdf"
    title: "Searchable Symmetric Encryption: Improved Definitions and Efficient Constructions (Curtmola et al.)"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://dl.acm.org/doi/10.1145/3617991"
    title: "A Survey on Searchable Symmetric Encryption (ACM Computing Surveys 2023)"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://github.com/encryptedsystems/Clusion"
    title: "Clusion — Java SSE library (Brown University Encrypted Systems Lab)"
    accessed: "2026-03-18"
    type: "repo"
  - url: "https://faculty.cc.gatech.edu/~aboldyre/papers/bclo.pdf"
    title: "Order-Preserving Symmetric Encryption (Boldyreva et al.)"
    accessed: "2026-03-18"
    type: "paper"
  - url: "https://connect2id.com/blog/deterministic-encryption-with-aes-siv"
    title: "Deterministic Encryption with AES-SIV"
    accessed: "2026-03-18"
    type: "blog"
  - url: "https://developers.google.com/tink/deterministic-aead"
    title: "Deterministic AEAD — Google Tink"
    accessed: "2026-03-18"
    type: "docs"
  - url: "https://datatracker.ietf.org/doc/html/rfc5297"
    title: "RFC 5297 — AES-SIV Authenticated Encryption"
    accessed: "2026-03-18"
    type: "docs"
---

# Searchable Encryption Schemes

## summary

Searchable encryption allows queries to execute directly over encrypted data
without decrypting the entire dataset. Three main families exist, each trading
security guarantees for query capability: (1) **deterministic encryption** —
same plaintext always produces same ciphertext, enabling equality queries;
(2) **order-preserving encryption (OPE)** — ciphertext preserves plaintext
ordering, enabling range queries; (3) **searchable symmetric encryption (SSE)**
— uses encrypted inverted indexes for keyword search. For a pure Java library
doing field-level encryption, deterministic encryption (AES-SIV) is the most
practical starting point — it is implementable with `javax.crypto`, requires no
external state, and enables the highest-value query type (equality/index lookup).

## how-it-works

### scheme-families

**Deterministic Encryption (DET)**
Encrypting the same plaintext with the same key always yields the same
ciphertext. The standard construction is AES-SIV (RFC 5297): a synthetic IV
is derived deterministically from the plaintext and optional associated data
via S2V (a PRF based on CMAC), then AES-CTR encrypts with that IV. The IV
doubles as an authentication tag.

**Order-Preserving Encryption (OPE)**
Ciphertext preserves the numerical ordering of plaintext: if `a < b` then
`Enc(a) < Enc(b)`. The Boldyreva et al. scheme uses hypergeometric sampling
to map plaintexts to a larger ciphertext space while preserving order. Stateless
(only needs the key). Stateful variants (mOPE by Popa et al.) achieve ideal
OPE security via an interactive protocol with a server-side tree.

**Searchable Symmetric Encryption (SSE)**
Builds an encrypted inverted index: for each keyword, a list of matching
document identifiers is encrypted and stored. Queries submit a token derived
from the keyword and the key; the server locates and returns matching encrypted
entries. Dynamic SSE (DSSE) supports inserts and deletes.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| DET key size | AES key for SIV mode | 256-bit (AES-256-SIV uses 512-bit split key) | Standard security level |
| OPE domain/range | Plaintext bits → ciphertext bits | 32→64 or 64→128 | Larger range = less leakage |
| SSE index structure | Inverted index backing | HashMap or B-tree | Query performance |

## algorithm-steps

### deterministic-encryption-aes-siv (recommended for jlsm)

1. **Key setup**: generate 512-bit key K = K1 || K2 (two 256-bit AES keys)
2. **S2V (IV derivation)**: compute IV = S2V(K1, AD, plaintext) using
   AES-CMAC chaining over associated data and plaintext
3. **Encrypt**: ciphertext = AES-CTR(K2, IV, plaintext)
4. **Output**: IV || ciphertext (IV serves as both nonce and auth tag)
5. **Decrypt**: recompute S2V over decrypted plaintext, compare to IV for
   authentication. Mismatch = tampering or wrong key.

### order-preserving-encryption-boldyreva

1. **Setup**: define plaintext domain D = [1..M], ciphertext range R = [1..N]
   where N >> M (typically N = M^2 or larger)
2. **Encrypt(key, m)**: use key-seeded PRG to sample from hypergeometric
   distribution HG(N, m, M), mapping m to a point in [1..N] preserving order
3. **Decrypt(key, c)**: binary search using Encrypt to find m such that
   Encrypt(key, m) = c

### sse-index-based (curtmola-sse-2)

1. **Build**: for each keyword w, collect document IDs {id1, id2, ...}
2. **Encrypt index**: for each w, derive token Tw = PRF(K, w), encrypt
   the ID list under Tw, store in dictionary keyed by hash(Tw)
3. **Search(K, w)**: client sends Tw = PRF(K, w) to server, server
   looks up dictionary entry, returns encrypted ID list
4. **Decrypt results**: client decrypts ID list using Tw

## implementation-notes

### pure-java-feasibility

| Scheme | Java Standard Library | External Deps | Complexity |
|--------|----------------------|---------------|------------|
| DET (AES-SIV) | `javax.crypto` AES + CMAC | None — pure JDK | ~200 lines |
| DET (AES-GCM-SIV) | Not in JDK — needs Bouncy Castle | External dep | ~50 lines with BC |
| OPE (Boldyreva) | `java.security.SecureRandom` + BigInteger | None — pure JDK | ~300 lines |
| OPE (mOPE) | Requires interactive protocol + server state | Complex | ~1000+ lines |
| SSE (Curtmola) | `javax.crypto` AES + `ConcurrentHashMap` | None — pure JDK | ~500 lines |

**Recommendation for jlsm**: AES-SIV deterministic encryption is implementable
with zero external dependencies using `javax.crypto.Cipher` (AES/CTR) and
`javax.crypto.Mac` (AES-CMAC). It enables equality queries on encrypted fields
via secondary indices. OPE (Boldyreva) is also pure-Java implementable for
range query support, but with weaker security guarantees.

### data-structure-requirements

- DET: no additional structures — encrypted value is same size + 16 bytes (IV)
- OPE: ciphertext is larger than plaintext (typically 2x bit width)
- SSE: separate encrypted inverted index per searchable field

### edge-cases-and-gotchas

- **DET leaks frequency**: identical plaintexts produce identical ciphertexts.
  An attacker observing encrypted values can tell when two fields have the same
  value. Mitigate with per-field keys or associated data binding.
- **OPE leaks at least half the plaintext bits**: the ROPF (random
  order-preserving function) model inherently reveals approximate plaintext
  position. Not suitable for high-sensitivity numeric fields.
- **SSE index size**: encrypted inverted index adds storage proportional to
  the total keyword-document pairs, not just document count.
- **Key per field vs key per table**: per-field keys prevent cross-field
  correlation but increase key management complexity.

## complexity-analysis

### encryption-decryption

| Scheme | Encrypt | Decrypt | Ciphertext Expansion |
|--------|---------|---------|---------------------|
| DET (AES-SIV) | O(n) — 2 AES passes | O(n) — 1 AES pass + verify | +16 bytes (IV/tag) |
| OPE (Boldyreva) | O(log N) — binary search | O(log N) | plaintext bits → ~2x bits |
| SSE (build) | O(W * D_avg) | N/A | ~2x index size |
| SSE (query) | O(1) token gen | O(r) result decrypt | N/A |

### memory-footprint

- DET: negligible overhead — just the key in memory
- OPE: negligible — stateless, key only
- SSE: encrypted index must fit in memory or be paged (significant for
  large vocabularies)

## tradeoffs

### strengths

- **DET**: simplest to implement, zero external deps, enables equality queries
  and exact-match index lookups. AES-SIV is an RFC standard with clear security
  properties. Authenticated — detects wrong-key decryption.
- **OPE**: enables range queries and sorted iteration on encrypted values.
  Stateless Boldyreva scheme is relatively simple.
- **SSE**: strongest security (only reveals access pattern), supports keyword
  and boolean queries on text.

### weaknesses

- **DET**: leaks equality (frequency analysis possible). Not IND-CPA secure.
  Cannot support range queries or ordering.
- **OPE**: weakest security of the three — leaks approximate value (at least
  half the bits). Vulnerable to inference attacks with known distribution.
  Not recommended for highly sensitive numeric data.
- **SSE**: most complex to implement. Index must be maintained on insert/delete
  (dynamic SSE adds significant complexity). Forward and backward privacy
  (hiding which updates affect which keywords) requires advanced constructions.

### compared-to-alternatives

- **Full Homomorphic Encryption (FHE)**: theoretically enables arbitrary
  computation on encrypted data but is 10^6x slower than plaintext operations.
  Not practical for a database library.
- **Trusted Execution Environments (TEE/SGX)**: hardware-based, not portable,
  not applicable to a pure Java library.
- **Client-side encryption with no search**: simplest but loses all query
  functionality — the brief explicitly rejects this approach.

## practical-usage

### when-to-use

- **DET (AES-SIV)**: field-level encryption where equality queries are needed
  (secondary index lookups, exact-match filters, join keys). Best for: email
  addresses, account IDs, enum-like fields, boolean flags.
- **OPE (Boldyreva)**: encrypted fields needing range queries or sorting
  (timestamps, prices, ages). Accept that approximate value leaks.
- **SSE**: full-text search over encrypted document content. Only if the
  additional index complexity is justified by the query requirements.

### when-not-to-use

- DET on fields with low cardinality (e.g., gender, country) — frequency
  analysis trivially reveals the mapping
- OPE on highly sensitive numeric fields where approximate value leakage is
  unacceptable (salaries, medical readings)
- SSE when the vocabulary is small — leakage profile becomes problematic

## reference-implementations

| Library | Language | URL | Maintenance |
|---------|----------|-----|-------------|
| Clusion | Java | github.com/encryptedsystems/Clusion | Low (academic) |
| Google Tink | Java/Go/C++ | developers.google.com/tink | Active (Google) |
| CryptDB | C++ | css.csail.mit.edu/cryptdb | Archived |
| javax.crypto | Java (JDK) | Built-in | JDK maintained |

## code-skeleton

```java
// Deterministic encryption with AES-SIV (pure javax.crypto)
class AesSivFieldEncryptor {
    private final SecretKey k1; // CMAC key
    private final SecretKey k2; // CTR key

    AesSivFieldEncryptor(byte[] key512bit) {
        // Split 512-bit key into two 256-bit halves
        k1 = new SecretKeySpec(key, 0, 32, "AES");
        k2 = new SecretKeySpec(key, 32, 32, "AES");
    }

    byte[] encrypt(byte[] plaintext, byte[] associatedData) {
        // 1. Derive IV via S2V (AES-CMAC chain)
        byte[] iv = s2v(k1, associatedData, plaintext);
        // 2. Clear bit 31 and 63 of IV for CTR nonce
        iv[8] &= 0x7F; iv[12] &= 0x7F;
        // 3. AES-CTR encrypt
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, k2, new IvParameterSpec(iv));
        byte[] ct = ctr.doFinal(plaintext);
        // 4. Prepend IV (serves as auth tag)
        return concat(iv, ct);
    }

    byte[] decrypt(byte[] sivCiphertext, byte[] associatedData) {
        byte[] iv = Arrays.copyOf(sivCiphertext, 16);
        byte[] ct = Arrays.copyOfRange(sivCiphertext, 16, sivCiphertext.length);
        // 1. AES-CTR decrypt
        iv[8] &= 0x7F; iv[12] &= 0x7F;
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.DECRYPT_MODE, k2, new IvParameterSpec(iv));
        byte[] pt = ctr.doFinal(ct);
        // 2. Verify: recompute S2V and compare to stored IV
        byte[] check = s2v(k1, associatedData, pt);
        if (!MessageDigest.isEqual(check, Arrays.copyOf(sivCiphertext, 16))) {
            throw new SecurityException("Decryption failed — wrong key or tampered");
        }
        return pt;
    }
}
```

## Updates 2026-03-18

### What changed
Added encrypted full-text search capability analysis for inverted index integration.

### Encrypted Full-Text Search via Inverted Index

Three tiers of encrypted full-text search, layered by increasing capability and complexity:

**Tier 1 — DET keyword match (simplest, pure javax.crypto)**
Tokenize text into terms, encrypt each term with AES-SIV (deterministic).
Store encrypted terms in the existing `LsmInvertedIndex` composite key format:
`[4-byte BE term length][encrypted-term-bytes][doc-id-bytes]`.
Search: encrypt the query term with same key, do standard index lookup.
Supports: exact keyword match, boolean AND/OR of keywords.
Does NOT support: prefix, wildcard, phrase, proximity, fuzzy.
Leakage: search pattern (which documents match which encrypted term), frequency.
Implementation: ~50 lines on top of existing LsmInvertedIndex — just encrypt
terms before indexing and before querying.

**Tier 2 — DET with position-aware indexing (phrase support)**
Extend the inverted index entry to include encrypted term positions. Each
posting stores `(doc-id, [position-list])` where positions are encrypted with
OPE (order-preserving) so that position comparisons work on ciphertext.
Phrase query: encrypt each query term (DET), look up postings, then check
that OPE-encrypted positions are consecutive (OPE preserves ordering).
Proximity query: check that position difference ≤ threshold on OPE ciphertexts.
Supports: exact keyword, boolean, phrase ("hello world"), proximity (NEAR/3).
Does NOT support: prefix, wildcard, fuzzy.
Leakage: term frequency, position ordering (from OPE).
Implementation: ~200 lines — extends inverted index posting format, adds
position-aware query evaluation. Requires OPE for positions only (small domain).

**Tier 3 — SSE encrypted inverted index (strongest security)**
Build a separate encrypted index structure per the Curtmola SSE-2 scheme:
for each term, derive a search token `Tw = PRF(K, term)`, encrypt the
document ID list under `Tw`, store in a dictionary keyed by `hash(Tw)`.
The index itself reveals nothing until a search token is submitted.
Dynamic SSE (Dyn2Lev) supports add/delete with forward privacy.
Supports: keyword, boolean, potentially range (with extensions).
Does NOT support: phrase/proximity without additional position structures.
Leakage: access pattern (which entries are returned), search pattern (repeated queries).
Implementation: ~500 lines for static SSE, ~800+ for dynamic. Can use
Clusion library patterns as reference (Java, academic quality).

**Recommendation for jlsm:** Start with Tier 1 (DET keyword match) — it
requires minimal code and composes directly with the existing LsmInvertedIndex.
Add Tier 2 (position-aware) as a follow-on for phrase queries. Defer Tier 3
(full SSE) unless the access-pattern leakage of Tier 1/2 is unacceptable.

### New sources
1. [EPKS — Dynamic Private Keyword Search](https://dl.acm.org/doi/10.1145/2940328) — encrypted inverted index with binary search and dynamic updates
2. [SSE Designs and Challenges (ACM Computing Surveys)](https://dl.acm.org/doi/abs/10.1145/3064005) — comprehensive SSE design survey covering boolean, range, and dynamic schemes
3. [Brown University SSE Blog Series Part 5](https://esl.cs.brown.edu/blog/how-to-search-on-encrypted-data-searchable-symmetric-encryption-part-5/) — practical walkthrough of inverted-index SSE constructions

## sources

1. [Curtmola et al. — SSE Improved Definitions](https://eprint.iacr.org/2006/210.pdf) — foundational SSE paper defining CKA1/CKA2 security
2. [ACM Survey on SSE (2023)](https://dl.acm.org/doi/10.1145/3617991) — comprehensive survey covering static/dynamic SSE, forward/backward privacy
3. [Clusion Java SSE Library](https://github.com/encryptedsystems/Clusion) — academic Java implementation of 8 SSE schemes (Brown University)
4. [Boldyreva et al. — Order-Preserving Encryption](https://faculty.cc.gatech.edu/~aboldyre/papers/bclo.pdf) — foundational OPE paper with hypergeometric construction
5. [RFC 5297 — AES-SIV](https://datatracker.ietf.org/doc/html/rfc5297) — standard for deterministic authenticated encryption
6. [Google Tink — Deterministic AEAD](https://developers.google.com/tink/deterministic-aead) — production-grade deterministic encryption guidance
7. [Connect2id — AES-SIV Blog](https://connect2id.com/blog/deterministic-encryption-with-aes-siv) — practical introduction to deterministic encryption use cases

---
*Researched: 2026-03-18 | Next review: 2026-09-18*
