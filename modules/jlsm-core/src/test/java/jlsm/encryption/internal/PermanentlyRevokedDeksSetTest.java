package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;

/**
 * Tests for {@link PermanentlyRevokedDeksSet} (R83g, P4-28). Bounded durable container; default 10K
 * cap; over-capacity rejected.
 *
 * @spec encryption.primitives-lifecycle R83g
 */
class PermanentlyRevokedDeksSetTest {

    private static DekHandle handle(int v) {
        return new DekHandle(new TenantId("t"), new DomainId("d"), new TableId("tab"),
                new DekVersion(v));
    }

    @Test
    void defaultCapacityIs10K() {
        assertEquals(10_000, PermanentlyRevokedDeksSet.DEFAULT_CAPACITY);
    }

    @Test
    void emptyHasNoMembersAndDefaultCapacity() {
        final PermanentlyRevokedDeksSet s = PermanentlyRevokedDeksSet.empty();
        assertEquals(0L, s.size());
        assertFalse(s.contains(handle(1)));
        assertEquals(PermanentlyRevokedDeksSet.DEFAULT_CAPACITY, s.capacity());
    }

    @Test
    void addProducesNewSetWithMember() {
        final PermanentlyRevokedDeksSet s0 = PermanentlyRevokedDeksSet.empty();
        final PermanentlyRevokedDeksSet s1 = s0.add(handle(1));
        assertNotSame(s0, s1);
        assertFalse(s0.contains(handle(1)));
        assertTrue(s1.contains(handle(1)));
        assertEquals(0L, s0.size());
        assertEquals(1L, s1.size());
    }

    @Test
    void addMultipleHandles() {
        PermanentlyRevokedDeksSet s = PermanentlyRevokedDeksSet.empty();
        for (int i = 1; i <= 5; i++) {
            s = s.add(handle(i));
        }
        assertEquals(5L, s.size());
        for (int i = 1; i <= 5; i++) {
            assertTrue(s.contains(handle(i)));
        }
    }

    @Test
    void addNullHandleRejected() {
        final PermanentlyRevokedDeksSet s = PermanentlyRevokedDeksSet.empty();
        assertThrows(NullPointerException.class, () -> s.add(null));
    }

    @Test
    void containsNullHandleRejected() {
        final PermanentlyRevokedDeksSet s = PermanentlyRevokedDeksSet.empty();
        assertThrows(NullPointerException.class, () -> s.contains(null));
    }

    @Test
    void addingDuplicateIsIdempotent() {
        final PermanentlyRevokedDeksSet s1 = PermanentlyRevokedDeksSet.empty().add(handle(1));
        final PermanentlyRevokedDeksSet s2 = s1.add(handle(1));
        // size remains 1; the resulting set still contains the handle
        assertEquals(1L, s2.size());
        assertTrue(s2.contains(handle(1)));
    }

    @Test
    void addBeyondCapacityThrows() {
        // Construct a small-cap set so we can saturate it inexpensively.
        final Set<DekHandle> seed = new HashSet<>();
        seed.add(handle(1));
        seed.add(handle(2));
        final PermanentlyRevokedDeksSet s = new PermanentlyRevokedDeksSet(seed, 2);
        assertEquals(2L, s.size());
        assertThrows(IllegalStateException.class, () -> s.add(handle(3)),
                "adding beyond capacity must throw IllegalStateException so caller "
                        + "can emit overflow event and FAIL the tenant (P4-28)");
    }

    @Test
    void addingExistingMemberAtCapacityIsOk() {
        // Capacity-equal set: re-adding an existing member must not throw because the
        // resulting set's size is unchanged.
        final Set<DekHandle> seed = new HashSet<>();
        seed.add(handle(1));
        seed.add(handle(2));
        final PermanentlyRevokedDeksSet s = new PermanentlyRevokedDeksSet(seed, 2);
        final PermanentlyRevokedDeksSet s2 = s.add(handle(1));
        assertEquals(2L, s2.size());
    }

    @Test
    void canonicalConstructorRejectsNonPositiveCapacity() {
        final Set<DekHandle> empty = Set.of();
        assertThrows(IllegalArgumentException.class, () -> new PermanentlyRevokedDeksSet(empty, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PermanentlyRevokedDeksSet(empty, -1));
    }

    @Test
    void canonicalConstructorRejectsOversizeSeed() {
        final Set<DekHandle> seed = new HashSet<>();
        seed.add(handle(1));
        seed.add(handle(2));
        seed.add(handle(3));
        assertThrows(IllegalArgumentException.class, () -> new PermanentlyRevokedDeksSet(seed, 2));
    }

    @Test
    void canonicalConstructorRejectsNullElements() {
        final HashSet<DekHandle> seed = new HashSet<>();
        seed.add(handle(1));
        seed.add(null);
        assertThrows(NullPointerException.class, () -> new PermanentlyRevokedDeksSet(seed, 5));
    }
}
