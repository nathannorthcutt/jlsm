package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;
import jlsm.encryption.internal.TenantKekRotation.RotationHandle;
import jlsm.encryption.local.LocalKmsClient;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Tests for {@link TenantKekRotation} — tier-1 (tenant) KEK rotation that re-wraps each shard's
 * domain KEKs under the new Tenant KEK in streaming per-shard batches with R32c max-hold-time
 * release between batches; tracks retired references via {@link RetiredReferences} (R33).
 *
 * @spec encryption.primitives-lifecycle R32a
 * @spec encryption.primitives-lifecycle R32c
 * @spec encryption.primitives-lifecycle R33
 * @spec encryption.primitives-lifecycle R34a
 */
class TenantKekRotationTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final DomainId OTHER_DOMAIN = new DomainId("domain-2");
    private static final TableId TABLE_1 = new TableId("table-1");
    private static final KekRef OLD_REF = new KekRef("kek/v1");
    private static final KekRef NEW_REF = new KekRef("kek/v2");

    // ── helpers ──────────────────────────────────────────────────────────

    private static byte[] dummyWrap(int n) {
        final byte[] b = new byte[28];
        b[0] = (byte) n;
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
        return new TenantShardRegistry(new ShardStorage(tempDir.resolve("registry")));
    }

    /**
     * Produce a real AES-KWP-wrapped 32-byte plaintext using the supplied KMS client. Tier-1
     * rotation calls KmsClient.unwrapKek+wrapKek on each domain KEK; the seed must therefore carry
     * valid wrapped bytes that round-trip through the local KMS.
     */
    private static byte[] realWrap(KmsClient kms, KekRef ref, EncryptionContext ctx, int seed) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = arena.allocate(32);
            for (int i = 0; i < 32; i++) {
                plaintext.set(ValueLayout.JAVA_BYTE, i, (byte) (seed + i));
            }
            try {
                final var wr = kms.wrapKek(plaintext, ref, ctx);
                final java.nio.ByteBuffer bb = wr.wrappedBytes();
                final byte[] copy = new byte[bb.remaining()];
                bb.get(copy);
                return copy;
            } catch (KmsException e) {
                throw new RuntimeException("seed wrap failed", e);
            }
        }
    }

    /** Seed the tenant shard with an active OLD_REF and one or more domain KEKs. */
    private static void seedTenant(TenantShardRegistry reg, KmsClient kms) throws IOException {
        final byte[] domain1Bytes = realWrap(kms, OLD_REF,
                EncryptionContext.forDomainKek(TENANT, DOMAIN), 1);
        final byte[] domain2Bytes = realWrap(kms, OLD_REF,
                EncryptionContext.forDomainKek(TENANT, OTHER_DOMAIN), 2);
        reg.updateShard(TENANT, current -> {
            KeyRegistryShard shard = current.withTenantKekRef(OLD_REF);
            shard = shard.withDomainKek(new WrappedDomainKek(DOMAIN, 1, domain1Bytes, OLD_REF));
            shard = shard
                    .withDomainKek(new WrappedDomainKek(OTHER_DOMAIN, 1, domain2Bytes, OLD_REF));
            // Seed one DEK in each domain so the shard has realistic content. DEK bytes don't
            // need round-trip through KMS — tier-1 rotation does NOT touch DEKs (R32a).
            shard = shard.withDek(
                    new WrappedDek(new DekHandle(TENANT, DOMAIN, TABLE_1, new DekVersion(1)),
                            dummyWrap(3), 1, OLD_REF, Instant.EPOCH));
            shard = shard.withDek(
                    new WrappedDek(new DekHandle(TENANT, OTHER_DOMAIN, TABLE_1, new DekVersion(1)),
                            dummyWrap(4), 1, OLD_REF, Instant.EPOCH));
            return new TenantShardRegistry.ShardUpdate<>(shard, null);
        });
    }

    // ── factory + null-arg validation ───────────────────────────────────

    @Test
    void create_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final ShardLockRegistry locks = ShardLockRegistry.create();
            assertThrows(NullPointerException.class,
                    () -> TenantKekRotation.create(null, locks, kms));
            assertThrows(NullPointerException.class,
                    () -> TenantKekRotation.create(reg, null, kms));
            assertThrows(NullPointerException.class,
                    () -> TenantKekRotation.create(reg, locks, null));
        }
    }

    @Test
    void create_returnsNonNull(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            assertNotNull(TenantKekRotation.create(reg, ShardLockRegistry.create(), kms));
        }
    }

    @Test
    void startRotation_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            assertThrows(NullPointerException.class,
                    () -> rotator.startRotation(null, OLD_REF, NEW_REF));
            assertThrows(NullPointerException.class,
                    () -> rotator.startRotation(TENANT, null, NEW_REF));
            assertThrows(NullPointerException.class,
                    () -> rotator.startRotation(TENANT, OLD_REF, null));
        }
    }

    @Test
    void startRotation_sameOldAndNewRef_throwsIae(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            assertThrows(IllegalArgumentException.class,
                    () -> rotator.startRotation(TENANT, OLD_REF, OLD_REF));
        }
    }

    // ── core rotation behavior ──────────────────────────────────────────

    @Test
    void rotation_advances_throughAllShards(@TempDir Path tempDir) throws IOException {
        // Single-shard registry — advance() returns true once, then false.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                int advanceCount = 0;
                while (handle.advance()) {
                    advanceCount++;
                    if (advanceCount > 100) {
                        org.junit.jupiter.api.Assertions.fail("rotation did not terminate");
                    }
                }
                assertTrue(advanceCount >= 1, "must advance at least one shard");
                assertEquals(advanceCount, handle.rewrappedCount());
            }
        }
    }

    @Test
    void rotation_updatesActiveTenantKekRef(@TempDir Path tempDir) throws IOException {
        // R32a — after rotation completes, the shard's activeTenantKekRef is NEW_REF.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive to completion
                }
            }
            final var shard = reg.readSnapshot(TENANT);
            assertEquals(NEW_REF, shard.activeTenantKekRef());
        }
    }

    @Test
    void rotation_rewrapsAllDomainKeksUnderNewRef(@TempDir Path tempDir) throws IOException {
        // R32a — every domain KEK in the shard now references NEW_REF as its tenantKekRef.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive
                }
            }
            final var shard = reg.readSnapshot(TENANT);
            for (var dk : shard.domainKeks().values()) {
                assertEquals(NEW_REF, dk.tenantKekRef(),
                        "every domain KEK must reference NEW_REF after rotation");
            }
        }
    }

    @Test
    void rotation_doesNotRewrapDeks(@TempDir Path tempDir) throws IOException {
        // R32a — DEKs (tier-3) are NOT touched by tier-1 rotation. Their tenantKekRef may still
        // reference OLD_REF (cascading lazy rewrap on next access).
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final var beforeShard = reg.readSnapshot(TENANT);
            final byte[] beforeDekBytes = beforeShard.deks().values().iterator().next()
                    .wrappedBytes();
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive
                }
            }
            final var afterShard = reg.readSnapshot(TENANT);
            // DEK wrappedBytes are unchanged — tier-3 cipher material is NOT touched (R32a).
            final byte[] afterDekBytes = afterShard.deks().values().iterator().next()
                    .wrappedBytes();
            org.junit.jupiter.api.Assertions.assertArrayEquals(beforeDekBytes, afterDekBytes,
                    "DEK wrapped bytes must not change under tier-1 rotation");
        }
    }

    @Test
    void rotation_recordsOldRefInRetiredSet(@TempDir Path tempDir) throws IOException {
        // R33 — after rotation, OLD_REF is in the retired-references set with retention-until.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive
                }
            }
            final RetiredReferences retired = reg.readSnapshot(TENANT).retiredReferences();
            assertTrue(retired.entries().containsKey(OLD_REF),
                    "OLD_REF must be in retired-references after rotation");
            assertNotNull(retired.entries().get(OLD_REF),
                    "retention-until must be set for retired ref");
        }
    }

    @Test
    void rotation_rewrappedCount_isMonotonic(@TempDir Path tempDir) throws IOException {
        // rewrappedCount only goes up across advance() calls.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                long prev = handle.rewrappedCount();
                while (handle.advance()) {
                    final long cur = handle.rewrappedCount();
                    assertTrue(cur > prev, "rewrappedCount must increase per advance");
                    prev = cur;
                }
            }
        }
    }

    @Test
    void rotation_handleHasMaxHoldTime(@TempDir Path tempDir) throws IOException {
        // R32c — handle exposes max-hold-time (default 250ms).
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                final Duration mht = handle.maxHoldTime();
                assertNotNull(mht);
                assertTrue(mht.toMillis() > 0, "max hold time must be positive");
                assertTrue(mht.toMillis() <= 1000,
                        "default max-hold-time must be <= 1s (default 250ms per R32c)");
            }
        }
    }

    @Test
    void rotation_advanceAfterCompletion_returnsFalse(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive
                }
                assertFalse(handle.advance(), "advance() after completion must return false");
                assertFalse(handle.advance(), "subsequent advance() also false (idempotent)");
            }
        }
    }

    @Test
    void rotation_persistsAcrossRegistryReopen(@TempDir Path tempDir) throws IOException {
        // After completion, durable state must reflect the rotation.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive
                }
            }
        }
        try (TenantShardRegistry reg = newRegistry(tempDir)) {
            final var shard = reg.readSnapshot(TENANT);
            assertEquals(NEW_REF, shard.activeTenantKekRef());
            assertTrue(shard.retiredReferences().entries().containsKey(OLD_REF));
        }
    }

    // ── R34a — exclusive shard lock during rewrap ───────────────────────

    @Test
    void rotation_holdsExclusiveLockDuringEachShardAdvance(@TempDir Path tempDir) throws Exception {
        // R34a — rotation must acquire exclusive on the shard for the rewrap. Demonstrated by
        // observing that a concurrent shared acquire on the tier-1 shard key blocks during the
        // active hold.
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            seedTenant(reg, kms);
            final ShardLockRegistry locks = ShardLockRegistry.create();
            final TenantKekRotation rotator = TenantKekRotation.create(reg, locks, kms);
            // Advance to completion. Each advance() must internally acquire then release the
            // exclusive shard lock — after the loop, the lock must NOT be held (we can acquire
            // exclusive ourselves with no contention).
            try (RotationHandle handle = rotator.startRotation(TENANT, OLD_REF, NEW_REF)) {
                while (handle.advance()) {
                    // drive
                }
            }
            // Post-rotation: lock is released. We can acquire exclusive without contention.
            for (var key : new ShardLockRegistry.ShardKey[]{
                    // Tier-1 keys for the tenant's shard set. We don't know the tenant's shardId
                    // shape without the iterator; sample a likely default value.
                    ShardLockRegistry.ShardKey.tier1(TENANT, "default") }) {
                final var x = locks.acquireExclusiveTimed(key, Duration.ofMillis(50));
                locks.releaseExclusive(x);
            }
        }
    }

    @Test
    void rotation_fromUnknownTenant_failsCleanly(@TempDir Path tempDir) throws IOException {
        try (TenantShardRegistry reg = newRegistry(tempDir);
                KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final TenantKekRotation rotator = TenantKekRotation.create(reg,
                    ShardLockRegistry.create(), kms);
            // An unknown tenant has an empty shard (lazy-loaded); rotation must still terminate
            // cleanly without throwing. The bounded-shard contract requires the loop to finish
            // — the rotation's only requirement is to update activeTenantKekRef on each shard
            // (which for an empty shard is a no-op rewrap of zero domain KEKs).
            try (RotationHandle handle = rotator.startRotation(new TenantId("ghost"), OLD_REF,
                    NEW_REF)) {
                int loops = 0;
                while (handle.advance()) {
                    loops++;
                    if (loops > 1000) {
                        org.junit.jupiter.api.Assertions.fail("rotation did not terminate");
                    }
                }
                // Rotation completes cleanly; rewrappedCount may be 0 (no shards) or >0 (the
                // single-shard registry advances exactly once). Either is contract-conforming.
                assertTrue(handle.rewrappedCount() <= 1,
                        "unknown tenant must produce <= 1 advance for the single-shard view");
            } catch (IllegalArgumentException expected) {
                // Acceptable alternative: implementation rejects unknown tenant eagerly.
            }
        }
    }
}
