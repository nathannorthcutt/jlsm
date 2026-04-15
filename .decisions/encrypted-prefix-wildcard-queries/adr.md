---
problem: "encrypted-prefix-wildcard-queries"
date: "2026-04-15"
version: 2
status: "accepted"
decision_refs: ["encrypted-index-strategy", "per-field-key-binding"]
spec_refs: ["F46"]
---

# Encrypted Prefix/Wildcard Queries

## Problem

How should prefix/wildcard queries (`LIKE 'foo%'`) work on DET-encrypted
text fields? Standard DET ciphertexts do not support prefix matching.

## Decision

**Prefix tokenization + DET encryption** -- the `EncryptedPrefixIndexer`
generates all byte-level prefixes of each term (from configurable min
length to full length), encrypts each prefix independently with AES-SIV
using the per-field derived key, and stores them in the existing
`LsmInvertedIndex` composite key format. Prefix queries encrypt the query
prefix and perform an exact lookup.

Key design choices resolved by F46:
- Byte-aligned prefix extraction (R1, R5) -- efficient, same tokenization
  on both index and query sides
- Configurable min/max prefix length to bound storage overhead (R2-R3)
- Reuses existing `LsmInvertedIndex` composite key format (R7) -- no
  structural changes needed
- AES-SIV with field name as associated data prevents cross-field
  correlation (R4)
- Leakage profile: L4 -- prefix frequency distribution exposed at rest,
  strictly more than DET equality (R19-R20)
- Key rotation: incomplete results during rotation window (R16), same
  limitation as all DET-indexed fields (F41 R36)

## Context

Originally deferred during encrypted-index-strategy decision (2026-03-18)
because core encryption features (per-field keys, leakage documentation)
had not been implemented. KB research was complete (prefix-fuzzy-searchable-
encryption.md) recommending this approach. Now resolved with F41 APPROVED
providing per-field HKDF keys and documented leakage profiles.

## Alternatives Considered

- **ORE (Lewi-Wu)**: Enables `LIKE 'foo%'` as range query. Stronger than
  OPE but leaks order and common-prefix lengths between all pairs. ~800-1000
  lines, 4-8x ciphertext expansion. No production pure-Java ORE library.
  Rejected for v1 due to complexity and leakage analysis burden.
- **SSE + prefix index**: Opaque at rest (stronger security than DET) but
  requires separate index structure (~600 lines). Deferred -- may be
  revisited if at-rest leakage from DET prefix tokens becomes a concern.

## Consequences

- `LIKE 'foo%'` queries now work on DET-encrypted fields with ~100 lines
  of new code
- Storage overhead: O(L * N) index entries per field where L = avg term
  length, N = doc count -- bounded by configurable min prefix length
- Leakage is strictly more than DET equality -- per-field HKDF keys and
  documented leakage profiles mitigate but do not eliminate this
- No production encrypted database supports prefix queries -- this is
  novel functionality
