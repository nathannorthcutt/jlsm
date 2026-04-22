package jlsm.core.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

    // -------------------------------------------------------------------------
    // bufferSize() accessor — sstable.pool-aware-block-size R1, R2
    // isClosed() accessor — sstable.pool-aware-block-size R0
    // Structural: class-final R0a, backing-field-final R1a
    // -------------------------------------------------------------------------

    // @spec sstable.pool-aware-block-size.R1
    @Test
    void bufferSize_returnsConfiguredValue() {
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(8192)
                .acquireTimeoutMillis(1000).build()) {
            assertEquals(8192L, pool.bufferSize());
        }
    }

    // @spec sstable.pool-aware-block-size.R1
    @Test
    void bufferSize_matchesBuilderInput_forVariousSizes() {
        long[] sizes = { 1024L, 4096L, 8192L, 1L << 20 }; // 1 KiB, 4 KiB, 8 KiB, 1 MiB
        for (long size : sizes) {
            try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(size)
                    .acquireTimeoutMillis(1000).build()) {
                assertEquals(size, pool.bufferSize(),
                        "bufferSize() must return the configured value for size=" + size);
            }
        }
    }

    // @spec sstable.pool-aware-block-size.R0
    @Test
    void isClosed_false_onNewPool() {
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64)
                .acquireTimeoutMillis(1000).build()) {
            assertFalse(pool.isClosed(), "fresh pool must report isClosed() == false");
        }
    }

    // @spec sstable.pool-aware-block-size.R0
    @Test
    void isClosed_true_afterClose() {
        var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64).acquireTimeoutMillis(1000)
                .build();
        pool.close();
        assertTrue(pool.isClosed(), "after close(), isClosed() must return true");
    }

    // @spec sstable.pool-aware-block-size.R0
    @Test
    void isClosed_true_afterRepeatClose() {
        var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(64).acquireTimeoutMillis(1000)
                .build();
        pool.close();
        pool.close();
        assertTrue(pool.isClosed(), "isClosed() must remain true after idempotent repeat close()");
    }

    // @spec sstable.pool-aware-block-size.R2
    @Test
    void bufferSize_returnsConfiguredValue_afterClose() {
        var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(16384)
                .acquireTimeoutMillis(1000).build();
        pool.close();
        assertEquals(16384L, pool.bufferSize(),
                "R2: bufferSize() must return configured value after close()");
    }

    // @spec sstable.pool-aware-block-size.R2
    @Test
    void bufferSize_doesNotThrow_afterClose() {
        var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(4096).acquireTimeoutMillis(1000)
                .build();
        pool.close();
        assertDoesNotThrow(pool::bufferSize,
                "R2: bufferSize() must never throw regardless of close() state");
    }

    // @spec sstable.pool-aware-block-size.R0a — class stays public final
    @Test
    void arenaBufferPool_classIsFinal() {
        assertTrue(Modifier.isFinal(ArenaBufferPool.class.getModifiers()),
                "R0a: ArenaBufferPool class must remain public final to prevent isClosed() spoofing");
    }

    // @spec sstable.pool-aware-block-size.R1a — backing field for bufferSize() must be final
    @Test
    void bufferSize_backingField_isFinal() throws ReflectiveOperationException {
        Field[] fields = ArenaBufferPool.class.getDeclaredFields();
        Field match = null;
        for (Field f : fields) {
            if (f.getType() == long.class && "bufferSize".equals(f.getName())) {
                match = f;
                break;
            }
        }
        assertNotNull(match,
                "R1a: ArenaBufferPool must have a long field named 'bufferSize' backing bufferSize()");
        assertTrue(Modifier.isFinal(match.getModifiers()),
                "R1a: the long bufferSize field must be declared final (no volatile/synchronized)");
        assertFalse(Modifier.isVolatile(match.getModifiers()),
                "R1a: volatile is explicitly forbidden as a safe-publication mechanism");
    }

    // Defensive (Lens B: int-backed-long-api) — verifies bufferSize() preserves long semantics
    // by constructing a pool at MAX_BLOCK_SIZE (32 MiB) — a value that a naive int-backed
    // implementation could truncate on the accessor. 32 MiB < Integer.MAX_VALUE so the ctor
    // succeeds.
    @Test
    void bufferSize_preservesLongSemantics_atLargeValues() {
        long largeButAllocatable = 1L << 25; // 32 MiB = SSTableFormat.MAX_BLOCK_SIZE
        try (var pool = ArenaBufferPool.builder().poolSize(1).bufferSize(largeButAllocatable)
                .acquireTimeoutMillis(1000).build()) {
            assertEquals(largeButAllocatable, pool.bufferSize(),
                    "bufferSize() must return the long value without narrowing/truncation");
        }
    }
}
