---
problem: "Canonical byte-encoding of EncryptionContext as AAD when wrapping DEKs under domain KEKs (AES-GCM)"
slug: "aad-canonical-encoding"
captured: "2026-04-23"
status: "draft"
---

# Constraint Profile — aad-canonical-encoding

## Problem Statement

Define the canonical byte-encoding of `EncryptionContext` when it is passed as
Additional Authenticated Data (AAD) to AES-GCM during DEK wrap/unwrap under the
domain KEK. The encoding is load-bearing for on-disk ciphertext interop: wrap
and unwrap must produce byte-identical AAD so GCM tag verification succeeds;
any deviation produces a `KmsPermanentException` / auth failure and is
observationally indistinguishable from ciphertext tampering.

This decision retroactively captures a choice landed inline during WU-2
implementation of the three-tier key hierarchy (spec v9 R80a/R80a-1). Downstream
work definitions WD-02 through WD-05 (block-level envelope, key rotation, DEK
caching, WAL encryption integration) all depend on this encoding being fixed.

## Constraints

### Scale

Multi-tenant production systems. Every wrap/unwrap path (WAL writes, SSTable
block encryption, compaction re-encryption, DEK cache miss) executes the AAD
encoding. Context payloads are small (typically 3–5 string attributes, total
<200 bytes). The encoding routine is not a hotspot — it runs once per
wrap/unwrap, not once per byte. Scale pressure is on **determinism across
deployments**, not throughput.

### Resources

- Pure Java 25, no external dependencies (no JSON/CBOR/Protobuf libraries)
- Must work in constrained container deployments (low `-Xmx`)
- Off-heap friendly: produces a `byte[]` or `MemorySegment` suitable for
  `Cipher.updateAAD(byte[])` or equivalent
- No network/filesystem access during encoding

### Complexity Budget

- Consumers (including third-party `KmsClient` implementations) must be able
  to reproduce the exact AAD bytes from the spec alone. A language-agnostic
  reimplementation must be feasible if non-Java consumers appear (e.g., a
  Python migration tool that needs to unwrap a DEK produced by a jlsm writer).
- Implementation must fit in a single method readable by a maintainer
  unfamiliar with the codebase; complex encoders (CBOR, Protobuf) push this
  burden onto the consumer or require library version pinning.

### Accuracy / Correctness

**Zero tolerance for non-determinism.** An AAD byte mismatch between wrap
time and unwrap time causes GCM authentication failure; the unwrap call
throws and the caller cannot distinguish "the ciphertext was tampered with"
from "the AAD encoder was non-deterministic." Specifically required:

- Same `EncryptionContext` (same keys, same values, same `Purpose`) must
  produce byte-identical AAD regardless of:
  - Map iteration order (HashMap vs LinkedHashMap vs TreeMap)
  - Java version / VM / GC settings
  - Attribute insertion order by the caller
- Canonicalization collisions must be impossible — `{tenant=a, table=bc}` and
  `{tenant=ab, table=c}` must produce different AAD bytes
- The `Purpose` discriminant must survive source-code refactors (moving enum
  constants, inserting a new constant) without silently invalidating previously
  wrapped ciphertext

### Operational

- **Wire-format change requires ciphertext migration.** Any modification to
  the AAD encoding forces re-wrap of every persisted DEK — potentially
  millions per tenant. The encoding must be stable across jlsm versions.
- **Cross-version interoperability.** A DEK wrapped by jlsm v1.0 must be
  unwrappable by jlsm v1.N for all N, as long as the AAD encoding is
  unchanged.
- **Diagnosable failures.** When an AAD mismatch occurs in the field, the
  encoding must be inspectable (printable hex, reproducible from logged
  context) — rules out opaque formats that require a decoder library to
  interpret.

### Fit

- Pure Java 25 with Panama FFM — `MemorySegment` / `ByteBuffer` friendly
- AES-GCM via `javax.crypto.Cipher#updateAAD(byte[])` — consumer is a byte
  array/segment, not an object
- `Purpose` enum already exists in spec v9 R80a with a stable `code()`
  accessor pinned to integer values (`domain_kek=1, dek=2,
  rekey_sentinel=3, health_check=4`)
- Context is `Map<String, String>` per `kms-integration-model` ADR — keys
  and values are already strings
- **Consistency with HKDF `info` encoding (R11)**. R11 already specifies
  length-prefixed UTF-8 + 4-byte big-endian lengths for the parallel
  problem (binding a tuple of identifiers to a crypto operation via HKDF
  info). Choosing the same canonical pattern for AAD reduces maintenance
  burden (one encoding style to audit) and reviewer cognitive load
  (a reviewer familiar with R11 can verify R80a encoding by inspection).
  [Added during constraint falsification — surfaced from
  `.spec/domains/encryption/primitives-lifecycle.md#R11`.]

## Constraint Falsification — 2026-04-23

Sources checked:
- `.spec/domains/encryption/primitives-lifecycle.md` (R11, R80a, R80a-1)
- `.spec/domains/encryption/ciphertext-envelope.md`
- `.decisions/kms-integration-model/adr.md`
- `.kb/systems/security/three-level-key-hierarchy.md`
- `.kb/systems/security/dek-caching-policies-multi-tenant.md`
- `.kb/systems/security/sstable-block-level-ciphertext-envelope.md`

Added to Fit: consistency with R11 HKDF `info` encoding pattern.

Noted as out-of-scope for this ADR (candidates for future deferred ADRs):
- Attribute-set forward-compatibility across jlsm versions (adding a new
  required context key is an ecosystem-migration problem, not a wire-format
  problem).
- Context attribute value normalization (e.g., whether `tenantId` UTF-8 must
  be Unicode NFC-normalized). This ADR takes the position that caller
  identifiers are bytes-as-supplied — normalization responsibility is pushed
  to the caller. May revisit if cross-runtime identifier-drift bugs appear.

## Key Constraints (most narrowing)

1. **Byte-exact determinism across Map implementations and JVM versions** —
   This rules out any encoding that relies on Java's `Map.entrySet()` order,
   `toString()`, `hashCode()`-based ordering, or platform-default string
   encoding. Sorted keys + explicit UTF-8 + length prefixes are mandatory.

2. **Wire-format stability across source refactors** — This rules out
   `Purpose.ordinal()` (which reorders silently if enum constants are moved)
   and any form of Java serialization (which embeds class metadata). The
   discriminant must be a spec-pinned integer whose value is source-code-visible
   and change-reviewable.

3. **Zero-dep, spec-reproducible, single-method encode** — This rules out JSON
   (canonicalization is its own subproblem — RFC 8785 JCS), CBOR (library
   dependency, non-deterministic in general mode), and Protobuf (same). A
   custom TLV-style encoding satisfies this without pulling in a library.

## Unknown / Not Specified

None — the invocation supplied values for all six dimensions. Falsification
(Step 1b) may surface implied constraints from related specs/ADRs.
