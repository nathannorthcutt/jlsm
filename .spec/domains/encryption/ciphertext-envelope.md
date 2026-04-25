---
{
  "id": "encryption.ciphertext-envelope",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "per-field-key-binding",
    "dek-scoping-granularity",
    "three-tier-key-hierarchy"
  ],
  "kb_refs": [
    "systems/security/three-level-key-hierarchy",
    "systems/security/encryption-key-rotation-patterns",
    "systems/security/sstable-block-level-ciphertext-envelope"
  ],
  "open_obligations": [],
  "_extracted_from": "encryption.primitives-lifecycle (R22, R22a)"
}
---

# encryption.ciphertext-envelope — Per-Field Ciphertext Envelope Format

## Requirements

### On-disk byte layout per variant

R1. Every encrypted field value persisted to MemTable, WAL, or SSTable must begin with a 4-byte big-endian plaintext DEK version tag, followed by variant-specific bytes. The version tag alone does not carry tenant or domain identity; those are resolved from the containing SSTable's footer metadata by the reader (see `encryption.primitives-lifecycle` R22b and R23a). The concrete layouts are:

- **Opaque (AES-GCM)** — `[4B DEK version | 12B nonce | ciphertext | 16B GCM tag]`. Total overhead 32 bytes.
- **Deterministic (AES-SIV)** — `[4B DEK version | 16B synthetic IV | ciphertext]`. Total overhead 20 bytes. The S2V synthetic IV provides inline authentication per RFC 5297.
- **OrderPreserving (Boldyreva OPE)** — `[4B DEK version | 1B length prefix | 8B OPE ciphertext long | 16B detached HMAC-SHA256 tag]`. Total 29 bytes. The 16-byte detached tag is computed per `encryption.primitives-variants` R78 and binds the OPE ciphertext to the UTF-8 field name and the DEK version. MAC verification must run before the OPE inverse.
- **DistancePreserving (DCPE/SAP)** — `[4B DEK version | 8B perturbation seed | N*4B encrypted floats | 16B detached HMAC-SHA256 tag]` where N is the vector dimension. Total `8 + N*4 + 20` bytes (4 version + 8 seed + 4N ciphertext + 16 tag). The 16-byte detached tag is computed per `encryption.primitives-variants` R79 and binds the seed and encrypted vector to the UTF-8 field name and the DEK version. MAC verification must run before the DCPE inverse.

R1a. The layout is uniform across storage tiers: the identical byte sequence that lives in a MemTable entry must flow unchanged through the WAL and land byte-identical in an SSTable. No storage tier wraps or rewraps the per-field envelope; the DEK used at ingress is the DEK whose version appears in the 4-byte tag throughout the record's lifetime until compaction-driven re-encryption changes it.

R1b. Each variant's total-byte formula is fixed except for ciphertext length in the Opaque variant and vector dimension N in the DistancePreserving variant. Writers must produce the exact byte count implied by the layout for the encrypted value; readers must reject any stored blob whose byte count is inconsistent with the variant's formula and the recorded length (where length is implicit from the containing field's framing in the record envelope).

R1c. Multi-byte integer fields (the 4-byte DEK version tag, the 4-byte encrypted-float lanes of DCPE) must be encoded big-endian. The 16-byte synthetic IV / GCM tag / HMAC tag are opaque byte sequences and are written verbatim in the order specified by the underlying primitive.

### Version tag validity

R2. The 4-byte DEK version tag must contain a positive integer (1 or greater) when interpreted as a signed big-endian int. If the reader encounters a version tag of 0 or a negative value, it must throw IOException indicating corrupt ciphertext. This applies to both normal reads and compaction re-encryption reads.

R2a. The version-0 / negative check must be performed as part of the same lookup path as the key-registry hash map check (see `encryption.primitives-lifecycle` R64 — the wait-free immutable-map lookup). The implementation must not branch to a separate error path before performing the map lookup. Version 0 is never inserted into the map, so both version-0 and version-not-found follow the same code path, preserving the constant-time property of R64.

R2b. A ciphertext whose 4-byte version tag is valid (positive integer) but whose value does not match any DEK version in the registry for the SSTable's resolved `(tenantId, domainId, tableId)` scope must cause decryption to throw IllegalStateException with a message identifying the missing version number and the scope. The error message must not reveal key material or partial key bytes.

### Consumer interface

R3. Downstream specs that consume this envelope (notably `serialization.encrypted-field-serialization`, `sstable.*`, `wal.encryption`) must reference R1 and R2 normatively and must not redefine any byte position or variant-total-length independently. If a consumer needs additional per-record framing (e.g., field length prefix in a document envelope), that framing sits outside the encrypted envelope's 4B-version-tag-prefixed bytes.

R3a. Pre-encrypted field values supplied by callers (per `encryption.primitives-lifecycle` R5–R5c) must conform to R1 at the byte level: the caller writes R1's exact layout, with the caller-generated DEK version in the 4-byte prefix. The library verifies the version tag against the registry (R5c) but does not re-check the internal layout beyond the version position.

### Scope of this spec

R4. This spec defines ONLY the per-field wire format and version-tag validity. The following concerns are out of scope and are canonically specified elsewhere:

- Reader scope validation (SSTable footer-derived `(tenantId, domainId, tableId)` vs caller's `Table` handle): `encryption.primitives-lifecycle` R22b.
- SSTable footer metadata (per-file scope identifier, DEK version set): `encryption.primitives-lifecycle` R23a.
- Key derivation (HKDF) producing the per-field key used by the encryption primitives: `encryption.primitives-lifecycle` R9–R16c.
- Primitive algorithms and MAC construction (how OPE ciphertext or DCPE seed is computed): `encryption.primitives-variants`.
- Rotation semantics, compaction-driven re-encryption, registry lifecycle: `encryption.primitives-lifecycle`.

---

## Design Narrative

### Intent

This spec is the canonical wire-format contract for per-field ciphertext. It is extracted from `encryption.primitives-lifecycle` R22 and R22a to allow downstream domains (`sstable`, `wal`, `serialization`, `query`) to depend on a stable, narrowly-scoped interface without importing the broader lifecycle (rotation, registry sharding, failure state machine) they do not need.

### What this spec does NOT cover

- How DEKs are derived, wrapped, or rotated — see `encryption.primitives-lifecycle`.
- How the primitive itself computes ciphertext/tag — see `encryption.primitives-variants`.
- Per-SSTable scope checks or footer layout — see `encryption.primitives-lifecycle` R22b, R23a.
- Pre-encrypted-value signalling (the pre-encrypted bitset) — see `encryption.primitives-lifecycle` R1–R8.

### Relationship to `encryption.primitives-lifecycle`

This spec is a **normative companion** to `encryption.primitives-lifecycle`. primitives-lifecycle R22 and R22a now cross-reference this spec. The wire-format invariants are canonically defined here and inherit downstream via the `requires` graph.

### Relationship to `serialization.encrypted-field-serialization`

That spec governs **how** the document serializer applies the format (when to encrypt, where in the serialize/deserialize flow). This spec governs **what** the format is. A consumer that needs both should import both.

### Verification notes

- Existing implementation annotations (`@spec F41.R22` across 14+ sites) remain valid: F41.R22 still exists in `encryption.primitives-lifecycle` as a normative pointer to R1 of this spec. Adding an `@spec encryption.ciphertext-envelope.R1` annotation alongside `F41.R22` is a recommended but optional improvement; the existing annotations are not invalidated.
- The v5/v6/v7 edits to F41.R22 (OPE/DCPE detached MAC, scope resolution) are mechanically reproduced in this spec's R1 and the cross-references. No behavioral change.

### Version history

**v1 — 2026-04-23 — initial extraction.** Created to satisfy WD-02's "Interface-contract spec published for cross-domain consumption" acceptance criterion within the `implement-encryption-lifecycle` work group. Content is a 1:1 relocation of `encryption.primitives-lifecycle` R22 and R22a byte-format and version-tag-validity text, with the cross-scope and footer-metadata clauses left in primitives-lifecycle as lifecycle concerns.

### Verified: v1 — 2026-04-24 (WD-02 annotation pass)

| Req | Verdict | Evidence |
|-----|---------|----------|
| R1 | **PARTIAL** | Variant body bytes produced by `AesGcmEncryptor`, `AesSivEncryptor`, `BoldyrevaOpeEncryptor` (via `FieldEncryptionDispatch`), `DcpeSapEncryptor`. **The 4-byte plaintext DEK version tag prefix is NOT yet produced** — current encryptors emit variant-specific bytes only. Spec R1's full envelope (`[4B DEK version | variant bytes]`) is not realised on the wire. |
| R1a | PARTIAL (emergent) | No explicit enforcement site — property emerges from caller byte-identity; `DocumentSerializerPreEncryptedTest` pre-encrypted round-trips exercise indirectly. No cross-tier (MemTable→WAL→SSTable) integration test pins byte identity. |
| R1b | SATISFIED | Writer byte-count + reader length-check enforced in `AesGcmEncryptor`, `AesSivEncryptor`, `DcpeSapEncryptor.fromBlob`, `FieldEncryptionDispatch.opeDecryptTyped`, `CiphertextValidator.validate`. Tests: `AesGcmEncryptorTest`, `AesSivEncryptorTest`, `DcpeSapEncryptorTest::blob_wrongLengthRejected`, `CiphertextValidatorTest`. |
| R1c | SATISFIED (partial scope) | Big-endian explicit in DCPE seed+float-lane writes (`DcpeSapEncryptor.toBlob`/`fromBlob`), OPE 8B long BE in `FieldEncryptionDispatch.opeEncryptTyped`. **The 4B DEK version tag is not yet written** because it is not implemented. Test gap: no hex-literal byte-pattern test pins BE encoding. |
| R2  | **VIOLATED / NOT IMPLEMENTED** | No envelope reader parses a 4-byte DEK version tag; no IOException is thrown for 0/negative version because the prefix does not exist on the wire. |
| R2a | **VIOLATED / NOT IMPLEMENTED** | No wait-free R64 immutable-map lookup on version is wired to envelope read path. |
| R2b | **VIOLATED / NOT IMPLEMENTED** | `EncryptionKeyHolder.resolveDek` throws `DekNotFoundException` but is not invoked from an envelope read site. |
| R3  | UNTESTABLE | Cross-spec documentation requirement (consumer specs must reference normatively). |
| R3a | SATISFIED | `CiphertextValidator.validate` runs at pre-encrypted ingress; tests in `CiphertextValidatorTest` (6 methods) + `DocumentSerializerPreEncryptedTest` (3 methods). |
| R4  | UNTESTABLE | Scope exclusion declaration (pure documentation). |

**Overall: PASS_WITH_NOTES** — the spec content is correct and the existing per-field envelope body layer is mostly implemented (R1b, R1c for variant bodies, R3a). The **4-byte DEK version tag prefix is unimplemented**, rendering R1 PARTIAL and R2/R2a/R2b VIOLATED. This is a wire-format change owned by WD-02's implementation stage (`/work-start`) — the current annotation pass verified the spec text against existing code without modifying the wire format.

Annotations: 35 (up from 11). Impl+test coverage now spans R1, R1a, R1b, R1c, R3a.

**Obligations opened:**
- `OB-ciphertext-envelope-01`: implement the 4-byte big-endian DEK version tag prefix in every variant's writer output (R1). Reader path must parse it (R2), apply wait-free R64-compatible lookup (R2a), and throw `IllegalStateException` with scope-identifying message on registry miss (R2b). This is WD-02 implementation-stage work — the encryptor pipeline's byte format changes + `CiphertextValidator` length formulas update (OPE 25→29, etc.).
- `OB-ciphertext-envelope-02`: add cross-tier byte-identity integration test for R1a (MemTable entry bytes === WAL record bytes === SSTable persisted bytes).
- `OB-ciphertext-envelope-03`: add hex-literal byte-pattern test for R1c pinning DCPE seed + float-lane BE encoding against a known vector.

State unchanged: spec remains APPROVED v1. Obligations track the implementation gap, not a spec defect.
