---
problem: "aad-canonical-encoding"
date: "2026-04-23"
version: 1
status: "confirmed"
supersedes: null
amends:
  - "kms-integration-model"
depends_on:
  - "three-tier-key-hierarchy"
  - "kms-integration-model"
files:
  - ".spec/domains/encryption/primitives-lifecycle.md"
  - "modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java"
  - "modules/jlsm-core/src/main/java/jlsm/core/io/KmsClient.java"
---

# ADR — AAD Canonical Encoding for Context-Bound Ciphertext Wrapping

## Document Links

| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |
| Prerequisites | [`../three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md), [`../kms-integration-model/adr.md`](../kms-integration-model/adr.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| Three-Level Key Hierarchy | R11 HKDF info encoding precedent (length-prefixed UTF-8) | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |
| SSTable Block-Level Ciphertext Envelope | Parallel AAD-binding use case at block level | [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) |
| DEK Caching Policies (Multi-Tenant) | AAD-as-cache-key consumer of the canonical encoding | [`.kb/systems/security/dek-caching-policies-multi-tenant.md`](../../.kb/systems/security/dek-caching-policies-multi-tenant.md) |

---

## Files Constrained by This Decision

<!-- Key source files this decision affects. Used by /curate to detect drift. -->

- `.spec/domains/encryption/primitives-lifecycle.md` — R80a / R80a-1 are the canonical statement
- `modules/jlsm-core/src/main/java/jlsm/core/io/EncryptionKeyHolder.java` — callers of wrap/unwrap
- `modules/jlsm-core/src/main/java/jlsm/core/io/KmsClient.java` — SPI boundary; implementers must encode AAD identically on wrap and unwrap paths
- Downstream WDs WD-02 / WD-03 / WD-04 / WD-05 in `.work/implement-encryption-lifecycle/` all consume this encoding

## Problem

Define the canonical byte-encoding of `EncryptionContext` (a `Map<String,String>` plus a `Purpose` discriminant) used as Additional Authenticated Data (AAD) when wrapping DEKs under domain KEKs via AES-GCM. The encoding is load-bearing: wrap-time and unwrap-time AAD must be byte-identical or GCM authentication fails. This decision retroactively captures a choice landed inline during WU-2 implementation of the three-tier key hierarchy.

## Constraints That Drove This Decision

- **Byte-exact determinism across Map implementations and JVM versions.** HashMap/LinkedHashMap/TreeMap iteration order varies; AAD encoding must not depend on it. A Map-order-dependent encoding produces non-reproducible wraps and outage-class failures.
- **Wire-format stability across source refactors.** The `Purpose` discriminant must survive enum-constant reorder without silently invalidating previously wrapped DEKs. `ordinal()` fails this; a spec-pinned integer (`code()`) passes.
- **Zero external dependencies.** jlsm-core's encryption path must not pull a JSON/CBOR/Protobuf library for a 200-byte payload that custom length-prefixed TLV handles in ~30 lines of Java.
- **Consistency with R11 HKDF info encoding.** R11 already specifies length-prefixed UTF-8 + 4-byte big-endian lengths for the parallel context-binding problem (HKDF `info`). Choosing the same pattern for AAD gives the codebase one canonical encoding to audit rather than two.

## Decision

**Chosen approach: Length-prefixed TLV (custom, sorted keys, `Purpose.code()` discriminant)**

The canonical byte-encoding of an `EncryptionContext` used as AAD is:

```
AAD := [4-byte-BE Purpose.code()]
    || [4-byte-BE attribute-count]
    || repeated for each (key, value) pair, sorted lexicographically by UTF-8-byte key:
         [4-byte-BE key-utf8-byte-length]
         || [key UTF-8 bytes]
         || [4-byte-BE value-utf8-byte-length]
         || [value UTF-8 bytes]
```

Concrete properties:

- **Discriminant:** 4-byte big-endian `Purpose.code()` (spec-pinned: `domain_kek=1, dek=2, rekey_sentinel=3, health_check=4`). Never `Purpose.ordinal()`.
- **Attribute ordering:** sorted by lexicographic comparison of the UTF-8-encoded key bytes. Achievable via `TreeMap` with the default `String.compareTo` comparator (which orders by UTF-16 code unit, which for well-formed Unicode matches UTF-8 byte-order) — or equivalently by copying into a sorted structure keyed on the UTF-8 byte arrays.
- **Length widths:** all length fields are 4-byte non-negative big-endian integers (matches R11).
- **String encoding:** UTF-8, bytes-as-supplied by caller. No Unicode normalization (NFC/NFD/NFKC/NFKD) inside the encoder. Callers are responsible for identifier stability.
- **Attribute-set contents:** governed by R80a (minimum: `tenantId`, `domainId`, `purpose`) and R80a-1 (DEK purpose adds `tableId`, `dekVersion`). The encoder encodes whatever the caller supplies; spec-level rejection of unknown `purpose` values happens before encoding.

## Rationale

### Why length-prefixed TLV

- **Determinism on all three failure modes.** Length prefixes prevent canonicalization collisions (`{tenant=a,table=bc}` vs `{tenant=ab,table=c}`); sorted keys eliminate Map iteration nondeterminism; `code()` survives enum-constant reorder.
- **Zero external dependencies.** Pure `java.nio.ByteBuffer` + `TreeMap`. The encoder is ~30 lines and directly inspectable.
- **Consistency with R11.** R11 HKDF info uses the same 4-byte-BE-length + UTF-8 pattern for a parallel context-binding problem. The codebase has one canonical pattern rather than two.
- **Diagnosability.** A hex dump of AAD bytes is directly human-readable: lengths followed by UTF-8 text.

### Why not JSON with RFC 8785 (JCS)

- Determinism is outsourced to a 12,000-word canonicalization RFC implemented by a third-party library. Library bugs in Unicode escape or number canonicalization produce silent outage-class auth failures. Violates zero-dep.

### Why not CBOR (RFC 8949 §4.2.1 Deterministic Encoding)

- Requires a CBOR library (first external dep in encryption path) to cover a 200-byte payload that custom TLV handles inline.

### Why not Protobuf with deterministic serialization

- Largest external dep (~1.5 MB protobuf-java runtime); violates zero-dep constraint outright. Google's own docs warn that deterministic serialization is not guaranteed stable across protobuf library versions — directly contradicts the operational constraint of wire-format stability.

### Why sorted keys (not insertion-ordered)

- Insertion-ordered would require every call site to pass a `LinkedHashMap` with a specific canonical key order. A caller accidentally passing a `HashMap` would produce a wrap that cannot be unwrapped. Sorting inside the encoder is a single point of enforcement.

### Why `Purpose.code()` (not `Purpose.ordinal()`)

- `ordinal()` is JVM-defined as source-position in the enum declaration. Moving a constant or inserting one before an existing one silently changes the ordinal of every constant after it — and silently invalidates every previously-wrapped DEK. `code()` is a spec-pinned integer that a code reviewer would reject PR'ing a change to. Spec R80a explicitly forbids `ordinal()` for persistence-bound fields.

### Why UTF-8 bytes-as-supplied (not Unicode NFC normalization)

- Caller owns identifier stability. Silently transforming caller data inside the encoder introduces a hidden contract. If cross-runtime identifier drift occurs, the right fix is at the ingestion boundary, not inside the cryptographic primitive.

## Implementation Guidance

- The encoder belongs in `jlsm-core` near the `KmsClient` SPI boundary (specifically, inside the default implementation path that formats AAD bytes before calling `Cipher.updateAAD(byte[])`).
- The encoder must reject `null` keys or values eagerly with `NullPointerException` per coding-guidelines (not rely on downstream NPE).
- The encoder should validate the `Purpose` discriminant against the closed set from R80a before encoding; an unknown `Purpose` must throw `IllegalArgumentException` before any AAD bytes are produced.
- For `purpose=dek` wraps, callers must supply `tableId` and `dekVersion` per R80a-1; the encoder itself does not check purpose-specific attribute sets (this is a layer-above concern) but downstream unit tests should cover the R80a-1 inclusion rule.
- Sort key must be the UTF-8 byte sequence, not the Java `String`. For typical ASCII-only identifiers (`tenantId`, `domainId`, `tableId`, `dekVersion`, `purpose`), `String.compareTo` and UTF-8-byte comparison coincide. For non-ASCII keys (unlikely in practice since keys are jlsm-owned identifiers) the implementation must sort by UTF-8 bytes explicitly.
- Output is a `byte[]` suitable for `Cipher.updateAAD(byte[])`. Pool allocation is unnecessary (payload is small); plain heap allocation is acceptable.
- Reference test vector for the R80a pattern should be added to the primitives-lifecycle test suite: `{purpose=dek, tenantId=t1, domainId=d1, tableId=tbl1, dekVersion=1}` → known-good hex. This guards against silent encoder regressions across refactors.

## What This Decision Does NOT Solve

- **Attribute-set forward compatibility across jlsm versions** — adding a new required context key between versions is an ecosystem-migration problem, not a wire-format problem. If jlsm v1.N+1 requires an attribute that v1.N didn't supply, unwrap of v1.N-wrapped DEKs fails. A future ADR will address attribute-set evolution if it becomes relevant.
- **Context attribute value normalization** — whether `tenantId` bytes are Unicode NFC-normalized before being passed is caller responsibility. A future ADR may revisit if cross-runtime identifier-drift bugs appear in production.
- **Non-Java consumer interoperability** — the encoding is language-agnostic (the BNF in this ADR is sufficient), but a dedicated "AAD encoding implementation guide" for non-Java clients does not yet exist. A future doc will address if/when such clients appear.
- **Heterogeneous attribute value types** — all values are currently strings (R80a-1 serializes `dekVersion` as decimal UTF-8). If a future `purpose` value needs a binary or numeric attribute, a type-tag extension would be needed, which is a spec amendment.

## Conditions for Revision

This ADR should be re-evaluated if:

- **R11 HKDF info encoding is revised.** R80a should track R11 (coordinated re-wrap migration) so the codebase retains one canonical context-binding pattern.
- **A non-string attribute value becomes required.** Would need a type-tag extension to the TLV format.
- **A non-Java consumer of wrapped DEKs appears** (e.g., cross-language migration tooling, a Python backup restore utility). The encoding itself is language-agnostic, but a dedicated implementation guide would be needed.
- **Cross-runtime identifier-drift bugs are observed** (Unicode normalization disagreements between clients). May require adding an NFC normalization step at the encoder, which itself would be a wire-format change.
- **AWS/GCP KMS client implementations require an AWS-native AAD format** for KMS-side audit that differs from jlsm's canonical form. The SPI currently passes `Map<String,String>` to `KmsClient` implementations, which is sufficient — but if a concrete `KmsClient` plugin requires the raw AAD bytes in a specific format, this ADR would need to clarify whether jlsm's canonical encoding is the sole format or whether `KmsClient` implementations may re-encode.

---

*Confirmed by: retroactive architect capture (landed inline during WU-2, codified in spec v9 R80a / R80a-1) | Date: 2026-04-23*
*Full scoring: [evaluation.md](evaluation.md)*
