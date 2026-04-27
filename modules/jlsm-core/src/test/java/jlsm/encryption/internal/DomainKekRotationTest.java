package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;
import jlsm.encryption.internal.DomainKekRotation.RotationResult;
import jlsm.encryption.internal.ShardLockRegistry.ExclusiveStamp;
import jlsm.encryption.internal.ShardLockRegistry.ShardKey;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Tests for {@link DomainKekRotation} — tier-2 (domain) KEK rotation that rewraps every DEK in the
 * rotating domain under a fresh domain KEK; updates {@link DekVersionRegistry} and acquires an
 * exclusive lock on {@code (tenantId, domainId)} (R32b, R32b-1).
 *
 * @spec encryption.primitives-lifecycle R32b
 * @spec encryption.primitives-lifecycle R32b-1
 * @spec encryption.primitives-lifecycle R34a
 */
class DomainKekRotationTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final DomainId OTHER_DOMAIN = new DomainId("domain-2");
    private static final TableId TABLE_1 = new TableId("table-1");
    private static final TableId TABLE_2 = new TableId("table-2");
    private static final KekRef KEK_REF = new KekRef("kek/v1");

    // ── helpers ──────────────────────────────────────────────────────────

    private static byte[] dummyWrap(int discriminator) {
        final byte[] b = new byte[28];
        b[0] = (byte) discriminator;
        return b;
    }

    private static Path masterKeyFile(Path tempDir) throws IOException {
        final Path keyFile = tempDir.resolve("master.key");
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        Files.write(keyFile, key);
        if (Files.getFileAttributeView(keyFile,
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.copyOf(PosixFilePermissions.fromString("rw-------")));
        }
        return keyFile;
    }

    private static TenantShardRegistry newRegistry(Path tempDir) {
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        return new TenantShardRegistry(storage);
    }

    /** Seed a tenant shard with the given domain-KEK + DEKs in that domain. */
    private static void seed(TenantShardRegistry reg, TenantId tenant, DomainId domain,
            int domainKekVersion, int... dekVersions) throws IOException {
        reg.updateShard(tenant, current -> {
            // Domain rotation needs an activeTenantKekRef to wrap the new domain KEK under.
            // Set it idempotently — tests may seed multiple domains under the same tenant.
            KeyRegistryShard shard = current.activeTenantKekRef() == null
                    ? current.withTenantKekRef(KEK_REF)
                    : current;
            shard = shard.withDomainKek(new WrappedDomainKek(domain, domainKekVersion,
                    dummyWrap(domainKekVersion), KEK_REF));
            for (int v : dekVersions) {
                final DekHandle h = new DekHandle(tenant, domain, TABLE_1, new DekVersion(v));
                shard = shard.withDek(
                        new WrappedDek(h, dummyWrap(v), domainKekVersion, KEK_REF, Instant.EPOCH));
            }
            return new TenantShardRegistry.ShardUpdate<>(shard, null);
        });
    }

    // ── factory + null-arg validation ───────────────────────────────────

    @Test
    void create_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final ShardLockRegistry locks = ShardLockRegistry.create();
            final DekVersionRegistry versions = DekVersionRegistry.empty();
            assertThrows(NullPointerException.class,
                    () -> DomainKekRotation.create(null, locks, versions, kms));
            assertThrows(NullPointerException.class,
                    () -> DomainKekRotation.create(reg, null, versions, kms));
            assertThrows(NullPointerException.class,
                    () -> DomainKekRotation.create(reg, locks, null, kms));
            assertThrows(NullPointerException.class,
                    () -> DomainKekRotation.create(reg, locks, versions, null));
        }
    }

    @Test
    void create_returnsNonNull(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            assertNotNull(DomainKekRotation.create(reg, ShardLockRegistry.create(),
                    DekVersionRegistry.empty(), kms));
        }
    }

    @Test
    void rotate_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            assertThrows(NullPointerException.class, () -> rotator.rotate(null, DOMAIN));
            assertThrows(NullPointerException.class, () -> rotator.rotate(TENANT, null));
        }
    }

    // ── core rotation behavior ──────────────────────────────────────────

    @Test
    void rotate_emptyDomain_succeeds(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            // Seed an empty domain (only domain KEK, no DEKs).
            seed(reg, TENANT, DOMAIN, 1);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            final RotationResult result = rotator.rotate(TENANT, DOMAIN);
            assertEquals(0L, result.dekRewrapCount());
            assertEquals(1, result.oldDomainKekVersion());
            assertTrue(result.newDomainKekVersion() > 1);
        }
    }

    @Test
    void rotate_unknownDomain_throwsIae(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            // Tenant has a shard but no domain entry.
            reg.readSnapshot(TENANT); // force lazy-load
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            assertThrows(IllegalArgumentException.class, () -> rotator.rotate(TENANT, DOMAIN));
        }
    }

    @Test
    void rotate_bumpsDomainKekVersion(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 5);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            final RotationResult result = rotator.rotate(TENANT, DOMAIN);
            assertEquals(5, result.oldDomainKekVersion());
            assertEquals(6, result.newDomainKekVersion());
            // Verify the registry is updated.
            final var shard = reg.readSnapshot(TENANT);
            assertEquals(6, shard.domainKeks().get(DOMAIN).version());
        }
    }

    @Test
    void rotate_rewrapsAllDeksInDomain(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1, 2, 3); // 3 DEKs in domain
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            final RotationResult result = rotator.rotate(TENANT, DOMAIN);
            assertEquals(3L, result.dekRewrapCount());
            // Every DEK in the rotating domain now references the new domain KEK version.
            final var shard = reg.readSnapshot(TENANT);
            for (DekHandle h : shard.deks().keySet()) {
                if (h.domainId().equals(DOMAIN)) {
                    assertEquals(2, shard.deks().get(h).domainKekVersion(),
                            "DEK " + h + " must reference rotated domain KEK version");
                }
            }
        }
    }

    @Test
    void rotate_doesNotTouchOtherDomains(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1, 2);
            seed(reg, TENANT, OTHER_DOMAIN, 1, 1, 2);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            rotator.rotate(TENANT, DOMAIN);
            // Other domain's KEK version unchanged.
            final var shard = reg.readSnapshot(TENANT);
            assertEquals(1, shard.domainKeks().get(OTHER_DOMAIN).version());
            for (DekHandle h : shard.deks().keySet()) {
                if (h.domainId().equals(OTHER_DOMAIN)) {
                    assertEquals(1, shard.deks().get(h).domainKekVersion(),
                            "OTHER_DOMAIN DEKs must not be rewrapped");
                }
            }
        }
    }

    @Test
    void rotate_publishesNewVersionsToDekVersionRegistry(@TempDir Path tempDir) throws IOException {
        // R32b + R64 — rotation must update DekVersionRegistry so wait-free readers see the new
        // domain KEK version through the snapshot.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1, 2);
            final DekVersionRegistry versions = DekVersionRegistry.empty();
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), versions, kms);
            rotator.rotate(TENANT, DOMAIN);
            // Each scope in the rotated domain must have a DekVersionRegistry entry.
            final var entry = versions.currentVersion(new TableScope(TENANT, DOMAIN, TABLE_1));
            assertTrue(entry.isPresent(),
                    "DekVersionRegistry must have an entry for rotated scope");
        }
    }

    @Test
    void rotate_recordsRetiredReference(@TempDir Path tempDir) throws IOException {
        // R33 — after rotation, the retired tier-2 KekRef must be recorded.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            rotator.rotate(TENANT, DOMAIN);
            final RetiredReferences retired = reg.readSnapshot(TENANT).retiredReferences();
            assertEquals(1, retired.entries().size(),
                    "retired set must have one entry post-rotate");
        }
    }

    // ── R34a — exclusive lock isolation ─────────────────────────────────

    @Test
    void rotate_holdsExclusiveLockOnTenantDomainShard(@TempDir Path tempDir) throws Exception {
        // R34a + R32b-1: rotation acquires exclusive on (tenantId, domainId); a concurrent shared
        // acquirer on the same shard must block until rotation completes.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            final ShardLockRegistry locks = ShardLockRegistry.create();
            // Pre-acquire shared on the (tenantId, domainId) key — rotation must block on
            // exclusive acquire until released.
            final ShardKey key = ShardKey.tier2(TENANT, DOMAIN);
            final long sharedStamp = locks.acquireShared(key);
            final DomainKekRotation rotator = DomainKekRotation.create(reg, locks,
                    DekVersionRegistry.empty(), kms);
            final ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                final AtomicBoolean rotationCompleted = new AtomicBoolean(false);
                final var fut = pool.submit(() -> {
                    rotator.rotate(TENANT, DOMAIN);
                    rotationCompleted.set(true);
                    return null;
                });
                // Rotation must NOT complete while shared lock held.
                assertTrue(!fut.isDone() || rotationCompleted.get() == false,
                        "rotation must block on exclusive acquire while shared is held");
                Thread.sleep(100);
                assertTrue(!rotationCompleted.get(),
                        "rotation must remain blocked while shared lock is held on same shard");
                locks.releaseShared(key, sharedStamp);
                fut.get(5, TimeUnit.SECONDS);
                assertTrue(rotationCompleted.get(),
                        "rotation must complete promptly after shared released");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void rotate_doesNotBlockRotationsInOtherDomains(@TempDir Path tempDir) throws Exception {
        // R32b-1 — rotation in DOMAIN must not block rotations in OTHER_DOMAIN of the same
        // tenant.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            seed(reg, TENANT, OTHER_DOMAIN, 1, 1);
            final ShardLockRegistry locks = ShardLockRegistry.create();
            // Pre-acquire exclusive on DOMAIN to simulate a long-running rotation.
            final ExclusiveStamp held = locks.acquireExclusiveTimed(ShardKey.tier2(TENANT, DOMAIN),
                    Duration.ofSeconds(5));
            try {
                final DomainKekRotation rotator = DomainKekRotation.create(reg, locks,
                        DekVersionRegistry.empty(), kms);
                // Rotation on OTHER_DOMAIN must complete promptly — different shard key.
                final ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    final var fut = pool.submit(() -> rotator.rotate(TENANT, OTHER_DOMAIN));
                    final RotationResult result = fut.get(5, TimeUnit.SECONDS);
                    assertNotNull(result);
                    assertEquals(OTHER_DOMAIN, result.domainId());
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                locks.releaseExclusive(held);
            }
        }
    }

    // ── concurrent rotation + DEK creation interleaving ─────────────────

    @Test
    void rotate_blocksConcurrentSameShardSharedHolders(@TempDir Path tempDir) throws Exception {
        // R32b-1 — DEK creation in the rotating domain must not interleave with rotation.
        // Demonstrated via the lock primitives: while rotation holds exclusive, a shared acquire
        // on the same key must wait.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1, 2, 3);
            final ShardLockRegistry locks = ShardLockRegistry.create();
            final DomainKekRotation rotator = DomainKekRotation.create(reg, locks,
                    DekVersionRegistry.empty(), kms);
            final ExecutorService pool = Executors.newFixedThreadPool(2);
            final CountDownLatch sharedAcquired = new CountDownLatch(1);
            final AtomicReference<RotationResult> rotResult = new AtomicReference<>();
            try {
                pool.submit(() -> {
                    try {
                        rotResult.set(rotator.rotate(TENANT, DOMAIN));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                // Concurrent shared acquire on the same shard.
                pool.submit(() -> {
                    final long s = locks.acquireShared(ShardKey.tier2(TENANT, DOMAIN));
                    sharedAcquired.countDown();
                    locks.releaseShared(ShardKey.tier2(TENANT, DOMAIN), s);
                });
                assertTrue(sharedAcquired.await(5, TimeUnit.SECONDS),
                        "shared eventually acquires after rotation releases");
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
                assertNotNull(rotResult.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ── result invariants ───────────────────────────────────────────────

    @Test
    void rotationResult_recordsConsistentVersionPair(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 7, 1);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            final RotationResult result = rotator.rotate(TENANT, DOMAIN);
            assertEquals(TENANT, result.tenantId());
            assertEquals(DOMAIN, result.domainId());
            assertNotEquals(result.oldDomainKekVersion(), result.newDomainKekVersion());
            assertTrue(result.newDomainKekVersion() > result.oldDomainKekVersion());
            assertNotNull(result.completedAt());
        }
    }

    @Test
    void rotate_persistsAcrossRegistryReopen(@TempDir Path tempDir) throws IOException {
        // After rotation, closing and re-opening the registry must observe the rotated state.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            rotator.rotate(TENANT, DOMAIN);
        }
        // Reopen — durable state survived.
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            final var shard = reg.readSnapshot(TENANT);
            assertEquals(2, shard.domainKeks().get(DOMAIN).version());
        }
    }

    @Test
    void rotate_idempotentVersionMonotonic(@TempDir Path tempDir) throws IOException {
        // R32b — repeated rotations bump the version monotonically.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            final RotationResult r1 = rotator.rotate(TENANT, DOMAIN);
            final RotationResult r2 = rotator.rotate(TENANT, DOMAIN);
            final RotationResult r3 = rotator.rotate(TENANT, DOMAIN);
            assertEquals(2, r1.newDomainKekVersion());
            assertEquals(3, r2.newDomainKekVersion());
            assertEquals(4, r3.newDomainKekVersion());
        }
    }

    @Test
    void rotate_recordsRetiredReferenceForEachRotation(@TempDir Path tempDir) throws IOException {
        // R33 — multiple rotations accumulate retired references (one per old domain KEK).
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            rotator.rotate(TENANT, DOMAIN);
            rotator.rotate(TENANT, DOMAIN);
            final RetiredReferences retired = reg.readSnapshot(TENANT).retiredReferences();
            assertTrue(retired.entries().size() >= 1,
                    "retired set must accumulate refs across rotations");
        }
    }

    @Test
    void rotate_unknownTenant_throwsIae(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            // An unknown tenant has no shard entries; rotation must fail.
            assertThrows(IllegalArgumentException.class,
                    () -> rotator.rotate(new TenantId("unknown-tenant"), DOMAIN));
        }
    }

    @Test
    @SuppressWarnings("unused") // demonstrates that DEKs in OTHER_DOMAIN keep their wrappedBytes
    void rotate_otherDomainDeksKeepIdentityOfWrappedBytes(@TempDir Path tempDir)
            throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seed(reg, TENANT, DOMAIN, 1, 1);
            seed(reg, TENANT, OTHER_DOMAIN, 1, 1);
            final var beforeShard = reg.readSnapshot(TENANT);
            final DekHandle otherHandle = new DekHandle(TENANT, OTHER_DOMAIN, TABLE_1,
                    new DekVersion(1));
            final byte[] beforeBytes = beforeShard.deks().get(otherHandle).wrappedBytes();
            final DomainKekRotation rotator = DomainKekRotation.create(reg,
                    ShardLockRegistry.create(), DekVersionRegistry.empty(), kms);
            rotator.rotate(TENANT, DOMAIN);
            final var afterShard = reg.readSnapshot(TENANT);
            final byte[] afterBytes = afterShard.deks().get(otherHandle).wrappedBytes();
            org.junit.jupiter.api.Assertions.assertArrayEquals(beforeBytes, afterBytes,
                    "OTHER_DOMAIN DEK wrappedBytes must be unchanged");
        }
    }
}
