package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.KeyRegistryShard;
import jlsm.encryption.internal.ShardPathResolver;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Adversarial tests for EncryptionKeyHolder concurrency — verifies that R62a (atomic close vs.
 * in-flight work) holds for all cache-mutating operations, not just deriveFieldKey.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R62a.
 */
class ConcurrencyAdversarialTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final KekRef KEK_REF = new KekRef("local-master");

    // --- helpers ----------------------------------------------------------

    private static Path writeMasterKey(Path dir) throws IOException {
        final Path keyFile = dir.resolve("master.key");
        final byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) (i + 7);
        }
        Files.write(keyFile, bytes);
        if (Files.getFileAttributeView(dir,
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        return keyFile;
    }

    /**
     * Delegating KmsClient that blocks inside {@code wrapKek} until {@code release} is counted
     * down, allowing a test to pause the holder mid-provisioning of a domain KEK.
     */
    private static final class BlockingKms implements KmsClient {

        private final KmsClient delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingKms(KmsClient delegate, CountDownLatch entered, CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new KmsTransientException("release latch timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KmsTransientException("interrupted", e);
            }
            return delegate.wrapKek(plaintextKek, kekRef, context);
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            return delegate.unwrapKek(wrappedBytes, kekRef, context);
        }

        @Override
        public boolean isUsable(KekRef kekRef) throws KmsException {
            return delegate.isUsable(kekRef);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Delegating KmsClient that synchronises every {@code wrapKek} caller on a shared barrier so
     * two concurrent provisioning threads both pass the "shard empty" check before either commits.
     * Used to reproduce the TOCTOU in {@code nextDomainKekVersion}.
     */
    private static final class BarrierKms implements KmsClient {

        private final KmsClient delegate;
        private final CyclicBarrier barrier;

        BarrierKms(KmsClient delegate, CyclicBarrier barrier) {
            this.delegate = delegate;
            this.barrier = barrier;
        }

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KmsTransientException("interrupted", e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new KmsTransientException("barrier failed", e);
            }
            return delegate.wrapKek(plaintextKek, kekRef, context);
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            return delegate.unwrapKek(wrappedBytes, kekRef, context);
        }

        @Override
        public boolean isUsable(KekRef kekRef) throws KmsException {
            return delegate.isUsable(kekRef);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    // --- R62a: close must wait for in-flight openDomain --------------------

    @Test
    @Timeout(20)
    // Finding: F-R1.concurrency.1.1
    // Bug: openDomain mutates cacheArena + domainCache without holding deriveGuard.readLock,
    // so close() (which takes the write lock and closes cacheArena) does not wait for
    // an in-flight openDomain. An openDomain racing with close can call
    // cacheArena.allocate after the arena has been closed and throw.
    // Correct behavior: close must block until in-flight openDomain completes (R62a), OR
    // openDomain must fail cleanly with IllegalStateException(closed) — never propagate
    // an arena-closed IllegalStateException / low-level failure.
    // Fix location: EncryptionKeyHolder.openDomain / loadOrProvisionDomainKek /
    // unwrapAndCacheDomainKek / provisionDomainKek — each must hold
    // deriveGuard.readLock() around the cacheArena+domainCache mutation.
    // Regression watch: do not introduce a deadlock — close takes the write lock and
    // openDomain must never call close itself under the read lock.
    void test_openDomain_concurrentClose_doesNotRaceArenaAllocation(@TempDir Path tempDir)
            throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient realKms = new LocalKmsClient(keyFile);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final BlockingKms blockingKms = new BlockingKms(realKms, entered, release);
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final TenantShardRegistry registry = new TenantShardRegistry(storage);
        final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(blockingKms)
                .registry(registry).activeTenantKekRef(KEK_REF).build();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        final AtomicReference<Throwable> openError = new AtomicReference<>();
        try {
            // Thread A: begin openDomain. It will block inside wrapKek waiting on `release`.
            final Future<Void> opener = exec.submit(() -> {
                try {
                    holder.openDomain(TENANT, DOMAIN);
                } catch (Throwable t) {
                    openError.set(t);
                }
                return null;
            });

            // Wait until openDomain is actually inside wrapKek (cacheArena mutation imminent).
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "BlockingKms.wrapKek must be entered before close is scheduled");

            // Thread B: schedule close(). Per R62a, close must wait for in-flight openDomain.
            final Future<Void> closer = exec.submit(() -> {
                holder.close();
                return null;
            });

            // While openDomain is still mid-flight, close() must NOT have completed.
            // If close returns here, it closed cacheArena while openDomain still intends to
            // allocate from it — the race described in F-R1.concurrency.1.1.
            try {
                closer.get(300, TimeUnit.MILLISECONDS);
                fail("close() returned while openDomain was still in-flight — "
                        + "R62a violated: close did not wait for openDomain's cacheArena mutation "
                        + "(F-R1.concurrency.1.1)");
            } catch (TimeoutException expected) {
                // Good — close is blocked as required.
            }

            // Now let openDomain's wrapKek return; openDomain should then complete, and close
            // should complete shortly after.
            release.countDown();

            opener.get(5, TimeUnit.SECONDS);
            closer.get(5, TimeUnit.SECONDS);

            final Throwable err = openError.get();
            assertFalse(
                    err instanceof IllegalStateException && err.getMessage() != null
                            && err.getMessage().toLowerCase().contains("closed"),
                    "openDomain must not observe a closed cacheArena — close must wait (R62a). "
                            + "Got: " + err);
            // Any surviving error would also indicate an unexpected race outcome.
            if (err != null && !(err instanceof IllegalStateException)) {
                fail("openDomain threw an unexpected exception under race: " + err);
            }
        } finally {
            exec.shutdownNow();
            // Best-effort — holder may already be closed.
            try {
                holder.close();
            } catch (RuntimeException ignored) {
            }
            registry.close();
            realKms.close();
            assertNotNull(storage);
        }
    }

    // --- R62/R29: concurrent provisionDomainKek must not silently collide on version 1 ----

    @Test
    @Timeout(20)
    // Finding: F-R1.concurrency.1.3
    // Bug: provisionDomainKek reads nextDomainKekVersion BEFORE entering the per-tenant
    // updateShard writer lock. Two concurrent openDomain calls on a fresh shard both observe
    // the empty snapshot, both compute version=1, both wrap independently, and both
    // successfully commit withDomainKek(wdk@v1). Because withDomainKek keys by domainId only,
    // the second commit silently overwrites the first — the shard ends with one domain KEK at
    // version 1 containing the last-committer's wrapped bytes, while the earlier committer
    // believes its own wrapped bytes are the persisted ones.
    // Correct behavior: at most one of the two threads commits at version 1; the later
    // committer must detect the prior commit inside the serialized critical section and
    // produce a version > 1 (or abandon its plaintext and re-unwrap the winner's bytes). The
    // persisted domain-KEK version after both openDomain calls must be >= 2.
    // Fix location: EncryptionKeyHolder.provisionDomainKek — move nextDomainKekVersion
    // (and construction of the WrappedDomainKek) inside the registry.updateShard mutator so
    // the version is computed against the per-tenant-serialized current snapshot.
    // Regression watch: do not introduce a deadlock — the mutator already runs under the
    // writer lock; keep KMS calls outside of it. Do not break idempotent openDomain hits
    // (cache present + unexpired).
    void test_provisionDomainKek_concurrentFreshShard_doesNotSilentlyCollideAtVersion1(
            @TempDir Path tempDir) throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient realKms = new LocalKmsClient(keyFile);
        // Barrier admits exactly 2 parties; both wrapKek callers wait until both have arrived
        // so both complete their "shard empty" read before either proceeds to updateShard.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final BarrierKms barrierKms = new BarrierKms(realKms, barrier);
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final TenantShardRegistry registry = new TenantShardRegistry(storage);
        final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(barrierKms)
                .registry(registry).activeTenantKekRef(KEK_REF).build();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        final AtomicReference<Throwable> errA = new AtomicReference<>();
        final AtomicReference<Throwable> errB = new AtomicReference<>();
        try {
            final Future<Void> a = exec.submit(() -> {
                try {
                    holder.openDomain(TENANT, DOMAIN);
                } catch (Throwable t) {
                    errA.set(t);
                }
                return null;
            });
            final Future<Void> b = exec.submit(() -> {
                try {
                    holder.openDomain(TENANT, DOMAIN);
                } catch (Throwable t) {
                    errB.set(t);
                }
                return null;
            });

            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);

            // Neither thread should have observed an unexpected failure — both are valid
            // concurrent openDomain calls on a fresh shard.
            if (errA.get() != null) {
                fail("thread A failed unexpectedly: " + errA.get());
            }
            if (errB.get() != null) {
                fail("thread B failed unexpectedly: " + errB.get());
            }

            // Both threads committed a wrapped domain KEK; the barrier ensured both saw the
            // empty snapshot at the "wrapped==null" check. A correct implementation must have
            // the later committer observe the prior commit and bump the version — otherwise
            // the second wrapped-bytes silently overwrite the first at v1 and any DEK
            // previously wrapped under the first plaintext is unrecoverable from the
            // registry (F-R1.concurrency.1.3).
            final var snapshot = registry.readSnapshot(TENANT);
            final var wdk = snapshot.domainKeks().get(DOMAIN);
            assertNotNull(wdk, "after both openDomain calls, the shard must contain a "
                    + "WrappedDomainKek for the domain");
            assertTrue(wdk.version() >= 2,
                    "two concurrent provisionDomainKek on a fresh shard must NOT both land "
                            + "at version 1 — the later committer must observe the first and "
                            + "produce version >= 2. Got version=" + wdk.version()
                            + " (F-R1.concurrency.1.3: silent v1 collision)");
        } finally {
            exec.shutdownNow();
            try {
                holder.close();
            } catch (RuntimeException ignored) {
            }
            registry.close();
            realKms.close();
            assertNotNull(storage);
        }
    }

    // --- R55: unwrapAndCacheDomainKek must validate plaintext length before caching --------

    /**
     * Delegating KmsClient that truncates the plaintext returned by {@code unwrapKek} to a caller-
     * supplied length, simulating a buggy or malicious KMS adapter. The wrapped bytes are still
     * produced normally via the real {@code wrapKek}, so the registry contents pass structural
     * checks — only the unwrap return value is poisoned.
     */
    private static final class TruncatingUnwrapKms implements KmsClient {

        private final KmsClient delegate;
        private final int plaintextBytes;

        TruncatingUnwrapKms(KmsClient delegate, int plaintextBytes) {
            this.delegate = delegate;
            this.plaintextBytes = plaintextBytes;
        }

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            return delegate.wrapKek(plaintextKek, kekRef, context);
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            final UnwrapResult real = delegate.unwrapKek(wrappedBytes, kekRef, context);
            try {
                final Arena owner = Arena.ofShared();
                final MemorySegment truncated = owner.allocate(plaintextBytes);
                if (plaintextBytes > 0) {
                    final long copyLen = Math.min(plaintextBytes, real.plaintext().byteSize());
                    MemorySegment.copy(real.plaintext(), 0, truncated, 0, copyLen);
                }
                return new UnwrapResult(truncated, owner);
            } finally {
                real.owner().close();
            }
        }

        @Override
        public boolean isUsable(KekRef kekRef) throws KmsException {
            return delegate.isUsable(kekRef);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    @Timeout(20)
    // Finding: F-R1.concurrency.1.6
    // Bug: unwrapAndCacheDomainKek allocates and caches whatever plaintext length the KmsClient
    // returns from unwrapKek — no DOMAIN_KEK_BYTES (32) check. A misbehaving/malicious KMS
    // adapter returning a zero-length or wrong-length plaintext poisons the shared domainCache;
    // the error surfaces later at an unrelated call site (AesGcmContextWrap.wrap in generateDek)
    // as an opaque InvalidKeyException rather than a KMS-contract violation at the boundary.
    // Correct behavior: unwrapAndCacheDomainKek must validate
    // unwrap.plaintext().byteSize() == DOMAIN_KEK_BYTES at the SPI boundary (R55) and throw a
    // KmsPermanentException before allocating in cacheArena or publishing to domainCache.
    // Fix location: EncryptionKeyHolder.unwrapAndCacheDomainKek — add a length guard immediately
    // after kmsClient.unwrapKek returns, inside the try so unwrap.owner() is still closed on the
    // failure path.
    // Regression watch: the unwrap owner arena must still be closed on the rejection path (do
    // not skip the finally); the 32-byte happy path must still complete normally.
    void test_unwrapAndCacheDomainKek_rejectsWrongLengthPlaintextAtKmsBoundary(
            @TempDir Path tempDir) throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient realKms = new LocalKmsClient(keyFile);

        // Phase 1: provision a real domain KEK using the real KMS so the shard contains a valid
        // WrappedDomainKek. Close the holder; the registry/storage persists.
        final Path registryDir = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryDir);
        final TenantShardRegistry registry1 = new TenantShardRegistry(storage);
        final EncryptionKeyHolder provisioner = EncryptionKeyHolder.builder().kmsClient(realKms)
                .registry(registry1).activeTenantKekRef(KEK_REF).build();
        try {
            provisioner.openDomain(TENANT, DOMAIN);
        } finally {
            provisioner.close();
        }

        // Phase 2: open the same domain through a holder whose KMS truncates unwrap plaintext
        // to 16 bytes (wrong length: DOMAIN_KEK_BYTES is 32). The facade must refuse this at the
        // SPI boundary instead of caching the poisoned 16-byte segment.
        final TruncatingUnwrapKms truncatingKms = new TruncatingUnwrapKms(realKms, 16);
        final TenantShardRegistry registry2 = new TenantShardRegistry(storage);
        final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(truncatingKms)
                .registry(registry2).activeTenantKekRef(KEK_REF).build();
        try {
            try {
                holder.openDomain(TENANT, DOMAIN);
                fail("openDomain must reject a wrong-length plaintext from KmsClient.unwrapKek "
                        + "at the SPI boundary before caching (F-R1.concurrency.1.6 / R55). "
                        + "Expected a KmsException; got clean return.");
            } catch (KmsException expected) {
                // Good — the length mismatch was caught at the boundary.
                final String msg = expected.getMessage();
                assertNotNull(msg, "KmsException must carry an explanatory message");
            }
        } finally {
            try {
                holder.close();
            } catch (RuntimeException ignored) {
            }
            registry2.close();
            realKms.close();
            assertNotNull(storage);
        }
    }

    // --- F-R1.concurrency.2.1: readSnapshot must not return null on close race --------------

    @Test
    @Timeout(30)
    // Finding: F-R1.concurrency.2.1
    // Bug: TenantShardRegistry.readSnapshot checks requireOpen() once on entry, then calls
    // entry(tenantId) and returns entry.snapshot.get(). A close() racing between entry() and
    // snapshot.get() can set the snapshot to null via getAndSet(null) (line 110), after which
    // readSnapshot returns null — violating its documented contract (no null return) and
    // breaking R62/R62a which state that close is observable via IllegalStateException only.
    // Correct behavior: readSnapshot must either return a non-null shard OR throw
    // IllegalStateException when racing close — it must never return null.
    // Fix location: TenantShardRegistry.readSnapshot — re-check closed.get() (or equivalently
    // guard the null-return path) after reading the snapshot; OR have close() acquire the
    // per-tenant writer lock before nulling so readers that race close see a non-null shard or
    // IllegalStateException.
    // Regression watch: do not introduce a deadlock; do not change the wait-free property on
    // the common (non-racing) path beyond adding a second volatile read of `closed`.
    void test_readSnapshot_concurrentClose_mustNotReturnNull(@TempDir Path tempDir)
            throws Exception {
        // Many reader threads hammer readSnapshot in tight loops on a cached tenant set;
        // one closer thread calls close() after the readers have warmed. Close's inner loop
        // iterates cached entries calling snapshot.getAndSet(null) (line 110) BEFORE calling
        // tenants.clear() (line 115). A reader that passed requireOpen() before close's CAS
        // but reaches entry.snapshot.get() after close has nullified its tenant's entry
        // observes null. That null propagates out of readSnapshot violating R62/R62a (close
        // must be observable only via IllegalStateException).
        //
        // To maximise race hits:
        // - MANY reader threads (saturate CPU): probability of at least one thread being
        // in the tiny window between lines 60 and 61 when close fires is proportional to
        // thread count × loop frequency.
        // - LARGE tenant set: close's iteration phase is longer, widening the observable
        // "some entries nulled, others not" window from the reader's perspective.
        // - Multiple rounds with fresh registries: if one round misses the race, the next
        // round gets another shot.
        final int rounds = 40;
        final int readerThreads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        final int numTenants = 512;
        final ExecutorService exec = Executors.newFixedThreadPool(readerThreads + 1);
        final AtomicReference<String> nullObservation = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            outerRound: for (int r0 = 0; r0 < rounds; r0++) {
                if (nullObservation.get() != null) {
                    break;
                }
                final int round = r0;
                final Path registryDir = tempDir.resolve("reg-" + round);
                final ShardStorage storage = new ShardStorage(registryDir);
                final TenantShardRegistry registry = new TenantShardRegistry(storage);
                final TenantId[] tenants = new TenantId[numTenants];
                for (int i = 0; i < numTenants; i++) {
                    tenants[i] = new TenantId("t-" + round + "-" + i);
                    registry.readSnapshot(tenants[i]); // warm cache
                }

                final CyclicBarrier barrier = new CyclicBarrier(readerThreads + 1);
                final Future<?>[] readers = new Future<?>[readerThreads];
                for (int t = 0; t < readerThreads; t++) {
                    readers[t] = exec.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            outer: while (true) {
                                for (int i = 0; i < numTenants; i++) {
                                    try {
                                        final Object r = registry.readSnapshot(tenants[i]);
                                        if (r == null) {
                                            nullObservation.compareAndSet(null,
                                                    "round=" + round + " tenant=" + i);
                                            break outer;
                                        }
                                    } catch (IllegalStateException expected) {
                                        break outer;
                                    }
                                }
                            }
                        } catch (Throwable ex) {
                            error.compareAndSet(null, ex);
                        }
                        return null;
                    });
                }
                final Future<?> closer = exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        // Delay so readers have warmed up and have many in-flight calls at
                        // various stages of readSnapshot when close fires.
                        Thread.sleep(2);
                        registry.close();
                    } catch (Throwable ex) {
                        error.compareAndSet(null, ex);
                    }
                    return null;
                });

                for (Future<?> r : readers) {
                    r.get(30, TimeUnit.SECONDS);
                }
                closer.get(30, TimeUnit.SECONDS);
                if (error.get() != null) {
                    fail("round " + round + ": unexpected error: " + error.get());
                }
                if (nullObservation.get() != null) {
                    break outerRound;
                }
            }
            assertTrue(nullObservation.get() == null,
                    "readSnapshot returned null during close race (at " + nullObservation.get()
                            + "). R62/R62a require close to be "
                            + "observable via IllegalStateException, never via null return "
                            + "(F-R1.concurrency.2.1). Fix: re-check closed.get() in readSnapshot "
                            + "after reading snapshot, OR have close() acquire per-tenant "
                            + "writer locks before nulling.");
        } finally {
            exec.shutdownNow();
        }
    }

    // --- F-R1.concurrency.2.2: updateShard must not commit a disk write after close returned -

    @Test
    @Timeout(30)
    // Finding: F-R1.concurrency.2.2
    // Bug: TenantShardRegistry.updateShard passes two requireOpen() checks (entry + inside lock)
    // then invokes the mutator, then calls storage.writeShard, then snapshot.set. close() does
    // NOT acquire entry.writerLock before getAndSet(null)/clear, so an in-flight updateShard
    // that already entered the mutator can proceed past close: it will commit
    // storage.writeShard (a durable atomic rename on disk) AFTER close() returned. That is a
    // phantom post-close durable write — R62/R62a demand close be atomic with in-flight work.
    // Correct behavior: either close blocks on in-flight writers (preferred), or the writer
    // must detect the race before storage.writeShard and throw IllegalStateException without
    // persisting. In no case may a shard file be created/updated on disk after close returned.
    // Fix location: TenantShardRegistry.updateShard — add a third requireOpen() check
    // immediately before storage.writeShard (skip write on racing close), OR have close()
    // acquire each entry.writerLock before nulling/zeroizing to drain in-flight writers.
    // Regression watch: do not deadlock close against an in-flight writer; do not break the
    // happy path (non-racing updateShard must still persist).
    void test_updateShard_concurrentClose_doesNotCommitDiskWriteAfterClose(@TempDir Path tempDir)
            throws Exception {
        final Path registryDir = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryDir);
        final TenantShardRegistry registry = new TenantShardRegistry(storage);

        // Seed the registry with a baseline shard for TENANT so we have a persisted "before"
        // state to compare against. We use withTenantKekRef to mutate to a recognisable value.
        final KekRef baselineKekRef = new KekRef("baseline-kek");
        registry.updateShard(TENANT, current -> {
            final var seeded = current.withTenantKekRef(baselineKekRef);
            return new TenantShardRegistry.ShardUpdate<>(seeded, null);
        });

        // Confirm the baseline persisted.
        assertNotNull(registry.readSnapshot(TENANT).activeTenantKekRef());

        final CountDownLatch mutatorEntered = new CountDownLatch(1);
        final CountDownLatch mutatorRelease = new CountDownLatch(1);
        final KekRef postCloseKekRef = new KekRef("post-close-kek");

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        final AtomicReference<Throwable> writerError = new AtomicReference<>();
        try {
            // Thread A: enter updateShard, the mutator signals and then blocks — while blocked,
            // thread A holds entry.writerLock. Close() does not acquire writerLock, so close
            // can race ahead.
            final Future<Void> writer = exec.submit(() -> {
                try {
                    registry.updateShard(TENANT, current -> {
                        mutatorEntered.countDown();
                        try {
                            if (!mutatorRelease.await(10, TimeUnit.SECONDS)) {
                                throw new RuntimeException("mutatorRelease timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("interrupted", e);
                        }
                        // Produce a post-close update that names a distinct KekRef so we can
                        // detect whether it was persisted.
                        final var updated = current.withTenantKekRef(postCloseKekRef);
                        return new TenantShardRegistry.ShardUpdate<>(updated, null);
                    });
                } catch (Throwable t) {
                    writerError.set(t);
                }
                return null;
            });

            // Wait until A is inside the mutator (has passed both requireOpen checks and holds
            // the writerLock).
            assertTrue(mutatorEntered.await(5, TimeUnit.SECONDS),
                    "mutator must be entered before close is scheduled");

            // Thread B: close(). Close does not acquire writerLock, so it will race past A.
            final Future<Void> closer = exec.submit(() -> {
                registry.close();
                return null;
            });

            // close must complete (it does not acquire the writerLock); confirm it returned
            // while A is still holding the mutator — this is the moment the race window opens.
            closer.get(5, TimeUnit.SECONDS);

            // Now release the mutator. A will proceed past the mutator into
            // storage.writeShard — which persists to disk AFTER close returned (the bug).
            mutatorRelease.countDown();

            // Wait for A to complete. With the fix applied, A must either:
            // (a) throw IllegalStateException from a third requireOpen() before writeShard,
            // OR
            // (b) have been drained by close (close acquires writerLock) and already complete
            // before mutatorRelease — this branch is impossible with our latch sequencing
            // (we only count down AFTER closer.get returned), so in the "close drains"
            // variant of the fix, close would instead block on closer.get above and never
            // return — that variant cannot reach this point without failing the prior
            // closer.get(5s) call.
            // Either way: after this race, the on-disk shard must NOT reflect A's
            // post-close update. Read the file bytes directly via a fresh storage — do not go
            // through the (now-closed) registry.
            writer.get(10, TimeUnit.SECONDS);

            final ShardStorage verifyStorage = new ShardStorage(registryDir);
            final Optional<KeyRegistryShard> onDisk = verifyStorage.loadShard(TENANT);
            assertTrue(onDisk.isPresent(),
                    "baseline shard must still exist on disk after close — close should not "
                            + "delete the persisted shard");
            final KekRef persistedKekRef = onDisk.get().activeTenantKekRef();
            assertNotNull(persistedKekRef, "persisted activeTenantKekRef must be non-null");
            assertFalse(persistedKekRef.equals(postCloseKekRef),
                    "On-disk shard reflects an updateShard that committed AFTER close() "
                            + "returned — phantom post-close durable write "
                            + "(F-R1.concurrency.2.2). close() must either drain in-flight "
                            + "writers OR updateShard must re-check closed before "
                            + "storage.writeShard. persisted activeTenantKekRef=" + persistedKekRef
                            + " (expected baseline=" + baselineKekRef + ").");
            // writerError may legitimately carry an IllegalStateException (fix variant (a));
            // that is the intended correct behavior and not a regression.
            final Throwable err = writerError.get();
            if (err != null && !(err instanceof IllegalStateException)) {
                fail("writer thread threw an unexpected exception: " + err);
            }
        } finally {
            exec.shutdownNow();
            // registry already closed
        }
    }

    // --- F-R1.concurrency.2.3: entry() must not install new tenants after close -----------

    @Test
    @Timeout(30)
    // Finding: F-R1.concurrency.2.3
    // Bug: TenantShardRegistry.entry() uses computeIfAbsent to install a new TenantEntry on
    // first access. There is no closed.get() re-check AFTER computeIfAbsent returns, so a
    // thread that passed requireOpen() (in readSnapshot or updateShard) can race close() and
    // install a fresh TenantEntry into `tenants` AFTER close's tenants.clear() executes. The
    // installed entry's snapshot is non-null (its salt bytes are live in the heap), violating
    // R69 best-effort zeroise-on-close for this tenant and allowing the racing caller to
    // receive a valid shard from a closed registry — R62/R62a demand close be observable via
    // IllegalStateException.
    // Correct behavior: after a close-racing readSnapshot/entry call completes, either the
    // caller observes IllegalStateException (registry closed) OR the tenant is not present in
    // `tenants` (no new entry was installed after close). No caller may receive a non-null
    // shard for a never-before-accessed tenant once close() has returned.
    // Fix location: TenantShardRegistry.entry — after computeIfAbsent returns, re-check
    // closed.get() and throw IllegalStateException if closed. Best-effort zeroise the
    // just-synthesised/loaded salt bytes if reachable via the created entry.
    // Regression watch: do not break the fast-path (cached tenant) — the re-check must only
    // fire on the slow path after CIA actually installs a new entry; do not introduce a
    // deadlock with close; the happy non-racing path must still succeed.
    void test_entry_concurrentClose_doesNotInstallNewTenantAfterClose(@TempDir Path tempDir)
            throws Exception {
        // To maximise race hits: many reader threads each target a UNIQUE tenant (never
        // accessed before), so every readSnapshot call MUST go through entry()'s slow path
        // (computeIfAbsent). A single closer thread fires close() after readers have warmed.
        // If any reader successfully returns a non-null shard after close() completed, AND
        // the just-created tenant is present in the registry's `tenants` map at end-of-close,
        // the bug is confirmed: entry() installed the TenantEntry post-close.
        final int rounds = 40;
        final int readerThreads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        final int tenantsPerReader = 128;
        final ExecutorService exec = Executors.newFixedThreadPool(readerThreads + 1);
        final AtomicReference<String> postCloseSuccessObservation = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            outerRound: for (int r0 = 0; r0 < rounds; r0++) {
                if (postCloseSuccessObservation.get() != null) {
                    break;
                }
                final int round = r0;
                final Path registryDir = tempDir.resolve("reg-entry-" + round);
                final ShardStorage storage = new ShardStorage(registryDir);
                final TenantShardRegistry registry = new TenantShardRegistry(storage);

                final CyclicBarrier barrier = new CyclicBarrier(readerThreads + 1);
                final java.util.concurrent.atomic.AtomicBoolean closeCompleted = new java.util.concurrent.atomic.AtomicBoolean(
                        false);
                final Future<?>[] readers = new Future<?>[readerThreads];
                for (int t0 = 0; t0 < readerThreads; t0++) {
                    final int t = t0;
                    readers[t] = exec.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            for (int i = 0; i < tenantsPerReader; i++) {
                                // Each (round, reader, i) triple names a UNIQUE tenant that
                                // has NEVER been touched in this registry, forcing entry()
                                // onto the CIA slow path every time.
                                final TenantId tid = new TenantId("t-" + round + "-" + t + "-" + i);
                                try {
                                    final Object r = registry.readSnapshot(tid);
                                    // If close already completed and we got back a non-null
                                    // shard for a tenant never seen before this registry, the
                                    // bug is confirmed.
                                    if (r != null && closeCompleted.get()) {
                                        postCloseSuccessObservation.compareAndSet(null,
                                                "round=" + round + " reader=" + t + " tenant=" + i);
                                        return null;
                                    }
                                } catch (IllegalStateException expected) {
                                    // Correct behavior on race — either requireOpen fired
                                    // at the start or the post-CIA re-check fired. Continue
                                    // to the next tenant so we keep poking the race window.
                                }
                            }
                        } catch (Throwable ex) {
                            error.compareAndSet(null, ex);
                        }
                        return null;
                    });
                }
                final Future<?> closer = exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        Thread.sleep(1); // let readers warm up
                        registry.close();
                        closeCompleted.set(true);
                    } catch (Throwable ex) {
                        error.compareAndSet(null, ex);
                    }
                    return null;
                });

                for (Future<?> r : readers) {
                    r.get(30, TimeUnit.SECONDS);
                }
                closer.get(30, TimeUnit.SECONDS);
                if (error.get() != null) {
                    fail("round " + round + ": unexpected error: " + error.get());
                }
                if (postCloseSuccessObservation.get() != null) {
                    break outerRound;
                }
            }
            assertTrue(postCloseSuccessObservation.get() == null,
                    "readSnapshot on a never-before-seen tenant returned a non-null shard "
                            + "AFTER close() completed (at " + postCloseSuccessObservation.get()
                            + "). entry() installed a new TenantEntry via computeIfAbsent "
                            + "after close's tenants.clear() ran — R62/R62a require close be "
                            + "observable via IllegalStateException, and R69 best-effort salt "
                            + "zeroise-on-close is bypassed for the newly-installed tenant "
                            + "(F-R1.concurrency.2.3). Fix: re-check closed.get() inside "
                            + "entry() after computeIfAbsent returns.");
        } finally {
            exec.shutdownNow();
        }
    }

    // --- F-R1.concurrency.2.4: cross-tenant updateShard re-entry must not deadlock -----------

    @Test
    @Timeout(15)
    // Finding: F-R1.concurrency.2.4
    // Bug: TenantShardRegistry.updateShard acquires entry.writerLock via an unconditional
    // .lock() call with no documented lock-ordering contract and no thread-local re-entry
    // guard. A mutator that re-enters updateShard for a DIFFERENT tenant is legal per the
    // API surface (the Javadoc does not forbid it). With two threads performing AB/BA calls,
    // a classic lock-ordering deadlock results: thread A holds T1 and waits on T2;
    // thread B holds T2 and waits on T1. Neither can progress; close() cannot unblock them.
    // Correct behavior: a mutator invoking updateShard re-entrantly (on any tenant) must
    // fail fast with a descriptive runtime exception instead of acquiring a second writer
    // lock. The thread-local re-entry guard surfaces the API misuse at its first occurrence
    // and makes the AB/BA deadlock unreachable.
    // Fix location: TenantShardRegistry.updateShard — install a thread-local re-entry flag
    // around the mutator invocation; throw IllegalStateException if the flag is already set
    // when updateShard is called. Release the flag in finally alongside writerLock.unlock().
    // Regression watch: the guard must be released on every exit path (normal, exception);
    // single-level (non-nested) updateShard must still succeed; the fast path overhead must
    // remain proportional to a thread-local read. Documentation should spell out "mutator
    // must not call updateShard".
    void test_updateShard_crossTenantReentryFromMutator_doesNotDeadlock(@TempDir Path tempDir)
            throws Exception {
        final Path registryDir = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryDir);
        final TenantShardRegistry registry = new TenantShardRegistry(storage);
        try {
            final TenantId t1 = new TenantId("tenant-1");
            final TenantId t2 = new TenantId("tenant-2");

            // Warm both tenants so entry() slow-path doesn't skew the test.
            registry.readSnapshot(t1);
            registry.readSnapshot(t2);

            final CyclicBarrier barrier = new CyclicBarrier(2);
            final ExecutorService exec = Executors.newFixedThreadPool(2);
            final AtomicReference<Throwable> errA = new AtomicReference<>();
            final AtomicReference<Throwable> errB = new AtomicReference<>();
            try {
                // Thread A: updateShard(T1, mutator that calls updateShard(T2, ...))
                final Future<Void> a = exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        registry.updateShard(t1, currentT1 -> {
                            // Let B acquire its outer lock before we attempt re-entry.
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("interrupted", e);
                            }
                            try {
                                registry.updateShard(t2,
                                        currentT2 -> new TenantShardRegistry.ShardUpdate<>(
                                                currentT2, null));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return new TenantShardRegistry.ShardUpdate<>(currentT1, null);
                        });
                    } catch (Throwable t) {
                        errA.set(t);
                    }
                    return null;
                });
                // Thread B: updateShard(T2, mutator that calls updateShard(T1, ...))
                final Future<Void> b = exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        registry.updateShard(t2, currentT2 -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("interrupted", e);
                            }
                            try {
                                registry.updateShard(t1,
                                        currentT1 -> new TenantShardRegistry.ShardUpdate<>(
                                                currentT1, null));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return new TenantShardRegistry.ShardUpdate<>(currentT2, null);
                        });
                    } catch (Throwable t) {
                        errB.set(t);
                    }
                    return null;
                });

                // Both threads must complete within the timeout — a deadlock would cause them
                // to hang indefinitely. A correct implementation detects the re-entry and
                // throws; the throw releases the outer writer lock via finally so the peer
                // thread can also finish (its own mutator's re-entry attempt will likewise
                // detect and throw).
                try {
                    a.get(5, TimeUnit.SECONDS);
                    b.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException hung) {
                    fail("Cross-tenant updateShard re-entry from within a mutator deadlocked — "
                            + "two threads performing AB/BA acquisitions of per-tenant writer "
                            + "locks hung indefinitely (F-R1.concurrency.2.4). Fix: install a "
                            + "thread-local re-entry guard in updateShard that throws on nested "
                            + "invocation, OR impose a total lock-acquisition order across "
                            + "tenants.");
                }

                // Correct behavior: the nested updateShard call must have thrown an exception
                // visible to the mutator, which propagates up through Thread A/B. We do NOT
                // require a specific exception type (IllegalStateException is expected from
                // the re-entry guard; IOException or RuntimeException wrapping it are also
                // acceptable). What we DO require: neither thread completed normally, i.e.,
                // at least one of them surfaced an error rather than silently committing a
                // phantom nested mutation.
                assertTrue(errA.get() != null || errB.get() != null,
                        "At least one of the two re-entrant updateShard calls must surface an "
                                + "error — a mutator is not permitted to re-enter updateShard. "
                                + "Both threads completing silently would mean the re-entrancy "
                                + "guard is absent AND the nested calls happened to not deadlock "
                                + "by timing luck, which violates the API contract.");
            } finally {
                exec.shutdownNow();
            }
        } finally {
            registry.close();
        }
    }

    // --- F-R1.concurrency.3.1: recoverOrphanTemps must not race-delete in-flight writeShard --

    @Test
    @Timeout(20)
    // Finding: F-R1.concurrency.3.1
    // Bug: ShardStorage.recoverOrphanTemps globs *.tmp files and deletes any whose CRC does
    // not validate. A concurrent writeShard is mid-FileChannel-write — the temp file exists
    // but the CRC trailer has not yet been written. recoverOrphanTemps reads the partial file,
    // CRC fails, and deletes it. writeShard's subsequent Files.move(ATOMIC_MOVE) throws
    // NoSuchFileException, the write is lost, and the caller's atomic operation is silently
    // corrupted.
    // Correct behavior: recoverOrphanTemps must not destroy in-flight writer temp files. A
    // concurrent writeShard that is actively writing/fsyncing its temp must be able to
    // complete its atomic rename without recovery having deleted the file out from under it.
    // Fix location: ShardStorage.writeShard (hold an exclusive advisory lock across the temp-
    // write window) + ShardStorage.handleOrphan (tryLock each temp before acting on it; skip
    // on lock contention).
    // Regression watch: orphan recovery of truly-crashed (unlocked) temp files must still
    // delete/promote them; the normal write path must not deadlock; handleOrphan must release
    // its lock on every exit path.
    void test_recoverOrphanTemps_doesNotDeleteInFlightWriteShardTemp(@TempDir Path tempDir)
            throws Exception {
        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        final TenantId tenant = new TenantId("tenantA");

        // Seed an initial shard so the tenant directory exists and has a baseline.
        final KeyRegistryShard seed = new KeyRegistryShard(tenant, java.util.Map.of(),
                java.util.Map.of(), KEK_REF, new byte[32]);
        storage.writeShard(tenant, seed);

        // Locate the tenant directory (same resolver the writer uses).
        final Path tenantDir = ShardPathResolver.shardPath(registryRoot, tenant).getParent();
        assertTrue(Files.isDirectory(tenantDir), "tenant directory must exist after writeShard");

        // Simulate an in-flight writeShard: create a temp file, write partial bytes (no CRC
        // trailer yet), and hold an exclusive FileLock on it from a separate thread. This is
        // exactly the state ShardStorage.writeShard is in between the first ch.write and the
        // final Files.move — an unlocked file that is open for write elsewhere in the JVM.
        final String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        final Path inFlightTemp = tenantDir.resolve("shard.bin." + suffix + ".tmp");
        Files.createFile(inFlightTemp);
        Files.write(inFlightTemp, new byte[]{ 'K', 'R', 'S', 'H', 0, 1 });

        final CountDownLatch lockHeld = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        final AtomicReference<Throwable> writerErr = new AtomicReference<>();
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            final Future<Void> writer = exec.submit(() -> {
                try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                        inFlightTemp, java.nio.file.StandardOpenOption.READ,
                        java.nio.file.StandardOpenOption.WRITE);
                        java.nio.channels.FileLock _lock = ch.lock()) {
                    lockHeld.countDown();
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                        throw new RuntimeException("releaseWriter latch timed out");
                    }
                } catch (Throwable t) {
                    writerErr.set(t);
                }
                return null;
            });

            assertTrue(lockHeld.await(5, TimeUnit.SECONDS),
                    "simulated writer must have acquired its exclusive lock before recovery runs");

            // Run orphan recovery while the in-flight writer still holds the lock. Recovery
            // must leave the locked temp alone; destroying it would orphan the writer's
            // atomic-rename step (NoSuchFileException on Files.move) and lose the update.
            storage.recoverOrphanTemps();

            // The in-flight temp file must still exist — a concurrent writer is relying on it
            // for its pending Files.move.
            assertTrue(Files.exists(inFlightTemp),
                    "recoverOrphanTemps destroyed an in-flight writeShard temp file that was "
                            + "currently held under an exclusive OS lock by another thread. The "
                            + "writer's subsequent Files.move would throw NoSuchFileException "
                            + "and its atomic update would be silently lost "
                            + "(F-R1.concurrency.3.1). Fix: recoverOrphanTemps must probe each "
                            + "temp with FileChannel.tryLock before touching it; on lock "
                            + "contention, the file belongs to a concurrent writer — skip it.");

            releaseWriter.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertTrue(writerErr.get() == null,
                    "simulated writer thread must not have errored: " + writerErr.get());
        } finally {
            exec.shutdownNow();
            releaseWriter.countDown();
        }
    }

    // --- F-R1.concurrency.3.2: recoverOrphanTemps must not overwrite a newer just-committed shard
    // -

    @Test
    @Timeout(60)
    // Finding: F-R1.concurrency.3.2
    // Bug: handleOrphan's "strictly newer" check compares the orphan against tryLoad(shardPath)
    // at line 303. Between that load and Files.move(orphan, shardPath, ATOMIC_MOVE,
    // REPLACE_EXISTING) at line 307, a concurrent writeShard can atomically rename a NEWER
    // shard into shardPath. Recovery then stomps the freshly-committed newer shard with the
    // older orphan because Files.move with REPLACE_EXISTING does not check the target's
    // contents. The FileChannel.lock added by the F-R1.concurrency.3.1 fix is per-temp-file
    // and does NOT cover shardPath itself, so the writer's lock on its OWN temp does nothing
    // to block recovery's rename onto shardPath.
    // Correct behavior: after handleOrphan decides to promote an orphan, the promotion must
    // fail (leave the orphan alone, or delete it) if the target shardPath has been modified
    // since the load used for isStrictlyNewer. Equivalently: handleOrphan must serialize
    // against writeShard on the final shard path — either by holding an exclusive advisory
    // lock on shardPath across both tryLoad+Files.move, or by re-reading shardPath inside a
    // CAS-style loop and aborting the promotion if it is no longer strictly older than the
    // orphan.
    // Fix location: ShardStorage.handleOrphan (lines 275-324) and/or ShardStorage.writeShard
    // (lines 136-200). A natural fix: acquire an OS-level exclusive lock on a sibling "lock
    // file" (e.g., shard.bin.lock) inside writeShard across the entire write window AND
    // inside handleOrphan across the entire tryLoad+Files.move window. Alternatively, re-load
    // shardPath immediately before Files.move and abort the promotion if it is no longer
    // strictly older than the orphan.
    // Regression watch: the normal orphan promotion path (crashed writer, no concurrent
    // writer) must still succeed; writeShard must not deadlock against a recovery thread;
    // the fix must not downgrade a promoted orphan back to a lost state.
    void test_recoverOrphanTemps_doesNotOverwriteNewerJustCommittedShard(@TempDir Path tempDir)
            throws Exception {
        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        final TenantId tenant = new TenantId("tenantA");
        final Path shardPath = ShardPathResolver.shardPath(registryRoot, tenant);

        // Pre-build three shard payloads at distinct domain-KEK versions so isStrictlyNewer
        // has a clear total order to drive the TOCTOU:
        // V2 = baseline on disk (existing before the race)
        // V3 = orphan payload (isStrictlyNewer(V3, V2) == true)
        // V4 = writer payload (isStrictlyNewer(V4, V3) == true, but writer rename lands
        // AFTER recovery's tryLoad(V2) returns)
        final byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x5A);
        final byte[] wrapped = new byte[40];
        Arrays.fill(wrapped, (byte) 0x01);
        final KeyRegistryShard v2 = new KeyRegistryShard(tenant, java.util.Map.of(),
                java.util.Map.of(DOMAIN, new WrappedDomainKek(DOMAIN, 2, wrapped, KEK_REF)),
                KEK_REF, salt);
        final KeyRegistryShard v3 = new KeyRegistryShard(tenant, java.util.Map.of(),
                java.util.Map.of(DOMAIN, new WrappedDomainKek(DOMAIN, 3, wrapped, KEK_REF)),
                KEK_REF, salt);
        final KeyRegistryShard v4 = new KeyRegistryShard(tenant, java.util.Map.of(),
                java.util.Map.of(DOMAIN, new WrappedDomainKek(DOMAIN, 4, wrapped, KEK_REF)),
                KEK_REF, salt);

        // Capture V3's on-disk serialized form by writing to a scratch tenant dir, reading
        // the bytes, and then using those bytes to seed the orphan file in the real tenant
        // dir. This avoids reimplementing the serialize+CRC logic in the test.
        final Path scratchRoot = tempDir.resolve("scratch");
        final ShardStorage scratchStorage = new ShardStorage(scratchRoot);
        scratchStorage.writeShard(tenant, v3);
        final byte[] v3Bytes = Files.readAllBytes(ShardPathResolver.shardPath(scratchRoot, tenant));

        // Iterations: the TOCTOU window between tryLoad(shardPath) and Files.move(orphan,
        // shardPath, ...) is wide enough that a concurrent writer's fsync+rename frequently
        // interleaves. We reset the baseline and orphan at the start of each iteration so a
        // prior iteration's promotion does not bleed in.
        final int iterations = 200;
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                // Reset shardPath to V2.
                storage.writeShard(tenant, v2);
                // Plant a valid-CRC V3 orphan in the tenant dir (unlocked — simulates
                // crashed prior writer).
                final Path tenantDir = shardPath.getParent();
                assertTrue(Files.isDirectory(tenantDir),
                        "tenant directory must exist after writeShard");
                final Path orphan = tenantDir.resolve("shard.bin."
                        + java.util.UUID.randomUUID().toString().replace("-", "") + ".tmp");
                Files.write(orphan, v3Bytes);

                // Race: writer commits V4; recovery scans and finds the V3 orphan.
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final AtomicReference<Throwable> errW = new AtomicReference<>();
                final AtomicReference<Throwable> errR = new AtomicReference<>();
                final Future<?> writer = exec.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        storage.writeShard(tenant, v4);
                    } catch (Throwable t) {
                        errW.set(t);
                    }
                    return null;
                });
                final Future<?> recovery = exec.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        storage.recoverOrphanTemps();
                    } catch (Throwable t) {
                        errR.set(t);
                    }
                    return null;
                });
                writer.get(15, TimeUnit.SECONDS);
                recovery.get(15, TimeUnit.SECONDS);

                // OverlappingFileLockException is an orthogonal issue in the F-R1.concurrency.3.1
                // fix (the writer+recovery per-JVM lock-tracking race) — skip such iterations so
                // this test focuses on F-R1.concurrency.3.2 (the shardPath TOCTOU). Any non-OFLE
                // failure is still a real regression.
                if (errW.get() instanceof java.nio.channels.OverlappingFileLockException) {
                    continue;
                }
                if (errR.get() instanceof java.nio.channels.OverlappingFileLockException) {
                    continue;
                }
                if (errW.get() != null) {
                    fail("iter " + i + ": writer failed unexpectedly: " + errW.get());
                }
                if (errR.get() != null) {
                    fail("iter " + i + ": recovery failed unexpectedly: " + errR.get());
                }

                // Invariant: because writeShard(V4) returned successfully, a later reader
                // must not observe a shard OLDER than V4. Recovery has three legal outcomes:
                // (a) runs before writer and promotes orphan V3 (shardPath goes V2->V3),
                // then writer commits V4 (shardPath ends at V4);
                // (b) runs after writer, loads V4 as existing, sees orphan V3 is NOT
                // strictly newer, deletes the orphan (shardPath stays V4);
                // (c) runs concurrently with writer, observes V2 as existing under
                // isStrictlyNewer, but defers the promotion to avoid stomping a
                // just-landed V4 (shardPath ends at V4).
                // The BUG is the fourth, illegal outcome: recovery loaded V2, writer then
                // committed V4, then recovery's Files.move stomped V4 back to V3. After both
                // threads return, a fresh load must not return V3. If it returns V3, V4 has
                // been silently lost — the exact regression F-R1.concurrency.3.2 describes.
                final Optional<KeyRegistryShard> afterRace = storage.loadShard(tenant);
                assertTrue(afterRace.isPresent(),
                        "iter " + i + ": shard must be present after writer+recovery race");
                final int onDiskVersion = afterRace.get().domainKeks().get(DOMAIN).version();
                if (onDiskVersion == 3) {
                    fail("iter " + i + ": recoverOrphanTemps stomped a just-committed newer "
                            + "shard (V4) with an older orphan (V3). Final on-disk version=3 "
                            + "despite writeShard(V4) returning success — TOCTOU on shardPath "
                            + "between handleOrphan's tryLoad (line 303) and Files.move "
                            + "(line 307) (F-R1.concurrency.3.2). This is a silent security "
                            + "regression: a rotated-away KEK is re-exposed, or a newly "
                            + "registered DEK is lost.");
                }
                assertTrue(onDiskVersion == 2 || onDiskVersion == 4,
                        "iter " + i + ": final on-disk domain-KEK version must be V2 (orphan "
                                + "safely deferred, writer won outright) or V4 (orphan "
                                + "consumed as V3 then writer bumped to V4, or orphan "
                                + "deferred and writer committed V4). Got unexpected " + "version="
                                + onDiskVersion);
            }
        } finally {
            exec.shutdownNow();
        }
    }

    // --- R10b: verifySaltForTenant first-write race must not allow silent salt overwrite ----

    @Test
    @Timeout(60)
    // Finding: F-R1.concurrency.1.5
    // Bug: verifySaltForTenant's empty-shard pre-check (isAllZero(salt) && deks.isEmpty()
    // && domainKeks.isEmpty()) runs OUTSIDE the per-tenant serialized updateShard critical
    // section. Two holders with DIFFERENT salts (S1 != S2) racing on a fresh shard can both
    // observe the empty state, both call persistConfiguredSalt, and the second committer's
    // mutator unconditionally overwrites the first committer's salt (the mutator writes
    // `this.hkdfSalt` without checking current.hkdfSalt()). Result: registry persists the
    // last-write salt, but the first-write holder has already cached
    // saltVerified[T]=TRUE and continues to derive with its own (now-divergent) salt —
    // silent R10b salt drift.
    // Correct behavior: at most one holder's salt is persisted. The holder whose salt was
    // NOT persisted must throw IllegalArgumentException (salt mismatch) from openDomain —
    // either immediately (first-write contention detected inside the serialized mutator) or
    // on its next operation. The registry's persisted salt must match the salt of every
    // holder that returned successfully from openDomain.
    // Fix location: EncryptionKeyHolder.persistConfiguredSalt — inside the updateShard
    // mutator, re-check current.hkdfSalt(): if it is already non-default AND not equal to
    // this.hkdfSalt, throw (to be surfaced as IllegalArgumentException by the caller). The
    // re-check MUST happen inside the mutator (post-lock), not before.
    // Regression watch: idempotent same-salt writes must remain safe (Arrays.equals(current,
    // this.hkdfSalt) short-circuits cleanly). Initial first-writers on a fresh shard must
    // still succeed.
    void test_verifySaltForTenant_concurrentFreshShard_doesNotSilentlyOverwriteSalt(
            @TempDir Path tempDir) throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient realKms = new LocalKmsClient(keyFile);
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            // Run multiple iterations to reliably trigger the race window between readSnapshot
            // (line 348) and persistConfiguredSalt.updateShard. The writeShard path performs
            // filesystem I/O (fsync + rename), so the window is measured in milliseconds — a
            // CyclicBarrier aligns both openDomain entries and the race occurs with high
            // probability per iteration. Each iteration uses a FRESH registry + storage dir
            // because EncryptionKeyHolder.close() closes the shared registry.
            final int iterations = 10;
            for (int i = 0; i < iterations; i++) {
                final Path registryDir = tempDir.resolve("registry-" + i);
                final ShardStorage storage = new ShardStorage(registryDir);
                final TenantShardRegistry registry = new TenantShardRegistry(storage);
                final byte[] saltA = new byte[32];
                final byte[] saltB = new byte[32];
                // Deterministic but distinct and non-zero.
                for (int k = 0; k < 32; k++) {
                    saltA[k] = (byte) (0x11 + k);
                    saltB[k] = (byte) (0x22 + k);
                }

                final CyclicBarrier barrier = new CyclicBarrier(2);
                final EncryptionKeyHolder holderA = EncryptionKeyHolder.builder().kmsClient(realKms)
                        .registry(registry).activeTenantKekRef(KEK_REF).hkdfSalt(saltA).build();
                final EncryptionKeyHolder holderB = EncryptionKeyHolder.builder().kmsClient(realKms)
                        .registry(registry).activeTenantKekRef(KEK_REF).hkdfSalt(saltB).build();
                final AtomicReference<Throwable> errA = new AtomicReference<>();
                final AtomicReference<Throwable> errB = new AtomicReference<>();

                final Future<Void> a = exec.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        holderA.openDomain(TENANT, DOMAIN);
                    } catch (Throwable t) {
                        errA.set(t);
                    }
                    return null;
                });
                final Future<Void> b = exec.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        holderB.openDomain(TENANT, DOMAIN);
                    } catch (Throwable t) {
                        errB.set(t);
                    }
                    return null;
                });
                a.get(15, TimeUnit.SECONDS);
                b.get(15, TimeUnit.SECONDS);

                // Read the registry's persisted salt BEFORE closing holders (close() also
                // closes the shared registry — a quirk of EncryptionKeyHolder's lifecycle).
                final byte[] persisted = registry.readSnapshot(TENANT).hkdfSalt();

                // Invariant: the persisted salt MUST match the salt of every holder that
                // returned success from openDomain. If holder H succeeded but the registry's
                // salt != H's salt, that is silent salt drift — holder H will derive with its
                // own (stale) salt while the registry says otherwise (F-R1.concurrency.1.5).
                if (errA.get() == null) {
                    assertTrue(Arrays.equals(persisted, saltA),
                            "iter " + i + ": holder A returned success from openDomain but the "
                                    + "registry's persisted salt does not match A's salt — "
                                    + "silent salt drift (F-R1.concurrency.1.5). A will derive "
                                    + "with saltA while the registry stores a different salt; "
                                    + "later readers/rotations will silently produce "
                                    + "mismatched ciphertexts.");
                }
                if (errB.get() == null) {
                    assertTrue(Arrays.equals(persisted, saltB),
                            "iter " + i + ": holder B returned success from openDomain but the "
                                    + "registry's persisted salt does not match B's salt — "
                                    + "silent salt drift (F-R1.concurrency.1.5).");
                }

                // Close holders (one close() will also close the registry; the second close()
                // on the holder will be idempotent on the holder but the second registry.close
                // is also idempotent).
                try {
                    holderA.close();
                } catch (RuntimeException ignored) {
                }
                try {
                    holderB.close();
                } catch (RuntimeException ignored) {
                }
            }
        } finally {
            exec.shutdownNow();
            realKms.close();
        }
    }
}
