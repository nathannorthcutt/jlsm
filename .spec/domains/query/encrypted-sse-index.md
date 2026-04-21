---
{
  "id": "query.encrypted-sse-index",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query",
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants",
    "encryption.primitives-key-holder"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_extracted_from": "encryption.primitives-variants (R36,R37,R38,R39,R40,R41)"
}
---
# query.encrypted-sse-index — Encrypted SSE Index

## Requirements

R1. The SSE encrypted index must derive two sub-keys from the master key: one for the PRF (search token derivation) and one for posting encryption. Sub-key derivation must use HMAC-SHA256 with distinct labels. The master key array must be zeroed after derivation.

R2. Search token derivation must be deterministic: the same term under the same PRF key must always produce the same token. This is required for the caller to search without revealing the plaintext term to the index after initial token derivation.

R3. Each add operation must increment a per-term state counter and derive a unique storage address from the token and counter value. This provides forward privacy: a new addition cannot be linked to previous additions for the same term by observing storage addresses alone.

R4. The SSE index search method must accept a pre-derived token (not a plaintext term). The index must iterate storage addresses for counter values 0 through N until a miss is encountered, collecting and decrypting all live entries.

R5. The SSE index must support soft deletion by marking entries with a deleted marker byte. Deleted entries must remain in storage (to preserve counter continuity) but must be excluded from search results.

R6. SSE posting encryption must use AES-GCM with the storage address as authenticated associated data. This binds each encrypted posting to its address: moving an encrypted entry to a different address must cause decryption failure.

---

## Design Narrative

### Intent

Extracted from F03 application-layer requirements during the F03 follow-up
split (2026-04-20). Behavior is the F03 originals — see git history of
`.spec/_archive/migration-2026-04-20/encryption/F03-encrypt-memory-data.md`
for the original phrasing and design rationale.
