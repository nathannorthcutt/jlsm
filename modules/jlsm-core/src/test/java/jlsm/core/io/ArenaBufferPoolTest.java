package jlsm.core.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArenaBufferPoolTest {

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsZeroPoolSize() {
        assertThrows(IllegalArgumentException.class, () -> ArenaBufferPool.builder().poolSize(0)
                .bufferSize(64).acquireTimeoutMillis(1000).build());
    }

    @Test
    void rejectsNegativePoolSize() {
        assertThrows(IllegalArgumentException.class, () -> ArenaBufferPool.builder().poolSize(-1)
                .bufferSize(64).acquireTimeoutMillis(1000).build());
    }

    @Test
    void rejectsZeroBufferSize() {
        assertThrows(IllegalArgumentException.class, () -> ArenaBufferPool.builder().poolSize(2)
                .bufferSize(0).acquireTimeoutMillis(1000).build());
    }

    @Test
    void rejectsNegativeBufferSize() {
        assertThrows(IllegalArgumentException.class, () -> ArenaBufferPool.builder().poolSize(2)
                .bufferSize(-1).acquireTimeoutMillis(1000).build());
    }

    @Test
    void rejectsZeroTimeout() {
        assertThrows(IllegalArgumentException.class, () -> ArenaBufferPool.builder().poolSize(2)
                .bufferSize(64).acquireTimeoutMillis(0).build());
    }

    @Test
    void rejectsNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> ArenaBufferPool.builder().poolSize(2)
                .bufferSize(64).acquireTimeoutMillis(-1).build());
    }

    // -------------------------------------------------------------------------
    // Acquire / release
    // -------------------------------------------------------------------------

    @Test
    void acquireReturnsSegmentOfDeclaredSize() throws IOException {
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(128)
                .acquireTimeoutMillis(1000).build()) {
            MemorySegment seg = pool.acquire();
            assertEquals(128, seg.byteSize());
            pool.release(seg);
        }
    }

    @Test
    void exhaustedPoolThrowsIOException() {
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                .acquireTimeoutMillis(10).build()) {
            MemorySegment first = pool.acquire();
            // Pool is now empty; second acquire should time out
            assertThrows(IOException.class, pool::acquire);
            pool.release(first);
        } catch (IOException e) {
            fail("unexpected IOException outside pool exhaustion: " + e.getMessage());
        }
    }

    @Test
    void releaseAndReacquireReturnsUsableSegment() throws IOException {
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                .acquireTimeoutMillis(1000).build()) {
            MemorySegment seg = pool.acquire();
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 42);
            pool.release(seg);

            MemorySegment seg2 = pool.acquire();
            assertNotNull(seg2);
            assertEquals(64, seg2.byteSize());
            pool.release(seg2);
        }
    }

    @Test
    void acquireAllPoolSizeBuffersSimultaneously() throws IOException {
        int poolSize = 4;
        try (var pool = ArenaBufferPool.builder().poolSize(poolSize).bufferSize(64)
                .acquireTimeoutMillis(1000).build()) {
            List<MemorySegment> acquired = new ArrayList<>();
            for (int i = 0; i < poolSize; i++) {
                acquired.add(pool.acquire());
            }
            assertEquals(poolSize, acquired.size());
            for (MemorySegment seg : acquired) {
                pool.release(seg);
            }
        }
    }

    @Test
    void releaseNullThrowsNPE() {
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                .acquireTimeoutMillis(1000).build()) {
            assertThrows(NullPointerException.class, () -> pool.release(null));
        }
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    @Test
    void closeInvalidatesArena() throws IOException {
        ArenaBufferPool pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                .acquireTimeoutMillis(1000).build();
        MemorySegment seg = pool.acquire();
        pool.release(seg);
        pool.close();
        // After close, the arena is invalid; accessing the segment should fail
        assertThrows(Exception.class,
                () -> seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 1));
    }

    @Test
    void closeIsIdempotent() throws IOException {
        var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64).acquireTimeoutMillis(1000)
                .build();
        pool.close();
        // Should not throw
        assertDoesNotThrow(pool::close);
    }
}
