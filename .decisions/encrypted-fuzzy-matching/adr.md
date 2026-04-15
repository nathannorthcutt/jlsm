---
problem: "encrypted-fuzzy-matching"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["encrypted-index-strategy", "encrypted-prefix-wildcard-queries"]
spec_refs: ["F47"]
---

# Encrypted Fuzzy Matching

## Problem

How should fuzzy matching (edit distance / approximate string matching) work
on encrypted text fields? Edit distance computation does not work on
ciphertexts.

## Decision

**LSH + Bloom filter with AES-GCM encryption** -- the `EncryptedFuzzyMatcher`
extracts character n-gram shingles from document terms, computes k
locality-sensitive hash signatures per shingle, inserts all signatures into
a per-document `BlockedBloomFilter`, and encrypts the filter with AES-GCM.
Fuzzy queries compute LSH signatures for the query term and check them
against decrypted candidate filters.

Key design choices resolved by F47:
- Character-level n-grams (default bigrams) for edit distance approximation
  (R1-R3)
- HMAC-SHA256 with per-field HKDF-derived sub-keys for LSH (R5-R6)
- Existing `BlockedBloomFilter` reused (R7)
- AES-GCM encryption of filter (opaque at rest, minimal leakage) (R9)
- Threshold formula: `max(queryNgramCount - maxEditDistance * ngramSize + 1, 1)` (R13)
- Leakage profile: L2 -- opaque at rest, access pattern on query only (R22)
- Inherently approximate -- documented false positive/negative sources (R17-R18)

## Context

Originally deferred during encrypted-index-strategy decision (2026-03-18)
because core encryption features had not been implemented and accuracy
characteristics needed empirical validation. KB research was complete
(prefix-fuzzy-searchable-encryption.md). Now resolved with F41 APPROVED
providing per-field keys. Fuzzy matching was evaluated after prefix queries
as planned (shared infrastructure for per-field key derivation).

## Alternatives Considered

- **FHIPE (Function-Hiding Inner Product Encryption)**: Theoretically
  applicable via cosine/Jaccard on n-gram vectors. Requires bilinear
  pairings not available in `javax.crypto`. Not pure-Java viable.
- **Exact edit distance on plaintext**: Requires decrypting all candidates.
  The LSH + Bloom approach acts as a probabilistic filter, reducing the
  number of candidates that need decryption.

## Consequences

- Fuzzy matching on encrypted fields with ~200 lines of new code
- Inherently approximate -- false positives from Bloom FPR + LSH collisions,
  false negatives when edit distance exceeds overlap threshold
- Each document stores one encrypted Bloom filter per fuzzy-matching-enabled
  field -- storage overhead proportional to term count
- No production encrypted database supports fuzzy matching -- this is
  novel functionality
- Accuracy tunable via n-gram size, LSH hash count, and Bloom FPR parameters
