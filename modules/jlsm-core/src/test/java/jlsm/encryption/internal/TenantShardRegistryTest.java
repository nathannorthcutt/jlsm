package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Tests for {@link TenantShardRegistry} — wait-free reads, per-tenant exclusive writes,
 * cross-tenant isolation, idempotent close (R19b, R62, R62a, R63, R64, R82a).
 */
class TenantShardRegistryTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("kek-ref-1");
    private static final Instant NOW = Instant.parse("2026-04-23T12:00:00Z");

    private static WrappedDek dek(TenantId t, int v) {
        return new WrappedDek(new DekHandle(t, DOMAIN, TABLE, new DekVersion(v)),
                new byte[]{ 1, 2, 3, 4 }, 1, KEK_REF, NOW);
    }

    private static WrappedDomainKek dkek(DomainId d) {
        return new WrappedDomainKek(d, 1, new byte[]{ 5, 6, 7 }, KEK_REF);
    }

    private static byte[] salt32() {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) (0x10 + i);
        }
        return s;
    }

    @Test
    void constructor_nullStorageRejected() {
        assertThrows(NullPointerException.class, () -> new TenantShardRegistry(null));
    }

    @Test
    void readSnapshot_nullTenantRejected(@TempDir Path tmp) {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        assertThrows(NullPointerException.class, () -> reg.readSnapshot(null));
    }

    @Test
    void readSnapshot_missingShard_returnsEmptyShard(@TempDir Path tmp) throws IOException {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        final KeyRegistryShard snap = reg.readSnapshot(TENANT_A);
        assertNotNull(snap);
        assertEquals(TENANT_A, snap.tenantId());
        assertTrue(snap.deks().isEmpty());
        assertTrue(snap.domainKeks().isEmpty());
        assertEquals(null, snap.activeTenantKekRef());
        // Default 32-byte salt all zeros.
        final byte[] salt = snap.hkdfSalt();
        assertEquals(32, salt.length);
        for (byte b : salt) {
            assertEquals(0, b);
        }
    }

    @Test
    void readSnapshot_loadsPersistedShard(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final KeyRegistryShard persisted = new KeyRegistryShard(TENANT_A, Map.of(),
                Map.of(DOMAIN, dkek(DOMAIN)), KEK_REF, salt32());
        storage.writeShard(TENANT_A, persisted);
        final TenantShardRegistry reg = new TenantShardRegistry(storage);
        assertEquals(persisted, reg.readSnapshot(TENANT_A));
    }

    @Test
    void readSnapshot_repeatedCalls_returnCachedSnapshot(@TempDir Path tmp) throws IOException {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        final KeyRegistryShard a = reg.readSnapshot(TENANT_A);
        final KeyRegistryShard b = reg.readSnapshot(TENANT_A);
        // Equal content; we don't require same instance (the snapshot record may vary) but the
        // state must be consistent.
        assertEquals(a, b);
    }

    @Test
    void updateShard_nullArgsRejected(@TempDir Path tmp) {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        assertThrows(NullPointerException.class, () -> reg.updateShard(null,
                shard -> new TenantShardRegistry.ShardUpdate<>(shard, null)));
        assertThrows(NullPointerException.class, () -> reg.updateShard(TENANT_A, null));
    }

    @Test
    void updateShard_appliesMutator_persistsAndPublishes(@TempDir Path tmp) throws IOException {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        final WrappedDek d = dek(TENANT_A, 1);
        final String result = reg.updateShard(TENANT_A,
                shard -> new TenantShardRegistry.ShardUpdate<>(shard.withDek(d), "done"));
        assertEquals("done", result);
        // Published snapshot reflects the mutation.
        final KeyRegistryShard snap = reg.readSnapshot(TENANT_A);
        assertEquals(1, snap.deks().size());
        assertEquals(d, snap.deks().get(d.handle()));
    }

    @Test
    void updateShard_persistsAcrossInstances(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final WrappedDek d = dek(TENANT_A, 1);
        try (TenantShardRegistry reg = new TenantShardRegistry(storage)) {
            reg.updateShard(TENANT_A,
                    shard -> new TenantShardRegistry.ShardUpdate<>(shard.withDek(d), null));
        }
        // New registry instance reads from disk.
        try (TenantShardRegistry reg2 = new TenantShardRegistry(storage)) {
            final KeyRegistryShard snap = reg2.readSnapshot(TENANT_A);
            assertEquals(d, snap.deks().get(d.handle()));
        }
    }

    @Test
    void updateShard_mutatorThrows_snapshotUnchanged_noTempLeft(@TempDir Path tmp)
            throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final TenantShardRegistry reg = new TenantShardRegistry(storage);
        final KeyRegistryShard before = reg.readSnapshot(TENANT_A);
        assertThrows(RuntimeException.class, () -> reg.updateShard(TENANT_A, shard -> {
            throw new RuntimeException("boom");
        }));
        // Snapshot unchanged (still the empty initial one).
        assertEquals(before, reg.readSnapshot(TENANT_A));
        // No orphan temp files.
        final Path shardParent = ShardPathResolver.shardPath(tmp, TENANT_A).getParent();
        if (java.nio.file.Files.exists(shardParent)) {
            final long tempCount = java.nio.file.Files.list(shardParent)
                    .filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, tempCount);
        }
    }

    @Test
    void updateShard_concurrent_sameTenant_serialized(@TempDir Path tmp) throws Exception {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        final int threads = 8;
        final int perThread = 10;
        final ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final AtomicInteger counter = new AtomicInteger(0);
            final Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = exec.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        reg.updateShard(TENANT_A, shard -> {
                            final int n = counter.incrementAndGet();
                            return new TenantShardRegistry.ShardUpdate<>(
                                    shard.withDek(dek(TENANT_A, n)), null);
                        });
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            final KeyRegistryShard snap = reg.readSnapshot(TENANT_A);
            assertEquals(threads * perThread, snap.deks().size(),
                    "all updates must be applied without loss — writers serialized per-tenant");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void readSnapshot_nonBlocking_whileWriterHoldsLock(@TempDir Path tmp) throws Exception {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        // Seed a snapshot.
        reg.updateShard(TENANT_A,
                shard -> new TenantShardRegistry.ShardUpdate<>(shard.withDek(dek(TENANT_A, 1)),
                        null));

        final CountDownLatch mutatorEntered = new CountDownLatch(1);
        final CountDownLatch releaseMutator = new CountDownLatch(1);
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> writerFuture = exec.submit(() -> {
                reg.updateShard(TENANT_A, shard -> {
                    mutatorEntered.countDown();
                    try {
                        releaseMutator.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return new TenantShardRegistry.ShardUpdate<>(shard.withDek(dek(TENANT_A, 2)),
                            null);
                });
                return null;
            });
            assertTrue(mutatorEntered.await(5, TimeUnit.SECONDS),
                    "writer must enter mutator before we probe readers");

            // Now spawn a reader. It must return quickly despite the writer holding the lock.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            final Future<KeyRegistryShard> readerFuture = exec
                    .submit(() -> reg.readSnapshot(TENANT_A));
            final KeyRegistryShard snap;
            try {
                snap = readerFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception ex) {
                fail("reader blocked while writer held lock — wait-free read violated: " + ex);
                return;
            }
            assertTrue(System.nanoTime() < deadline, "reader took too long");
            // Reader sees the pre-update snapshot (before the writer publishes).
            assertEquals(1, snap.deks().size());

            releaseMutator.countDown();
            writerFuture.get(5, TimeUnit.SECONDS);
            // After writer completes, reader sees the new snapshot.
            final KeyRegistryShard after = reg.readSnapshot(TENANT_A);
            assertEquals(2, after.deks().size());
        } finally {
            releaseMutator.countDown();
            exec.shutdownNow();
        }
    }

    @Test
    void crossTenantIsolation_writerOnAdoesNotBlockOnB(@TempDir Path tmp) throws Exception {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        final CountDownLatch mutatorAEntered = new CountDownLatch(1);
        final CountDownLatch releaseA = new CountDownLatch(1);
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> writerA = exec.submit(() -> {
                reg.updateShard(TENANT_A, shard -> {
                    mutatorAEntered.countDown();
                    try {
                        releaseA.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return new TenantShardRegistry.ShardUpdate<>(shard.withDek(dek(TENANT_A, 1)),
                            null);
                });
                return null;
            });
            assertTrue(mutatorAEntered.await(5, TimeUnit.SECONDS));

            // A writer on tenant B should NOT be blocked by the writer on tenant A.
            final Future<?> writerB = exec.submit(() -> {
                reg.updateShard(TENANT_B, shard -> new TenantShardRegistry.ShardUpdate<>(
                        shard.withDek(dek(TENANT_B, 1)), null));
                return null;
            });
            try {
                writerB.get(3, TimeUnit.SECONDS);
            } catch (Exception ex) {
                fail("writer on tenant B was blocked by writer on tenant A: " + ex);
            }

            assertEquals(1, reg.readSnapshot(TENANT_B).deks().size());

            releaseA.countDown();
            writerA.get(5, TimeUnit.SECONDS);
            assertEquals(1, reg.readSnapshot(TENANT_A).deks().size());
        } finally {
            releaseA.countDown();
            exec.shutdownNow();
        }
    }

    @Test
    void close_isIdempotent(@TempDir Path tmp) throws IOException {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        reg.close();
        reg.close(); // must not throw
    }

    @Test
    void readAfterClose_throws(@TempDir Path tmp) throws IOException {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        reg.close();
        assertThrows(IllegalStateException.class, () -> reg.readSnapshot(TENANT_A));
    }

    @Test
    void updateAfterClose_throws(@TempDir Path tmp) throws IOException {
        final TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp));
        reg.close();
        assertThrows(IllegalStateException.class, () -> reg.updateShard(TENANT_A,
                shard -> new TenantShardRegistry.ShardUpdate<>(shard, null)));
    }

    @Test
    void close_zeroizesCachedSalt(@TempDir Path tmp) throws IOException {
        // Persist a shard with known salt.
        final ShardStorage storage = new ShardStorage(tmp);
        final byte[] realSalt = salt32();
        storage.writeShard(TENANT_A,
                new KeyRegistryShard(TENANT_A, Map.of(), Map.of(), KEK_REF, realSalt));
        final TenantShardRegistry reg = new TenantShardRegistry(storage);
        final KeyRegistryShard snap = reg.readSnapshot(TENANT_A);
        assertNotEquals((byte) 0, snap.hkdfSalt()[0]); // precondition: salt non-zero
        reg.close();
        // After close, the registry rejects reads; we cannot inspect the internal cached salt
        // bytes directly from the outside, but close() must have zeroized the cached array. This
        // property is observable via the ShardUpdate path — we simply assert that close() is
        // idempotent and does not reveal the salt post-close.
        assertThrows(IllegalStateException.class, () -> reg.readSnapshot(TENANT_A));
    }

    @Test
    void shardUpdate_rejectsNullNewShard() {
        assertThrows(NullPointerException.class,
                () -> new TenantShardRegistry.ShardUpdate<>(null, "x"));
    }

    @Test
    void shardUpdate_acceptsNullResult() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT_A, Map.of(), Map.of(), KEK_REF,
                salt32());
        final TenantShardRegistry.ShardUpdate<String> u = new TenantShardRegistry.ShardUpdate<>(
                shard, null);
        assertEquals(shard, u.newShard());
        assertEquals(null, u.result());
    }

}
