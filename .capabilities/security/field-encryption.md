---
title: "Field-Level Encryption"
slug: field-encryption
domain: security
status: active
type: core
tags: ["encryption", "security", "per-field", "at-rest", "searchable-encryption"]
features:
  - slug: encrypt-memory-data
    role: core
    description: "Per-field encryption with pluggable schemes (AES-SIV, AES-GCM, OPE) configured via schema annotations"
  - slug: extract-core-encryption
    role: extends
    description: "Core encryption primitives extracted to jlsm-core for client-side pre-encryption support"
  - slug: fix-encryption-performance
    role: quality
    description: "Orders-of-magnitude throughput improvement for OPE and AES-SIV operations"
  - slug: ope-type-aware-bounds
    role: quality
    description: "Type-aware OPE domain/range derivation; rejects impractical type+encryption combinations"
composes: []
spec_refs: ["F03"]
decision_refs: ["field-encryption-api-design", "encrypted-index-strategy", "pre-encrypted-document-signaling"]
kb_refs: ["algorithms/encryption", "systems/security"]
depends_on: ["data-management/schema-and-documents"]
enables: ["security/searchable-encryption"]
---

# Field-Level Encryption

Opt-in, per-field encryption that protects sensitive data in memory and on
disk. Each field in the schema can be individually encrypted using a scheme
chosen to match its query requirements: AES-SIV for deterministic equality
search, OPE for range queries on encrypted data, or AES-GCM for opaque
storage with no query capability.

## What it does

Fields are encrypted at the document level before storage and decrypted on
read. Encryption is configured per-field via EncryptionSpec annotations on
the schema's FieldDefinition. The encryption dispatch table routes each
field to its configured scheme, handles key material lifecycle, and supports
pre-encrypted documents from client-side encryption.

## Features

**Core:**
- **encrypt-memory-data** — per-field EncryptionSpec, type-aware dispatch, in-memory protection

**Extends:**
- **extract-core-encryption** — core encryption primitives extracted to jlsm-core for client-side pre-encryption

**Quality:**
- **fix-encryption-performance** — orders-of-magnitude OPE and AES-SIV throughput improvement
- **ope-type-aware-bounds** — type-aware OPE domain/range derivation; rejects impractical combinations

## Key behaviors

- Fields are encrypted per-field, not per-document — each field can use a different scheme
- Encryption scheme is chosen per-field via EncryptionSpec on FieldDefinition
- AES-SIV enables deterministic encryption for equality search on SSE indices
- OPE (Boldyreva scheme) enables range queries on encrypted integer/bounded-string fields
- AES-GCM provides authenticated encryption for fields that don't need searchability
- Key material is zeroed after use via AutoCloseable lifecycle
- Pre-encrypted documents bypass server-side encryption for client-controlled keys
- Encryption is transparent to the query layer — encrypted indices delegate to scheme-specific encryptors

## Related

- **Specs:** F03 (encryption domain)
- **Decisions:** field-encryption-api-design (schema annotation approach), encrypted-index-strategy (SSE + OPE strategy), pre-encrypted-document-signaling (client-side support)
- **KB:** algorithms/encryption (AES-SIV, OPE, GCM research), systems/security (key management patterns)
- **Depends on:** data-management/schema-and-documents (field types and FieldDefinition carry EncryptionSpec)
- **Enables:** security/searchable-encryption
- **Deferred work:** encryption-key-rotation, per-field-key-binding, encrypted-cross-field-joins, encrypted-fuzzy-matching, wal-entry-encryption, unencrypted-to-encrypted-migration
