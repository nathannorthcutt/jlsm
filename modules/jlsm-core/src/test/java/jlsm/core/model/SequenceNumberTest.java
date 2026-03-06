package jlsm.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SequenceNumberTest {

    @Test
    void negativeValueRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SequenceNumber(-1L));
    }

    @Test
    void zeroValueAccepted() {
        assertDoesNotThrow(() -> new SequenceNumber(0L));
    }

    @Test
    void nextReturnsIncrementedValue() {
        assertEquals(new SequenceNumber(1L), SequenceNumber.ZERO.next());
    }

    @Test
    void compareToRejectsNull() {
        assertThrows(NullPointerException.class, () -> SequenceNumber.ZERO.compareTo(null));
    }

    @Test
    void zeroConstantHasValueZero() {
        assertEquals(0L, SequenceNumber.ZERO.value());
    }

    @Test
    void compareToOrdering() {
        SequenceNumber one = new SequenceNumber(1L);
        SequenceNumber two = new SequenceNumber(2L);
        assertTrue(one.compareTo(two) < 0);
        assertTrue(two.compareTo(one) > 0);
        assertEquals(0, one.compareTo(one));
    }
}
