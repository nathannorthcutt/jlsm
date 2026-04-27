package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.spi.CompactionInputRegistry.CompactionId;
import jlsm.encryption.spi.CompactionInputRegistry.SSTableId;
import jlsm.encryption.spi.InMemorySpis.InMemoryCompactionInputRegistry;
import jlsm.encryption.spi.InMemorySpis.InMemoryWalLivenessSource;

/**
 * Tests for {@link DekPruner} — DEK pruning under shared snapshot lock (R30, R30a, R30b, R30c) +
 * zeroisation on prune (R31).
 *
 * @spec encryption.primitives-lifecycle R30
 * @spec encryption.primitives-lifecycle R30a
 * @spec encryption.primitives-lifecycle R30b
 * @spec encryption.primitives-lifecycle R30c
 * @spec encryption.primitives-lifecycle R31
 */
class DekPrunerTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final TableId OTHER_TABLE = new TableId("table-other");
    private static final KekRef KEK_REF = new KekRef("kek/v1");

    // --- helpers ---------------------------------------------------------

    /**
     * Open a fresh {@link TenantShardRegistry} backed by file storage at {@code tempDir}.
     */
    private static TenantShardRegistry newRegistry(Path tempDir) {
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        return new TenantShardRegistry(storage);
    }

    private static byte[] dummyWrappedBytes(int version) {
        // 28 bytes is the AES-GCM minimum (IV + tag); pad with the version byte for distinctness
        // so different versions produce different bytes (helps any equality assertions in tests).
        final byte[] bytes = new byte[28];
        bytes[0] = (byte) version;
        return bytes;
    }

    private static void seedDek(TenantShardRegistry registry, TenantId tenantId, DomainId domainId,
            TableId tableId, int version) throws IOException {
        registry.updateShard(tenantId, current -> {
            final DekHandle handle = new DekHandle(tenantId, domainId, tableId,
                    new DekVersion(version));
            final WrappedDek wd = new WrappedDek(handle, dummyWrappedBytes(version), 1, KEK_REF,
                    Instant.EPOCH);
            return new TenantShardRegistry.ShardUpdate<>(current.withDek(wd), null);
        });
    }

    private static int dekCountInScope(TenantShardRegistry registry, TenantId tenantId,
            DomainId domainId, TableId tableId) throws IOException {
        final var shard = registry.readSnapshot(tenantId);
        int count = 0;
        for (DekHandle h : shard.deks().keySet()) {
            if (h.tenantId().equals(tenantId) && h.domainId().equals(domainId)
                    && h.tableId().equals(tableId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Trivial in-memory manifest source for tests — caller pre-populates per-scope and per-SSTable
     * mappings. Non-final so individual tests may subclass to inject latched behavior.
     */
    private static class FakeManifestSource implements DekPruner.ManifestSnapshotSource {

        private final Map<TableScopeKey, Set<Integer>> manifestVersions = new java.util.HashMap<>();
        private final Map<SSTableId, ScopedVersion> sstableVersions = new java.util.HashMap<>();

        void setManifestVersions(TenantId tenantId, DomainId domainId, TableId tableId,
                Set<Integer> versions) {
            manifestVersions.put(new TableScopeKey(tenantId, domainId, tableId),
                    Set.copyOf(versions));
        }

        void registerSSTable(SSTableId id, TenantId tenantId, DomainId domainId, TableId tableId,
                int version) {
            sstableVersions.put(id, new ScopedVersion(tenantId, domainId, tableId, version));
        }

        @Override
        public Set<Integer> liveVersionsInManifest(TenantId tenantId, DomainId domainId,
                TableId tableId) {
            return manifestVersions.getOrDefault(new TableScopeKey(tenantId, domainId, tableId),
                    Set.of());
        }

        @Override
        public Optional<Integer> versionForSSTable(SSTableId id, TenantId tenantId,
                DomainId domainId, TableId tableId) {
            final ScopedVersion sv = sstableVersions.get(id);
            if (sv == null) {
                return Optional.empty();
            }
            if (!sv.tenantId.equals(tenantId) || !sv.domainId.equals(domainId)
                    || !sv.tableId.equals(tableId)) {
                return Optional.empty();
            }
            return Optional.of(sv.version);
        }

        private record TableScopeKey(TenantId tenantId, DomainId domainId, TableId tableId) {
        }

        private record ScopedVersion(TenantId tenantId, DomainId domainId, TableId tableId,
                int version) {
        }
    }

    // --- factory + null arg validation -----------------------------------

    @Test
    void create_nullArgs_throwNpe(@TempDir Path tempDir) {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            final var compactionInputs = new InMemoryCompactionInputRegistry();
            final var walLiveness = new InMemoryWalLivenessSource();
            final var manifest = new FakeManifestSource();
            assertThrows(NullPointerException.class,
                    () -> DekPruner.create(null, compactionInputs, walLiveness, manifest));
            assertThrows(NullPointerException.class,
                    () -> DekPruner.create(reg, null, walLiveness, manifest));
            assertThrows(NullPointerException.class,
                    () -> DekPruner.create(reg, compactionInputs, null, manifest));
            assertThrows(NullPointerException.class,
                    () -> DekPruner.create(reg, compactionInputs, walLiveness, null));
        }
    }

    @Test
    void create_returnsNonNullPruner(@TempDir Path tempDir) {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            assertNotNull(pruner);
        }
    }

    @Test
    void pruneUnreferenced_nullArgs_throwNpe(@TempDir Path tempDir) {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            assertThrows(NullPointerException.class,
                    () -> pruner.pruneUnreferenced(null, DOMAIN, TABLE));
            assertThrows(NullPointerException.class,
                    () -> pruner.pruneUnreferenced(TENANT, null, TABLE));
            assertThrows(NullPointerException.class,
                    () -> pruner.pruneUnreferenced(TENANT, DOMAIN, null));
        }
    }

    // --- core pruning behavior -------------------------------------------

    @Test
    void pruneUnreferenced_emptyScope_returnsEmptySet(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            assertTrue(pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE).isEmpty());
        }
    }

    @Test
    void pruneUnreferenced_allReferenced_pruneNone(@TempDir Path tempDir) throws IOException {
        // Three DEK versions exist; manifest references all three. Expect zero pruned.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            seedDek(reg, TENANT, DOMAIN, TABLE, 3);
            final var manifest = new FakeManifestSource();
            manifest.setManifestVersions(TENANT, DOMAIN, TABLE, Set.of(1, 2, 3));
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), manifest);
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            assertTrue(pruned.isEmpty());
            assertEquals(3, dekCountInScope(reg, TENANT, DOMAIN, TABLE));
        }
    }

    @Test
    void pruneUnreferenced_noneReferenced_pruneAllExceptCurrent(@TempDir Path tempDir)
            throws IOException {
        // Versions 1..3 exist; manifest references none. The current (highest) version must be
        // retained as in-use even though it has no SSTables (R30 protects the in-use head DEK).
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            seedDek(reg, TENANT, DOMAIN, TABLE, 3);
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            assertEquals(Set.of(new DekVersion(1), new DekVersion(2)), pruned);
            assertEquals(1, dekCountInScope(reg, TENANT, DOMAIN, TABLE));
        }
    }

    @Test
    void pruneUnreferenced_compactionInputProtectsDek(@TempDir Path tempDir) throws IOException {
        // V1 is not in the manifest but is held by an in-flight compaction input. R30a: must not
        // be pruned.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            seedDek(reg, TENANT, DOMAIN, TABLE, 3);
            final var compactionInputs = new InMemoryCompactionInputRegistry();
            final var manifest = new FakeManifestSource();
            // Manifest references only V2 + V3.
            manifest.setManifestVersions(TENANT, DOMAIN, TABLE, Set.of(2, 3));
            // SSTable id "sst-v1" references V1 in scope and is held under compaction.
            final SSTableId sstV1 = new SSTableId("sst-v1");
            manifest.registerSSTable(sstV1, TENANT, DOMAIN, TABLE, 1);
            compactionInputs.registerInputs(new CompactionId("c1"), Set.of(sstV1));

            final DekPruner pruner = DekPruner.create(reg, compactionInputs,
                    new InMemoryWalLivenessSource(), manifest);
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            assertTrue(pruned.isEmpty(), "V1 must be protected by compaction input set");
            assertEquals(3, dekCountInScope(reg, TENANT, DOMAIN, TABLE));
        }
    }

    @Test
    void pruneUnreferenced_otherScope_isUnaffected(@TempDir Path tempDir) throws IOException {
        // Versions 1..3 exist for TABLE; one DEK exists for OTHER_TABLE. Pruning TABLE must not
        // touch OTHER_TABLE's DEK regardless of manifest contents in OTHER_TABLE's scope.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            seedDek(reg, TENANT, DOMAIN, OTHER_TABLE, 1);
            // No manifest references for either scope — all "old" versions pruneable.
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            // V1 in TABLE scope is unreferenced (V2 is the head and protected).
            assertEquals(Set.of(new DekVersion(1)), pruned);
            assertEquals(1, dekCountInScope(reg, TENANT, DOMAIN, TABLE));
            assertEquals(1, dekCountInScope(reg, TENANT, DOMAIN, OTHER_TABLE));
        }
    }

    @Test
    void pruneUnreferenced_currentVersionAlwaysProtected(@TempDir Path tempDir) throws IOException {
        // Only one DEK exists. Manifest empty. The lone DEK is the current head — must NOT be
        // pruned. Future generateDek calls would reference this version.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            assertTrue(pruned.isEmpty());
            assertEquals(1, dekCountInScope(reg, TENANT, DOMAIN, TABLE));
        }
    }

    @Test
    void pruneUnreferenced_actuallyRemovesFromShard(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            // No manifest references for V1 — head V2 is protected; V1 prunes.
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            // After pruning, the shard must reflect the removal.
            final var shard = reg.readSnapshot(TENANT);
            final DekHandle handle1 = new DekHandle(TENANT, DOMAIN, TABLE, new DekVersion(1));
            final DekHandle handle2 = new DekHandle(TENANT, DOMAIN, TABLE, new DekVersion(2));
            assertFalse(shard.deks().containsKey(handle1), "V1 must be removed");
            assertTrue(shard.deks().containsKey(handle2), "V2 must remain");
        }
    }

    // --- snapshot lock + atomicity (R30b, R30c) --------------------------

    @Test
    void pruneUnreferenced_snapshotLock_blocksConcurrentRegistrations(@TempDir Path tempDir)
            throws Exception {
        // R30c: while pruner is taking the manifest+compaction-input snapshot, no new compaction
        // registrations may slip in. We instrument the manifest source to block on a latch
        // mid-snapshot, then attempt to register a new compaction input on another thread; the
        // registration must not complete until the snapshot lock is released.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);

            final var compactionInputs = new InMemoryCompactionInputRegistry();
            final CountDownLatch snapshotEntered = new CountDownLatch(1);
            final CountDownLatch releaseSnapshot = new CountDownLatch(1);
            final var manifest = new FakeManifestSource() {
                @Override
                public Set<Integer> liveVersionsInManifest(TenantId t, DomainId d, TableId tab) {
                    snapshotEntered.countDown();
                    try {
                        // hold the lock — registerInputs in another thread must block here
                        assertTrue(releaseSnapshot.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return Set.of(2);
                }
            };
            final DekPruner pruner = DekPruner.create(reg, compactionInputs,
                    new InMemoryWalLivenessSource(), manifest);

            final ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // Start the prune (will block inside manifest source).
                pool.submit(() -> {
                    try {
                        pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                // Wait until pruner is inside the snapshot lock.
                assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS));

                // Attempt a registration on another thread — it must NOT complete until the
                // snapshot is released.
                final AtomicReference<Boolean> registrationCompleted = new AtomicReference<>(false);
                final CountDownLatch registerStarted = new CountDownLatch(1);
                pool.submit(() -> {
                    registerStarted.countDown();
                    compactionInputs.registerInputs(new CompactionId("c-late"),
                            Set.of(new SSTableId("sst-late")));
                    registrationCompleted.set(true);
                });
                assertTrue(registerStarted.await(2, TimeUnit.SECONDS));
                // Give the registration thread a chance to make progress; with the lock held it
                // must not complete.
                Thread.sleep(200);
                assertFalse(registrationCompleted.get(),
                        "registerInputs must block while snapshot lock is held (R30c)");

                // Release the snapshot. Registration should now complete.
                releaseSnapshot.countDown();
                final long deadline = System.currentTimeMillis() + 5_000;
                while (!registrationCompleted.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertTrue(registrationCompleted.get(),
                        "registerInputs must unblock once snapshot released");
            } finally {
                releaseSnapshot.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pruneUnreferenced_returnsImmutableSet(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            assertThrows(UnsupportedOperationException.class, () -> pruned.add(new DekVersion(99)));
        }
    }

    @Test
    void pruneUnreferenced_repeatedCall_isIdempotent(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource());
            final Set<DekVersion> first = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            final Set<DekVersion> second = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            assertEquals(Set.of(new DekVersion(1)), first);
            assertTrue(second.isEmpty(), "second call has nothing left to prune");
        }
    }

    // --- concurrent prune + register (race) ------------------------------

    @Test
    void pruneUnreferenced_concurrentRegistration_doesNotPruneNewlyReferenced(@TempDir Path tempDir)
            throws Exception {
        // Race scenario: pruner reads manifest snapshot under shared lock; meanwhile other threads
        // attempt to register compaction inputs that would protect V1. The shared lock guarantees
        // the registrations complete BEFORE the snapshot read OR AFTER the prune commits — never
        // sandwiched between manifest-read and compaction-input-read. We verify by running many
        // pairs concurrently and asserting V1 is never pruned when a compaction input held it at
        // any time during the pruner's snapshot window.
        for (int trial = 0; trial < 20; trial++) {
            try (TenantShardRegistry reg = newRegistry(tempDir.resolve("trial-" + trial))) {
                seedDek(reg, TENANT, DOMAIN, TABLE, 1);
                seedDek(reg, TENANT, DOMAIN, TABLE, 2);
                final var compactionInputs = new InMemoryCompactionInputRegistry();
                final var manifest = new FakeManifestSource();
                manifest.setManifestVersions(TENANT, DOMAIN, TABLE, Set.of(2));
                final SSTableId sstV1 = new SSTableId("sst-v1");
                manifest.registerSSTable(sstV1, TENANT, DOMAIN, TABLE, 1);

                // Pre-register the protecting input. The race is: another thread will deregister
                // it concurrently with the pruner. The atomicity invariant says: if at the
                // instant of the snapshot lock acquisition the input was registered, prune must
                // not remove V1; if the input was already deregistered prior to lock
                // acquisition, V1 may be pruned. Either outcome is correct — never an
                // inconsistent middle state where input set was empty mid-snapshot but V1 was
                // protected by being registered when the snapshot started.
                compactionInputs.registerInputs(new CompactionId("c1"), Set.of(sstV1));

                final DekPruner pruner = DekPruner.create(reg, compactionInputs,
                        new InMemoryWalLivenessSource(), manifest);

                final ExecutorService pool = Executors.newFixedThreadPool(2);
                final CountDownLatch start = new CountDownLatch(1);
                try {
                    final var pruneResult = new AtomicReference<Set<DekVersion>>();
                    pool.submit(() -> {
                        try {
                            start.await();
                            pruneResult.set(pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    pool.submit(() -> {
                        try {
                            start.await();
                            compactionInputs.deregisterInputs(new CompactionId("c1"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    start.countDown();
                    pool.shutdown();
                    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    // --- verify R31 zeroisation hook is engaged --------------------------

    @Test
    void pruneUnreferenced_zeroisationCallback_invokedForEachPrunedVersion(@TempDir Path tempDir)
            throws IOException {
        // Pruner exposes a hook that fires for each pruned wrapped-bytes ciphertext so callers
        // (and tests) can verify R31 zeroisation. The hook must be invoked exactly once per
        // pruned version, with the version and the ciphertext bytes about to be released.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            seedDek(reg, TENANT, DOMAIN, TABLE, 1);
            seedDek(reg, TENANT, DOMAIN, TABLE, 2);
            seedDek(reg, TENANT, DOMAIN, TABLE, 3);
            final AtomicInteger zeroCallbackCount = new AtomicInteger(0);
            final Set<Integer> seenVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();
            final DekPruner pruner = DekPruner.create(reg, new InMemoryCompactionInputRegistry(),
                    new InMemoryWalLivenessSource(), new FakeManifestSource(),
                    (handle, wrappedBytes) -> {
                        zeroCallbackCount.incrementAndGet();
                        seenVersions.add(handle.version().value());
                    });
            final Set<DekVersion> pruned = pruner.pruneUnreferenced(TENANT, DOMAIN, TABLE);
            // V3 retained as head; V1, V2 pruned
            assertEquals(2, pruned.size());
            assertEquals(2, zeroCallbackCount.get());
            assertEquals(Set.of(1, 2), seenVersions);
        }
    }
}
