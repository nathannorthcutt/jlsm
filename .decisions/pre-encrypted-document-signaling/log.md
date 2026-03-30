---
problem: "pre-encrypted-document-signaling"
---

# Decision Log — Pre-Encrypted Document Signaling

## Entry 1 — Decision Confirmed
**Date:** 2026-03-19
**Type:** decision-confirmed
**Agent:** Architect

### Context
Part of the `extract-core-encryption` feature. The feature brief (scoping stage) identified the need for JlsmDocument to signal that its fields are already encrypted, so the serializer skips encryption on write while validating ciphertext structural integrity.

### Candidates Evaluated
1. **Boolean field** (`ofPreEncrypted`) — boolean on JlsmDocument, factory method. Score: 29/30.
2. **Wrapper type** (`PreEncryptedDocument`) — separate class wrapping JlsmDocument. Score: 18/30.
3. **Metadata map** — generic `Map<String, Object>` on JlsmDocument. Score: 15/30.
4. **Factory method** (`preEncrypted`) — boolean on JlsmDocument, named factory. Score: 29/30.

### Decision
Candidate 4 — factory method pattern with `JlsmDocument.preEncrypted(schema, nameValuePairs...)`. Identical to Candidate 1 internally; the factory name `preEncrypted` is more natural than `ofPreEncrypted`.

### Key reasoning
- Zero overhead: boolean field fits in alignment padding, branch prediction trivially handles the common (false) case
- Minimal API surface: one new factory method, no new types
- Best fit with existing architecture: JlsmDocument is final, deeply embedded; wrapper type would require pervasive changes
- Metadata map is architecturally inconsistent with the tight, schema-validated value holder design

## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Items:** per-field-pre-encryption, pre-encrypted-flag-persistence, client-side-encryption-sdk
**Summary:** 3 out-of-scope items promoted to tracked deferred decisions during /curate session.

---
