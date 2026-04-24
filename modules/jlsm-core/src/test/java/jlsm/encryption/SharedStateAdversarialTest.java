package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.KeyRegistryShard;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Adversarial tests for EncryptionKeyHolder shared-state concerns — verifies cache/shard coherence
 * invariants across concurrent mutators.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R18, R29, R62, R62a.
 */
class SharedStateAdversarialTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-X");
    private static final KekRef KEK_REF = new KekRef("local-master");

    // --- helpers ---------------------------------------------------------

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
     * Delegating KmsClient that synchronises every {@code wrapKek} caller on a shared barrier so
     * two concurrent provisioning threads both pass the "shard empty" readSnapshot check BEFORE
     * either enters updateShard. Used to force both threads into provisionDomainKek's updateShard
     * so the second commits at version=2 rather than taking the cache-populated fast path.
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

    /**
     * Clock that lets a test intercept a specific thread's call to {@link #instant()} and suspend
     * it on a latch. All other threads / calls pass through to the delegate.
     *
     * <p>
     * Used here to pause the earlier-committing provisioner between its {@code updateShard} return
     * and its {@code domainCache.put} — provisionDomainKek calls {@code clock.instant()} exactly
     * once between those two steps (line 630).
     */
    private static final class InterceptingClock extends Clock {

        private final Clock delegate;
        private final String interceptedThreadName;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;

        InterceptingClock(Clock delegate, String interceptedThreadName, CountDownLatch release) {
            this.delegate = delegate;
            this.interceptedThreadName = interceptedThreadName;
            this.release = release;
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            if (Thread.currentThread().getName().equals(interceptedThreadName)) {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "InterceptingClock release timed out on thread "
                                        + interceptedThreadName);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("InterceptingClock interrupted", e);
                }
            }
            return delegate.instant();
        }

        void awaitEntered(long timeout, TimeUnit unit)
                throws InterruptedException, TimeoutException {
            if (!entered.await(timeout, unit)) {
                throw new TimeoutException("thread " + interceptedThreadName
                        + " did not reach clock.instant() in time");
            }
        }
    }

    // --- F-R1.shared_state.1.1: cache/shard divergence under concurrent provisionDomainKek ----

    @Test
    @Timeout(30)
    // Finding: F-R1.shared_state.1.1
    // Bug: provisionDomainKek computes the cached plaintext segment, wraps it under the
    // tenant KEK, persists a WrappedDomainKek via updateShard (version now computed inside
    // the mutator per F-R1.concurrency.1.3), then calls domainCache.put OUTSIDE the
    // per-tenant serialization boundary. Two concurrent openDomain calls on a fresh shard
    // each hold a distinct plaintext; updateShard serializes the persist step (shard ends
    // with the later-committer's WrappedDomainKek at version 2, the earlier committer's
    // WrappedDomainKek at version 1 is overwritten in withDomainKek's per-domainId map),
    // but the two domainCache.put calls race: if the earlier committer (whose version=1
    // plaintext no longer matches the persisted shard) wins the put, the cache holds
    // plaintext-A@v1 while the shard holds wrappedBytes-B@v2. Every subsequent generateDek
    // wraps DEKs under plaintext-A and records domainKekVersion=1; those DEKs are persisted
    // under a KEK that is NOT the one the shard records, so on any restart (or TTL expiry
    // triggering re-unwrap to plaintext-B), the previously-wrapped DEKs become
    // permanently undecryptable.
    // Correct behavior: the domainCache entry and the persisted WrappedDomainKek must be
    // coherent after every completed openDomain — specifically, the cache entry's version
    // must equal the shard's current WrappedDomainKek version for that (tenant, domain),
    // and the cached plaintext must correspond to the persisted wrappedBytes.
    // Fix location: EncryptionKeyHolder.provisionDomainKek — either publish the cache entry
    // INSIDE the updateShard mutator (so the cache put is serialized with the shard commit
    // under the per-tenant writer lock), or have the loser of the race re-read the shard
    // and re-unwrap rather than caching its own pre-commit plaintext.
    // Regression watch: do not deadlock — cacheArena.allocate + domainCache.put must not
    // invoke updateShard recursively. Do not break the fast-path cache hit in openDomain.
    void test_provisionDomainKek_concurrentFreshShard_cacheCoherentWithShard(@TempDir Path tempDir)
            throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient realKms = new LocalKmsClient(keyFile);
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final TenantShardRegistry registry = new TenantShardRegistry(storage);

        // The BarrierKms forces both threads to pass their "shard empty" readSnapshot before
        // either enters updateShard. Without this, the second thread would see the first
        // thread's wdk@v=1 in the shard and take the unwrap-and-cache path (not provision)
        // — avoiding the concurrent-provision scenario entirely.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final BarrierKms barrierKms = new BarrierKms(realKms, barrier);

        // InterceptingClock pauses thread-A between its updateShard return and its
        // domainCache.put — provisionDomainKek calls clock.instant() exactly once in that
        // window (line 630). That lets thread-B run the entire provisionDomainKek to
        // completion (updateShard commits version=2, domainCache.put publishes
        // plaintext-B@v2), then we release thread-A so its domainCache.put (plaintext-A@v1)
        // overwrites B's entry. Final state: cache=(plaintext-A, v=1) but shard=
        // (wrappedBytes-B, v=2) — the F-R1.shared_state.1.1 divergence.
        final CountDownLatch releaseA = new CountDownLatch(1);
        final InterceptingClock clock = new InterceptingClock(Clock.systemUTC(), "thread-A",
                releaseA);
        final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(barrierKms)
                .registry(registry).activeTenantKekRef(KEK_REF).clock(clock).build();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        final AtomicReference<Throwable> errA = new AtomicReference<>();
        final AtomicReference<Throwable> errB = new AtomicReference<>();
        try {
            // Thread A: runs first, enters wrapKek (blocks on barrier), commits v=1 in
            // updateShard, blocks at clock.instant() before domainCache.put.
            final Future<Void> a = exec.submit(() -> {
                Thread.currentThread().setName("thread-A");
                try {
                    holder.openDomain(TENANT, DOMAIN);
                } catch (Throwable t) {
                    errA.set(t);
                }
                return null;
            });

            // Thread B: runs concurrently. B's wrapKek also waits on the barrier so both
            // threads pass readSnapshot==empty before either proceeds. After the barrier
            // releases, A's updateShard wins the per-tenant writer lock (A entered
            // provisionDomainKek first, though this is a scheduling detail). B's updateShard
            // sees current=A's wdk@v=1 and commits wdk@v=2. B then runs clock.instant()
            // unmolested (InterceptingClock only pauses thread-A) and publishes its
            // CachedDomainKek@v=2.
            final Future<Void> b = exec.submit(() -> {
                Thread.currentThread().setName("thread-B");
                try {
                    holder.openDomain(TENANT, DOMAIN);
                } catch (Throwable t) {
                    errB.set(t);
                }
                return null;
            });

            // Wait for thread-A to reach the intercepted clock.instant() (updateShard has
            // already committed). Without this wait, we might release A before B has
            // completed its full provision sequence.
            clock.awaitEntered(15, TimeUnit.SECONDS);

            // Verify thread-A's updateShard committed v=1. Whichever thread took the per-
            // tenant writer lock first is the v=1 committer; we named A as the paused
            // thread but the ordering here depends on scheduling.
            final KeyRegistryShard afterA = registry.readSnapshot(TENANT);
            final var wdkAfterA = afterA.domainKeks().get(DOMAIN);
            assertNotNull(wdkAfterA, "the paused thread must have committed a WrappedDomainKek");

            // Wait for thread-B to complete its full openDomain (includes domainCache.put).
            b.get(15, TimeUnit.SECONDS);
            if (errB.get() != null) {
                fail("thread-B failed unexpectedly: " + errB.get());
            }

            // Release thread-A. A's clock.instant() returns, A proceeds to domainCache.put
            // with plaintext-A@v=1 — overwriting whatever B published.
            releaseA.countDown();
            a.get(15, TimeUnit.SECONDS);
            if (errA.get() != null) {
                fail("thread-A failed unexpectedly: " + errA.get());
            }

            // Shard now reflects the later-committer's wdk at v=2 (withDomainKek keys by
            // domainId, so the second commit overwrites the first entry).
            final KeyRegistryShard finalShard = registry.readSnapshot(TENANT);
            final var finalWdk = finalShard.domainKeks().get(DOMAIN);
            assertNotNull(finalWdk, "final shard must contain a WrappedDomainKek");
            final int shardVersion = finalWdk.version();
            assertEquals(2, shardVersion,
                    "shard should end at version 2 after two serialized provisioners (both "
                            + "passed the empty-shard readSnapshot via the barrier; the later "
                            + "committer computes nextDomainKekVersion=2 inside the mutator)");

            // The cache is observed indirectly through generateDek: the facade stamps
            // WrappedDek.domainKekVersion() with the in-memory cached version (line 285).
            // If the cache is coherent with the shard, the stamped version MUST equal the
            // shard's WrappedDomainKek version. If not, the cache plaintext does not
            // correspond to the persisted wrappedBytes — the F-R1.shared_state.1.1 divergence.
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            final KeyRegistryShard postDek = registry.readSnapshot(TENANT);
            final var wdek = postDek.deks().get(handle);
            assertNotNull(wdek, "generateDek must have persisted a WrappedDek");
            assertEquals(shardVersion, wdek.domainKekVersion(),
                    "R29 / F-R1.shared_state.1.1: after concurrent openDomain, the cached "
                            + "domain-KEK plaintext used by generateDek MUST correspond to the "
                            + "WrappedDomainKek version persisted in the shard. shardVersion="
                            + shardVersion + " dekStampedVersion=" + wdek.domainKekVersion()
                            + " — cache is wrapping DEKs under a plaintext that does NOT match "
                            + "the persisted wrappedBytes; those DEKs will be permanently "
                            + "undecryptable after cache eviction or holder restart.");
        } finally {
            releaseA.countDown();
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

    // --- F-R1.shared_state.2.1: publish-after-close via snapshot.set must not leave a
    // non-null snapshot on a TenantEntry that close() already drained ----------------------

    @Test
    @Timeout(60)
    // Finding: F-R1.shared_state.2.1
    // Bug: TenantShardRegistry.updateShard performs requireOpen() at line 140 BEFORE
    // storage.writeShard and entry.snapshot.set. close() does not acquire entry.writerLock,
    // so if close() interleaves AFTER the requireOpen() guard but DURING storage.writeShard
    // (the fsync window spans several ms), close's CAS and getAndSet(null)/zeroizeSalt run
    // on W's entry while W is inside writeShard. When W resumes, entry.snapshot.set
    // unconditionally re-publishes a non-null snapshot on the TenantEntry close() believed
    // it had drained — I-CL violated. The root-cause observation: updateShard's successful
    // return (no exception) post-close implies snapshot.set ran post-close, re-populating
    // the drained entry.
    // Correct behavior: if close() has run by the time updateShard is about to publish
    // snapshot, updateShard must throw IllegalStateException instead of returning. A
    // successful return of updateShard implies the publish happened while the registry was
    // still open (close had not yet CAS'd).
    // Fix location: TenantShardRegistry.updateShard — re-check closed AFTER storage.writeShard
    // returns (before snapshot.set). AND/OR have close() acquire entry.writerLock before
    // draining to fence out in-flight writers so close cannot reach its drain step until the
    // writer's finally-block releases the lock.
    // Regression watch: do not deadlock close against an in-flight writer; do not break the
    // happy path (non-racing updateShard must still persist + publish); do not break
    // readers that race close via requireOpen in readSnapshot.
    void test_updateShard_publishAfterClose_writerMustNotReturnSuccessfullyAfterClose(
            @TempDir Path tempDir) throws Exception {
        // Strategy: many rounds of racing updateShard against close. In each round we
        // record a happens-before fact: did close's CAS complete BEFORE the writer
        // returned success? If yes AND the writer returned without exception, the
        // writer re-published to the drained entry (snapshot.set ran after close's
        // getAndSet(null)/zeroizeSalt).
        //
        // We observe this without reflection: close() is invoked from thread C and sets
        // an AtomicBoolean `closeCompleted` AFTER close() returns. The writer records its
        // return status (success vs IllegalStateException) in writerOutcome. If in any
        // round: closeCompleted was set AND writerOutcome == SUCCESS, the post-close
        // publish happened — F-R1.shared_state.2.1 confirmed.
        //
        // Note: close() draining requires acquiring writerLock on each tenant, which will
        // block until the writer's finally releases it. So close() returning implies the
        // writer already released the lock — which means either the writer threw and
        // skipped snapshot.set (correct), or the writer ran snapshot.set (bug) and close
        // then drained it.
        final int rounds = 40;
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                final Path registryDir = tempDir.resolve("reg-shared-2-1-v2-" + round);
                final ShardStorage storage = new ShardStorage(registryDir);
                final TenantShardRegistry registry = new TenantShardRegistry(storage);

                // Seed baseline shard so updateShard has a realistic write window.
                final KekRef baselineKekRef = new KekRef("baseline-" + round);
                registry.updateShard(TENANT, current -> {
                    final var seeded = current.withTenantKekRef(baselineKekRef);
                    return new TenantShardRegistry.ShardUpdate<>(seeded, null);
                });

                final KekRef postCloseKekRef = new KekRef("post-close-" + round);
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final AtomicBoolean closeCompleted = new AtomicBoolean(false);
                final AtomicBoolean closeCasObserved = new AtomicBoolean(false);
                final AtomicReference<String> writerOutcome = new AtomicReference<>("PENDING");
                final AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

                final Future<Void> writer = exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        Thread.yield();
                        registry.updateShard(TENANT, current -> {
                            final var updated = current.withTenantKekRef(postCloseKekRef);
                            return new TenantShardRegistry.ShardUpdate<>(updated, null);
                        });
                        // updateShard returned without exception — snapshot.set ran.
                        writerOutcome.set("SUCCESS");
                    } catch (IllegalStateException expected) {
                        writerOutcome.set("CLOSED");
                    } catch (Throwable t) {
                        writerOutcome.set("UNEXPECTED");
                        unexpectedError.set(t);
                    }
                    return null;
                });

                final Future<Void> closer = exec.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        // Short spin to let W enter updateShard's body (past line 140).
                        for (int i = 0; i < 100; i++) {
                            Thread.yield();
                        }
                        closeCasObserved.set(true);
                        registry.close();
                        closeCompleted.set(true);
                    } catch (Throwable t) {
                        unexpectedError.compareAndSet(null, t);
                    }
                    return null;
                });

                writer.get(15, TimeUnit.SECONDS);
                closer.get(15, TimeUnit.SECONDS);

                if (unexpectedError.get() != null) {
                    fail("round " + round + ": unexpected error: " + unexpectedError.get());
                }
                assertTrue(closeCasObserved.get(), "closer must have started");

                // The telltale: close completed AND the writer returned success. The
                // writer's snapshot.set ran after close's getAndSet(null)/zeroizeSalt
                // drained the entry. Check on-disk state as corroborating evidence: if
                // the writer returned success, both writeShard (disk) AND snapshot.set
                // (memory) ran; if close also completed, then close's drain happened
                // after the writer's publish — the F-R1.shared_state.2.1 re-populate.
                if ("SUCCESS".equals(writerOutcome.get()) && closeCompleted.get()) {
                    // Confirm via disk state that writeShard committed the post-close
                    // bytes — this strengthens the claim that the writer raced past
                    // close's CAS rather than "writer finished before close started".
                    final ShardStorage verifyStorage = new ShardStorage(registryDir);
                    final Optional<KeyRegistryShard> onDisk = verifyStorage.loadShard(TENANT);
                    assertTrue(onDisk.isPresent(), "shard must exist on disk");
                    final KekRef persistedKekRef = onDisk.get().activeTenantKekRef();
                    if (postCloseKekRef.equals(persistedKekRef)) {
                        fail("round " + round + ": writer returned success AND close "
                                + "returned successfully, and the on-disk shard reflects "
                                + "the writer's post-close update. snapshot.set at line "
                                + "142 re-published a non-null snapshot on the drained "
                                + "TenantEntry after close's getAndSet(null)/zeroizeSalt "
                                + "(F-R1.shared_state.2.1). Fix: re-check closed AFTER "
                                + "writeShard (before snapshot.set) AND/OR have close() "
                                + "acquire entry.writerLock before draining to fence "
                                + "out in-flight writers.");
                    }
                }
            }
        } finally {
            exec.shutdownNow();
        }
    }

    // --- F-R1.shared_state.3.1: Parent directory is never fsync'd — atomic rename is not
    // crash-durable -------------------------------------------------------------------------

    @Test
    @Timeout(30)
    // Finding: F-R1.shared_state.3.1
    // Bug: ShardStorage.writeShard performs temp-write + ch.force(true) + Files.move(...,
    // ATOMIC_MOVE, REPLACE_EXISTING) but NEVER fsyncs the parent directory after the rename.
    // On POSIX filesystems (ext4 data=writeback, xfs without metadata ordering, many network
    // filesystems) the directory-entry change from `shard.bin.<uuid>.tmp` → `shard.bin` is
    // NOT persisted to the on-disk journal until the parent dentry is fsync'd. A crash between
    // the rename and the eventual background flush loses the dentry change; the shard reverts
    // to its prior content (or to absent) on restart, while the writer's caller already
    // believed the write was durable and may have triggered side-effects (cluster announce,
    // returning success to user). Javadoc at line 152 claims "atomically persist" — the
    // claim is not met without parent-dir fsync. The fix: after Files.move, open the parent
    // directory with FileChannel.open(parent, READ) and call force(true) to flush the
    // dentry change to the journal.
    // Correct behavior: writeShard's bytecode must contain AT LEAST TWO FileChannel.open
    // invocations paired with FileChannel.force(Z)V invocations — one for the temp-file
    // data fsync (already present at lines 197-205), and a SECOND one for the parent
    // directory fsync after Files.move at line 217. Pre-fix, the method contains exactly
    // one FileChannel.open + one FileChannel.force; post-fix, it contains at least two
    // of each.
    // Fix location: ShardStorage.writeShard around line 217 (after Files.move, before or
    // inside the try-finally's committed branch).
    // Regression watch: the parent-dir fsync must run ONLY on commit success (not on
    // cleanup path for uncommitted temp files). The channel must be in READ mode (not
    // WRITE) because POSIX does not permit opening a directory for WRITE. The fsync must
    // use force(true) (metadata included) — force(false) does not guarantee dentry
    // durability on all filesystems. On non-POSIX platforms where opening a directory as
    // a FileChannel is unsupported, the implementation may skip (with a best-effort
    // comment), but on the test platform (Linux/ext4) the call must be present.
    //
    // Verification approach: inspect the compiled bytecode of writeShard using the Java
    // 25 java.lang.classfile API. Count the direct invocations of FileChannel.open and
    // FileChannel.force in the method body. Before the fix: exactly 1 of each (the
    // temp-file fsync). After the fix: at least 2 of each (temp-file + parent-dir).
    // This is a structural test — it does not simulate a crash, but it proves the fix
    // is wired into the code path. A power-cut simulation is not feasible in a unit
    // test; structural verification is the standard mechanical proof for "call X must
    // be issued after Y" contracts on durability.
    void test_writeShard_parentDirectoryFsyncAfterAtomicRename_R20Postcondition(
            @TempDir Path tempDir) throws Exception {
        // Locate the compiled ShardStorage.class on the test classpath. ClassLoader
        // exposes the resource by its binary path.
        final String resource = "jlsm/encryption/internal/ShardStorage.class";
        final var classBytesStream = SharedStateAdversarialTest.class.getClassLoader()
                .getResourceAsStream(resource);
        assertNotNull(classBytesStream, "ShardStorage.class must be on the test classpath");
        final byte[] classBytes;
        try (var in = classBytesStream) {
            classBytes = in.readAllBytes();
        }

        final ClassModel model = ClassFile.of().parse(classBytes);

        // Find the writeShard(TenantId, KeyRegistryShard) method. Match by name only —
        // the tenantId/shard types are in the same package and there is exactly one
        // writeShard method on ShardStorage.
        MethodModel writeShard = null;
        for (MethodModel m : model.methods()) {
            if (m.methodName().stringValue().equals("writeShard")) {
                writeShard = m;
                break;
            }
        }
        assertNotNull(writeShard, "writeShard method must exist on ShardStorage");

        // Count FileChannel.open and FileChannel.force invocations in the method body.
        // A typical temp-file fsync yields one of each. The fix adds a second pair for
        // the parent-directory fsync after Files.move.
        int fileChannelOpenInvocations = 0;
        int fileChannelForceInvocations = 0;
        int filesMoveInvocations = 0;

        final var codeOpt = writeShard.code();
        assertTrue(codeOpt.isPresent(), "writeShard must have a code attribute");
        for (var element : codeOpt.get()) {
            if (element instanceof InvokeInstruction invoke) {
                final String owner = invoke.owner().asInternalName();
                final String name = invoke.name().stringValue();
                if (owner.equals("java/nio/channels/FileChannel") && name.equals("open")) {
                    fileChannelOpenInvocations++;
                } else if (owner.equals("java/nio/channels/FileChannel") && name.equals("force")) {
                    fileChannelForceInvocations++;
                } else if (owner.equals("java/nio/file/Files") && name.equals("move")) {
                    filesMoveInvocations++;
                }
            }
        }

        // Sanity: the method always performs the atomic rename and the temp-file fsync.
        assertTrue(filesMoveInvocations >= 1,
                "writeShard must contain at least one Files.move invocation (atomic rename)");
        assertTrue(fileChannelOpenInvocations >= 1,
                "writeShard must contain at least one FileChannel.open invocation (temp-file write)");

        // The durability claim: after the fix, a SECOND FileChannel.open + FileChannel.force
        // pair must exist — the parent-directory fsync that makes the atomic rename's
        // dentry change crash-durable. Pre-fix this assertion fails because the method
        // contains only the temp-file fsync (1 open, 1 force).
        assertTrue(fileChannelOpenInvocations >= 2,
                "R20 / F-R1.shared_state.3.1: writeShard must fsync the parent directory "
                        + "after Files.move to make the rename's dentry change crash-durable. "
                        + "Expected at least 2 FileChannel.open invocations in writeShard "
                        + "(one for the temp-file data fsync, one for the parent-dir fsync "
                        + "after the atomic rename), but found only " + fileChannelOpenInvocations
                        + ". Fix: after Files.move at line 217, add "
                        + "`try (FileChannel dir = FileChannel.open(shardPath.getParent(), "
                        + "StandardOpenOption.READ)) { dir.force(true); }` inside the committed "
                        + "branch so the rename's directory-entry change is persisted to the "
                        + "filesystem journal before writeShard returns. Without this fsync, "
                        + "a crash between the rename and the OS's background dentry flush "
                        + "reverts the shard to its prior content, violating the Javadoc's "
                        + "'atomically persist' postcondition (line 152) — the caller observes "
                        + "a successful return for a write the filesystem has not actually "
                        + "committed.");
        assertTrue(fileChannelForceInvocations >= 2,
                "R20 / F-R1.shared_state.3.1: writeShard must call FileChannel.force on the "
                        + "parent directory after Files.move. Expected at least 2 "
                        + "FileChannel.force invocations (temp-file + parent-dir), but found "
                        + "only " + fileChannelForceInvocations
                        + ". The parent-directory fsync is what persists the rename's "
                        + "directory-entry change. Without force(true) on the parent dir, a "
                        + "crash after the rename reverts the shard to its prior on-disk "
                        + "state; the caller's post-writeShard side-effects (cluster "
                        + "announce, success return) leak a key-version claim that the "
                        + "filesystem has not durably committed.");

        // tempDir is used here only to keep the @TempDir parameter wired into the test
        // lifecycle (JUnit requires used parameters). No file I/O is needed — the test
        // verifies the fix at the bytecode level.
        assertNotNull(tempDir);
    }

    // --- F-R1.shared_state.4.1: LocalKmsClient.wrapKek races with close() on the shared
    // master-key segment — no read-side lock serialises wrap against close. ----------------

    @Test
    @Timeout(60)
    // Finding: F-R1.shared_state.4.1
    // Bug: LocalKmsClient guards its open/closed lifecycle only with AtomicBoolean closed.
    // wrapKek checks requireOpen() then reads masterKeySegment inside
    // AesKeyWrap.wrap(masterKeySegment, plaintextKek). If close() runs concurrently after
    // requireOpen() passed, close performs masterKeySegment.fill((byte) 0) followed by
    // masterArena.close() while the wrap is mid-read. Two adversarial outcomes are possible
    // under this race:
    // (a) AesKeyWrap.wrap's MemorySegment.copy reads partially-zeroed master-key bytes
    // and returns a ciphertext wrapped under a predictable (all-zero or partially-
    // zero) KEK — a silent confidentiality failure where wrapKek returns success but
    // the WrapResult does not match the deterministic AES-KWP output for the real
    // master key.
    // (b) AesKeyWrap's segment read throws IllegalStateException because the arena was
    // closed mid-read. Current wrapKek catches IllegalStateException and translates
    // it to KmsPermanentException, so (b) is contractually acceptable — but (a) is
    // not: any successful WrapResult whose bytes differ from the deterministic AES-
    // KWP output is a catastrophic silent corruption.
    // Correct behavior: any wrapKek that returns a WrapResult MUST return the deterministic
    // AES-KWP ciphertext for the real master key. Close must not be able to corrupt the
    // master segment while a wrap is in flight. With a correct read-write lock: close takes
    // the writeLock and blocks until all in-flight wraps release their readLocks, so every
    // successful wrap sees the intact master key and returns the expected bytes; wraps that
    // start after close's writeLock observe closed=true at requireOpen and throw
    // KmsPermanentException. No wrap ever returns corrupted bytes.
    // Fix location: LocalKmsClient — add a ReentrantReadWriteLock (mirroring
    // OffHeapKeyMaterial.rwLock / EncryptionKeyHolder.deriveGuard). wrapKek and unwrapKek
    // acquire the readLock across requireOpen + AesKeyWrap.wrap/unwrap + return; close
    // acquires the writeLock across the CAS + fill + arena.close so close fences out all
    // in-flight reads.
    // Regression watch: must not deadlock — close must not call back into wrapKek under
    // the writeLock. The readLock scope must include the AesKeyWrap call (the read of the
    // segment), not just the closed check. Do not serialise wraps against each other —
    // multiple concurrent wrappers should all acquire the readLock and proceed in parallel.
    void test_wrapKek_racesWithClose_allSuccessfulWrapsMatchDeterministicOutput(
            @TempDir Path tempDir) throws Exception {
        // Strategy: run many rounds of "wrapKek in a tight loop on thread W" racing against
        // "close() on thread C". Pre-compute the expected deterministic AES-KWP wrapped
        // bytes under the real master key. Each round: collect every WrapResult and every
        // exception. Assert (1) every exception is a KmsException (the SPI contract allows
        // only KmsException subtypes to escape), and (2) every successful WrapResult's
        // bytes equal the pre-computed expected bytes — a different wrapping is proof that
        // the master segment was corrupted by close's fill() mid-wrap.
        //
        // Without a readLock, the race window between `MemorySegment.copy(kek, ...)` inside
        // AesKeyWrap.wrap (line 64) and close's `masterKeySegment.fill((byte) 0)` produces
        // either partial-zero key reads (outcome a) or IllegalStateException (outcome b).
        // Across thousands of rounds this is near-certain to trigger at least once.
        final int rounds = 200;
        final ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            // Deterministic master key (32 bytes). AES-KWP is deterministic so
            // wrap(masterKey, plaintextKek) has exactly one correct ciphertext.
            final byte[] masterKeyBytes = new byte[32];
            for (int i = 0; i < 32; i++) {
                masterKeyBytes[i] = (byte) (i + 1);
            }

            // Plaintext KEK to wrap (32 bytes of a distinct pattern so it differs from the
            // master key).
            final byte[] plaintextKekBytes = new byte[32];
            for (int i = 0; i < 32; i++) {
                plaintextKekBytes[i] = (byte) (0x80 ^ (i + 1));
            }

            final KekRef kekRef = new KekRef("local-master");
            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

            // Compute expected wrapped bytes once, with a dedicated client that is never
            // closed during the wrap. This is the reference ciphertext.
            final Path expectedKeyFile = tempDir.resolve("expected-master.key");
            writeNamedMasterKey(expectedKeyFile, masterKeyBytes);
            final byte[] expectedWrapped;
            try (LocalKmsClient expectedClient = new LocalKmsClient(expectedKeyFile);
                    Arena pkArena = Arena.ofConfined()) {
                final MemorySegment plaintextKek = pkArena.allocate(plaintextKekBytes.length);
                MemorySegment.copy(plaintextKekBytes, 0, plaintextKek, ValueLayout.JAVA_BYTE, 0,
                        plaintextKekBytes.length);
                final WrapResult refResult = expectedClient.wrapKek(plaintextKek, kekRef, ctx);
                expectedWrapped = new byte[refResult.wrappedBytes().remaining()];
                refResult.wrappedBytes().duplicate().get(expectedWrapped);
            }

            final HexFormat hex = HexFormat.of();
            final List<String> corruptedHex = new ArrayList<>();
            final AtomicInteger totalWraps = new AtomicInteger();
            final AtomicInteger totalKmsExceptions = new AtomicInteger();
            final AtomicReference<Throwable> nonKmsException = new AtomicReference<>();

            for (int round = 0; round < rounds; round++) {
                final Path keyFile = tempDir.resolve("master-" + round + ".key");
                writeNamedMasterKey(keyFile, masterKeyBytes);
                final LocalKmsClient client = new LocalKmsClient(keyFile);

                // Per-round off-heap plaintextKek lives in a shared arena so the wrapper
                // thread can read it freely. Confined would require ownership transfer.
                final Arena pkArena = Arena.ofShared();
                try {
                    final MemorySegment plaintextKek = pkArena.allocate(plaintextKekBytes.length);
                    MemorySegment.copy(plaintextKekBytes, 0, plaintextKek, ValueLayout.JAVA_BYTE, 0,
                            plaintextKekBytes.length);

                    final CountDownLatch startRace = new CountDownLatch(1);
                    final AtomicBoolean stop = new AtomicBoolean(false);
                    final List<byte[]> observedWraps = new ArrayList<>();
                    final Object wrapsLock = new Object();

                    final Future<Void> wrapper = exec.submit(() -> {
                        startRace.await();
                        while (!stop.get()) {
                            try {
                                final WrapResult r = client.wrapKek(plaintextKek, kekRef, ctx);
                                final byte[] bytes = new byte[r.wrappedBytes().remaining()];
                                r.wrappedBytes().duplicate().get(bytes);
                                synchronized (wrapsLock) {
                                    observedWraps.add(bytes);
                                }
                                totalWraps.incrementAndGet();
                            } catch (KmsException expected) {
                                totalKmsExceptions.incrementAndGet();
                                // Once closed, wraps throw KmsPermanentException — stop
                                // looping; the race window has passed.
                                if (expected instanceof KmsPermanentException) {
                                    return null;
                                }
                            } catch (Throwable t) {
                                nonKmsException.compareAndSet(null, t);
                                return null;
                            }
                        }
                        return null;
                    });

                    final Future<Void> closer = exec.submit(() -> {
                        startRace.await();
                        // Tiny stagger so the wrapper enters its tight loop before close
                        // fires. Without this, close often runs before wrapKek is even
                        // entered and the race window is missed entirely.
                        for (int i = 0; i < 50; i++) {
                            Thread.onSpinWait();
                        }
                        client.close();
                        stop.set(true);
                        return null;
                    });

                    startRace.countDown();
                    closer.get(10, TimeUnit.SECONDS);
                    wrapper.get(10, TimeUnit.SECONDS);

                    synchronized (wrapsLock) {
                        for (byte[] observed : observedWraps) {
                            if (!Arrays.equals(observed, expectedWrapped)) {
                                corruptedHex.add(
                                        "round=" + round + " bytes=" + hex.formatHex(observed));
                                if (corruptedHex.size() >= 3) {
                                    // Three examples are enough for the failure report;
                                    // stop collecting.
                                    break;
                                }
                            }
                        }
                    }
                } finally {
                    pkArena.close();
                    // client.close() was called on the closer thread; calling close again
                    // is a no-op (CAS-idempotent), but may throw if the arena is already
                    // closed on a re-entry — guard defensively.
                    try {
                        client.close();
                    } catch (RuntimeException ignored) {
                    }
                }

                if (nonKmsException.get() != null || !corruptedHex.isEmpty()) {
                    // Bail early — we have enough evidence. No need to run the remaining
                    // rounds.
                    break;
                }
            }

            if (nonKmsException.get() != null) {
                fail("R66/R69 / F-R1.shared_state.4.1: wrapKek threw a non-KmsException under "
                        + "concurrent close — contract violation (the SPI declares throws "
                        + "KmsException). cause=" + nonKmsException.get());
            }

            assertTrue(corruptedHex.isEmpty(),
                    "R62a/R66 / F-R1.shared_state.4.1: wrapKek returned WrapResult bytes that "
                            + "do NOT match the deterministic AES-KWP output under concurrent "
                            + "close(). This means close's masterKeySegment.fill((byte) 0) ran "
                            + "during AesKeyWrap.wrap's MemorySegment.copy of the master key, "
                            + "causing the cipher to wrap the plaintext KEK under a zero (or "
                            + "partial-zero) KEK — a catastrophic silent confidentiality "
                            + "failure. Fix: add a ReentrantReadWriteLock to LocalKmsClient "
                            + "(mirroring OffHeapKeyMaterial.rwLock); wrapKek/unwrapKek hold "
                            + "the readLock across the segment read; close holds the "
                            + "writeLock across fill + arena.close. Expected wrapped (hex): "
                            + hex.formatHex(expectedWrapped) + ". Examples of corrupt outputs: "
                            + String.join(" | ", corruptedHex) + ". totalWraps=" + totalWraps.get()
                            + " totalKmsExceptions=" + totalKmsExceptions.get());
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Helper to write a POSIX-0600 master key file with the given bytes. Used by the LocalKmsClient
     * wrap/close race test where each round provisions a fresh client on its own master-key file
     * under tempDir.
     */
    private static void writeNamedMasterKey(Path keyFile, byte[] bytes) throws IOException {
        Files.write(keyFile, bytes);
        if (Files.getFileAttributeView(keyFile.getParent(),
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }
}
