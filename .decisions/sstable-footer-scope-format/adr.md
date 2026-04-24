---
problem: "sstable-footer-scope-format"
date: "2026-04-24"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - ".spec/domains/sstable/footer-encryption-scope.md"
  - ".spec/domains/encryption/primitives-lifecycle.md"
---

# ADR — SSTable Footer Scope Format

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| SSTable Block-Level Ciphertext Envelope and Key-ID Signalling | Surveyed production patterns (external registry, per-file prefix, per-block footer); informed candidate enumeration | [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) |
| Three-Level Key Hierarchy | Establishes the `(tenantId, domainId, tableId, dekVersion)` tuple that the footer encodes | [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md) |

---

## Problem

How does the SSTable footer carry `(tenantId, domainId, tableId)` scope
metadata and the DEK version set so the encryption read path can (a)
locate the correct DEK under jlsm's three-tier key hierarchy, and
(b) reject cross-scope reads with `IllegalStateException` **before any
DEK lookup** fires — satisfying `encryption.primitives-lifecycle` R22b
and R23a?

## Constraints That Drove This Decision

- **Cross-scope fast-fail is a hard ordering requirement** (R22b): scope
  comparison must happen before any DEK lookup. Footer layout must make
  scope trivially-accessible from the first post-magic read.
- **Backward compatibility with v5 SSTables**: pre-encryption files on
  disk must remain readable. No online migration. Reader dispatches on
  format magic.
- **Integrity integration**: the v5 footer (per `sstable-end-to-end-integrity`
  ADR) uses per-section CRC32C + fsync ordering + magic-as-commit-marker.
  Any new footer section must participate in this scheme — magic cannot
  be ambiguous about which layout a file uses.
- **Per-field encryption model** (primitives-lifecycle R5–R22): scope
  metadata lives at the SSTable tier; per-field ciphertext continues to
  flow byte-identically across MemTable/WAL/SSTable (R1a).

## Decision

**Chosen approach: v5→v6 format bump with a fixed-position scope section**
([KB source](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md))

New SSTables write footer magic `v6`. The footer is extended with a scope
section appended before the footer-wide CRC32C and magic. Layout:

```
[existing v5 footer fields — sections + per-section checksums]
[scope-length:u32 BE]
[tenantIdUtf8-length:u32 BE][tenantIdUtf8 bytes]
[domainIdUtf8-length:u32 BE][domainIdUtf8 bytes]
[tableIdUtf8-length:u32 BE][tableIdUtf8 bytes]
[dek-version-count:u16 BE][dek-version-1:u32 BE]...[dek-version-N:u32 BE]
[footerChecksum:u32 BE]   ← CRC32C covers everything above
[magic:u64]               ← v6 commit marker
```

A v6 reader verifies magic → computes footer CRC → parses scope section
→ compares declared scope against caller's `Table` handle (via the
catalog API, see dependent ADR `table-handle-scope-exposure`). A
mismatch throws `IllegalStateException` before any DEK resolution.

Existing v5 SSTables keep working via format-version dispatch. SSTables
with `encryption=off` always write v5; v6 is produced only when
encryption is configured for the writing Table.

## Rationale

### Why v5→v6 with fixed-position scope section

- **Fast-fail clarity**: scope at a known location enables a single read
  + compare before any cryptographic work. This is the cleanest possible
  realisation of R22b's ordering requirement.
- **Integrity integration is automatic**: the scope section participates
  in the existing section-CRC32C + magic-commit-marker scheme. No new
  integrity primitive is needed.
- **Format evolution is a well-trodden path**: `SSTableFormat` has
  already evolved v2→v3→v4→v5. A v6 bump is mechanical — one new magic
  constant, one branch in the reader dispatch, one branch in the writer.
- **Self-describing files**: backup/restore and file moves don't need an
  external registry. Every SSTable carries its own scope metadata.
- **Cryptographic defence is already in place**: WD-01's HKDF key
  derivation (primitives-lifecycle R11) binds DEK material to the full
  `(tenantId, domainId, tableId, dekVersion)` tuple. A wrong-scope
  reader cannot derive the right key — decryption fails cryptographically
  regardless of footer bytes. Footer scope + R22b is a
  **fast-fail / clear-error** layer above the cryptographic defence;
  CRC32C coverage is adequate for detecting accidental corruption,
  which is all this layer needs to defend against.

### Why not C2 — v5→v6 with TLV extensions

TLV extensions add a framework (tag registry, length parsing, unknown-tag
policy) to support forward extensibility. We have one concrete extension
need (scope) and no committed second use-case. The codebase's
"don't design for hypothetical futures" rule penalises this — and the
one forward extension we'd nominally want (per-block encryption metadata)
is a full re-architecting that another version bump would be warranted
for anyway.

### Why not C3 — optional extension within v5 (no version bump)

Hard-disqualified: undermines the magic-as-commit-marker invariant from
`sstable-end-to-end-integrity`. A v5 reader encountering a v5 magic
expects a fully-known footer layout; silently-appended bytes create a
two-class reader world and weaken R22b's hard invariant.

### Why not C4 — external path-keyed registry

Doubles the durability surface (registry + manifest + SSTable files must
stay coherent); registry/manifest drift is a new failure mode; breaks
the self-describing-file principle that runs through the existing sstable
specs; backup/restore must carry and coordinate the registry. Per the
KB survey, Cockroach-style external registries carry a dominant failure
mode: "registry corruption = all files undecipherable."

## Implementation Guidance

- **New constant in `SSTableFormat`**: `FOOTER_MAGIC_V6 = 0x<bytes>`
  (distinct from the existing v5 magic). Writer emits v6 only when an
  encryption context is configured on the Table; otherwise emits v5.
- **Scope bytes**: `TenantId.value()`, `DomainId.value()`, and
  `TableId.value()` are UTF-8 strings (records from WD-01's
  `jlsm.encryption` package). Length-prefix each with a 4-byte BE u32.
- **DEK version encoding**: sorted ascending, 4-byte BE u32 each.
  Count prefix is a 2-byte BE u16 (practical ceiling ~16 during rotation
  straddle). Writer asserts count ≤ 65535 at build time.
- **Reader dispatch**: on `open`, read magic first. If v5, skip scope
  section entirely (no scope, decryption is not configured for this
  file). If v6, read footer CRC, parse scope section, compare against
  caller Table-handle scope. On mismatch, throw `IllegalStateException`
  with message naming the expected vs declared scope (no DEK / key
  material leakage).
- **Writer integrity ordering**: scope section bytes are included in
  the footer-wide CRC32C computation. Compute CRC over
  `[existing footer bytes ‖ scope section bytes]`. Preserves the
  existing magic-as-commit-marker invariant.
- **Byte order**: big-endian throughout, consistent with the rest of
  the SSTable format.
- **No natural alignment assumption**: use `ValueLayout.*.withByteAlignment(1)`
  for `MemorySegment` access, matching project convention (see WAL
  encoding notes in project memory).

See [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
for production-system design references (CockroachDB registry, RocksDB
prefix header, DuckDB per-block + footer).

## What This Decision Does NOT Solve

- **Per-block AES-GCM encryption transition** — deferred as a separate
  architectural decision. If that transition is pursued, the footer
  would gain additional fields (DEK handle, algorithm tag, nonce
  strategy, footer MAC) and the per-variant layouts in
  `encryption.primitives-variants` would be fundamentally restructured.
  OPE/DCPE compatibility is a hard constraint on any such transition.
- **Table-handle scope exposure API** — the catalog-side contract for
  deriving `(tenantId, domainId, tableId)` from a `Table` handle is a
  separate ADR (`table-handle-scope-exposure`).
- **Cryptographic tamper detection beyond what HKDF scope binding
  delivers** — the footer CRC32C covers accidental corruption only.
  Malicious on-disk tamper is resisted by the HKDF-derived keys
  (WD-01), not by this ADR.
- **DEK version set size above 16** — current rotation cadence won't
  produce this, but extended rotation-pause catchups could. Writer
  asserts ≤ 65535 at build; if the assertion fires, revisit.

## Conditions for Revision

- Per-block AES-GCM transition is scheduled within 12 months — in that
  case, re-evaluate against C2 (TLV extensions) or a v7 bump that
  accommodates the per-block fields
- Scope identifiers (`TenantId`/`DomainId`/`TableId`) grow beyond ~128B
  typical — footer size budget would need review
- End-to-end-integrity ADR is revised to abandon magic-as-commit-marker
  — changes the invariants this decision relies on

---
*Confirmed by: user deliberation | Date: 2026-04-24*
*Full scoring: [evaluation.md](evaluation.md)*
