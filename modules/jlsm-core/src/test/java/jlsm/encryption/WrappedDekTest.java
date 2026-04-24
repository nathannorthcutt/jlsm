package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Tests for {@link WrappedDek} — validates R19 persistence record shape. */
class WrappedDekTest {

    private static final DekHandle HANDLE = new DekHandle(new TenantId("t1"), new DomainId("d1"),
            new TableId("tbl"), new DekVersion(1));
    private static final KekRef KEK = new KekRef("k1");
    private static final Instant NOW = Instant.parse("2026-04-23T10:00:00Z");

    @Test
    void constructor_rejectsNullHandle() {
        assertThrows(NullPointerException.class,
                () -> new WrappedDek(null, new byte[]{ 1 }, 1, KEK, NOW));
    }

    @Test
    void constructor_rejectsNullWrappedBytes() {
        assertThrows(NullPointerException.class, () -> new WrappedDek(HANDLE, null, 1, KEK, NOW));
    }

    @Test
    void constructor_rejectsNullTenantKekRef() {
        assertThrows(NullPointerException.class,
                () -> new WrappedDek(HANDLE, new byte[]{ 1 }, 1, null, NOW));
    }

    @Test
    void constructor_rejectsNullCreatedAt() {
        assertThrows(NullPointerException.class,
                () -> new WrappedDek(HANDLE, new byte[]{ 1 }, 1, KEK, null));
    }

    @Test
    void constructor_rejectsZeroDomainKekVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new WrappedDek(HANDLE, new byte[]{ 1 }, 0, KEK, NOW));
    }

    @Test
    void constructor_rejectsNegativeDomainKekVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new WrappedDek(HANDLE, new byte[]{ 1 }, -1, KEK, NOW));
    }

    @Test
    void constructor_defensivelyCopiesInputBytes() {
        final byte[] input = { 1, 2, 3 };
        final WrappedDek w = new WrappedDek(HANDLE, input, 1, KEK, NOW);
        input[0] = 99;
        final byte[] via = w.wrappedBytes();
        assertEquals(1, via[0], "Construction must defensively copy input array");
    }

    @Test
    void accessor_returnsFreshCopy() {
        final byte[] input = { 1, 2, 3 };
        final WrappedDek w = new WrappedDek(HANDLE, input, 1, KEK, NOW);
        final byte[] a = w.wrappedBytes();
        final byte[] b = w.wrappedBytes();
        assertNotSame(a, b, "Each accessor call must return a fresh clone");
        assertArrayEquals(a, b);
    }

    @Test
    void equals_byteArrayContentComparison() {
        // Records' default equals would compare array identity; custom impl must use content.
        final WrappedDek a = new WrappedDek(HANDLE, new byte[]{ 1, 2, 3 }, 1, KEK, NOW);
        final WrappedDek b = new WrappedDek(HANDLE, new byte[]{ 1, 2, 3 }, 1, KEK, NOW);
        assertEquals(a, b);
    }

    @Test
    void equals_differentBytesAreNotEqual() {
        final WrappedDek a = new WrappedDek(HANDLE, new byte[]{ 1, 2, 3 }, 1, KEK, NOW);
        final WrappedDek b = new WrappedDek(HANDLE, new byte[]{ 9, 2, 3 }, 1, KEK, NOW);
        assertNotEquals(a, b);
    }

    @Test
    void hashCode_stableForEqualInstances() {
        final WrappedDek a = new WrappedDek(HANDLE, new byte[]{ 1, 2, 3 }, 1, KEK, NOW);
        final WrappedDek b = new WrappedDek(HANDLE, new byte[]{ 1, 2, 3 }, 1, KEK, NOW);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toString_doesNotLeakKeyBytes() {
        final byte[] sensitive = { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
        final WrappedDek w = new WrappedDek(HANDLE, sensitive, 1, KEK, NOW);
        final String s = w.toString();
        assertFalse(s.contains("DEAD"), "toString must not leak byte content");
        assertFalse(s.contains("deadbeef"), "toString must not leak byte content");
        assertTrue(s.contains("4 bytes"), "toString should indicate byte count instead");
    }
}
