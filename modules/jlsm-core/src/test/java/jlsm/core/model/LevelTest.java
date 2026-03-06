package jlsm.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LevelTest {

    @Test
    void negativeIndexRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Level(-1));
    }

    @Test
    void zeroIndexAccepted() {
        assertDoesNotThrow(() -> new Level(0));
    }

    @Test
    void nextReturnsIncrementedLevel() {
        assertEquals(new Level(1), Level.L0.next());
    }

    @Test
    void l0ConstantHasIndexZero() {
        assertEquals(0, Level.L0.index());
    }
}
