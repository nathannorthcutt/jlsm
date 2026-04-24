package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;

/** Tests for {@link UnwrapResult} — validates R80/R66 KMS unwrap-result lifetime ownership. */
class UnwrapResultTest {

    @Test
    void constructor_rejectsNullPlaintext() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(NullPointerException.class, () -> new UnwrapResult(null, arena));
        }
    }

    @Test
    void constructor_rejectsNullArena() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment seg = arena.allocate(16);
            assertThrows(NullPointerException.class, () -> new UnwrapResult(seg, null));
        }
    }

    @Test
    void accessors_roundTripComponents() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment seg = arena.allocate(16);
            final UnwrapResult r = new UnwrapResult(seg, arena);
            assertEquals(seg, r.plaintext());
            assertEquals(arena, r.owner());
        }
    }
}
