---
problem: "pre-ga-format-deprecation-policy"
date: "2026-04-24"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/local/LocalWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/remote/RemoteWriteAheadLog.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/TableCatalog.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/CiphertextValidator.java"
  - ".spec/CLAUDE.md"
  - ".decisions/CLAUDE.md"
  - "CHANGELOG.md"
---

# ADR — Pre-GA Format-Version Deprecation Policy

## Document Links

| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Research brief | [research-brief.md](research-brief.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| Format-Version Deprecation Strategies in Production Database Systems | Primary evidence — surveys 5 production strategies, comparison matrix, pattern taxonomy | [`.kb/systems/database-engines/format-version-deprecation-strategies.md`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md) |
| Encryption Key Rotation Patterns | Analog for compaction-driven rewrite as the natural rewrite vector | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |
| Database Catalog Persistence Patterns | Catalog format-version-byte at metadata head; backward-compat reads | [`.kb/systems/database-engines/catalog-persistence-patterns.md`](../../.kb/systems/database-engines/catalog-persistence-patterns.md) |
| Dispatch-Discriminant Corruption Bypass | Anti-pattern: single-bit magic flip routes to legacy branch; informs the per-collection watermark cross-check | [`.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md`](../../.kb/patterns/validation/dispatch-discriminant-corruption-bypass.md) |
| Version Discovery Self-Only With No External Cross-Check | Anti-pattern: reader derives version from self-magic only; informs the catalog-mediated watermark cross-check | [`.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md`](../../.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md) |

## Problem

How does jlsm retire old versions of any persistently-stored or on-wire
format artefact (SSTable formats, WAL segments, catalog `table.meta`,
ciphertext envelope, document serializer) without indefinitely carrying
backward-compatibility code, while preserving user data through every
version transition?

The policy must work in two regimes — pre-GA (zero on-disk users; eager
delete safe) and post-GA (users have data in old formats; migration must
be transparent or operator-actionable) — and must be exercised
immediately on SSTable v1–v4 while the cost is zero.

## Constraints That Drove This Decision

- **Automatic forward migration path required.** A writer at version
  v_N encountering an older file must produce a v_N rewrite without
  operator intervention, on the natural rewrite vector for that artefact
  (compaction for SSTables, rotation for WAL, metadata-write for
  catalog). This rules out manual-tool-only mechanisms.
- **No en-masse auto-rewrite without operator request.** Bounded
  background work is allowed; bulk rewrites require explicit operator
  command. This rules out aggressive auto-rewriters that could surprise
  running deployments.
- **Cross-artefact uniform application.** Policy must apply to SSTable,
  WAL, catalog, envelope, serializer uniformly. This rules out
  per-artefact-bespoke approaches and favours a single registry-style
  mechanism.
- **Pre-GA + post-GA dual regime.** Policy must work under both "zero
  migration debt" today and "users have old data" tomorrow without
  structural rewrite. ≥ 1 major release cycle deprecation window
  post-GA after write support drops.
- **Read-only past-window = hard error.** Writable storage gets inline
  rewrite-on-read past window; read-only storage past window throws
  with a diagnostic naming the file, format version, and operator action.

## Decision

**Chosen approach: [Full mechanism set (Candidate C)](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns)** — composing
RocksDB-style declarative compat-matrix with jlsm's existing
compaction-driven rewrite pattern, layered with a per-collection
watermark, bounded background sweep, and operator-triggered targeted
upgrade command.

### The policy in one paragraph

Every versioned artefact (SSTable, WAL, catalog `table.meta`, ciphertext
envelope, document serializer) carries a 4-byte big-endian
format-version discriminant readable without decoding the body.
Writers always emit the current version (the **prefer-current-version
rule**). A file at version v_{N-k} is upgraded to v_N on the next
natural rewrite — compaction for SSTables, rotation for WAL,
metadata-write for catalog. A bounded low-priority background sweep
catches files that natural rewrites miss; an explicit
`Engine.upgradeFormat(scope)` command lets operators force bulk
upgrades. The catalog tracks per-collection format-version watermarks
(generalising R9a-mono from `sstable.footer-encryption-scope`) for
cross-check against self-reported magics. Format inventory metrics
expose the current state to operators. After ≥ 1 major release cycle
post-GA, write support for an older version drops; readers continue to
support it for the deprecation window. Past the window, writable
storage does an inline rewrite-on-read; read-only storage throws a
hard error naming the file, the format version, and the operator
action ("roll back to a binary that supports format vN, OR re-mount
writable and trigger a migration").

### Six required mechanisms

| Code | Mechanism | Default | Rewrite trigger |
|------|-----------|---------|-----------------|
| Prefer-current-version rule | Writers always emit current; every rewrite path upgrades | ON (invariant) | n/a (rule, not mechanism) |
| C — Pre-deprecation read-time warnings | One-shot per-process per-(artefact, version) when a reader opens a file approaching window end | ON | reader log + metric |
| D′ — Format inventory + status API | Per-artefact-class counters (within-window / past-window / current); `Engine.formatStatus()` programmatic surface | ON | n/a (observability) |
| A″ — Bounded low-priority background sweep | Gradually queues outdated artefacts for rewrite; configurable rate limit; cascading allowed | ON, configurable | sweep scheduler |
| E — Per-collection format watermark | Catalog tracks oldest format version per collection; updated on rewrite; O(1) cross-collection check | ON | catalog watermark |
| F — Operator-triggered targeted upgrade command | `Engine.upgradeFormat(scope)` for explicit bulk rewrite; the only en-masse path | available, opt-in | operator |

### State machine per format-version

```
        +--------+        +-------------+        +--------------+        +----------+
        | active |--->----|  deprecated |--->----| read-only-ok |--->----| retired  |
        +--------+        +-------------+        +--------------+        +----------+
            |                   |                       |                     |
            |               window=≥1 major          window expires        readers
        write+read         readers warn;             (writable: inline    refuse with
        supported          writers stop emit         rewrite. read-only:  diagnostic;
                           (write support drops)     hard error)          spec archived
```

### Pre-GA deviation

Pre-GA, the deprecation window is **zero**. As soon as a format is
superseded, all of (a) writer code paths that emit it, (b) reader code
paths that parse it, (c) tests covering those paths, and (d) the spec
file and ADR governing it transition together. The first exercise of
this policy is the SSTable v1–v4 collapse, which removes
`SSTableFormat.MAGIC` (v1), `MAGIC_V2`, `MAGIC_V3`, `MAGIC_V4`,
their corresponding reader/writer paths, and archives
`sstable/format-v2.md` and `sstable/v3-format-upgrade.md`.

## Rationale

### Why the full mechanism set ([C](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns))

- **Automatic forward migration path** (Resources, weight 3) — covered
  by compaction-driven rewrite (existing) + bounded sweep (new) +
  inline rewrite-on-read past window (new). Three trigger paths cover
  every file lifecycle case; cold L6 files that natural compaction
  never touches get caught by the sweep or by operator command.
- **No en-masse auto-rewrite without operator request** (Accuracy,
  weight 3) — the sweep is bounded by configurable rate limit; only
  `Engine.upgradeFormat(scope)` does bulk work and only when invoked.
- **Cross-artefact uniform application** (Fit, weight 3) — the same
  six mechanisms apply per-artefact-class. SSTable, WAL, catalog,
  envelope, serializer each declare their format-version field, hook
  into the inventory + watermark + sweep + targeted-command
  infrastructure.
- **Composes with existing migration ADRs** —
  [`unencrypted-to-encrypted-migration`](../unencrypted-to-encrypted-migration/adr.md)
  established compaction-driven rewrite as jlsm's pattern; this policy
  generalises it. R9a-mono in
  [`sstable.footer-encryption-scope`](../../.spec/domains/sstable/footer-encryption-scope.md)
  is the first instance of the per-collection watermark.
- **Atomic-commit invariant** carries through every rewrite path via
  [`sstable.end-to-end-integrity`](../../.spec/domains/sstable/end-to-end-integrity.md)
  R39's tmp-path + atomic-move pattern. Cross-substrate uniform
  (FileChannel + remote NIO providers) is preserved.

### Why not [Candidate B (compaction + operator-only bulk upgrade)](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns)

- **Operational = 2.** No built-in inventory; no warnings; operators
  must build their own monitoring queries to see what's behind. Read-
  only deployments past window have no inventory to plan an upgrade
  window. C is a strict superset of B.

### Why not [Candidate A (hands-off opportunistic)](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#patterns)

- **Operational = 1.** Cold-L6 files that never compact accumulate at
  end-of-window without a fallback. The deprecation window cannot
  close on read-only deployments. Hard requirement violated.

### Why not [Candidate D (cluster-version-gate)](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system)

- **Fit = 1.** Cluster-version-gate requires a control plane. jlsm is
  a library, not a cluster product. Structurally inapplicable to the
  library layer (the C+D hybrid as an opt-in extension is captured
  separately).

### Why not [Candidate E (out-of-band utility, pg_upgrade-style)](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system)

- **Resources = 1.** Operator-triggered utility fails the "automatic
  forward migration path required" hard constraint. Also user
  explicitly rejected a separate `jlsm-legacy-reader` tool earlier in
  the constraint interview.

## Implementation Guidance

### Per-artefact-class registry

Each versioned artefact class declares:

```
- artefact-class name (e.g. "sstable", "wal", "catalog-meta", "envelope")
- current-version (the version writers emit)
- supported-read-versions (the set readers can parse)
- deprecation-window (default ≥ 1 major)
- rewrite-vector (compaction | rotation | metadata-write | inline)
- watermark-source (catalog field that tracks oldest version present)
```

The registry is a static immutable structure populated at module init
time; format constants live in `*Format` classes (e.g.,
`SSTableFormat`, `WalFormat`, `CatalogMetaFormat`).

### Format-version cross-check (downgrade-attack mitigation)

When a reader dispatches on self-magic, it must cross-check against
the catalog watermark for that collection per
[`patterns/validation/version-discovery-self-only-no-external-cross-check`](../../.kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md).
A file claiming an older magic than the catalog's high-water-minus-
deprecation-window must throw `IOException` indicating tamper / corruption
without revealing further bytes. Per-format integrity specs
(`sstable-active-tamper-defence` and successors per artefact) provide
the cryptographic defence; the watermark cross-check is the
fast-fail / clear-error layer above it.

### Bounded sweep configuration

```
EngineConfig.formatSweep
  enabled: true                    # default ON; can be disabled for
                                   # production-sensitive deployments
  intervalMillis: 3_600_000        # default 1 hour
  bytesPerCycle: 16 * 1024 * 1024  # default 16 MiB; scales per cycle
  filesPerCycle: 4                 # default 4 files per cycle
  priority: low                    # yields to overlap-driven compaction;
                                   # configurable down to near-zero impact
  cascading: true                  # allow rewrites triggered by sweep
                                   # to trigger downstream compactions
```

### Inventory + status API

```java
public interface Engine {
  // Existing methods unchanged.
  FormatStatus formatStatus();   // O(1) per-artefact-class counters
  Job upgradeFormat(UpgradeScope scope) throws IOException;
}

public record FormatStatus(
    Map<String, ArtefactClassStatus> byArtefactClass) {}

public record ArtefactClassStatus(
    int currentVersion,
    int oldestPresentVersion,
    Map<Integer, Long> filesByVersion,
    int filesWithinWindow,
    int filesPastWindow) {}
```

### Pre-deprecation read-time warnings

A reader opening a file at version v_{N-k} where v_{N-k} is within the
deprecation window emits a one-shot warning (per-process,
per-(artefact-class, version)). Format:

```
[jlsm] artefact=<class> version=<v> file=<path> approaches deprecation;
       window expires in <release-counter>; trigger compaction or
       Engine.upgradeFormat(<scope>) to upgrade in place.
```

Past-window writable: same warning + inline rewrite-on-read fires
transparently.
Past-window read-only: hard error, NO retry of the read.

### CHANGELOG.md per state transition

Every transition documented in this policy produces a CHANGELOG row
under the appropriate heading per
[`.claude/rules/documentation.md`](../../.claude/rules/documentation.md):

- **Added** — new artefact-class registered with the policy
- **Changed** — current-version bump for an artefact class
- **Deprecated** — write support drops for a version (deprecation
  window opens)
- **Removed** — read support drops for a version (window expires);
  spec file moves to "Archived" index entry, ADR moves to "Closed"

### Pre-GA collapse procedure (first exercise)

Applied to SSTable v1–v4 as the first exercise of this policy:

1. Verify no SSTable v1–v4 files exist in any project's data
   directory. (Pre-GA: trivially true; no on-disk users.)
2. Remove `SSTableFormat.MAGIC` (v1), `MAGIC_V2`, `MAGIC_V3`,
   `MAGIC_V4` and all related constants
   (`FOOTER_SIZE`, `FOOTER_SIZE_V2`, `FOOTER_SIZE_V3`,
   `FOOTER_SIZE_V4`, `COMPRESSION_MAP_ENTRY_SIZE`).
3. Remove all reader paths (`readFooterV1`, v2/v3/v4 dispatch
   branches) from `TrieSSTableReader`. Magic dispatch reduces to
   v5 + v6.
4. Remove all writer paths (`writeFooterV1`, `writeFooterV3`,
   `writeFooterV4`, `finishV3Layout`) from `TrieSSTableWriter`.
   Writer emits v5 (when no encryption) or v6 (when encryption
   configured per WD-02).
5. Remove all v1–v4-specific tests.
6. Mark `.spec/domains/sstable/format-v2.md` `state: DEPRECATED`,
   `superseded_by: sstable.end-to-end-integrity`. Move the index
   entry from `.spec/CLAUDE.md` Recently Added to a new "Archived"
   section.
7. Mark `.spec/domains/sstable/v3-format-upgrade.md` `state: DEPRECATED`,
   `superseded_by: sstable.end-to-end-integrity`. Same archive
   treatment.
8. CHANGELOG.md `[Unreleased]` Removed: `SSTable formats v1–v4
   (collapsed pre-GA per pre-ga-format-deprecation-policy)`.
9. WD-02 (`.feature/implement-encryption-lifecycle--wd-02`) resumes
   on the collapsed baseline; design re-presented for approval per
   the paused-feature note in `status.md`.

## What This Decision Does NOT Solve

- **Cluster-wide format-version negotiation.** When jlsm is embedded
  in a cluster product, the consumer may layer cluster-version-gate
  semantics on top. The library exposes per-node behaviour; cluster
  coordination is out of scope. Tracked as deferred decision
  [`cluster-format-version-coexistence`](../cluster-format-version-coexistence/adr.md).
- **The major-vs-minor release cadence definition for jlsm itself.**
  The "≥ 1 major release cycle" deprecation window is anchored to a
  cadence that is not yet defined. Tracked as deferred decision
  [`jlsm-release-cadence`](../jlsm-release-cadence/adr.md).
- **Format-version downgrade-attack defence beyond the watermark
  cross-check.** This policy provides the fast-fail / clear-error
  layer; the cryptographic defence (manifest MAC, per-block AEAD,
  signed format-version magic, etc.) is delegated to per-format
  integrity specs. SSTable's per-format defence is tracked at
  [`sstable-active-tamper-defence`](../sstable-active-tamper-defence/adr.md)
  (already deferred from WD-02). WAL, catalog, and envelope per-format
  defences will be opened as those needs arise.

## Conditions for Revision

- **Sweep keep-up empirically inadequate** — if post-GA monitoring
  shows the bounded background sweep cannot keep pace with cold-file
  accumulation, fall back to the **lazy-on-demand** alternative
  (mechanism A″ removed entirely; inline rewrite-on-read +
  operator-triggered command only). Constraints.md flags this as the
  pre-GA hedge; falsification surfaced it as the most dangerous
  assumption.
- **Cluster-mode coexistence becomes blocking** — if a real consumer
  embeds jlsm in a cluster product and cluster-version coordination
  fights with the per-node policy, revisit via
  [`cluster-format-version-coexistence`](../cluster-format-version-coexistence/adr.md)
  and adopt the C+D hybrid (no-op cluster-version hook for embedders).
- **Per-collection watermark proves untenable at scale** — the
  watermark mechanism is novel; no surveyed production system has it.
  If aggregation across hundreds × hundreds of artefact classes
  becomes a hotpath, revisit the watermark design.
- **Operator preference observed for explicit-command-only model** —
  if operators consistently disable the bounded sweep and rely
  exclusively on `Engine.upgradeFormat(scope)`, downgrade to
  Candidate B (sweep + inventory removed; operator-only escape hatch
  remains).

---
*Confirmed by: user deliberation | Date: 2026-04-24*
*Full scoring: [evaluation.md](evaluation.md)*
