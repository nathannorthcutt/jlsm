---
title: "Ciphertext format + SSTable v6 footer scope signalling (implement-encryption-lifecycle WD-02)"
type: feature-footprint
tags: [encryption, ciphertext-envelope, sstable, footer-scope, multi-tenant, dek-version, catalog, sealed-table, adversarial-hardening, pre-ga-deprecation]
feature_slug: implement-encryption-lifecycle--wd-02
work_group: implement-encryption-lifecycle
shipped: 2026-04-25
domains:
  - encryption
  - sstable
  - engine
  - footer-scope-signalling
  - dek-version-dispatch
  - catalog-mutation
constructs:
  - "TableScope"
  - "ReadContext"
  - "EncryptionMetadata"
  - "IdentifierValidator"
  - "V6Footer"
  - "EnvelopeCodec"
  - "CatalogLock"
  - "FileBasedCatalogLock"
  - "CatalogLockFactory"
  - "CatalogIndex"
  - "WriterCommitHook"
  - "EngineWriterCommitHook"
  - "SSTableFormat"
  - "TrieSSTableWriter"
  - "TrieSSTableReader"
  - "TableMetadata"
  - "Table"
  - "Engine"
  - "LocalEngine"
  - "ClusteredEngine"
  - "CatalogTable"
  - "CatalogClusteredTable"
  - "TableCatalog"
  - "FieldEncryptionDispatch"
  - "CiphertextValidator"
  - "DocumentSerializer"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/TableScope.java"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/ReadContext.java"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/EnvelopeCodec.java"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/internal/IdentifierValidator.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/SSTableFormat.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/V6Footer.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableWriter.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
  - "modules/jlsm-core/src/main/java/jlsm/sstable/WriterCommitHook.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/EncryptionMetadata.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/Table.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/TableMetadata.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/Engine.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/LocalEngine.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogTable.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogIndex.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogLock.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogLockFactory.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/FileBasedCatalogLock.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/EngineWriterCommitHook.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/TableCatalog.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredEngine.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/CatalogClusteredTable.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldEncryptionDispatch.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/CiphertextValidator.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/DocumentSerializer.java"
related:
  - ".kb/architecture/feature-footprints/implement-encryption-lifecycle--wd-01.md"
  - ".kb/architecture/feature-footprints/implement-sstable-enhancements--wd-03.md"
  - ".kb/systems/database-engines/format-version-deprecation-strategies.md"
  - ".kb/patterns/transactions/stage-then-publish-disk-before-memory.md"
  - ".kb/patterns/validation/error-message-info-flow-discipline.md"
  - ".kb/patterns/validation/caller-supplied-mutable-input-defensive-snapshot.md"
  - ".kb/patterns/resource-management/file-lock-handle-resource-lifecycle.md"
  - ".kb/patterns/resource-management/writer-state-machine-runtime-fault-containment.md"
  - ".kb/patterns/validation/dispatch-discriminant-corruption-bypass.md"
  - ".kb/patterns/validation/version-discovery-self-only-no-external-cross-check.md"
  - ".kb/patterns/validation/silent-fallthrough-integrity-defense-coupled-to-flag.md"
decision_refs:
  - "sstable-footer-scope-format"
  - "table-handle-scope-exposure"
  - "pre-ga-format-deprecation-policy"
  - "module-dag-sealed-type-public-factory-carve-out"
spec_refs:
  - "sstable.footer-encryption-scope"
  - "encryption.ciphertext-envelope"
  - "encryption.primitives-lifecycle"
  - "engine.clustered-table-construction"
  - "engine.catalog-operations"
research_status: stable
last_researched: "2026-04-25"
---

# Ciphertext format + SSTable v6 footer scope signalling

## Shipped outcome

Lands the WD-02 half of the F41 encryption lifecycle: the on-disk
ciphertext envelope (4-byte BE DEK-version prefix on every variant) and
the SSTable v6 footer scope-signalling layer that lets a reader (a)
locate the correct DEK under multi-tenant key scoping and (b) reject
mis-routed cross-scope SSTables before any DEK lookup fires. Together
these unblock WD-04 (compaction-driven re-encryption) and complete the
"reader can find the right DEK" half of `encryption.primitives-lifecycle`
R22b/R23a/R24.

The v6 footer extends v5 in-place — `[v5 file w/ MAGIC_V5] +
[scope-section bytes] + [u32 BE scope-total-size] + [MAGIC_V6 8B]`. v5's
self-checksum and magic are preserved so backward CRC remains valid;
trailing-magic dispatch sees v6 first. The scope section records
`(tenantId, domainId, tableId)` plus the strictly-ascending DEK-version
set declared at write time; reader parses scope before any decryption,
compares to the `Optional<TableScope>` derived from the caller's
`Table` handle, and materialises the DEK-version set as a `Set<Integer>`
threaded into a `ReadContext` consumed by `FieldEncryptionDispatch` for
the R3e dispatch gate.

The catalog mutation path (`Engine.createEncryptedTable` /
`enableEncryption`) is the engine-side complement: 5-step protocol
under a per-table `CatalogLock` (file-lock + per-JVM mutex hybrid with
PID-with-bounded-reclaim liveness), atomic `table.meta` rewrite under
that lock, monotonic `CatalogIndex` high-water bump, and an
`AtomicReference<TableMetadata>` publication. `TrieSSTableWriter.finish`
acquires the same lock during R10c so a writer that constructed before
`enableEncryption` cannot commit a v5 footer to an encrypted table.

Five specs involved:
- `sstable.footer-encryption-scope` — v5→v9 (created in this feature; nine
  amendments through TDD/audit cycle)
- `encryption.ciphertext-envelope` — v1→v2 (closed three obligations:
  4B prefix + R64 lookup, cross-tier byte-identity, DCPE seed BE pin)
- `encryption.primitives-lifecycle` — v9→v10 (R22, R22a, R22b, R23, R23a,
  R24 direct-annotated once footer scope landed)
- `engine.clustered-table-construction` — v1 (created during
  audit-spec-author to document 4-arg/5-arg `forEngine` "transport-only
  handle" contract)
- `engine.catalog-operations` — v2→v3 (R7b 5-step expanded with
  CatalogLock + CatalogIndex high-water + rollback semantics)

## Key constructs

**New (10) + 4 modified-extensively + 8 modified-touched + 2 renamed/relocated.**
Exported in `jlsm.encryption`, `jlsm.engine`, and `jlsm.sstable`;
internal in `jlsm.encryption.internal`, `jlsm.engine.internal`,
`jlsm.engine.cluster.internal`, `jlsm.sstable.internal`.

### `jlsm.encryption` (exported, jlsm-core)

- `TableScope` — `record TableScope(TenantId, DomainId, TableId)`; null-checked
  components, redacted `toString`. Identity-equal under R8c
- `ReadContext` — public record `(Set<Integer> allowedDekVersions)` with
  defensive `Set.copyOf` snapshot at construction; threaded by
  `TrieSSTableReader` to `MemorySerializer.deserialize` for the R3e gate
- `EnvelopeCodec` — pure 4B BE DEK-version prefix codec (`prefixVersion` /
  `parseVersion` / `stripPrefix`). `VERSION_PREFIX_LENGTH = 4`.
  `parseVersion` and `stripPrefix` both throw `IOException` on
  under-length input (harmonised contract)

### `jlsm.encryption.internal` (NOT exported; qualified-export to `jlsm.engine`, `jlsm.table`)

- `IdentifierValidator` — eager R2b/R2c/R2e identifier rules + R12
  position-only diagnostics on the read path (no byte-value leakage)

### `jlsm.sstable` (exported, jlsm-core)

- `WriterCommitHook` SPI — `acquire(String tableName) → Lease`; `Lease`
  carries `freshScope() → Optional<TableScope>` for R10c TOCTOU defence.
  Public SPI exists in jlsm-core to avoid a downward dependency on
  jlsm-engine; `EngineWriterCommitHook` is the production impl

### `jlsm.sstable.internal` (NOT exported)

- `V6Footer` — pure codec. `encodeScopeSection(TableScope, int[])` snapshots
  the caller's `int[]` once at entry to defeat TOCTOU between validate +
  encode. `Parsed(scope, dekVersionSet, footerEnd)` snapshots
  `dekVersionSet` via `Set.copyOf` in the compact constructor.
  `materialiseDekVersionSet` returns a HashSet for O(1) `R3e` membership

### `jlsm.engine` (exported, jlsm-engine)

- `EncryptionMetadata` — `record EncryptionMetadata(TableScope scope)`
  attached as the 5th component of `TableMetadata` (`Optional<EncryptionMetadata>`).
  Forward-compat: future per-table encryption facts (allowed-DEK window,
  KEK alias, rotation policy) extend this record without breaking
  `TableMetadata`

### `jlsm.engine.internal` (NOT exported)

- `CatalogLock` interface — `acquire(String tableName) → Handle`; same
  lock acquired by both `Engine.enableEncryption` (R7b) and
  `TrieSSTableWriter.finish` (R10c) for the same table name. Idempotent
  `Handle.close()`
- `FileBasedCatalogLock` + `CatalogLockFactory` — file-lock + per-JVM
  `ReentrantLock` hybrid with refcounted `jvmLocks` entry to bound the
  per-table-name lock-entry growth; PID + bounded-reclaim liveness with
  `nanoTime` deadline + overflow-safe loop guard; `ProcessHandle.of(pid)`
  for cross-platform liveness (no `/proc` short-circuit); 5-second
  reclaim window
- `CatalogIndex` — `[magic][count]([nameLen:u16][name][version:u32])*` on
  disk; cold-start "missing entry = table does not exist" (R9b);
  monotonic per-table version (R9a-mono); stage-then-publish in
  `setHighwater` so in-memory readers see the new value only after disk
  durability
- `EngineWriterCommitHook` — production `WriterCommitHook` wired into
  `LocalEngine.createJlsmTable`; exposes `freshScope()` that re-reads
  `TableMetadata` under the catalog lock so R10c TOCTOU is reachable
  end-to-end
- `CatalogTable` — renamed from `LocalTable`; package-private
  constructor; explicitly NOT `Serializable`; sealed permit of `Table`

### `jlsm.engine.cluster.internal` (NOT exported)

- `CatalogClusteredTable` — renamed + relocated from
  `jlsm.engine.cluster.ClusteredTable`; public static `forEngine(...)`
  factory (the legitimate construction surface — package-private ctor
  not directly reachable from `jlsm.engine.cluster`'s `ClusteredEngine`,
  per the `module-dag-sealed-type-public-factory-carve-out` ADR);
  explicitly NOT `Serializable`; sealed permit of `Table`

### Modified extensively (4)

- `SSTableFormat` — `MAGIC_V6 = 0x4A4C534D53535406L`; v6 dispatch entry
- `TrieSSTableWriter` — TableScope-aware Builder
  (`scope` / `dekVersions` / `commitHook` / `tableNameForLock`); v6 emit
  via `V6Footer.encodeScopeSection` after the v5 sections; FAILED state
  on any RuntimeException from the commit hook (broadened catch);
  R10d emit-order + double-fsync (data file + parent dir) with a
  non-FileChannel listener at the close-before-move boundary so
  application-level fsync still fires on rename failure for non-FileChannel
  outputs; tmp-file cleanup on close-without-finish; R10c finish protocol
  acquires `CatalogLock` then re-reads `freshScope()` and rejects
  finish if construction-time scope diverges
- `TrieSSTableReader` — magic dispatch (v5 vs v6); `open(Path,
  Deserializer, Optional<TableScope>)` overload (jlsm-core can't import
  jlsm-engine, so reader takes the scope record, not the `Table`); R5
  `IllegalStateException` when expectedScope is empty AND footer is v6;
  R6 mismatch rejection with R6c language; `<V> Optional<V> get(MemorySegment,
  MemorySerializer<V>)` typed accessor that threads `ReadContext` to
  `MemorySerializer.deserialize(segment, ReadContext)`
- `FieldEncryptionDispatch` — 4-arg ctor `(JlsmSchema, OffHeapKeyMaterial,
  int currentDekVersion, IntPredicate versionResolver)`; 2-arg ctor
  delegates with `DekVersion.FIRST.value()` + permissive resolver;
  `applyEnvelopeWrap()` final pass wraps every non-null encryptor
  (prefix on encrypt) and decryptor (parse + R64 resolver gate + strip +
  delegate); `decryptWithContext(int, byte[], ReadContext)` parses
  version + checks R3e/R3f gate + strips + delegates; legacy decryptor
  registry-miss surfaces as `IllegalStateException` (was
  `UncheckedIOException(IOException)`)

### Modified — touched (8)

- `TableMetadata` — 5th component `Optional<EncryptionMetadata>` with
  backward-compat 4-arg ctor
- `Table` — `sealed interface Table permits jlsm.engine.internal.CatalogTable,
  jlsm.engine.cluster.internal.CatalogClusteredTable`
- `Engine` — interface gains `createEncryptedTable(String name, Schema,
  TableScope)` and `enableEncryption(String name, TableScope)` as
  default methods that throw UOE so existing test stubs need no
  override
- `LocalEngine` — implements both methods via 5-step protocol;
  `AtomicReference<TableMetadata>` publication for step 5
- `ClusteredEngine` — return type `ClusteredTable → Table`; both methods
  delegate to the originating node's `LocalEngine`; cross-node
  propagation deferred to WD-05 once cluster control plane has a stable
  RPC primitive for catalog broadcasts
- `TableCatalog` — `table.meta` leading format-version byte (`0x4A`
  legacy heuristic, `0x02` encryption-aware); encryption block
  `[hasEncryption:byte] + [tenantLen][tenant][domainLen][domain][tableLen][table]`;
  R10e cleanup walks each table directory on `open()` and deletes
  `*.partial.*` files; `updateEncryption` rolls back the `table.meta`
  rewrite if `setHighwater` fails; `registerInternal` stages a LOADING
  placeholder via `putIfAbsent` then `replace(LOADING, READY)` so
  concurrent readers never observe READY before disk durability;
  `open()` does deferred-rescan of directories whose in-memory
  catalog-index lookup misses, defending against cross-process register
  during open
- `CiphertextValidator` — length formulas updated for the 4B prefix:
  SIV `16→20`, GCM `28→32`, OPE `25→29`, DCPE `8 + 4N + 16 → 12 + 4N + 16`;
  positivity guard rejects zero/negative parsed version (R1c/R2)
- `DocumentSerializer` — encrypt path wraps DCPE blob with
  `EnvelopeCodec.prefixVersion`; decrypt path strips prefix before
  `fromBlob`; threads `ReadContext` through a default
  `MemorySerializer.deserialize(MemorySegment, ReadContext)` method so
  non-encrypted serializers preserve identical behavior

### Renamed / relocated (2)

- `jlsm.engine.internal.LocalTable` → `jlsm.engine.internal.CatalogTable`
  (in-place rename)
- `jlsm.engine.cluster.ClusteredTable` → `jlsm.engine.cluster.internal.CatalogClusteredTable`
  (rename + relocation; the relocation is the breaking change for
  external consumers of `ClusteredTable` — within this repo, cluster
  test stubs `RecordingTable` / `StubTable` / `PermissiveStubTable` /
  the anonymous `Table` in `ResourceLifecycleAdversarialTest` migrated
  to a `TestTableStubs` package-private factory pattern per R8g)

## API change the caller sees

```java
// Engine path: enable encryption on a new or existing table
TableScope scope = new TableScope(
    TenantId.of("tenant-a"),
    DomainId.of("orders"),
    TableId.of("line_items"));

Table t = engine.createEncryptedTable("line_items", schema, scope);
// or, on an already-created plaintext table:
engine.enableEncryption("line_items", scope);

// Reader path: scope is derived from the Table handle, not from the
// SSTable footer (defence-in-depth — R6a)
Optional<TableScope> expected =
    table.metadata().encryption().map(em -> em.scope());

try (TrieSSTableReader r = TrieSSTableReader.open(path, valueDeser, expected)) {
    // get() threads ReadContext (allowedDekVersions) to deserialize, so
    // the dispatch gate sees only versions declared in the footer
    Optional<Document> v = r.get(key, schemaSerializer);
}
```

Caller-visible guarantees:
- A v6 SSTable opened with empty `expectedScope` throws
  `IllegalStateException` before any decryption (R5).
- A v6 SSTable whose footer scope does not equal the caller-supplied
  scope component-wise throws `IllegalStateException` before any
  decryption (R6, message follows R6c language; no scope bytes leaked).
- A `decrypt` for a DEK version not in the footer's
  declared set throws `IllegalStateException` naming the scope (R3e/R24).
- `Engine.createEncryptedTable` and `enableEncryption` are atomic under
  a per-table `CatalogLock`; partial failures (e.g. `setHighwater`
  failing after `writeMetadata` succeeded) roll back the `table.meta`
  rewrite so disk and in-memory views stay consistent.
- `TrieSSTableWriter.finish()` for an encrypted table re-reads scope
  via `WriterCommitHook.Lease.freshScope()` under the catalog lock; if
  scope changed between writer construction and finish (e.g. concurrent
  `enableEncryption` on the same name), the writer transitions to
  FAILED with R12-clean diagnostics rather than committing v6 footer
  bytes mismatched against current catalog scope.
- `CatalogLock` re-entry on the same JVM is rejected with a clear
  `IllegalStateException` (no `OverlappingFileLockException`); close
  from a non-holder thread is rejected with `IllegalStateException`;
  the OS lock is released only after the JVM lock — no TOCTOU window
  between deletion + lock release (deletion removed entirely;
  `RECLAIM_WINDOW_NS` uses `nanoTime` for monotonicity).

## Cross-references

**ADRs created / exercised by this feature:**
- [`sstable-footer-scope-format`](../../../.decisions/sstable-footer-scope-format/adr.md)
  — created in this feature. v5→v6 footer bump with fixed-position scope
  section after v5 sections; trailing-magic dispatch; CRC32C over scope
  section (R2d); identifier rules (R2b/R2c/R2e); DEK-version-set
  encoding (R3/R3a/R3b)
- [`table-handle-scope-exposure`](../../../.decisions/table-handle-scope-exposure/adr.md)
  v2 — created in this feature. `TableMetadata.encryption: Optional<EncryptionMetadata>`;
  sealed `Table` with two permits (`CatalogTable`, `CatalogClusteredTable`);
  threat-model R13a-c carve-out for `--add-exports`-reachable subclass
  metadata forgery (escalated to spec-author per RELAX-1/3)
- [`pre-ga-format-deprecation-policy`](../../../.decisions/pre-ga-format-deprecation-policy/adr.md)
  — **first exercise.** v1–v4 SSTable formats collapsed in commit
  `db800ca` to clear the deck for v6, validating the policy on its own
  introduction
- [`module-dag-sealed-type-public-factory-carve-out`](../../../.decisions/module-dag-sealed-type-public-factory-carve-out/adr.md)
  — created during retro to formalise the `CatalogClusteredTable.forEngine(...)`
  carve-out. Sealed type's permits live in non-exported packages; their
  package-private constructors are not directly reachable from a
  cooperating exported class in a sibling package within the same module.
  A public static factory in the permit type is the single legitimate
  construction surface and does not violate the spirit of R8f (defence-in-depth
  via non-exported package + non-public constructor remains intact)

**Specs created / amended:**
- `sstable.footer-encryption-scope` — created v5; v5→v9 through TDD/audit
  cycles (R10c writer-finish protocol clarification, R6c message
  language, R8e/R8f/R8g sealed-Table widening, R3c/R3e zero-DEK-set
  carve-out, R12 byte-leak prohibition tightening)
- `engine.clustered-table-construction` v1 — created during
  audit-spec-author. Documents the 4-arg/5-arg
  `CatalogClusteredTable.forEngine` overloads' "transport-only handle"
  contract (RELAX-2 escalation outcome)
- `encryption.ciphertext-envelope` v1→v2 — closed obligations
  OB-ciphertext-envelope-01 (4B prefix + R64 lookup +
  IllegalStateException on miss), -02 (cross-tier byte-identity), -03
  (DCPE seed BE pin)
- `engine.catalog-operations` v2→v3 — R7b 5-step protocol formalised
  with CatalogLock acquisition, CatalogIndex high-water bump,
  rollback-on-partial-failure for `updateEncryption`, deferred-rescan
  in `open()` against cross-process race
- `encryption.primitives-lifecycle` v9→v10 — R22, R22a, R22b, R23, R23a,
  R24 direct-annotated once footer scope landed

**KB entries used / created:**
- Used during authoring:
  [WD-01 footprint](implement-encryption-lifecycle--wd-01.md),
  [WD-03 sstable footprint](implement-sstable-enhancements--wd-03.md)
- Created during audit + retro (2026-04-25):
  [`format-version-deprecation-strategies`](../../systems/database-engines/format-version-deprecation-strategies.md),
  [`stage-then-publish-disk-before-memory`](../../patterns/transactions/stage-then-publish-disk-before-memory.md),
  [`error-message-info-flow-discipline`](../../patterns/validation/error-message-info-flow-discipline.md),
  [`caller-supplied-mutable-input-defensive-snapshot`](../../patterns/validation/caller-supplied-mutable-input-defensive-snapshot.md),
  [`file-lock-handle-resource-lifecycle`](../../patterns/resource-management/file-lock-handle-resource-lifecycle.md),
  [`writer-state-machine-runtime-fault-containment`](../../patterns/resource-management/writer-state-machine-runtime-fault-containment.md)

## Adversarial pipeline summary

| Phase | Findings | Applied |
|-------|----------|---------|
| `/feature-test` (TDD) | 205 tests across 4 work units (37 WU-1 + 68 WU-2 + 35 WU-4 + 65 WU-3) | baseline |
| Pre-existing flake fix | `ClusteredTableScanParallelTest.scan_racingWithClose_noLeakedClients` resolved via explicit `liveClients` registry on `CatalogClusteredTable` (idempotent client close + sweep) | unblocking-only |
| `/audit` run-001 | 45 findings across 4 active lenses (contract_boundaries, dispatch_routing, resource_lifecycle, shared_state); concurrency + data_transformation pruned to zero qualifying constructs | 21 CONFIRMED_AND_FIXED, 5 IMPOSSIBLE (false positives), 5 FIX_IMPOSSIBLE (escalated to /spec-author), 14 COVERED_BY_EXISTING_TEST |

**Audit pipeline health:** confirmation rate 88.9%, fix rate 80.8%,
impossibility rate 19.2%, false-positive rate 11.1%, cross-cluster
unresolved 0. Spec coverage 36/72 requirements exercised (50%). Four
cross-domain compositions (3 same-bug-different-angle, 1
independent-on-same-construct).

**RELAX escalations (5) → /spec-author:**
- RELAX-1/RELAX-3 — `CatalogClusteredTable.metadata()` non-final permits
  subclass forgery via `--add-exports`. Resolved by `module-dag-sealed-type-public-factory-carve-out`
  ADR + `engine.clustered-table-construction` spec; threat-model R13a-c
  carve-out documented as accepted out-of-model.
- RELAX-2 — 4-arg/5-arg `forEngine` overloads silently route through
  transport when `localEngine == null`. Resolved by
  `engine.clustered-table-construction` v1 documenting the
  "transport-only handle" intended contract.
- RELAX-4 — scoped `TrieSSTableWriter` without `commitHook` silently
  bypasses R10c. Resolved by `sstable.footer-encryption-scope` R10c
  amendment permitting the legacy unit-test path with documented
  R10c-bypass semantics; engine path enforces `commitHook` always
  present via `EngineWriterCommitHook`.
- RELAX-5 — `ReadContext(Set.of())` accepts empty set silently. Resolved
  by `sstable.footer-encryption-scope` R3c/R3e/R3f addendum: empty set
  is the spec-mandated representation for zero-DEK-count v6 footers
  (loud failure deferred to envelope dispatch time per R3f, not
  construction).

## Noteworthy constraints and pitfalls

- **v6 footer extends v5 in-place; trailing-magic dispatch sees v6 first.**
  The v5 self-checksum and `MAGIC_V5` bytes are preserved unchanged so
  a v6 file remains parseable as a v5 file up to the v5 footer; the
  trailing scope section + scope-total-size + `MAGIC_V6` is the
  signalling extension. Reader walks back via the u32 scope-total-size
  before invoking `V6Footer.parse`. Any future format bump (v7+) must
  preserve the same trailing-magic-first dispatch invariant.
- **Reader scope source is the `Table` handle, not the footer (R6a).**
  Comparing footer scope to a value derived from the same footer
  (cryptographically bound or not) defeats the cross-scope-rejection
  defence — defence-in-depth requires an external trust source. The
  reader API takes `Optional<TableScope>` rather than reading scope
  from the file; jlsm-engine derives the optional from
  `table.metadata().encryption().map(em -> em.scope())`.
- **R10c TOCTOU defence requires the engine writer factory to wire a
  commit hook — the byte-layer protocol alone is insufficient.** A
  scoped writer constructed without `commitHook` silently bypasses the
  staged-then-publish protocol. The fix added
  `EngineWriterCommitHook` and wired `LocalEngine.createJlsmTable` via
  `TrieSSTableWriter.builder().commitHook(...).tableNameForLock(...)`.
  R10c boundary observation surfaced this as a cross-module gap (the
  byte-layer protocol existed but the engine-side hook was absent).
- **`TableCatalog.updateEncryption` rollback pairs disk + memory state.**
  Without the rollback, `setHighwater` failure after `writeMetadata`
  succeeded leaves disk-vs-memory inconsistency. The fix wraps
  `setHighwater` in a try/catch that rewrites the prior `table.meta`
  using the captured `existing` value + original format version on
  failure; `addSuppressed` records rewrite failures; the `IOException`
  still propagates.
- **`CatalogIndex.setHighwater` is stage-then-publish.** Snapshot
  `entries`, mutate the snapshot, encode bytes, persist via atomic
  rename, then promote the snapshot to the live `entries` reference.
  Publishing the new value to in-memory readers before disk durability
  is a divergence bug we caught at audit.
- **`CatalogLock` per-table-name jvmLocks entries are refcounted.** A
  naive `ConcurrentHashMap` leaks one `ReentrantLock` entry per
  distinct table name forever — every distinct name in the lifetime of
  the JVM contributes a permanent entry. The fix uses a refcounted
  entry type with `compute` + atomic remove on refs==0 + balanced
  release on every failure path including re-entry rejection.
- **`FileBasedCatalogLock` deletion was dropped entirely** (post-release
  `Files.deleteIfExists(lockFile)` removed) — there is no longer a
  window in which an awaiting JVM can grab the file lock while this
  JVM still holds the OS-level lock pointer.
- **`isLivePid` must not depend on `/proc`.** Containers and chroots
  without `/proc` misclassify live PIDs as dead. The fix routes all
  platforms through `ProcessHandle.of(pid).isPresent()`.
- **`RECLAIM_WINDOW_NS` uses `nanoTime`, not wall-clock.** The 5-second
  reclaim window with `System.currentTimeMillis()` is exploitable via
  NTP jumps or admin clock adjustments. Loop guard rewritten as the
  overflow-safe `now - deadline < 0L` form.
- **Module DAG forbids `jlsm-core` from importing `jlsm-engine`.** The
  reader API takes `Optional<TableScope>` and the `WriterCommitHook` is
  a public SPI in `jlsm.sstable` rather than the spec's notional
  `Table` parameter — the engine derives the optional from the table
  handle and supplies it. Captured as the `module-dag-sealed-type-public-factory-carve-out`
  ADR + a follow-up KB pattern: spec authors writing across module
  boundaries must check the DAG before pinning a parameter type.
- **`EnvelopeCodec.parseVersion` and `stripPrefix` share an error type.**
  Both throw `IOException` on under-length input — collapsing
  `IllegalArgumentException` (was on `stripPrefix`) into the checked
  type allows all three call sites to use a single try/catch block;
  audit caught the asymmetry as a contract-boundaries finding.
- **Legacy decryptor registry-miss must surface as `IllegalStateException`.**
  The legacy decryptor path was throwing `UncheckedIOException(IOException)`
  on R64 registry miss; this conflates the state-error channel with
  genuine I/O failure. Caller R24 expects a state error. Fixed.
- **`CiphertextValidator` length formulas all gain 4 bytes** for the
  prefix: SIV 16→20, GCM 28→32, OPE 25→29, DCPE `8 + 4N + 16` →
  `12 + 4N + 16`. Test fixture byte-position offsets across
  `FieldEncryptionDispatchTest` + `BoundedStringOpeTest` shifted +4
  for OPE; resolved.
- **`R8g` test stubs migrated to a package-private `TestTableStubs`
  factory.** `RecordingTable`, `StubTable`, `StubTableImpl`,
  `PermissiveStubTable`, and the anonymous `Table` in
  `ResourceLifecycleAdversarialTest` all live as package-private
  subclasses of `CatalogClusteredTable` in
  `jlsm.engine.cluster.internal` test package. The `non-sealed` permit
  + `--add-exports` reachability is the *named* threat-model carve-out;
  closing it at the type level (`final metadata()`) was deferred to
  spec-author for the RELAX-1/3 escalation rather than fixed in-branch.
- **First exercise of the pre-GA format deprecation policy.** v1–v4
  SSTable formats collapsed (commit `db800ca`) before v6 landed. The
  policy was authored, then *immediately exercised on its own
  introduction* — surfacing one undocumented site (recovery scan) that
  required an explicit "no recovery is supported for collapsed formats"
  decision. KB entry [`format-version-deprecation-strategies`](../../systems/database-engines/format-version-deprecation-strategies.md)
  captures the lesson: pre-GA format collapse is loud
  (`IllegalStateException` on read) rather than silent, and recovery
  paths must be explicitly enumerated as either supporting collapsed
  formats or not.
- **Pre-existing `ClusteredTableScanParallelTest` flake**
  (`scan_racingWithClose_noLeakedClients`) resolved orthogonally during
  coordinator-finalize. Diagnosis: under full-check load the per-future
  `whenComplete` cleanup occasionally fails to fire for 2-3
  `RemotePartitionClient` instances per 50-iteration test run.
  Fix: explicit `liveClients` concurrent-set on `CatalogClusteredTable`
  populated at client creation, swept in `close()` as a safety net;
  `RemotePartitionClient.close()` is idempotent (compareAndSet guard)
  so double-close from `whenComplete` + sweep decrements the
  OPEN_INSTANCES counter at most once per client.

## Prior art displaced (from this feature group)

- **`jlsm.engine.internal.LocalTable`** → renamed to
  `jlsm.engine.internal.CatalogTable`. Same package; no
  external-consumer breakage within this repo.
- **`jlsm.engine.cluster.ClusteredTable`** → renamed and *relocated* to
  `jlsm.engine.cluster.internal.CatalogClusteredTable`. The relocation
  is the breaking change for external consumers of the cluster API.
  `ClusteredEngine.createTable` / `getTable` return type widened from
  `ClusteredTable` to `Table` (sealed). Within this repo, cluster test
  stubs migrated to the `TestTableStubs` factory pattern per R8g.
- **SSTable v1–v4 formats** — collapsed in commit `db800ca` per the
  pre-GA format deprecation policy (first exercise). Readers now reject
  v1–v4 magics with a loud `IllegalStateException`; the recovery scan
  path explicitly does not support collapsed formats.
- **`encryption.primitives-lifecycle` R22b/R23a/R24** — were unimplemented
  before this feature (no SSTable footer scope metadata existed);
  direct-annotation closure deferred to v10 once footer scope landed.

## Related work definitions (same work group)

- WD-01 `encryption.primitives-lifecycle` (key hierarchy) — COMPLETE.
  Provides `TenantId`, `DomainId`, `TableId`, `DekVersion`,
  `EncryptionContext`, `KmsClient` SPI, and `EncryptionKeyHolder`
  consumed by both write + read paths in WD-02.
- WD-03 `encryption.dek-lifecycle-and-rotation` — DRAFT, BLOCKED on WD-02
  (now unblocked). Will add prune (R30), rotation cascade (R32),
  three-state machine (R76), and rekey API (R78) on top of the
  ciphertext envelope and footer scope contracts.
- WD-04 `encryption.compaction-migration` — DRAFT, BLOCKED on WD-02
  (now unblocked). Consumes footer scope + DEK-version set to compute
  convergence and re-encrypt input SSTables to the current DEK version
  during compaction.
- WD-05 `encryption.runtime-concerns` — DRAFT, BLOCKED on WD-02 + WD-03.
  Absorbs the cluster control-plane broadcast/ack ordering layer
  deferred from WD-02 (`ClusteredEngine.createEncryptedTable` /
  `enableEncryption` cross-node propagation), plus leakage profile
  (R44–R54), encrypt-once invariant runtime (R72–R75d), and LRU cache
  eviction.

## Follow-up

- Spec promotion: `sstable.footer-encryption-scope` v9, `encryption.ciphertext-envelope` v2,
  `encryption.primitives-lifecycle` v10, `engine.clustered-table-construction`
  v1, `engine.catalog-operations` v3 — all DRAFT pending `/spec-verify`
  promotion to APPROVED.
- Cluster control-plane broadcast/ack ordering layer for
  `ClusteredEngine.createEncryptedTable` / `enableEncryption` — deferred
  to WD-05 once the cluster control plane has a stable RPC primitive
  for catalog broadcasts. Currently delegates to originating-node's
  `LocalEngine`; cross-node propagation relies on each node's catalog
  refresh on next view change.
- Threat-model R13a-c carve-out documented but not closed at the type
  level — `CatalogClusteredTable.metadata()` is non-`final` to permit
  test-stub override via `--add-exports`. Closing this requires either
  (a) Phase 2 test-file migration to a sealed-permit factory shim or
  (b) accepting documentation-only remediation. Tracked under R8g.
- Zero third-party dependencies added to the build.
