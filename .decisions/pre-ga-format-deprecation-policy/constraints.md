---
problem: "How does jlsm retire old on-disk and on-wire format versions (SSTable, WAL, catalog table.meta, ciphertext envelope, document serializer) without accumulating indefinite backward-compatibility debt? Establish a policy that works pre-GA (zero on-disk users, eager delete safe) and post-GA (users have data in old formats, migration tooling required). Exercise it immediately on SSTable v1–v4."
slug: "pre-ga-format-deprecation-policy"
captured: "2026-04-24"
status: "draft"
---

# Constraint Profile — pre-ga-format-deprecation-policy

## Problem Statement

How does jlsm retire old versions of any persistently-stored or on-wire
format artefact (SSTable formats v1–v6+, WAL segment formats, catalog
`table.meta` formats, per-field ciphertext envelope versions, document
serializer versions, transport framing versions) without indefinitely
carrying backward-compatibility code, while preserving the user's data
through any version transitions?

The policy must work in both regimes:

- **Pre-GA** (current state): zero on-disk users; SSTable v1–v4 + WAL
  legacy formats can be eagerly deleted without migration debt.
- **Post-GA** (future state): users have data on disk in older formats;
  migration must be transparent or operator-actionable; readers must
  not silently drift; old formats must eventually retire to bound
  compat-code lifetime.

The first live exercise of the policy is the SSTable v1–v4 collapse.

## Constraints

### Scale

**Inferred / not explicitly captured.** Today: ~6 versioned artefact
classes (SSTable, WAL, catalog metadata, ciphertext envelope, document
serializer, transport framing). Per-class version count today: SSTable
holds 5 historical magics (v1–v5) + v6 incoming; others 1–2 each.

Growth is tied to feature cadence — every feature that touches an
on-disk or on-wire shape adds a version bump. Steady-state projection:
1–3 bumps per artefact per year as the project matures. Mechanism must
scale to dozens of artefacts × dozens of versions each without rework.

### Resources

**Agentic team with input from humans; effectively unbounded compute,
research capacity, and 24/7 availability.** Maintenance cost of compat
code paths is not a binding constraint.

**Hard requirement: automatic forward migration path.** Manual operator
migration tools are not the primary mechanism. A writer at version N
encountering an older file must produce a v_N rewrite without operator
intervention, on the natural rewrite vector for that artefact
(compaction for SSTables, rotation for WAL, metadata-write for
catalog, etc.).

### Complexity Budget

**Unbounded** — explicit user statement. This composes with the
existing project-level `feedback_complexity_not_a_concern.md`.

### Accuracy / Correctness

The policy must preserve these invariants across every deprecation
transition:

1. **Prefer-current-version rule.** Writers always emit the current
   format version. Compaction merges, inline rewrite-on-read past
   window, the bounded sweep, and operator-triggered targeted upgrades
   all write the current version. The system never voluntarily rewrites
   a file at an older format than the current writer supports.
2. **Crash-safety / atomic rewrite.** A rewrite-in-progress that
   crashes mid-write must not corrupt the existing file. Magic-as-commit-
   marker (per `sstable.end-to-end-integrity`) is preserved through the
   rewrite path. Either the new file commits (magic visible) or the
   original file is intact.
3. **Idempotency.** Re-running a rewrite on an already-upgraded file
   is a no-op or a same-version re-emission. Aligns with
   `.claude/rules/io-internals.md` idempotency rule.
4. **No silent format drift.** A reader must never silently produce
   the wrong bytes because of a format mismatch. Either the file is
   recognised and read correctly, or a clear error fires.
5. **No en-masse auto-rewrite without explicit operator request.** The
   bounded background sweep MUST stay bounded; cascading from
   foreground compaction is allowed but the engine never bulk-rewrites
   all outdated files unilaterally.
6. **Read-only past-window = hard error.** A reader on writable
   storage past the deprecation window does an inline rewrite-on-read.
   A reader on read-only storage past the window throws a hard error
   identifying the file, the format version, and the operator action
   ("roll back to a binary that supports format vN, OR re-mount
   writable and trigger a migration compaction"). No silent fallthrough.

### Operational Requirements

- **Pre-GA window: zero.** Eager delete as soon as a format is
  superseded by a newer writer. SSTable v1–v4 is the first exercise.
- **Post-GA deprecation window: ≥ 1 major release cycle** after write
  support drops. (Tightened from "≥ 2 minor OR ≥ 1 major"; rationale:
  major-only window gives read-only deployers more lead time and
  aligns with SemVer convention that breaking changes belong on majors.)
- **Within window**: all storage paths read OK. Writable storage gets
  inline rewrite-on-read. Read-only storage emits a per-process
  one-shot deprecation warning (mechanism C).
- **Past window**: writable = inline rewrite-on-read; read-only = hard
  error.
- **Default behaviour**: auto-upgrade as we write (compaction +
  any-rewrite path). Bounded background sweep ON by default at low
  priority. Operator can lower priority near-zero for production-
  sensitive deployments. Cascading auto-triggered work is permitted.
  Pre-GA hedge: monitor in production once shipped; tighten or relax
  the default if behaviour surprises operators.
- **No bulk rewrites except via operator-triggered targeted upgrade
  command** (mechanism F).

### Fit

The policy must compose with existing infrastructure:

- **Existing migration ADRs**:
  [`unencrypted-to-encrypted-migration`](../unencrypted-to-encrypted-migration/adr.md)
  (compaction-driven migration pattern),
  [`string-to-bounded-string-migration`](../string-to-bounded-string-migration/adr.md)
  (scan-quarantine for non-conforming data),
  [`table-migration-protocol`](../table-migration-protocol/adr.md)
  (5-phase Raft learner cluster-level migration). The deprecation
  policy reuses the compaction-driven rewrite vector rather than
  introducing a parallel one.
- **Spec lifecycle metadata** (already in `.spec/CLAUDE.md`):
  `status: ACTIVE | STABLE | DEPRECATED`, `state: DRAFT | APPROVED | INVALIDATED`,
  `amends` / `amended_by`, `invalidates`. Policy formalises *when* these
  fields transition during the deprecation lifecycle.
- **ADR lifecycle metadata** (already in `.decisions/CLAUDE.md`):
  `supersedes:`, "Closed" section with reasons, `history.md` archive.
  Policy formalises when ADRs governing retired formats move to closed.
- **WD-02's `meta_format_version_highwater` (R9a-mono)** in the catalog
  index. Mechanism E (per-collection format watermark) reuses this
  field rather than introducing a parallel watermark.
- **CHANGELOG.md** per `.claude/rules/documentation.md`. Every
  deprecation transition gets a CHANGELOG entry under
  Removed / Changed.
- **`io-internals.md` idempotency rule** — automatic rewrites must be
  idempotent so retries / cascades are safe.

### Six required mechanisms (derived from above)

The policy locks these in as required mechanisms (named for the menu
labels used during constraint collection):

| Code | Mechanism | Default |
|------|-----------|---------|
| Prefer-current rule | Writers always emit current; every rewrite path upgrades | ON |
| C  | Pre-deprecation read-time warnings (one-shot per process per (artefact, version)) | ON |
| D′ | Format inventory + status API across ALL versioned artefacts; per-class counters; programmatic surface | ON |
| A″ | Bounded low-priority background sweep; cascading allowed; priority configurable down to near-zero | ON, configurable |
| E  | Per-collection format watermark (composes with R9a-mono) | ON |
| F  | Operator-triggered targeted upgrade command — the ONLY bulk-rewrite path | available, opt-in |

## Key Constraints (most narrowing)

1. **Automatic forward migration path required (Resources).** Eliminates
   any policy that requires explicit operator action as the primary
   upgrade vector. Forces composition with the existing compaction-
   driven migration pattern.
2. **No en-masse auto-rewrite without operator request (Correctness).**
   Bounds the auto-sweep to a low-priority work budget; rules out
   aggressive auto-rewriters that could surprise running deployments.
3. **Cross-artefact uniform application (Scale + Fit).** Policy must
   apply to SSTable, WAL, catalog metadata, envelope, serializer
   uniformly — rules out per-artefact-bespoke approaches; favours a
   single registry-style mechanism.
4. **Pre-GA + Post-GA dual-regime (Operational).** Policy must work
   under both "zero migration debt" and "users have old data" without
   structural rewrite. Forces a window-based design with consistent
   behaviour across regimes.

## Constraint Falsification — 2026-04-24

Checked the following sources for implied constraints not stated in the
user's profile:

- `.spec/domains/sstable/end-to-end-integrity.md` (R39 atomic commit,
  R23 fsync discipline, R47 CRC unconditional, R42 section-name vocabulary)
- `.spec/domains/sstable/v3-format-upgrade.md` (R17–R19 magic dispatch,
  R24 compaction tolerates source-version diversity)
- `.spec/domains/sstable/footer-encryption-scope.md` (R9a-mono catalog
  format-version high-water)
- `.spec/domains/sstable/format-v2.md`,
  `.spec/domains/wal/{compression, encryption}.md`,
  `.spec/domains/encryption/ciphertext-envelope.md`
- `.decisions/{unencrypted-to-encrypted-migration,
  string-to-bounded-string-migration, table-migration-protocol}/adr.md`
- `.kb/systems/security/{encryption-key-rotation-patterns,
  dek-revocation-vs-rotation}.md`
- `.kb/patterns/validation/{version-discovery-self-only-no-external-
  cross-check, dispatch-discriminant-corruption-bypass}.md`
- `.kb/systems/database-engines/catalog-persistence-patterns.md`
- `.claude/rules/{io-internals, documentation, code-quality}.md`

User accepted all five implied constraints surfaced. Added to the
profile below:

### Atomic commit invariant (Correctness, added)

Format-upgrade rewrites MUST use the per-writer tmp-path + atomic-move
commit pattern from `sstable.end-to-end-integrity` R39. Direct overwrite
of an existing file is forbidden. The magic-as-commit-marker invariant
holds across the rewrite — a partially-written upgraded file with no
final magic is not visible to readers; the original remains intact.
Crash mid-rewrite leaves on-disk state recoverable.

### Cross-substrate uniformity (Resources / Operational, added)

The rewrite path MUST work uniformly on FileChannel (POSIX,
ATOMIC_MOVE supported) and on remote NIO providers (S3/GCS, no
ATOMIC_MOVE). Inline rewrite-on-read cannot assume a FileChannel-only
optimisation; it must compose with the existing backend-conditional
commit pattern from `end-to-end-integrity` R39 (atomic move where
available, content-addressed commit + tmp-cleanup where not). Aligns
with `.claude/rules/io-internals.md`.

### Format-version downgrade-attack defence (Correctness, added)

When a deprecated format version is fully retired (read support
dropped), files on disk that still claim the retired magic become an
attack surface — an attacker rewriting current bytes as the retired
magic to bypass features the retired format never had (e.g., the
SSTable v6 → v5 swap to skip footer-scope validation, per
`sstable.footer-encryption-scope` R13's threat-model boundary).

The policy MUST specify:
- whether downgrade-attack defence is intrinsic to this policy
  (e.g., the format inventory + per-collection watermark always
  cross-check incoming reads against the catalog's recorded
  high-water version, refusing reads of magics older than the
  high-water minus one), OR
- explicitly delegated to per-format integrity specs (e.g.,
  `sstable-active-tamper-defence` ADR for SSTables, separate
  defences per artefact).

Composes with `.kb/patterns/validation/version-discovery-self-only-
no-external-cross-check.md` and
`.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md`.
The policy text resolves this in the Decision section.

### Idempotent re-emission for cascading sweeps (Correctness, added)

When the bounded sweep cascades (rewrite triggers compaction triggers
more rewrites), a second pass on an already-current file must be a
same-version no-op or same-version re-emission — never a
corruption-hazard. Sweep state must tolerate concurrent foreground
compaction touching the same file (one wins, the other detects
no-work-needed and exits). Aligns with `.claude/rules/io-internals.md`
idempotency rule.

### CHANGELOG.md entry per deprecation transition (Fit, added)

Every state transition documented in this policy
(write-support-drops, read-support-drops, spec-archived,
ADR-supersedes) MUST produce a CHANGELOG row under the appropriate
heading (Removed / Changed) per `.claude/rules/documentation.md`.
This makes the deprecation history user-visible without requiring
operators to read the spec corpus.

## Unknown / Not Specified

- **Release cadence** — jlsm does not yet have a defined major/minor
  release cycle. The "≥ 1 major release cycle" window is anchored to a
  cadence that doesn't yet exist. The policy must say what counts as a
  major release and how the project defines its versioning. This is a
  derivable item the ADR can address.
- **Cluster-wide format version negotiation** — when jlsm runs in
  cluster mode, do all nodes need to agree on the "current format
  version" before any writer emits it? This interacts with
  `cluster-membership-protocol` and `transport-multiplexed-framing`.
  Out of scope for this ADR; deferred to follow-up if cluster format
  divergence becomes a real issue.
