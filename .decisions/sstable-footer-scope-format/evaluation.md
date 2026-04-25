---
problem: "sstable-footer-scope-format"
evaluated: "2026-04-24"
candidates:
  - path: ".kb/systems/security/sstable-block-level-ciphertext-envelope.md"
    name: "C1 — v5→v6 fixed-position scope section"
  - path: ".kb/systems/security/sstable-block-level-ciphertext-envelope.md"
    name: "C2 — v5→v6 TLV extensions section"
  - path: ".kb/systems/security/sstable-block-level-ciphertext-envelope.md"
    name: "C3 — Optional extension within v5 (no version bump)"
  - path: ".kb/systems/security/sstable-block-level-ciphertext-envelope.md"
    name: "C4 — External path-keyed registry"
constraint_weights:
  scale: 2
  resources: 2
  complexity: 3
  accuracy: 3
  operational: 2
  fit: 2
---

# Evaluation — sstable-footer-scope-format

## References
- Constraints: [constraints.md](constraints.md)
- Primary KB source: [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
- Precedent ADR: [`.decisions/sstable-end-to-end-integrity/adr.md`](../sstable-end-to-end-integrity/adr.md)

## KB coverage caveat

The primary KB entry (`sstable-block-level-ciphertext-envelope`) envisions a
**per-block AES-GCM** encryption strategy for the whole SSTable. jlsm's current
spec-mandated design is **per-field** (primitives-lifecycle R5–R22). The KB's
"jlsm-design-sketch" section is forward-looking research, not a description of
the current architecture. This ADR's candidates are therefore scored against
production-system scope-signalling patterns (Cockroach registry, RocksDB
prefix header, DuckDB per-file footer) with the jlsm-specific constraint of
per-field encryption.

## Constraint Summary

The decision must (a) let the reader compare declared SSTable scope vs.
caller Table-handle scope before any DEK lookup, (b) participate in the
existing v5 section-CRC32C integrity scheme, (c) preserve backward-compat
with existing v5 SSTables that carry no scope metadata (pre-encryption),
and (d) keep the footer small enough not to regress SSTable-open latency.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Footer size matters but per-SSTable, not per-query |
| Resources | 2 | Few-hundred-byte growth is tolerable |
| Complexity | 3 | Format evolution is a recurring cost; simplicity compounds |
| Accuracy | 3 | Cross-scope isolation is a hard security invariant |
| Operational | 2 | Footer read is one-shot per file; no online migration |
| Fit | 2 | Existing v5 evolution pattern is straightforward to extend |

---

## Candidate C1 — v5→v6 fixed-position scope section

**KB source:** [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
**Relevant sections read:** `#how-it-works`, `#production-systems-compared`, `#edge-cases-and-gotchas`

Scope section appended after existing v5 footer fields, before the
footer-wide CRC32C and magic. New fields at known offsets:
`[scope-length:u32 BE][tenantId bytes][domainId bytes][tableId bytes]
[dek-version-count:u16 BE][dek-version-1:u32 BE]...[dek-version-N:u32 BE]`
Format version magic bumps v5 → v6. Old readers fail on magic check.

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 5 | 10 | O(60B) overhead for typical IDs + 8 versions; negligible vs 112B v5 footer |
| Resources | 2 | 4 | 8 | Length-prefixed fields → offsets computed not literal-fixed, but parse is a bounded loop with zero-allocation if careful |
| | | | | **Would be a 2 if:** deployments adopt path-style IDs averaging >400B each (not realistic for tenant/domain/table identifiers) |
| Complexity | 3 | 4 | 12 | Mechanical extension of v2→v3→v4→v5 pattern; one new magic; writer/reader dispatch stays simple |
| Accuracy | 3 | 5 | 15 | Scope at known offset enables first-op fast-fail after magic verify; clean R22b ordering compliance |
| | | | | **Would be a 2 if:** R22b were the primary cryptographic defence (it is not — HKDF scope binding from primitives-lifecycle R11 is). C1's CRC32C covers accidental corruption only. |
| Operational | 2 | 5 | 10 | No online migration; v5 stays v5, new writes are v6; reader dispatches on magic |
| Fit | 2 | 4 | 8 | Extends established SSTableFormat v5 constants; no new abstractions |
| **Total** | | | **63** | |

**Hard disqualifiers:** none.

**Key strengths:**
- Known-offset scope is the cleanest possible fast-fail comparison point
- Mirrors RocksDB/DuckDB "self-describing file" pattern from the KB
- v5/v6 reader dispatch already exists as a pattern

**Key weaknesses:**
- Adds a new magic number — future footer changes still require another
  version bump. No forward-extension mechanism.
- CRC32C on scope is adequate for corruption detection but is NOT
  cryptographic tamper resistance. Primary defence against wrong-key
  decryption is the HKDF scope binding already delivered by WD-01
  (primitives-lifecycle R11) — footer scope comparison is a fast-fail
  / clear-error mechanism above that.

---

## Candidate C2 — v5→v6 bump with TLV extensions section

**KB source:** [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
**Relevant sections read:** `#design-space-axes`, `#implementation-notes`, `#edge-cases-and-gotchas`

Footer gets a "footer extensions" section: `[ext-count:u16][ext-1-tag:u16]
[ext-1-length:u32][ext-1-bytes]...[ext-N-bytes][ext-block-crc32c]`.
Scope is tag 0x0001, DEK version set is tag 0x0002. Future extensions
(e.g., compression profile, per-table stats) get tags 0x0003+ without
another magic bump.

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 4 | 8 | Extra 6 bytes per extension for tag+length header; still negligible |
| Resources | 2 | 4 | 8 | TLV parse path is a small loop; still bounded and zero-alloc if careful |
| Complexity | 3 | 3 | 9 | TLV adds parse-loop, tag-registry concept, "unknown tag" handling semantics |
| | | | | **Would be a 2 if:** unknown-tag policy forced registry-awareness in the reader (opt-in vs skip) |
| Accuracy | 3 | 4 | 12 | Scope still early in the read path (first known-tag after magic); one extra dispatch |
| | | | | **Would be a 2 if:** a reader implemented unknown-tag-skip incorrectly, allowing a malformed scope extension to appear absent and bypass R22b (mitigated by making scope mandatory in v6) |
| Operational | 2 | 5 | 10 | Same rollout story as C1; plus future extensions cheap |
| Fit | 2 | 4 | 8 | Matches industry practice (Parquet file-level metadata extensions, Protobuf); slight new abstraction |
| **Total** | | | **55** | |

**Hard disqualifiers:** none.

**Key strengths:**
- Forward extensibility — future footer growth adds a tag, not a version
- Mirrors Parquet/Arrow metadata model; battle-tested

**Key weaknesses:**
- Speculative generality: we have one forward-looking extension need
  (maybe per-block encryption metadata if we adopt that model), and no
  concrete second use-case. Per the codebase's "don't design for
  hypothetical futures" rule, a TLV framework for one current extension
  is over-engineering.
- Introduces unknown-tag policy decision (skip vs fail) — another small
  ADR in its own right.

---

## Candidate C3 — Optional extension within v5 (no version bump)

**KB source:** [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
**Relevant sections read:** `#when-not-to-use`, `#edge-cases-and-gotchas`

Keep magic v5; append scope section after existing v5 footer. Readers
detect presence by checking if file-size > baseline-v5-footer-size.
Old readers miss the extension silently.

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 5 | 10 | No format change |
| Resources | 2 | 4 | 8 | Similar reader cost to C1 |
| Complexity | 3 | 2 | 6 | Breaks the "magic = commit marker" semantics from the v5 integrity ADR; "which version is this file really?" becomes ambiguous |
| Accuracy | 3 | 2 | 6 | Silent-skip on old readers creates a vulnerability: an encrypted SSTable opened by a legacy path sees no scope → no comparison → decryption may proceed. Cross-scope isolation becomes dependent on reader version discipline. |
| Operational | 2 | 3 | 6 | No magic change simplifies rollout but complicates diagnostics |
| Fit | 2 | 2 | 4 | Contradicts the existing v2→v5 format-version pattern |
| **Total** | | | **40** | |

**Hard disqualifiers:** violates existing v5 integrity semantics from
[`.decisions/sstable-end-to-end-integrity/adr.md`](../sstable-end-to-end-integrity/adr.md) — magic is the commit marker, implying the footer layout is fully known from
magic alone.

**Key strengths:** none that justify the semantic damage.

**Key weaknesses:**
- Undermines end-to-end integrity commit-marker invariant
- Creates a two-class reader world (scope-aware vs. scope-blind) which
  weakens R22b's hard invariant

---

## Candidate C4 — External path-keyed registry

**KB source:** [`.kb/systems/security/sstable-block-level-ciphertext-envelope.md`](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md)
**Relevant sections read:** `#the-three-dominant-patterns` (Pattern A), `#tradeoffs`

Cockroach-style: a separate file per deployment (e.g.,
`jlsm-encryption-registry.json`) maps SSTable relative path → scope +
DEK version set. Footer unchanged.

| Constraint | Weight | Score | Weighted | Evidence |
|------------|--------|-------|----------|----------|
| Scale | 2 | 3 | 6 | Registry scales with SSTable count; O(10^5+) entries for long-lived deployments; separate durability target |
| Resources | 2 | 4 | 8 | Registry cache competes with block cache; pathological load patterns possible |
| Complexity | 3 | 2 | 6 | Introduces a whole new durable artifact + consistency protocol with SSTable manifest |
| Accuracy | 3 | 3 | 9 | Scope lookup succeeds if registry is coherent; registry/manifest drift is a new failure mode |
| Operational | 2 | 2 | 4 | Backup/restore must carry the registry; file moves must update it; extra fsync ordering on every flush/compaction |
| Fit | 2 | 2 | 4 | Breaks the "SSTable is self-describing" principle that runs through current specs; requires catalog/manifest rework |
| **Total** | | | **37** | |

**Hard disqualifiers:** none strict, but contradicts the existing
self-describing-file principle in sstable.* specs.

**Key strengths:**
- Zero per-SSTable overhead
- Centralized rotation metadata

**Key weaknesses:**
- Registry durability/restore ergonomics are expensive to get right
- `.kb/systems/security/sstable-block-level-ciphertext-envelope.md#the-three-dominant-patterns` explicitly notes "registry corruption = all files undecipherable" as the dominant failure mode
- jlsm's LSM already has a manifest; adding a second metadata file to keep coherent doubles the durability surface

---

## Comparison Matrix

| Candidate | KB Source | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| [C1 — v6 fixed-position](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) | | 5 | 4 | 4 | 5 | 5 | 4 | **63** |
| [C2 — v6 TLV extensions](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) | | 4 | 4 | 3 | 4 | 5 | 4 | **55** |
| [C3 — optional within v5](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) | | 5 | 4 | 2 | 2 | 3 | 2 | **40** |
| [C4 — external registry](../../.kb/systems/security/sstable-block-level-ciphertext-envelope.md) | | 3 | 4 | 2 | 3 | 2 | 2 | **37** |

## Preliminary Recommendation

**C1 — v5→v6 bump with fixed-position scope section** wins on total (63 vs
55/40/37) after falsification. The advantage over C2 is concrete: the
codebase's "don't design for hypothetical futures" rule penalises TLV
speculation; the one forward extension we'd nominally want (per-block
encryption metadata if that model is later adopted) is enough of a
restructuring that it would likely need another version bump anyway.

**Recommendation holds after falsification** — but with explicit reframing:
footer scope + R22b comparison is a **fast-fail / clear-error** mechanism,
NOT the cryptographic tamper defence. Cryptographic defence against
wrong-scope decryption is delivered by the HKDF scope binding already in
place from WD-01 (primitives-lifecycle R11 derives DEK material from
the full `(tenantId, domainId, tableId, dekVersion)` tuple). Footer-scope
CRC32C is adequate for detecting accidental storage corruption, which is
all this layer needs to defend against.

## Falsification Adjustments (2026-04-24)

- C1 Accuracy reframed: fast-fail + clear error, not cryptographic defence.
  Score holds (R22b's actual requirement is ordering + error clarity).
- C1 Resources rescored 5→4: honest reflection of variable-length parse.
- No candidate change. Added "revisit this ADR if per-block AES-GCM
  migration is planned within 12 months" to Conditions for Revision.

## Risks and Open Questions

- **Per-block encryption transition**: if jlsm later adopts the KB's
  per-block AES-GCM model, the footer layout needs a major restructure
  (per-block nonces, footer MAC, etc.). That's a future-ADR-sized change
  that C1 doesn't uniquely foreclose — TLV extensions in C2 would not
  cover it either because the per-block model changes the block layout,
  not just the footer metadata. Defer as a separate decision.
- **Tamper resistance posture**: the scope section must be CRC32C-covered
  by the existing v5 section checksumming scheme (implied constraint from
  `sstable-end-to-end-integrity`). Any defence-in-depth beyond that (e.g.,
  AEAD over scope with a tenant-derived key) is deferred — the primary
  defence is R22b's Table-handle comparison.
- **TableScope record shape**: separate ADR (`table-handle-scope-exposure`)
  — decouples footer layout from catalog API.
