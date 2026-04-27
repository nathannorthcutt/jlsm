# Changelog

All notable changes to jlsm are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project uses sequential PR numbers for version tracking until a formal
semver release cadence is established.

---

## [Unreleased]

### Added — `jlsm-cluster` module + multiplexed transport baseline (implement-transport WD-01)
- **New module:** `modules/jlsm-cluster/` — Gradle subproject with public `jlsm.cluster` package + non-exported `jlsm.cluster.internal`. Module declares `requires jlsm.core`; qualified export `jlsm.cluster.internal to jlsm.engine` for `NodeAddressCodec` (used by membership protocol).
- **Migrated transport SPI types** from `jlsm.engine.cluster` to `jlsm.cluster`: `ClusterTransport`, `Message`, `MessageType`, `MessageHandler`, `NodeAddress`, plus internal helpers `NodeAddressCodec` and `InJvmTransport`. `MessageType` now carries a stable wire `tag()` byte (PING=0x00 .. STATE_DELTA=0x06) plus `fromTag()` reverse mapping. `jlsm-engine` updated to `requires transitive jlsm.cluster`; 60+ files across `jlsm-engine` mainline + tests rewired to import from the new location.
- **`transport.multiplexed-framing` v1 DRAFT → v3 APPROVED** — 2 adversarial-falsification rounds via `/spec-author` (Pass 2: 16 findings; Pass 3: 11 fix-consequence findings). All 27 net-new findings applied. New requirements: R23b (multi-handshake), R26b (write-failure cleanup + idempotent timeout-arming + value-conditional remove + scheduler-failure handling), R34c (response bypass to prevent handler-callback deadlock), R34d (sync vs async handler completion), R37c (per-connection abuse threshold for repeated R37 violations). Modified: R6, R20, R23a, R27, R28, R30, R30a, R37, R37a, R39 (3-option safe-publication), R40 (version + nodeId validation), R45 (counters j/k/l/m). Ambiguity score 0.00; 69 requirements; zero open obligations.
- **`MultiplexedTransport` impl** at `jlsm.cluster.internal.MultiplexedTransport` covering the foundational requirement set (R1-R29 wire format, dispatch, lifecycle; R45 counter surface). Supporting types: `Frame`/`FrameCodec` (R1-R5/R9/R10), `Handshake` (R40 + R40-bidi with version + nodeId + UTF-8 RFC 3629 validation), `PendingMap` (R6/R6a/R8/R26b/R27 with value-conditional removal), `PeerConnection` (R15-R20 reader/writer thread + lifecycle), `TransportMetrics` (R45). Static factory `MultiplexedTransport.start(...)` adopts R39 option (b) safe-publication.
- **Round-trip integration test** (WD-01 acceptance criterion): `MultiplexedTransportRoundTripIntegrationTest` exercises real localhost TCP for fire-and-forget delivery, request-response, 10 concurrent multi-stream requests, and post-close API rejection.
- **New ADR:** `transport-module-placement` confirming Option A (single `jlsm-cluster` module) over Option B (split api+impl) and Option D (SPI in jlsm-core, rejected on cohesion). Conditions for revision documented (thin-client consumer triggers A → B split).
- **Updated ADR `files:` fields:** `connection-pooling` and `transport-abstraction-design` now point at `modules/jlsm-cluster/...` paths.

### Added — Multi-frame reassembly + chunking + bounded dispatch (WD-01 round 2)
- **`Reassembler`** (`jlsm.cluster.internal`) — per-connection, per-stream-id reassembly state machine implementing R35/R35a/R36/R37/R37a/R38/R43a. Handles single-frame fast path, multi-frame buffering, drain state on per-stream limit (R37) or global budget exhaustion (R37a), corruption on type/seq mismatch (R43a). 11 unit tests.
- **`Chunker`** — outbound message chunker per R43/R43a/R44. Splits bodies exceeding `maxFrameSize - 14` into chunks with shared type/seq, MORE_FRAMES on non-final, RESPONSE flag preserved. Rejects oversized fire-and-forget per R44. 8 unit tests.
- **`AbuseTracker`** — sliding-window violation counter for R37c (per-connection abuse threshold; default 4 violations / 60 seconds → close connection). 7 unit tests.
- **`DispatchPool`** — bounded handler-dispatch pool for R34b (default 256 permits + 1024-deep queue). Permit-before-take dispatcher ordering keeps queue depth honest. R20 lazy-drain via per-task liveness check. 6 unit tests.
- **R30 cleanupBarrier + R30a barrier-await** — `peerDeparted(NodeAddress)` splits into atomic step (1) + dispatched step (2); subsequent `getOrConnect` awaits any in-flight cleanup before establishing a new connection. 5 integration tests.
- **R34c response bypass + R34c-bis closed-flag check** — incoming response frames complete pending futures directly on the reader thread (bypassing R34b queue) to prevent handler-callback deadlock; closed-flag check before completion increments R45(k) post-close discards.
- **R34d async handler completion** — dispatch vthread blocks on `response.get()` so the R34b semaphore is held until the handler's CF transitions to a terminal state (sync return OR async completion). 3 integration tests.
- **R43 outbound chunking** — `MultiplexedTransport.send/request` and the response path now route through `PeerConnection.writeMessage` which chunks bodies > `maxFrameSize - 14`. Write lock held for the entire chunk sequence (R15a). 3 large-message integration tests including a 5 MiB request/response round-trip.

### Added — Final WD-01 gap closure (round 3)
- **R23a per-peer locking refactor** — replaces the global `synchronized(peers)` with a per-`nodeId` `ConcurrentHashMap<String, Object>` lock map (stable lock identity; no eviction per R23a v3 lifetime rule). `getOrConnect` and the accept-loop's tie-break now serialize per-peer; distinct peers proceed in parallel.
- **R23b N≥3 simultaneous-handshake stress test** — `MultiplexedTransportSimultaneousHandshakeTest` exercises 8 concurrent same-peer requests, 3 distinct-peer concurrent sends, and 5 concurrent re-joins after `peerDeparted`. All converge on a single per-peer connection.
- **R26b scheduler-failure path** — `request()` now registers the `whenComplete` cleanup BEFORE arming `orTimeout`, and wraps `orTimeout` in a `RuntimeException` catch that completes the future with `IOException("transport unavailable")` and increments `R45(f)` write-failures. `MultiplexedTransportPendingInvariantTest` exercises rapid request cycling (200 round-trips, then forced connection-drop) and asserts the pending map drains to zero per R27.
- **`engine.clustering` `@spec` annotation retargeting** — the migrated SPI types (`ClusterTransport`, `Message`, `MessageType`, `NodeAddress`) now carry both the original `engine.clustering.R*` annotations (unchanged behavior) and supplemental `transport.multiplexed-framing.R*` annotations pointing at the multiplexed-framing spec's wire-format and lifecycle requirements.


- **`sstable.footer-encryption-scope`** spec v4 → v5 — R8e widened to two-permits sealed `Table` declaration (`CatalogTable` + `CatalogClusteredTable`); R8f/R6c/R8g updated to cover both internal classes and the cluster test-stub migration.
- **New jlsm-core types:** `jlsm.encryption.TableScope` (record `(TenantId, DomainId, TableId)`), `jlsm.encryption.EnvelopeCodec` (4-byte BE DEK version prefix codec), `jlsm.encryption.ReadContext` (record carrying `Set<Integer> allowedDekVersions` for the R3e dispatch gate), `jlsm.encryption.internal.IdentifierValidator` (R2e UTF-8 + control-codepoint discipline), `jlsm.sstable.WriterCommitHook` SPI (engine plugs into the writer R10c finish protocol without jlsm-core depending on jlsm-engine), `jlsm.sstable.internal.V6Footer`.
- **New jlsm-engine types:** `jlsm.engine.EncryptionMetadata` (record `(TableScope scope)`), `jlsm.engine.internal.CatalogIndex` (R9a-mono per-table format-version high-water enforcing cold-start + downgrade defence), `jlsm.engine.internal.CatalogLock` + `FileBasedCatalogLock` + `CatalogLockFactory` (per-table exclusive lock SPI shared by enableEncryption R7b and writer finish R10c, with file-lock + PID-with-bounded-reclaim liveness recovery).
- **`Engine` API surface:** `createEncryptedTable(name, schema, scope)` and `enableEncryption(name, scope)` (one-way; in-place disable is deferred per `encryption-disable-policy`).
- **SSTable v6 format:** `SSTableFormat.MAGIC_V6 = 0x4A4C534D53535406L`; v6 layout = `[v5 file] + [scope-section bytes] + [u32 BE scope-total-size] + [MAGIC_V6 8B]`. Encrypted tables emit v6; unencrypted tables continue to emit v5; reader dispatches on magic. R10d emit-order: data + metadata fsync → scope + footerChecksum → pre-magic fsync → magic → post-magic fsync.
- **Per-field envelope:** every encrypted field now begins with a 4-byte big-endian plaintext DEK version tag (R1). On decrypt: strip prefix → R3e Set membership check (no DEK lookup if version not in footer's declared set) → wait-free immutable-map resolveDek → variant decrypt. `CiphertextValidator` length formulas updated: AES-SIV 16→20, AES-GCM 28→32, OPE 25→29, DCPE 8+4N+16 → 12+4N+16.
- **Cross-tier byte-identity test** lands the OB-ciphertext-envelope-02 obligation: identical envelope bytes flow MemTable → WAL → SSTable.
- **205 new unit tests** across 4 work units; jlsm-core + jlsm-engine + jlsm-table + jlsm-indexing + jlsm-vector + jlsm-sql + tests/jlsm-remote-integration `./gradlew check` green.

### Changed — Public API: `ClusteredTable` relocated; `Table` sealed
- **Breaking:** `jlsm.engine.cluster.ClusteredTable` (formerly exported, public) relocated to `jlsm.engine.cluster.internal.CatalogClusteredTable` (non-exported). `ClusteredEngine.createTable` / `getTable` return `Table` (the public sealed interface) instead of `ClusteredTable`. External code consuming `ClusteredTable` directly must migrate to the public `Table` type.
- **Breaking:** `jlsm.engine.Table` is now `public sealed interface Table extends AutoCloseable permits jlsm.engine.internal.CatalogTable, jlsm.engine.cluster.internal.CatalogClusteredTable`. External `implements Table` no longer compiles. Test stubs migrate via the `TestTableStubs` factory in `jlsm.engine.cluster.internal` test scope.
- **Backward-compat:** `LocalTable` renamed to `CatalogTable` (still in `jlsm.engine.internal`). `TableMetadata` gains a 5th component `Optional<EncryptionMetadata> encryption`; the existing 4-arg constructor is preserved.

### Fixed — `ClusteredTableScanParallelTest` orthogonal cleanup leak
- Pre-existing intermittent failure (last touched in `5481ebe` before this branch). Under full `./gradlew check` load the per-future `whenComplete` cleanup occasionally failed to fire under concurrent close + cancel propagation, leaking 2–3 `RemotePartitionClient` instances per 50-iteration test run. `CatalogClusteredTable` now tracks every client created during `scan()` in a concurrent `liveClients` set and sweeps it in `close()` as a safety net. `RemotePartitionClient.close()` is idempotent (`compareAndSet` guard), so double-close from both the `whenComplete` path and the explicit sweep decrements `OPEN_INSTANCES` at most once per client.

### Known Gaps
- **Cluster control-plane ordering for `enableEncryption`**: `ClusteredEngine.enableEncryption` currently routes through the originating node's `LocalEngine` only; cross-node propagation relies on each node's catalog refresh on next view change. Quorum-based broadcast/ack is deferred to WD-05 (runtime concerns) when the cluster control plane has a stable RPC primitive for catalog broadcasts.
- **Active on-disk SSTable tamper**: WD-02 defends against accidental mis-routing and wrong-key derivation (HKDF scope binding from WD-01) but explicitly does NOT defend against active tamper by attackers with write access to SSTable bytes (R13/R13a-c). Delegated to the storage substrate; cryptographic file-integrity defence (manifest MAC, per-block AEAD) is deferred as `sstable-active-tamper-defence`.

### Removed — SSTable formats v1, v2, v3, v4 (pre-GA collapse)
- First exercise of the [pre-GA format-version deprecation policy](.decisions/pre-ga-format-deprecation-policy/adr.md). Writers now always emit v5; readers reject any other magic with `IncompleteSSTableException`. Deleted constants `SSTableFormat.MAGIC`, `MAGIC_V2`, `MAGIC_V3`, `MAGIC_V4`, `FOOTER_SIZE`, `FOOTER_SIZE_V2`, `FOOTER_SIZE_V3`, `FOOTER_SIZE_V4`, `COMPRESSION_MAP_ENTRY_SIZE`, `COMPRESSION_MAP_ENTRY_SIZE_V3`. Removed reader paths `readFooterV1`, `readKeyIndexV1`, and the v1/v2/v3/v4 dispatch branches in `readFooter`. Removed writer paths `writeFooterV1`, `writeFooterV3`, `writeFooterV4`, `finishV3Layout`, `finishWithDictionaryTraining`, plus the dictionary-training Builder surface (`dictionaryTraining`, `dictionaryBlockThreshold`, `dictionaryMaxBufferBytes`, `dictionaryMaxSize`, `formatVersion`) and `DictionaryTrainingResult`. The 2-arg/3-arg public constructors of `TrieSSTableWriter` are retained but now produce v5 (with `NoneCodec` when no codec is supplied). The "non-default blockSize requires a codec" Builder gate is gone — every writer is v5 with stored blockSize.
- Specs `sstable.format-v2` and `sstable.v3-format-upgrade` moved to `DEPRECATED` with `superseded_by: sstable.end-to-end-integrity`; both archived in `.spec/CLAUDE.md`. Manifest entries updated.
- Test files removed: `SSTableFormatV3Test`, `SSTableFormatV4Test`, `SSTableV3IntegrationTest`, `DictionaryCompressionWriterTest`, `DictionaryCompressionReaderTest`. Adversarial tests targeting v3-byte-layout reader bugs disabled with explanatory `@Disabled` reasons (the bugs are masked by stronger v5 integrity guards).

### Added — End-to-end SSTable integrity (implement-sstable-enhancements WD-03)
- `sstable.end-to-end-integrity` spec v2 DRAFT → v4 APPROVED → v5 DRAFT — v4 adopted 43 findings across spec-author Pass 2 (32 findings) + Pass 3 (11 findings); v5 adds 13 new requirements (R44-R56) + 4 refinements (R37/R38/R39/R43) + 2 open obligations (OB-01 writer FAILED-state, OB-02 writer-internal counter invariant) from post-implementation audit
- **5 new constructs in `jlsm-core`:**
  - `jlsm.sstable.CorruptSectionException` (exported) — IOException for metadata-section CRC32C mismatches; carries sectionName + expected/actual `int` checksums rendered `0x%08X`; 6 public `SECTION_*` constants (R42)
  - `jlsm.sstable.IncompleteSSTableException` (exported) — distinguishes partial-writes (missing magic OR file < footer size) from mid-file corruption (R40)
  - `jlsm.sstable.FsyncSkipListener` (exported) — `@FunctionalInterface` invoked when writer skips `force()` on non-FileChannel output (R23)
  - `jlsm.sstable.internal.VarInt` — canonical LEB128 unsigned [1, MAX_BLOCK_SIZE]; rejects 5-byte continuation, overflow, zero, and non-canonical trailing-zero payloads (R1-R6)
  - `jlsm.sstable.internal.V5Footer` — 112-byte big-endian footer record with encode/decode, footer-self-checksum scope `[0..100) ∪ [104..112)` (R16 includes magic), tight-pack validator (R37), dict sentinel helper (R15)
- **TrieSSTableWriter extensions (v5 path):** VarInt block prefix (R1/R3), per-section CRC32C (R13/R14), 3-fsync discipline (R19/R20/R21), FAILED + interrupt-flag preservation (R22), atomic commit via `<final>.partial.<writerId>` + `Files.move ATOMIC_MOVE` (R39), `Builder.fsyncSkipListener(FsyncSkipListener)` + `Builder.formatVersion(int)` opt-in, producer-side invariants on blockCount/mapLength/blockSize (R55), oversized-entry descriptive IOException at `append` boundary (R46), uniform `FileAlreadyExistsException` pre-check across ATOMIC_MOVE + fallback
- **TrieSSTableReader extensions (v5 path):** magic-first dispatch with `IncompleteSSTableException` on missing/unknown magic or sub-footer file (R25/R34/R40), footer-self-checksum verify (R26), speculative v5-hypothesis guard (R52) catches V5→legacy single-bit magic flips, eager-mode all-section verify (R27), lazy-mode atomic first-load (R28), reader FAILED state (R43) transitions on post-open verification failure and rejects subsequent calls with `IllegalStateException` (cause-chain preserved), recoveryScan (R7-R10) with VarInt walk + `blockCount` bound + `currentPos == mapOffset` post-condition, R38 recovery/read mutex via `ReentrantLock` + `AtomicInteger`, `RecoveryScanIterator` implements `AutoCloseable` (audit-driven), tight-packing validation (R37), R18 blockCount/mapLength ≥ 1, int-narrowing guards for `mapLength > 2^31` (R48), `open`/`openLazy` expectedVersion overload for external-authority cross-check (R54)
- **137+ feature tests + ~25 audit adversarial tests** across new classes (`{VarInt, V5Footer, SSTableFormatV5Constants, CorruptSectionException, IncompleteSSTableException, FsyncSkipListener, TrieSSTableWriterV5, TrieSSTableReaderV5, TrieSSTableReaderCorruption, TrieSSTableV5Concurrency}Test.java`) plus per-lens extensions to `{Concurrency, ContractBoundaries, DataTransformation, DispatchRouting}AdversarialTest.java`

### Fixed — Audit round-001 on TrieSSTableWriter + TrieSSTableReader
- **Reader FAILED-state transition was unimplemented** (R43) — no write site for `failureCause`/`failureSection`. Added `transitionToFailed` with ordered publish of failureSection before failureCause; wired into `get()` first-load CorruptSectionException catch
- **v5 per-block CRC32C was gated on a legacy `v3` flag alone** — v5 writes could ship without per-block CRCs. Broadened gate to `if (v3 || formatVersion == 5)`
- **`recoveryScan` / `acquireReaderSlot` check-then-act race** on `recoveryInProgress`/`activeReaderOps` pair — serialized check-and-modify under shared `recoveryLock`
- **`RecoveryScanIterator` abandonment held `recoveryLock` forever** — iterator now implements `AutoCloseable` with idempotent `close()` via `releaseOnceExhausted()`
- **Single-bit V5→legacy magic flip bypassed footer self-checksum** — speculative v5-hypothesis substitution in `readFooter` rejects as `CorruptSectionException(SECTION_FOOTER)` before legacy-branch dispatch
- **`ATOMIC_MOVE` silently overwrote existing output while non-atomic fallback threw `FileAlreadyExistsException`** — added pre-existence check so commit behavior is uniform across filesystems
- **`readBytes` could spin indefinitely** on stalled remote SeekableByteChannel providers — bounded to 1024 consecutive zero-progress reads with descriptive IOException
- **Int-narrowing guards** on v5 footer for mapOffset/mapLength/idxLength/fltLength/dictLength — prevents `IllegalArgumentException` from `ByteBuffer.allocate(-n)` when a crafted footer declares `length > Integer.MAX_VALUE`
- **`readBytes` negative-length guard** — rejects at boundary with IOException before `ByteBuffer.allocate`
- **`dictBufferedBytes` counter was not reset on buffer abandon** — counter-buffer pair now updates as a unit
- **`writeFooterV5` had no producer-side invariant guard** — now validates `blockCount ≥ 1`, `mapLength ≥ 1`, `blockSize` power-of-two in [MIN, MAX], `entryCount ≥ 1` before encoding
- **Torn volatile publish in `checkNotFailed`** — diagnostic could render `reader failed: null`; added `<pending>` sentinel when `failureSection` not yet set, while preserving cause chain
- **Ctor-failure unwind IMSE masking originating CorruptSectionException** — guarded outer-finally unlock with `recoveryLock.isHeldByCurrentThread()`
- **Silent CRC-bypass fallback in recovery scan's else branch** — replaced with `IllegalStateException` defensive guard
- **Tight-packing lower bound (`mapOffset == 0`) not validated** — added first-section-offset-must-be-positive guard (R37)
- **Legacy-branch (v1/v2/v3/v4) footer-structural failures leaked opaque IOException** — rewrapped via `validateFooterOrCorruptSection` into `CorruptSectionException`
- **Empty bloom-filter section (`fltLength = 0`) bypassed CRC check** (CRC32C of empty input = 0) — now rejected as `CorruptSectionException(SECTION_BLOOM_FILTER)` before CRC gate

### Changed — 9 pre-existing v3/v4 regression test sites
Updated to call `.formatVersion(3)` since codec-configured writer now defaults to v5. Test files: `SSTableV3IntegrationTest`, `DictionaryCompressionWriterTest`, `DictionaryCompressionReaderTest`.

### Added — 9 KB adversarial-finding entries (from audit)
- `patterns/concurrency/torn-volatile-publish-multi-field.md`
- `patterns/concurrency/check-then-act-across-paired-acquire-release.md`
- `patterns/resource-management/iterator-without-close-holds-coordination.md`
- `patterns/resource-management/unbounded-zero-progress-channel-read-loop.md`
- `patterns/resource-management/atomic-move-vs-fallback-commit-divergence.md`
- `patterns/validation/silent-fallthrough-integrity-defense-coupled-to-flag.md`
- `patterns/validation/dispatch-discriminant-corruption-bypass.md`
- `patterns/validation/version-discovery-self-only-no-external-cross-check.md`
- `patterns/validation/integer-overflow-silent-truncation.md` — "Updates 2026-04-22" section appended covering file-format length-field attack surface

### Known Gaps
- Spec v5 remains DRAFT; `/spec-verify` needed to promote to APPROVED
- 4 `@Disabled` WU-2 tests require infrastructure (Jimfs in-memory NIO FS for remote-provider tests, channel-factory injection for fsync-count assertions, interrupt-timing stability for ClosedByInterruptException path)
- OB-01: writer FAILED-state ratification at every IOException write-site (impl fixed; spec ratification deferred to avoid R3/R22 spec-code conflict)
- OB-02: writer-internal counter-buffer pair invariant (dictBufferedBytes ↔ dictBufferedBlocks)

### Added — Pool-aware block size (implement-sstable-enhancements WD-02)
- `sstable.pool-aware-block-size` spec v2 DRAFT → v5 APPROVED — 22 adversarial findings across spec-author Pass 2/3, all applied. Final v5 adds audit-surfaced ArenaBufferPool lifecycle requirements (R0b/c/d/e) and Builder-wide atomicity (R11b)
- `ArenaBufferPool.isClosed()` (R0) — canonical closure observer; class-final mandate (R0a) prevents subclass spoofing
- `ArenaBufferPool.bufferSize()` (R1) — backing field is mandated `final` (R1a) for safe publication without synchronization; returns configured value after close (R2)
- `TrieSSTableWriter.Builder.pool(ArenaBufferPool)` — derives block size from `pool.bufferSize()` when no explicit `blockSize(int)` override; eager R8 overflow + R9 validator checks at `pool()` call time for fail-fast symmetry with the explicit setter
- Build-time closed-pool re-check (R5a) catches pools closed between `pool()` and `build()`, scoped to the case where the pool is still the active block-size source
- Repeat-`pool()` atomicity (R11a) — failed validation is a no-op on all builder state; `Builder.build()` atomicity (R11b) extends this across the full fluent-API surface
- 43 new feature tests (10 in `ArenaBufferPoolTest`, 33 in new `TrieSSTableWriterPoolAwareBlockSizeTest`) covering every requirement + N1/N4 observable non-goals
- One-sentence README update describing the pool-derived block-size flow

### Fixed — Audit round-001 on ArenaBufferPool and TrieSSTableWriter.Builder
- **`ArenaBufferPool.close()` is now thread-safe** — `AtomicBoolean.compareAndSet(false, true)` replaces check-then-act; `Arena.ofShared().close()` (non-idempotent in JDK 25) no longer throws `IllegalStateException` under concurrent invocation
- **`isClosed()` publishes a happens-before edge** to Arena teardown — split into `closing` (atomic claim) + `closed` (post-teardown ack) so observers of `isClosed() == true` are guaranteed the Arena has been released
- **ArenaBufferPool constructor is transactional** — allocation-loop failures (including `OutOfMemoryError`) now close the partially-initialized Arena before propagating; NMT measurement confirms ~27 MiB leak eliminated per failed ctor
- **`close()` refuses to tear down while acquires are outstanding** — throws `IllegalStateException` with outstanding count instead of silently invalidating in-use segments
- **`TrieSSTableWriter.Builder.build()` no longer mutates `bloomFactory` on failed validation** — default lambda resolved into a method-local `final` variable only after all validation gates pass; retry with corrected configuration is now clean
- 5 regression adversarial tests added in two new `SharedStateAdversarialTest.java` + `ConcurrencyAdversarialTest.java` classes

### Added — KB pattern entries
- Updated `patterns/concurrency/non-atomic-lifecycle-flags.md` + `patterns/resource-management/non-idempotent-close.md` — ArenaBufferPool as confirmed instance, nuance on flag-after-teardown ordering when callee is non-idempotent
- New `patterns/validation/partial-init-no-rollback.md` — ctor off-heap variant + Builder commit-before-validate variant (both confirmed by audit)
- New `patterns/validation/mutation-outside-rollback-scope.md` — Builder-specific variant, guidance to resolve defaults into method-local `final` variables
- New `patterns/resource-management/pool-close-with-outstanding-acquires.md` — fail-fast lifecycle protocol for explicit-acquire pools

### Known Gaps
- R0b/R0c/R0d/R0e (ArenaBufferPool lifecycle requirements) currently live in `sstable.pool-aware-block-size` v5 as prerequisite amendments. They should migrate to a dedicated `io.arena-buffer-pool` spec when that is authored (noted in the v5 spec's design narrative)

### Added — Byte-budget block cache (implement-sstable-enhancements WD-01)
- `sstable.byte-budget-block-cache` spec v2 DRAFT → v4 APPROVED — byte-budget LRU displacing entry-count; 40+ requirements covering chokepoint structural enforcement (R6/R7), overflow protection (R29), oversized-entry admission (R11), zero-length rejection (R9/R9a), entry-count cap (R28a), reference lifetime contract (R15a), constructor-side sentinel detection (R3a), close-ordering assertions (R16), and use-after-close guards (R31)
- `LruBlockCache.Builder.byteBudget(long)` replaces removed `capacity(long)`; new `byteBudget()` accessor; transactional setter semantics (R2)
- `StripedBlockCache.Builder.byteBudget(long)` / `expectedMinimumBlockSize(long)` (R20a/R20b); constructor-side MAX_STRIPE_COUNT revalidation (R18a); partial-construction rollback closes already-constructed stripes (R48)
- `BlockCache` interface Javadoc: `capacity()` unit is bytes, `close()` mandates use-after-close ISE, default `getOrLoad` documents monitor-collision risk
- 45 new tests in `ByteBudgetBlockCacheTest.java` across 7 @Nested classes covering byte-budget eviction, overflow protection, R11 oversized-entry admission, R8 put-replace atomicity, R9/R9a zero-length rejection, R32 loader-exception guarantees, MemorySegment slice-size accounting, and reference-lifetime contracts
- 3 KB pattern entries: `reflective-bypass-of-builder-validation`, `interface-contract-missing-from-javadoc`, `fan-out-dispatch-deferred-exception-pattern`

### Changed — `sstable.striped-block-cache` spec v2 → v4
- v3 in-place amendment: R8, R9, R15, R43, R44 invalidated with strike-through notes pointing at superseding byte-budget requirements; R-number gaps preserved so 28 existing `@spec` annotations remain valid
- v4 audit reconciliation: R5 extended for deferred-exception eviction, R11 relaxed to permit non-linear splitmix64 pre-avalanche (defeats algebraic pre-image collisions), R46 extended for ISE translation across stripes
- `splitmix64Hash` gets pre-avalanche multiply-XOR-shift round before combining sstableId with blockOffset — cache key distribution changes on deploy (one-time cache rewarm, no on-disk format change)

### Performance
- Fixed-byte-budget eviction replaces fixed-entry-count eviction — cache memory usage is now predictable and proportional to configured byte budget, regardless of block size variation across SSTables (4 KiB local to 8 MiB remote)
- Added one multiply-XOR-shift per stripe dispatch (~1–2ns on x86) as the cost of eliminating algebraic hash pre-image collisions

### Fixed — Audit round-001 reconciliation
- `subtractBytes` invariant now enforced by runtime check (was `assert`-only, disappeared with `-da`)
- `StripedBlockCache.evict` uses deferred-exception pattern — exceptions from one stripe no longer abort fan-out to others
- `StripedBlockCache.size` translates concurrent-close ISE to a striped-level ISE (consistent with get/put/evict/getOrLoad)
- Reflective-bypass callers of `<init>(Builder)` now get `IllegalArgumentException` with the same diagnostic as `build()` (previously leaked sentinel values or threw `NegativeArraySizeException`)

### Known Gaps
- R28a entry-count cap test (`entryCountCap_R28a_rejectsNearIntegerMaxValue`) is `@Disabled` — requires ~Integer.MAX_VALUE entries which is impractical in a unit test; R28a is enforced in the implementation regardless

### Added — Spec coverage gap closure (close-coverage-gaps WD-01 + WD-02)
- `engine.clustering` v6 → v7 promoted DRAFT → APPROVED — 114/114 requirements reach direct `@spec` annotation coverage (impl + test)
- `engine.in-process-database-engine` v3 → v4 promoted DRAFT → APPROVED — 89/91 traced (R61 and R79 documented UNTESTABLE, retained as impl-only)
- `query.index-types` v1 promoted DRAFT → APPROVED — 31/31 requirements traced
- `query.query-executor` v1 promoted DRAFT → APPROVED — 22/22 requirements traced
- New test `LocalEngineTest.closeContinuesClosingRemainingTablesWhenOneFails` (R78); new tests for R4 / R12 / R19 / R20 in jlsm-table including `ModuleBoundariesTest` covering module-exports boundary

### Added — Encryption architecture decisions (implement-encryption-lifecycle WD-01)
- ADR `three-tier-key-hierarchy` (confirmed) — Tenant KEK → data-domain KEK → DEK; per-tenant KMS isolation always-on; 3 KMS flavors (`none` / `local` / `external`); HKDF hybrid deterministic derivation; sharded per-tenant registry; synthetic `_wal` domain per tenant; plaintext bounded to ingress; primary keys remain plaintext
- ADR `dek-scoping-granularity` (confirmed) — DEK identity is `(tenantId, domainId, tableId, dekVersion)`; per-SSTable and per-object scopes rejected by the encrypt-once invariant
- ADR `tenant-key-revocation-and-external-rotation` (confirmed) — `rekey` API with proof-of-control sentinel; streaming paginated execution with dual-reference migration; three-state per-tenant failure machine (N=5 permanent-failure / 1h grace defaults); opt-in polling
- ADR `kms-integration-model` (confirmed) — `KmsClient` SPI + transient/permanent exception hierarchy; 30 min cache TTL; 3-retry exponential backoff (100 ms → 400 ms → 1.6 s, ±25 % jitter); 10 s per-call timeout; encryption context carries `tenantId` + `domainId` + `purpose`
- ADR `tenant-lifecycle` (deferred) — decommission + data-erasure semantics parked until compliance requirement surfaces
- Amends `encryption-key-rotation` and `per-field-key-binding` (both previously assumed two-tier)

### Changed — Encryption spec v6 APPROVED (implement-encryption-lifecycle WD-01)
- `encryption.primitives-lifecycle` v4 DRAFT → v6 APPROVED — ~40 requirements amended for three-tier + new R71–R82b plus R32b-1, R32c, R75c, R75d, R78c-1, R78f, R80a-1
- Adversarial Pass 5 landed 3 Critical + 7 High + 6 Medium + 7 Low findings; all Critical + High + selected Low fixed in v6
- Verification Note added to `wal.encryption` — F42's "KEK" parameter resolves internally to the tenant's `_wal`-domain DEK-resolver; no F42 requirement text changes

### Added — Research KB entries (staged ahead of architect phases)
- `.kb/systems/security/three-level-key-hierarchy{,-detail}.md` — envelope, HKDF-info domain separation, wrap-primitive choices, reference designs
- `.kb/data-structures/caching/byte-budget-cache-variable-size-entries{,-detail}.md` — admission modes, W-TinyLFU weight-blind admission, pin-count overrun
- `.kb/systems/database-engines/pool-aware-sstable-block-sizing{,-detail}.md` — `block_size == pool.slotSize`, Panama FFM alignment constraints, jemalloc 25 % fragmentation bound

### Known Gaps
- `encryption.primitives-lifecycle` v6 is APPROVED but unimplemented (obligation `implement-f41-lifecycle` remains). Downstream encryption WDs (WD-02 ciphertext format, WD-03 DEK lifecycle + rotation, WD-04 compaction migration, WD-05 runtime concerns) are blocked on WD-01 implementation.
- 6 Medium adversarial findings (M1, M2, M4, M6) tracked for v6.1 amendment during implementation; M3 and M5 effectively resolved in v6.

### Added — Fault Tolerance and Smart Rebalancing (WD-05)
- `ClusterOperationalMode` enum (`NORMAL`, `READ_ONLY`) + `ClusteredEngine.operationalMode()` accessor — engine transitions to `READ_ONLY` when quorum is lost (F04.R41)
- `QuorumLostException` (checked `IOException` subtype) — thrown by `ClusteredTable.create/update/delete/insert` while the engine is in `READ_ONLY` mode; reads remain available (F04.R41)
- `SeedRetryTask` — background task that reinvokes `membership.start(seeds)` on a configurable interval while quorum is lost; idempotent start/stop (F04.R42)
- `ViewReconciler.reconcile(localView, proposedView)` — pure per-member merge applying higher-incarnation-wins with severity `DEAD > SUSPECTED > ALIVE` on ties; called from `RapidMembership.handleViewChangeProposal` before view installation (F04.R43)
- `GraceGatedRebalancer` — scheduled coordinator that drains `GracePeriodManager.expiredDepartures()` and invokes `RendezvousOwnership.differentialAssign(...)` for only the departed member's partitions; `cancelPending(NodeAddress)` aborts a pending rebalance when a node rejoins within grace (F04.R47, R48, R50)
- `RendezvousOwnership.differentialAssign(oldView, newView, affectedPartitionIds)` — partial recomputation that mutates cache entries only for the supplied partition IDs, so assignments for still-live members' partitions remain stable (F04.R48)
- `PartitionKeySpace` SPI — `partitionForKey`, `partitionsForRange`, `partitionCount`, `allPartitions`; thread-safe and immutable after construction (F04.R63)
- `SinglePartitionKeySpace` — trivial fallback mapping every key to one partition (no pruning, backward-compat)
- `LexicographicPartitionKeySpace(splitKeys, partitionIds)` — range-based partition layout with binary-search lookup; enables scan pruning to only overlapping partitions
- `RendezvousOwnership.ownersForKeyRange(tableName, fromKey, toKey, view, keyspace)` — resolves the set of owners whose partitions intersect `[fromKey, toKey)` (F04.R63)
- F04 spec version 5 → 6: R41–R43, R47–R50, R63 rewritten forward to describe shipped behaviour; `open_obligations` now empty
- 115 new tests across 11 test classes: `ViewReconcilerTest`, `SeedRetryTaskTest`, `GraceGatedRebalancerTest`, `RendezvousOwnershipDifferentialTest`, `RapidMembershipReconciliationTest`, `ClusteredTableReadOnlyTest`, `ClusteredEngineQuorumTest`, `SinglePartitionKeySpaceTest`, `LexicographicPartitionKeySpaceTest`, `ClusteredTableScanPruningTest`, `RendezvousOwnershipOwnersForKeyRangeTest`

### Changed — Fault Tolerance and Smart Rebalancing (WD-05)
- `ClusteredEngine.onViewChanged` — evaluates `newView.hasQuorum(config.consensusQuorumPercent())` on every view change and transitions `operationalMode` accordingly; replaces the prior immediate-rebalance logic with a grace-gated pathway that records departures into `GracePeriodManager` and lets `GraceGatedRebalancer` drive rebalancing asynchronously
- `RapidMembership.handleViewChangeProposal` — when a higher-epoch proposal is accepted (subject to R90's no-drop-alive check), delegates per-member reconciliation to `ViewReconciler.reconcile(...)` instead of overwriting the local view wholesale
- `ClusteredTable` — gained an 8-arg canonical constructor accepting `(TableMetadata, ClusterTransport, MembershipProtocol, NodeAddress, RendezvousOwnership, Engine, PartitionKeySpace, Supplier<ClusterOperationalMode>)`; legacy constructors delegate to it with `SinglePartitionKeySpace("default")` and a `() -> NORMAL` mode supplier (backward-compat)
- `ClusteredTable.scan(fromKey, toKey)` — delegates owner resolution to `RendezvousOwnership.ownersForKeyRange(...)` using the configured `PartitionKeySpace`; extracted `resolveScanOwners` and `emptyScanWithMetadata` helpers; preserves R60 local short-circuit, R77 parallel fanout, R100 client close, R67 ordered merge, R64 partial metadata
- `ClusteredTable.create/update/delete/insert` — consult `operationalMode` supplier at method entry and throw `QuorumLostException` when `READ_ONLY`
- `RendezvousOwnership` is now non-`final` to permit in-tree test spying (`GraceGatedRebalancerTest`); behaviour is unchanged

### Performance — Fault Tolerance and Smart Rebalancing (WD-05)
- Scans narrowed by `LexicographicPartitionKeySpace` contact only the partitions whose lexicographic range overlaps `[fromKey, toKey)` instead of every live member — scatter cost now scales with the number of intersecting partitions, not cluster size
- `differentialAssign` avoids full-cache invalidation on member departure: only the departed member's partition IDs are recomputed, so stable assignments on still-live members incur zero cache-miss cost after rebalance

### Known Gaps — Fault Tolerance and Smart Rebalancing (WD-05)
- Table-to-`PartitionKeySpace` configuration is currently by constructor argument; there is no declarative `TableMetadata` or SQL path to assign a range-partitioned layout yet — pruning is opt-in via the new `ClusteredTable` ctor overload
- `SeedRetryTask` retry interval is a construction parameter with no live tuning; per-retry failures are logged and swallowed without surfacing backoff state to the caller

### Added — Wire Query Binding Through StringKeyedTable (WD-03)
- `jlsm.table.QueryRunner<K>` — public functional interface (one method: `run(Predicate)`) used as the bridge between `TableQuery.execute()` and the internal `QueryExecutor` so table implementations can plug in an execution backend without leaking `jlsm.table.internal` types on the builder API
- `TableQuery.unbound()` and `TableQuery.bound(QueryRunner)` — explicit public factories replacing reflection-based construction for the unbound form; internal callers use `bound(...)` to wire a runner
- `JlsmTable.StringKeyed.query()` — default interface method returning an unbound `TableQuery<String>`; production implementations override it to return a bound instance
- `StringKeyedTable.query()` — returns a `TableQuery<String>` bound to the table's schema and `IndexRegistry` via `QueryExecutor.forStringKeys(...)`; empty predicate trees yield an empty iterator rather than an exception
- F05 spec v2 → v3: R37 rewritten forward — `table.query()` now returns a functional `TableQuery` bound to the table's storage and indices; UOE is retained only for schemaless tables. `OBL-F05-R37` resolved.
- 9 new tests in `TableQueryExecutionTest`: index-backed equality, scan-fallback on unindexed field, AND across index + scan predicates, OR union, empty result, Gte scan fallback, schema-mismatch IAE, predicate-tree inspection, unbound `execute()` UOE

### Changed — Wire Query Binding Through StringKeyedTable (WD-03)
- `StandardJlsmTable.StringKeyedBuilder` now materialises an `IndexRegistry` whenever a schema is configured, even with zero index definitions — the registry's document store acts as the schema-aware mirror used for scan-and-filter fallback. Schema-less tables continue to have no registry (and no queries).
- `LocalTable.query()` (jlsm-engine) no longer throws `UnsupportedOperationException` — it delegates to the underlying `JlsmTable.StringKeyed.query()`
- `FullTextTableIntegrationTest.noIndexDefinitions_tableBehavesAsBefore` updated to assert that the registry is present and empty instead of null (the `registry != null && isEmpty()` contract)
- `LocalTableTest.queryThrowsUnsupportedOperationException` renamed and rewritten to `queryReturnsUnboundTableQueryFromStub` — verifies the new delegation contract against a stub delegate

### Fixed — Wire Query Binding Through StringKeyedTable (WD-03)
- Long-standing known gap: `Table.query()` no longer throws `UnsupportedOperationException` for schema-configured `StringKeyed` tables — predicate execution routes through `QueryExecutor`, using registered secondary indices where supported and scan-and-filter fallback otherwise

### Added — Wire Full-Text Index Integration (WD-01)
- `jlsm.core.indexing.FullTextIndex.Factory` — SPI producing `FullTextIndex<MemorySegment>` per `(tableName, fieldName)`, the module-boundary contract between `jlsm-table` and `jlsm-indexing`
- `jlsm.indexing.LsmFullTextIndexFactory` — LSM-backed factory isolating each index on its own `LocalWriteAheadLog` + `TrieSSTable` + `LsmInvertedIndex.StringTermed` + `LsmFullTextIndex.Impl` chain
- `StandardJlsmTable.StringKeyedBuilder.addIndex(IndexDefinition)` and `.fullTextFactory(FullTextIndex.Factory)` — table-builder surface for registering secondary indices with the required factory; rejects FULL_TEXT with no factory at `build()`
- `StringKeyedTable` now routes `create/update/delete` through an optional `IndexRegistry` so FULL_TEXT indices stay synchronised with the primary tree
- F10 spec v2 → v3: R5/R79-R84 rewritten forward to describe the delegation contract; new Amendments section summarises WD-01
- 31 new tests: 18 in `FullTextFieldIndexTest` (adapter semantics against a fake backing), 9 in `LsmFullTextIndexFactoryTest` (factory round-trip against a real LSM-backed index), 4 in `FullTextTableIntegrationTest` (end-to-end: builder + registry + factory through `JlsmTable.StringKeyed`)

### Changed — Wire Full-Text Index Integration (WD-01)
- `IndexRegistry` gained a three-arg constructor accepting a `FullTextIndex.Factory`; the two-arg constructor remains and delegates with `null`. FULL_TEXT definitions without a factory now fail fast with `IllegalArgumentException` at construction instead of throwing `UnsupportedOperationException` on the first write
- `FullTextFieldIndex` is no longer a stub — it adapts `SecondaryIndex` mutations to the batch `FullTextIndex.index`/`remove` API and translates `FullTextMatch` predicates to `Query.TermQuery`
- `jlsm-indexing` test classpath now includes `jlsm-table` (test-only — production code still depends one way: `jlsm-table → jlsm-core`; `jlsm-indexing → jlsm-core`)
- `ResourceLifecycleAdversarialTest` + `IndexRegistryEncryptionTest` updated to reflect the new failure mode (IAE on missing factory / injectable-factory-that-throws) rather than the prior FULL_TEXT stub UOE

### Removed — Wire Full-Text Index Integration (WD-01)
- Obligation `OBL-F10-fulltext` resolved (removed from F10 `open_obligations`); WD-01 marked `COMPLETE` in the cross-module-integration work group

### Known Gaps — Wire Full-Text Index Integration (WD-01)
- Query-time wiring through `JlsmTable.query()` / `TableQuery.execute()` is still scope of `OBL-F05-R37` (a separate WD). The current PR exposes a `StringKeyedTable.indexRegistry()` accessor so integration tests can drive `SecondaryIndex.lookup` directly until that binding lands
- `LongKeyedTable` has not been wired for secondary indices — deferred; no WD caller currently requires it
- `FullTextIndex.Factory` does not yet thread through a shared `ArenaBufferPool`; each per-index LSM tree owns its own WAL + memtable allocations

### Added — Wire Vector Index Integration (WD-02)
- `VectorIndex.Factory` nested SPI in `jlsm.core.indexing.VectorIndex` — bridges `jlsm-table` and `jlsm-vector` without a static module dependency. Keyed on `(tableName, fieldName, dimensions, precision, similarityFunction)`; implementations pick the algorithm (IvfFlat vs Hnsw).
- `LsmVectorIndexFactory` in `jlsm-vector` — concrete factory producing `LsmVectorIndex` instances under per-(table, field) subdirectories. Two static builders: `ivfFlat(Path root, int numCentroids)` and `hnsw(Path root, int maxConnections, int efConstruction)`.
- `StandardJlsmTable.stringKeyedBuilder().vectorFactory(VectorIndex.Factory)` — optional builder parameter; tables that register a `VECTOR` index without a factory fail fast at `build()` with `IllegalArgumentException` instead of silently dropping writes.
- `VectorFieldIndex` (production implementation, in `jlsm.table.internal`) — adapts per-field `SecondaryIndex` mutation callbacks (`onInsert`/`onUpdate`/`onDelete`) to `VectorIndex.index/remove`; translates `VectorNearest(field, query, topK)` predicates to `VectorIndex.search(query, topK)` returning primary keys; handles null old-vector (update after unset field) and null new-vector (delete-semantics insert) without throwing.
- F10 spec v3 → v4: R87–R90 extended to describe the vector factory SPI and shipped behaviour; R6 promoted PARTIAL → SATISFIED; `open_obligations` now empty (both `OBL-F10-fulltext` and `OBL-F10-vector` resolved).
- 3 new test classes: `VectorFieldIndexTest` (in-memory backing + lifecycle), `LsmVectorIndexFactoryTest` (IvfFlat/Hnsw factory construction), `VectorTableIntegrationTest` (end-to-end JlsmTable + VECTOR index + nearest-neighbour query).

### Changed — Wire Vector Index Integration (WD-02)
- `VectorFieldIndex.onInsert/onUpdate/onDelete` no longer silent no-ops — writes to tables with `VECTOR` indices are now actually indexed (or the build fails fast). Previously the stub silently dropped all vector writes, a data-integrity hazard for tables with `VECTOR` indices.
- `VectorFieldIndex.supports(Predicate)` now returns `true` for `VectorNearest` whose field matches the index's field; previously always threw `UnsupportedOperationException`.
- `VectorFieldIndex.lookup(VectorNearest)` returns nearest-neighbour primary keys via the backing `VectorIndex.search`; previously threw `UnsupportedOperationException`.
- `IndexRegistry` gained a four-arg constructor `(schema, definitions, fullTextFactory, vectorFactory)` accepting both factories; the three-arg overload is retained for call-sites that do not register VECTOR indices.
- `VectorIndex` interface gained `precision()` accessor so `VectorFieldIndex` can validate incoming vectors match the configured precision.

### Fixed — Wire Vector Index Integration (WD-02)
- Silent-drop hazard: tables registering a `VECTOR` index previously accepted writes that were never indexed. This PR eliminates that path — all `VECTOR` writes either persist to the backing index or fail at `build()` time.

### Removed — Wire Vector Index Integration (WD-02)
- `OBL-F10-vector` flipped to `resolved` in `.spec/registry/_obligations.json`. Together with WD-01's resolution of `OBL-F10-fulltext`, `F10.open_obligations` is now empty.

### Known Gaps — Wire Vector Index Integration (WD-02)
- `LongKeyedTable` (integer-keyed table variant) is not wired for secondary indices. No caller currently creates a `LongKeyedTable` with a secondary index, so this is deferred until there is one.
- The factory does not yet use a shared `ArenaBufferPool` — each backing `LsmVectorIndex` allocates its own arena. Revisit if multi-index memory pressure becomes a concern.

### Added — Remote Dispatch and Parallel Scatter (WD-03)
- `QueryRequestPayload` — shared encoder/decoder for cluster `QUERY_REQUEST` payloads with `[tableNameLen][tableName UTF-8][partitionId][opcode][body]` format (F04.R68)
- `QueryRequestHandler` — server-side `MessageHandler` that routes `QUERY_REQUEST` messages to the correct local table via `Engine.getTable(name)` and serializes the `QUERY_RESPONSE`
- `PartitionClient.getRangeAsync(...)` — new default interface method returning `CompletableFuture<Iterator<TableEntry<String>>>` for async scatter-gather (F04.R77)
- `MembershipProtocol.removeListener(...)` — new default SPI method for ctor-failure rollback
- F04 spec version 4 → 5: 13 new requirements R102–R114 (ctor/close ordering, listener rollback, scatter cancellation propagation, response encoder overflow guard, merge iterator null-key rejection, range decode malformed-payload semantics)
- 3 new KB adversarial-finding patterns: `unsafe-this-escape-via-listener-registration`, `local-failure-masquerading-as-remote-outage`, `timeout-wrapper-does-not-cancel-source-future`
- 103 tests total across WD-03 (61 cycle-1 + 24 adversarial hardening + 18 audit)

### Changed — Remote Dispatch and Parallel Scatter (WD-03)
- `RemotePartitionClient` ctors now require a trailing `String tableName` parameter (breaking); all payload encoders use the new `QueryRequestPayload` format carrying table name + partition id
- `RemotePartitionClient.getRangeAsync(...)` override — transport-based async path with `orTimeout`, explicit source-future cancellation, and defensive handling of null/truncated/malformed responses
- `ClusteredTable.scan(...)` — parallel fanout via virtual-thread scatter executor + `CompletableFuture.allOf`; preserves local short-circuit (R60), ordered k-way merge (R67), partial-result metadata (R64), and per-node client close on every path (R100)
- `ClusteredEngine` — registers `QueryRequestHandler` in the constructor after all final fields are assigned; symmetric deregister before `transport.close()` in `close()`; rollback of membership listener if handler registration throws
- `ClusteredEngine.onViewChanged` — no-ops when close has begun, preventing post-close state mutations

### Performance — Remote Dispatch and Parallel Scatter (WD-03)
- `ClusteredTable.scan` fans out remote partition requests in parallel; prior implementation issued partitions sequentially with each request blocking on the previous response. Virtual-thread scatter executor keeps fanout non-blocking even when the transport layer has synchronous delivery semantics
- `QueryRequestHandler.handle` reads the incoming payload once instead of twice, halving defensive-clone allocation per dispatch

### Fixed — Remote Dispatch and Parallel Scatter (WD-03)
- `RemotePartitionClient.close()` check-then-set race that could corrupt `OPEN_INSTANCES` counter under concurrent close (replaced `volatile boolean` with `AtomicBoolean.compareAndSet`)
- `ClusteredTable.mergeOrdered` — explicit `IllegalStateException` on null `TableEntry` instead of `AssertionError`/NPE propagation from the heap comparator
- `decodeRangeResponsePayload` — distinguishes legitimately empty range from a populated payload with null schema (previously silent data loss)
- `getRangeAsync` — encoding errors propagate synchronously so upstream scatter logic does not mis-classify a local failure as a remote node outage
- `QueryRequestHandler.encodeRangeResponse` — `Math.addExact` overflow guard + `OutOfMemoryError` catch surface response-too-large as `IOException`
- `join()` rollback catches `Exception` so a checked-exception-leaking `DiscoveryProvider.deregister` no longer hides the original `membership.start` failure
- 12 additional issues surfaced by adversarial audit across concurrency, resource_lifecycle, and shared_state domains

### Removed — Remote Dispatch and Parallel Scatter (WD-03)
- Obligations `OBL-F04-R68-payload-table-id` and `OBL-F04-R77-parallel-scatter` resolved (removed from F04 open obligations); `WD-03` marked `COMPLETE` in the work group manifest

### Known Gaps — Remote Dispatch and Parallel Scatter (WD-03)
- `RemotePartitionClient.doQuery(...)` still returns an empty list — scored-entry response framing over the cluster transport is not yet wired (deferred)
- Wire-format versioning / magic byte deferred to a coordinated protocol spec cycle
- `doGet` null-schema conflation with not-found deferred pending a pre-existing test relaxation

### Added — SSTable v3 format
- SSTable v3 format: per-block CRC32C checksums for silent corruption detection and configurable block size for remote-backend optimization
- `CorruptBlockException` — diagnostic `IOException` subclass with block index and checksum mismatch details
- `TrieSSTableWriter.Builder` — new builder API for v3 format with `blockSize()` and `codec()` configuration
- `SSTableFormat` — v3 constants: `MAGIC_V3`, `FOOTER_SIZE_V3`, `HUGE_PAGE_BLOCK_SIZE` (2 MiB), `REMOTE_BLOCK_SIZE` (8 MiB), `validateBlockSize()`
- `CompressionMap` — v3 21-byte entries with CRC32C checksum, version-aware `deserialize(data, version)`
- F16 spec: SSTable v3 format upgrade (24 requirements)
- 32 new tests covering v3 write/read, backward compatibility, corruption detection, block size validation

### Known Gaps
- Old constructors still produce v2 files — v3 requires explicit opt-in via `TrieSSTableWriter.builder()`

---

### Added
- Engine clustering: peer-to-peer cluster membership, table/partition ownership, and scatter-gather queries in `jlsm-engine`
- `jlsm.engine.cluster` package: `ClusteredEngine`, `ClusteredTable`, `NodeAddress`, `ClusterConfig`, `Message`, `MembershipView`, `PartialResultMetadata`
- SPI interfaces: `ClusterTransport`, `DiscoveryProvider`, `MembershipProtocol`, `MembershipListener`
- `RapidMembership` — Rapid protocol with phi accrual failure detection
- `RendezvousOwnership` — HRW hashing for stateless partition-to-node assignment
- `GracePeriodManager` — configurable grace period before rebalancing on node departure
- `RemotePartitionClient` — serializes CRUD operations over cluster transport
- In-JVM test implementations: `InJvmTransport`, `InJvmDiscoveryProvider`
- 6 ADRs: cluster-membership-protocol, partition-to-node-ownership, rebalancing-grace-period-strategy, scatter-gather-query-execution, discovery-spi-design, transport-abstraction-design
- KB: cluster-membership-protocols (SWIM, Rapid, phi accrual)
- 172 new tests (340 total in jlsm-engine)

### Known Gaps
- `ClusteredTable.scan()` returns empty iterators — full document serialization over transport deferred
- `Table.query()` and `insert(JlsmDocument)` throw `UnsupportedOperationException` in clustered mode

---

## #21 — In-Process Database Engine (2026-03-19)

### Added
- New `jlsm-engine` module — in-process database engine with multi-table management
- `Engine` and `Table` interfaces with interface-based handle pattern
- `HandleTracker` — per-source handle lifecycle tracking with greedy-source-first eviction
- `TableCatalog` — per-table metadata directories with lazy recovery and partial-creation cleanup
- `LocalEngine` wires the full LSM stack (WAL, MemTable, SSTable, DocumentSerializer) per table
- ADR: engine-api-surface-design (interface-based handle pattern with lease eviction)
- ADR: table-catalog-persistence (per-table metadata directories)
- KB: catalog-persistence-patterns (4 patterns evaluated)
- 134 tests across 9 test classes

### Known Gaps
- `Table.query()` throws `UnsupportedOperationException` — `TableQuery` has a private constructor preventing cross-module instantiation

---

## #20 — Field-Level Encryption (2026-03-19)

### Added
- Field-level encryption support in `jlsm-core`: AES-GCM, AES-SIV, OPE (Boldyreva), DCPE-SAP
- `FieldEncryptionDispatch` for coordinating per-field encryption in document serialization
- Pre-encrypted document ingestion via `JlsmDocument.preEncrypted()`
- Searchable encryption via OPE-indexed fields
- `BoundedString` field type for OPE range bounds
- ADR: encrypted-index-strategy, pre-encrypted-document-signaling, bounded-string-field-type

### Performance
- Encryption hot-path optimized to avoid redundant key derivation

---

## #19 — DocumentSerializer Optimization (2026-03-19)

### Performance
- Heap fast path for deserialization — avoids MemorySegment allocation for common cases
- Precomputed constants and dispatch table for field type routing
- Measurable reduction in deserialization latency for schema-driven reads

---

## #18 — Streaming Block Decompression (2026-03-18)

### Performance
- Stream block decompression during v2 compressed SSTable scans
- Eliminates full-block materialization before iteration — reduces peak memory and latency

---

## #17 — Block-Level SSTable Compression (2026-03-18)

### Added
- `CompressionCodec` interface in `jlsm-core` with pluggable codec support
- `DeflateCodec` implementation with configurable compression level
- SSTable v2 format with per-block compression and framing
- ADR: compression-codec-api-design, sstable-block-compression-format

---

## #16 — Vector Field Type (2026-03-17)

### Added
- `FieldType.VectorType` with configurable element type (FLOAT16, FLOAT32) and fixed dimensions
- `Float16` half-precision floating-point support in `jlsm-table`
- ADR: vector-type-serialization-encoding (flat encoding, no per-vector metadata)

---

## #15 — Striped Block Cache (2026-03-17)

### Added
- `StripedBlockCache` — multi-threaded block cache using splitmix64 stripe hashing
- Configurable stripe count for concurrent access patterns
- ADR: stripe-hash-function (Stafford variant 13)

### Performance
- Significant reduction in cache contention under concurrent read workloads

---

## #14 — Block Cache Benchmark (2026-03-17)

### Added
- `LruBlockCacheBenchmark` JMH regression benchmark
- perf-review findings documented in `perf-output/findings.md`

---

## #13 — Table Partitioning (2026-03-17)

### Added
- Range-based table partitioning in `jlsm-table`
- `PartitionedTable`, `PartitionConfig`, `PartitionDescriptor` APIs
- Per-partition co-located secondary indices
- ADR: table-partitioning (range partitioning chosen over hash)

---

## #12 — JMH Benchmarking Infrastructure (2026-03-17)

### Added
- JMH benchmarking infrastructure with shared `jmh-common.gradle`
- `jlsm-bloom-benchmarks` and `jlsm-tree-benchmarks` modules
- Two run modes: snapshot (2 forks, 5 iterations) and sustained (1 fork, 30 iterations)
- JFR recording and async-profiler integration

### Performance
- Three performance fixes identified and applied during initial profiling

---

## #11 — Architecture Review (2026-03-16)

### Changed
- Codebase cleanup from architecture review pass
- ADR index and history updates

---

## #10 — SQL Parser (2026-03-16)

### Added
- New `jlsm-sql` module — hand-written recursive descent SQL SELECT parser
- Supports WHERE, ORDER BY, LIMIT, OFFSET, MATCH(), VECTOR_DISTANCE(), bind parameters
- Translates SQL queries into jlsm-table `Predicate` tree
- Schema validation at translation time

---

## #9 — Secondary Indices and Query API (2026-03-16)

### Added
- Secondary index support in `jlsm-table`: scalar field indices, full-text, vector
- Fluent `TableQuery` API with predicate tree (AND, OR, comparison operators)
- `QueryExecutor` for index-accelerated query execution

---

## #8 — Float16 Vector Support (2026-03-16)

### Added
- Half-precision floating-point (`Float16`) support
- Vector field storage with Float16 element type

---

## #7 — Vallorcine Integration (2026-03-16)

### Added
- Vallorcine tooling integration for feature development workflow
- TDD pipeline automation

---

## #6 — Document Table Module (2026-03-16)

### Added
- New `jlsm-table` module — schema-driven document model
- `JlsmSchema`, `JlsmDocument`, `FieldType`, `FieldDefinition`
- `DocumentSerializer` with schema-driven binary serialization
- CRUD operations via `JlsmTable.StringKeyed` and `JlsmTable.LongKeyed`
- JSON and YAML parsing/writing (no external libraries)

---

## #5 — Full-Text Search (2026-03-16)

### Added
- `LsmInvertedIndex` and `LsmFullTextIndex` in `jlsm-indexing`
- Tokenization pipeline with Porter stemming and English stop word filtering

---

## #3 — Module Consolidation (2026-03-16)

### Changed
- Consolidated 7 separate implementation modules into single `jlsm-core` module
- All interfaces and implementations now co-located for simpler dependency management

---

## #2 — S3 Remote Integration (2026-03-16)

### Added
- `jlsm-remote-integration` test module with S3Mock
- `RemoteWriteAheadLog` — one-file-per-record WAL for object store backends
- Remote filesystem support via Java NIO FileSystem SPI

---

## #1 — Initial Implementation (2026-03-16)

### Added
- Core LSM-Tree components: MemTable, SSTable (TrieSSTableWriter/Reader), WAL (LocalWriteAheadLog)
- Bloom filters (simple and blocked 512-bit variants)
- Compaction strategies: size-tiered, leveled, SPOOKY
- LRU block cache with off-heap support
- `StandardLsmTree` and typed wrappers (StringKeyed, LongKeyed, SegmentKeyed)
- JPMS module structure with `jlsm-core` as the foundation
- Full test suite with JUnit Jupiter
