package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link WrappedDomainKek} — validates R19/R17 tier-2 record shape. */
class WrappedDomainKekTest {

    private static final DomainId DOMAIN = new DomainId("d1");
    private static final KekRef KEK = new KekRef("k1");

    @Test
    void constructor_rejectsNullDomainId() {
        assertThrows(NullPointerException.class,
                () -> new WrappedDomainKek(null, 1, new byte[]{ 1 }, KEK));
    }

    @Test
    void constructor_rejectsNullWrappedBytes() {
        assertThrows(NullPointerException.class, () -> new WrappedDomainKek(DOMAIN, 1, null, KEK));
    }

    @Test
    void constructor_rejectsNullTenantKekRef() {
        assertThrows(NullPointerException.class,
                () -> new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1 }, null));
    }

    @Test
    void constructor_rejectsZeroVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new WrappedDomainKek(DOMAIN, 0, new byte[]{ 1 }, KEK));
    }

    @Test
    void constructor_rejectsNegativeVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new WrappedDomainKek(DOMAIN, -1, new byte[]{ 1 }, KEK));
    }

    @Test
    void constructor_defensivelyCopiesInputBytes() {
        final byte[] input = { 1, 2, 3 };
        final WrappedDomainKek w = new WrappedDomainKek(DOMAIN, 1, input, KEK);
        input[0] = 99;
        assertEquals(1, w.wrappedBytes()[0]);
    }

    @Test
    void accessor_returnsFreshCopy() {
        final WrappedDomainKek w = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1, 2, 3 }, KEK);
        final byte[] a = w.wrappedBytes();
        final byte[] b = w.wrappedBytes();
        assertNotSame(a, b);
        assertArrayEquals(a, b);
    }

    @Test
    void equals_byteArrayContentComparison() {
        final WrappedDomainKek a = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1, 2, 3 }, KEK);
        final WrappedDomainKek b = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1, 2, 3 }, KEK);
        assertEquals(a, b);
    }

    @Test
    void equals_differentBytesAreNotEqual() {
        final WrappedDomainKek a = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1, 2, 3 }, KEK);
        final WrappedDomainKek b = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 9, 2, 3 }, KEK);
        assertNotEquals(a, b);
    }

    @Test
    void hashCode_stableForEqualInstances() {
        final WrappedDomainKek a = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1, 2, 3 }, KEK);
        final WrappedDomainKek b = new WrappedDomainKek(DOMAIN, 1, new byte[]{ 1, 2, 3 }, KEK);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toString_doesNotLeakKeyBytes() {
        final byte[] sensitive = { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };
        final WrappedDomainKek w = new WrappedDomainKek(DOMAIN, 1, sensitive, KEK);
        final String s = w.toString();
        assertFalse(s.contains("DEAD"));
        assertFalse(s.contains("deadbeef"));
        assertTrue(s.contains("4 bytes"));
    }
}
