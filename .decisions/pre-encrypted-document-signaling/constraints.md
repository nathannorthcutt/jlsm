---
problem: "pre-encrypted-document-signaling"
date: "2026-03-19"
version: 1
---

# Constraint Profile — Pre-Encrypted Document Signaling

## Problem Statement
How should JlsmDocument signal that its fields are already encrypted — boolean flag, wrapper type, or metadata — and how does this flow through the serialization pipeline?

## Six Constraint Dimensions

### 1. Scale
- Target: millions of documents per second on the serialize path
- The signal mechanism must add zero or near-zero per-document overhead on the unencrypted path (the common case)
- Pre-encrypted path should be faster than encrypt-on-write because it skips crypto entirely (validation only)

### 2. Resources
- Pure Java 25 library with no external dependencies
- JPMS modular: JlsmDocument is in the exported `jlsm.table` package
- JlsmDocument is currently a final class (not a record due to mutable-like internals with package-private value access)
- Must not increase the per-document memory footprint for the overwhelmingly common unencrypted case

### 3. Complexity Budget
- Low — this is a narrow, well-scoped feature. The mechanism should be immediately obvious to API consumers
- JlsmDocument's existing API surface is small: one static factory `of(schema, nameValuePairs...)`, typed getters, JSON/YAML serialization
- Serializer integration should be a single branch (if pre-encrypted, skip encrypt + validate; else encrypt as before)

### 4. Accuracy / Correctness
- All-or-nothing semantics: if the signal is set, ALL encrypted fields must already be encrypted. No partial pre-encryption
- Structural validation on pre-encrypted write: ciphertext length must match what the EncryptionSpec would produce
- Read/decrypt path unchanged — always decrypts regardless of how the document was encrypted
- The signal is a trust assertion: the serializer validates structure but does not trial-decrypt to verify content

### 5. Operational
- Backward compatible: existing `JlsmDocument.of(schema, ...)` must continue to work with no changes
- Pre-encrypted flag set on a schema with zero encrypted fields is a no-op (vacuously true), not an error
- Error cases must produce descriptive exceptions: wrong ciphertext length, null bytes on a required-encrypted field

### 6. Fit
- Must compose with existing architecture: JlsmSchema (immutable), FieldDefinition (record with EncryptionSpec), DocumentSerializer (dispatch table pattern), FieldEncryptionDispatch
- DocumentAccess internal accessor pattern must still work (serializer accesses values via `doc.values()`)
- The signal must survive the serializer's perspective — it needs to know pre-encrypted status when `serialize()` is called
- Must not force changes to the deserialization path (the reader does not need to know how the document was encrypted)

## Hard Constraints (non-negotiable)
- No external dependencies
- Zero overhead on the unencrypted path
- All-or-nothing per-document semantics
- Backward compatible with existing JlsmDocument.of() API

## Soft Constraints (prefer but can trade off)
- Type safety (compile-time prevention of misuse) — preferred but not essential
- Minimal API surface increase — prefer one method/flag over multiple new types
- No binary format change needed — the serialized bytes should be identical whether the document was pre-encrypted or encrypted by the serializer
