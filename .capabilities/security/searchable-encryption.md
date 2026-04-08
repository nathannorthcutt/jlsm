---
title: "Searchable Encryption"
slug: searchable-encryption
domain: security
status: active
type: emergent
tags: ["encryption", "search", "SSE", "OPE", "deterministic-encryption"]
features: []
composes: ["security/field-encryption", "query/secondary-indices"]
spec_refs: []
decision_refs: ["encrypted-index-strategy"]
kb_refs: ["algorithms/encryption"]
depends_on: []
enables: []
---

# Searchable Encryption

Query encrypted data without decryption. Equality search on AES-SIV
encrypted fields via symmetric searchable encryption (SSE) indices. Range
queries on OPE-encrypted integer and bounded-string fields via standard
range indices operating on order-preserving ciphertext.

## What it does

This capability emerges from the composition of field-level encryption and
secondary indices. When a field is encrypted with a deterministic scheme
(AES-SIV) or an order-preserving scheme (OPE), the index structures built
by the secondary indices capability operate directly on ciphertext. Equality
indices match deterministic ciphertext; range indices compare order-preserved
ciphertext. The query layer is unaware of encryption — it delegates to the
index, which delegates to the encryption scheme.

No single feature created this capability. It exists because field-encryption
chose encryption schemes specifically designed to be index-compatible, and
secondary-indices builds index structures that operate on the field values
they're given — whether plaintext or ciphertext.

## Key behaviors

- AES-SIV encrypted fields support equality search via SSE — same plaintext always produces same ciphertext
- OPE encrypted fields support range queries — ciphertext preserves ordering of plaintext values
- AES-GCM encrypted fields are NOT searchable — opaque storage only
- The encrypted-index-strategy ADR defines a 3-tier full-text search capability (keyword, phrase, SSE)
- Index structures are unaware of encryption — they operate on whatever bytes the field contains
- Query results are decrypted after retrieval, not during index traversal

## Related

- **Composes:** security/field-encryption (encryption schemes), query/secondary-indices (index structures)
- **Decisions:** encrypted-index-strategy (SSE + OPE strategy, 3-tier full-text)
- **KB:** algorithms/encryption (searchable encryption research)
- **Deferred work:** encrypted-prefix-wildcard-queries, encrypted-fuzzy-matching, encrypted-cross-field-joins, index-access-pattern-leakage
