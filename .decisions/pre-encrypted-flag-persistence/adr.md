---
problem: "pre-encrypted-flag-persistence"
date: "2026-03-30"
version: 1
status: "closed"
closed_date: "2026-04-14"
closed_reason: "non-issue"
---

# Pre-Encrypted Flag Persistence — Closed

## Problem
Persistence of the pre-encrypted flag in the serialized binary format for audit or provenance purposes — currently it is a write-side signal only.

## Why Closed
The pre-encrypted flag is intentionally a write-side signal that instructs the serializer to skip encryption. It has no semantic meaning at read time — the reader always decrypts. Persisting the flag in the binary format would:

1. **Add dead metadata**: the deserialization path never checks it, so it would be written but never consumed
2. **Conflict with the client-side encryption SDK pattern**: the KB research on client-side encryption patterns (`.kb/systems/security/client-side-encryption-patterns.md`) shows that production systems (MongoDB CSFLE, AWS DB Encryption SDK) handle pre-encrypted detection via per-field type bytes in the ciphertext wrapper, not via document-level flags. If per-field encryption markers are ever needed, they belong in the `client-side-encryption-sdk` decision, not here
3. **Violate the parent ADR's design intent**: the `pre-encrypted-document-signaling` ADR explicitly chose a boolean field specifically because it is ephemeral — "The pre-encrypted flag is not persisted in the binary format — it is purely a write-side signal"

If audit/provenance tracking is needed in the future, it should be a separate concern (write-audit log, event sourcing) rather than metadata embedded in the document binary format.

## Parent ADR
[pre-encrypted-document-signaling](../pre-encrypted-document-signaling/adr.md)

---
*Closed by: architect agent | Date: 2026-04-14*
*Reason: Non-issue — persisting a write-side flag adds dead metadata with no consumer*
