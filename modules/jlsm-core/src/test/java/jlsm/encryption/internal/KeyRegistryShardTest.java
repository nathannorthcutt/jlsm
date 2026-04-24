package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Tests for {@link KeyRegistryShard} — record invariants + wither methods (R19, R10b).
 */
class KeyRegistryShardTest {

    private static final TenantId TENANT = new TenantId("tenantA");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final DekVersion V1 = new DekVersion(1);
    private static final DekVersion V2 = new DekVersion(2);
    private static final KekRef KEK_REF_A = new KekRef("kek-ref-A");
    private static final KekRef KEK_REF_B = new KekRef("kek-ref-B");
    private static final Instant NOW = Instant.parse("2026-04-23T12:00:00Z");

    private static WrappedDek dek(DekVersion v) {
        return new WrappedDek(new DekHandle(TENANT, DOMAIN, TABLE, v), new byte[]{ 1, 2, 3, 4 }, 1,
                KEK_REF_A, NOW);
    }

    private static WrappedDomainKek domainKek(DomainId d, int version) {
        return new WrappedDomainKek(d, version, new byte[]{ 5, 6, 7, 8 }, KEK_REF_A);
    }

    private static byte[] salt32() {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) i;
        }
        return s;
    }

    @Test
    void constructor_rejectsNullTenantId() {
        assertThrows(NullPointerException.class,
                () -> new KeyRegistryShard(null, Map.of(), Map.of(), KEK_REF_A, salt32()));
    }

    @Test
    void constructor_rejectsNullDeks() {
        assertThrows(NullPointerException.class,
                () -> new KeyRegistryShard(TENANT, null, Map.of(), KEK_REF_A, salt32()));
    }

    @Test
    void constructor_rejectsNullDomainKeks() {
        assertThrows(NullPointerException.class,
                () -> new KeyRegistryShard(TENANT, Map.of(), null, KEK_REF_A, salt32()));
    }

    @Test
    void constructor_rejectsNullSalt() {
        assertThrows(NullPointerException.class,
                () -> new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A, null));
    }

    @Test
    void constructor_acceptsNullActiveTenantKekRef_forBootstrap() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), null,
                salt32());
        assertEquals(TENANT, shard.tenantId());
        assertEquals(null, shard.activeTenantKekRef());
    }

    @Test
    void construction_defensiveCopiesSalt() {
        final byte[] original = salt32();
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                original);
        original[0] = (byte) 0xFF;
        // Mutation of the source salt array must not be visible via the shard.
        assertNotEquals(original[0], shard.hkdfSalt()[0]);
    }

    @Test
    void accessor_hkdfSaltReturnsFreshClone() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        final byte[] a = shard.hkdfSalt();
        final byte[] b = shard.hkdfSalt();
        assertNotSame(a, b);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i]);
        }
        a[0] = (byte) 0xFE;
        // Mutating the returned array does not affect subsequent reads.
        assertNotEquals(a[0], shard.hkdfSalt()[0]);
    }

    @Test
    void construction_defensiveCopiesDeksMap() {
        final Map<DekHandle, WrappedDek> src = new HashMap<>();
        src.put(new DekHandle(TENANT, DOMAIN, TABLE, V1), dek(V1));
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, src, Map.of(), KEK_REF_A,
                salt32());
        // Attempt to mutate the returned map must fail (Map.copyOf returns unmodifiable).
        assertThrows(UnsupportedOperationException.class,
                () -> shard.deks().put(new DekHandle(TENANT, DOMAIN, TABLE, V2), dek(V2)));
    }

    @Test
    void construction_defensiveCopiesDomainKeksMap() {
        final Map<DomainId, WrappedDomainKek> src = new HashMap<>();
        src.put(DOMAIN, domainKek(DOMAIN, 1));
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), src, KEK_REF_A,
                salt32());
        assertThrows(UnsupportedOperationException.class,
                () -> shard.domainKeks().put(new DomainId("other"), domainKek(DOMAIN, 2)));
    }

    @Test
    void equals_byContent_ignoresInstanceIdentity() {
        final byte[] s = salt32();
        final KeyRegistryShard a = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A, s);
        final KeyRegistryShard b = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A, s);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_saltDifferenceBreaksEquality() {
        final byte[] s1 = salt32();
        final byte[] s2 = salt32();
        s2[0] = (byte) 0xAA;
        final KeyRegistryShard a = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A, s1);
        final KeyRegistryShard b = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A, s2);
        assertNotEquals(a, b);
    }

    @Test
    void toString_maskSaltBytes_noLeak() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        final String s = shard.toString();
        assertTrue(s.contains("32"), "toString should show salt size; was: " + s);
        assertTrue(s.contains("bytes"), "toString should name the unit; was: " + s);
        // No literal byte value like "[0, 1, 2, 3, ...]" should appear.
        assertTrue(!s.contains("[0, 1, 2"), "toString must not dump salt bytes: " + s);
    }

    @Test
    void withDek_addsNewDek_returnsNewShardWithUpdatedEntry() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        final WrappedDek d1 = dek(V1);
        final KeyRegistryShard updated = shard.withDek(d1);
        assertNotSame(shard, updated, "withDek must return a new instance");
        assertEquals(1, updated.deks().size());
        assertEquals(d1, updated.deks().get(d1.handle()));
        // Original shard is unchanged.
        assertEquals(0, shard.deks().size());
    }

    @Test
    void withDek_replacesExistingDekForSameHandle() {
        final WrappedDek d1 = dek(V1);
        final WrappedDek d1Replacement = new WrappedDek(d1.handle(), new byte[]{ 9, 9, 9, 9 }, 1,
                KEK_REF_A, NOW.plusSeconds(1));
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(d1.handle(), d1),
                Map.of(), KEK_REF_A, salt32());
        final KeyRegistryShard updated = shard.withDek(d1Replacement);
        assertEquals(1, updated.deks().size());
        assertEquals(d1Replacement, updated.deks().get(d1.handle()));
    }

    @Test
    void withDek_nullRejected() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        assertThrows(NullPointerException.class, () -> shard.withDek(null));
    }

    @Test
    void withDomainKek_addsNewDomainKek() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        final WrappedDomainKek dk = domainKek(DOMAIN, 1);
        final KeyRegistryShard updated = shard.withDomainKek(dk);
        assertNotSame(shard, updated);
        assertEquals(1, updated.domainKeks().size());
        assertEquals(dk, updated.domainKeks().get(DOMAIN));
    }

    @Test
    void withDomainKek_replacesEntryForSameDomain() {
        final WrappedDomainKek v1 = domainKek(DOMAIN, 1);
        final WrappedDomainKek v2 = domainKek(DOMAIN, 2);
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(DOMAIN, v1),
                KEK_REF_A, salt32());
        final KeyRegistryShard updated = shard.withDomainKek(v2);
        assertEquals(1, updated.domainKeks().size());
        assertEquals(v2, updated.domainKeks().get(DOMAIN));
    }

    @Test
    void withDomainKek_nullRejected() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        assertThrows(NullPointerException.class, () -> shard.withDomainKek(null));
    }

    @Test
    void withTenantKekRef_replacesActiveRef() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        final KeyRegistryShard updated = shard.withTenantKekRef(KEK_REF_B);
        assertNotSame(shard, updated);
        assertEquals(KEK_REF_B, updated.activeTenantKekRef());
        assertEquals(KEK_REF_A, shard.activeTenantKekRef());
    }

    @Test
    void withTenantKekRef_nullRejected() {
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(), Map.of(), KEK_REF_A,
                salt32());
        assertThrows(NullPointerException.class, () -> shard.withTenantKekRef(null));
    }

    @Test
    void wither_preservesOtherFields() {
        final byte[] salt = salt32();
        final WrappedDek existing = dek(V1);
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT,
                Map.of(existing.handle(), existing), Map.of(DOMAIN, domainKek(DOMAIN, 1)),
                KEK_REF_A, salt);
        final KeyRegistryShard updated = shard.withTenantKekRef(KEK_REF_B);
        assertEquals(shard.deks(), updated.deks());
        assertEquals(shard.domainKeks(), updated.domainKeks());
        assertEquals(TENANT, updated.tenantId());
        // Salt preserved (equals by contents).
        assertEquals(shard, new KeyRegistryShard(shard.tenantId(), shard.deks(), shard.domainKeks(),
                shard.activeTenantKekRef(), shard.hkdfSalt()));
        assertSame(salt.length, updated.hkdfSalt().length);
    }
}
