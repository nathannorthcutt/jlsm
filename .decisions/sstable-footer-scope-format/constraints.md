---
problem: "sstable-footer-scope-format"
slug: "sstable-footer-scope-format"
captured: "2026-04-24"
status: "draft"
---

# Constraint Profile — sstable-footer-scope-format

## Problem Statement

How does an SSTable's footer carry the `(tenantId, domainId, tableId)`
scope metadata + DEK version set so that the encryption read path can
(a) locate the correct DEK unambiguously under the three-tier key
hierarchy (WD-01), and (b) reject cross-scope reads with
`IllegalStateException` before any DEK lookup fires?

Concrete sub-questions:
1. **Format evolution**: v3→v4→...→v5 bump, or optional-extension in v5?
2. **Layout**: Where in the footer does scope live? Fixed-width or
   length-prefixed? Endianness?
3. **Backward compatibility**: How does a missing scope block read? "No
   encryption" / null-scope, vs. "legacy/unreadable"?
4. **Tamper resistance**: Is the scope block signed/CRC-protected on
   disk, or does the pre-decrypt Table-handle comparison alone suffice?
5. **DEK version set**: How is the "set of DEK versions used within this
   SSTable" encoded? Sorted array of 4B BE ints? Bitmap?

## Constraints

### Scale
- Per-SSTable metadata cost must stay small. Footer is read on every
  SSTable open and participates in the block cache warm-up path.
- Tenancy: up to O(10^4) tenants per deployment; each tenant may own
  O(10^3) tables. Scope IDs are UTF-8 strings of variable length
  (`TenantId`, `DomainId`, `TableId` are string-typed per WD-01).
- DEK version set per SSTable: typically 1 (pre-rotation), occasionally
  2 (rotation straddle), bounded in practice by the number of rotations
  since the SSTable was written. Realistic ceiling: 8–16 versions.

### Resources
- Library runs in constrained memory (containers, embedded). Footer
  cannot balloon. Current v5 footer: 112 bytes — adding scope should
  stay within a few hundred additional bytes for typical ID lengths.
- Java NIO / `MemorySegment` reads; zero-copy preferred.

### Complexity Budget
- Existing format evolution pattern (v2→v3→v4→v5) is well-established:
  `SSTableFormat.java` carries layout constants; writer/reader switch
  on magic + version. Adding v6 is mechanical.
- Backward-compat matters: pre-encryption SSTables on disk today must
  remain readable. "Encryption off" is a valid configuration.
- Small team; no dedicated infra for cryptographic key management
  beyond the three-tier hierarchy WD-01 just shipped.

### Accuracy / Correctness
- **Hard invariant**: cross-scope reads MUST be rejected before any DEK
  lookup. `encryption.primitives-lifecycle` R22b codifies this — the
  reader materialises expected scope from the `Table` handle (via
  catalog) and compares against the SSTable's declared scope.
- **No silent wrong-key decryption.** A mis-routed SSTable producing
  decryptable plaintext under a different tenant's DEK is a security
  incident. The comparison is the primary defence; tamper resistance
  on the scope bytes is a defence-in-depth question.
- **Round-trip byte-identity** of per-field ciphertext across tiers
  (MemTable → WAL → SSTable) must survive the footer change — this is
  canonical per `encryption.ciphertext-envelope` R1a.

### Operational
- Footer read latency: footer is read on SSTable open, typically once per
  file, not per query. Sub-millisecond expectation, bounded by disk I/O.
- Compaction rewrites: output SSTable declares a **single** DEK version
  per `encryption.primitives-lifecycle` R23a/R25b. Must not require a
  re-wrap of the footer during the hot compaction path.
- Format upgrade: no online migration. Existing SSTables stay at v5;
  new writes use v6. Readers must dispatch on version.

### Fit
- Java 25, JPMS, `MemorySegment` + `ValueLayout` for footer layout.
- Writer/reader lives in `jlsm-core` (`jlsm.sstable` + `jlsm.sstable.internal`).
- Integrates with `Table` catalog in `jlsm-engine` for scope derivation
  (separate ADR: `table-handle-scope-exposure`).
- Scope types (`TenantId`, `DomainId`, `TableId`) are already-defined
  records from WD-01 in `jlsm.encryption`.

## Constraint Falsification — 2026-04-24

**Checked sources**:
- `.spec/domains/encryption/primitives-lifecycle.md` (R22b, R23a, R24)
- `.spec/domains/sstable/end-to-end-integrity.md`
- `.decisions/sstable-end-to-end-integrity/adr.md`
- `.decisions/sstable-block-compression-format/adr.md`
- `.kb/systems/security/CLAUDE.md` (category index)

**Implied constraint found**:
- **Integrity integration** — `sstable-end-to-end-integrity` ADR establishes
  v5 section-CRC32C + fsync ordering + magic-as-commit-marker. Any new
  footer section must participate. Scope bytes cannot be added without
  being covered by a section CRC32C. This converts tamper-resistance
  "CRC on scope" from a choice to a minimum requirement.

## Key Constraints (most narrowing)

1. **Cross-scope read rejection is hard** — design must make the scope
   comparison the first operation after footer load, not buried in the
   DEK resolution path. Determines footer layout (scope must be
   plaintext, fixed/known offset).
2. **Backward compatibility with v5** — existing SSTables must open
   under the new reader. Forces version dispatch; forbids breaking
   changes to v5 layout.
3. **Tamper resistance posture** — the decision between "signed
   scope bytes" vs. "Table-handle comparison is sufficient" shapes
   whether the footer grows by scope bytes alone or scope + signature.

## Unknown / Not Specified

None materially — constraints are well-bounded by the WD-01 artifacts and
existing ADR precedents.
