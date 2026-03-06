package jlsm.core.model;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class EntryTest {

    private static final MemorySegment KEY = Arena.ofAuto().allocate(1);
    private static final MemorySegment VALUE = Arena.ofAuto().allocate(1);
    private static final SequenceNumber SEQ = SequenceNumber.ZERO;

    // Put validation

    @Test
    void putRejectsNullKey() {
        assertThrows(NullPointerException.class, () -> new Entry.Put(null, VALUE, SEQ));
    }

    @Test
    void putRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new Entry.Put(KEY, null, SEQ));
    }

    @Test
    void putRejectsNullSequenceNumber() {
        assertThrows(NullPointerException.class, () -> new Entry.Put(KEY, VALUE, null));
    }

    @Test
    void putAcceptsValidArguments() {
        assertDoesNotThrow(() -> new Entry.Put(KEY, VALUE, SEQ));
    }

    // Delete validation

    @Test
    void deleteRejectsNullKey() {
        assertThrows(NullPointerException.class, () -> new Entry.Delete(null, SEQ));
    }

    @Test
    void deleteRejectsNullSequenceNumber() {
        assertThrows(NullPointerException.class, () -> new Entry.Delete(KEY, null));
    }

    @Test
    void deleteAcceptsValidArguments() {
        assertDoesNotThrow(() -> new Entry.Delete(KEY, SEQ));
    }
}
