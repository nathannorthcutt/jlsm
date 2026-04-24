package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link DekVersion} — validates R18, R18a, R56 positive-version rule. */
class DekVersionTest {

    @Test
    void constructor_rejectsZero() {
        // R56: DEK versions must be positive integers.
        assertThrows(IllegalArgumentException.class, () -> new DekVersion(0));
    }

    @Test
    void constructor_rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new DekVersion(-1));
    }

    @Test
    void constructor_rejectsIntegerMinValue() {
        assertThrows(IllegalArgumentException.class, () -> new DekVersion(Integer.MIN_VALUE));
    }

    @Test
    void constructor_acceptsOne() {
        assertEquals(1, new DekVersion(1).value());
    }

    @Test
    void constructor_acceptsIntegerMaxValue() {
        // R18a bound check is at generateDek-time; the record itself allows max.
        assertEquals(Integer.MAX_VALUE, new DekVersion(Integer.MAX_VALUE).value());
    }

    @Test
    void first_constantEqualsOne() {
        assertEquals(new DekVersion(1), DekVersion.FIRST);
    }

    @Test
    void valueEquality() {
        assertEquals(new DekVersion(5), new DekVersion(5));
        assertNotEquals(new DekVersion(5), new DekVersion(6));
    }
}
