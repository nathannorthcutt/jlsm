package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link TableId} — validates R19 tier-3 scope component. */
class TableIdTest {

    @Test
    void constructor_rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new TableId(null));
    }

    @Test
    void constructor_rejectsEmptyValue() {
        assertThrows(IllegalArgumentException.class, () -> new TableId(""));
    }

    @Test
    void constructor_acceptsNonEmptyString() {
        assertEquals("orders", new TableId("orders").value());
    }

    @Test
    void valueEquality() {
        assertEquals(new TableId("t1"), new TableId("t1"));
    }
}
