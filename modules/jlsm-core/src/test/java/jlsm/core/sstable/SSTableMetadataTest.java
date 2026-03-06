package jlsm.core.sstable;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import org.junit.jupiter.api.Test;

class SSTableMetadataTest {

    private static final Path PATH = Path.of("/tmp/test.sst");
    private static final Level LEVEL = Level.L0;
    private static final MemorySegment KEY = Arena.ofAuto().allocate(1);
    private static final SequenceNumber SEQ = SequenceNumber.ZERO;

    private SSTableMetadata valid() {
        return new SSTableMetadata(1L, PATH, LEVEL, KEY, KEY, SEQ, SEQ, 0L, 0L);
    }

    @Test
    void validArgumentsAccepted() {
        assertDoesNotThrow(this::valid);
    }

    @Test
    void nullPathRejected() {
        assertThrows(NullPointerException.class,
                () -> new SSTableMetadata(1L, null, LEVEL, KEY, KEY, SEQ, SEQ, 0L, 0L));
    }

    @Test
    void nullLevelRejected() {
        assertThrows(NullPointerException.class,
                () -> new SSTableMetadata(1L, PATH, null, KEY, KEY, SEQ, SEQ, 0L, 0L));
    }

    @Test
    void nullSmallestKeyRejected() {
        assertThrows(NullPointerException.class,
                () -> new SSTableMetadata(1L, PATH, LEVEL, null, KEY, SEQ, SEQ, 0L, 0L));
    }

    @Test
    void nullLargestKeyRejected() {
        assertThrows(NullPointerException.class,
                () -> new SSTableMetadata(1L, PATH, LEVEL, KEY, null, SEQ, SEQ, 0L, 0L));
    }

    @Test
    void nullMinSequenceRejected() {
        assertThrows(NullPointerException.class,
                () -> new SSTableMetadata(1L, PATH, LEVEL, KEY, KEY, null, SEQ, 0L, 0L));
    }

    @Test
    void nullMaxSequenceRejected() {
        assertThrows(NullPointerException.class,
                () -> new SSTableMetadata(1L, PATH, LEVEL, KEY, KEY, SEQ, null, 0L, 0L));
    }

    @Test
    void negativeSizeBytesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SSTableMetadata(1L, PATH, LEVEL, KEY, KEY, SEQ, SEQ, -1L, 0L));
    }

    @Test
    void negativeEntryCountRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SSTableMetadata(1L, PATH, LEVEL, KEY, KEY, SEQ, SEQ, 0L, -1L));
    }
}
