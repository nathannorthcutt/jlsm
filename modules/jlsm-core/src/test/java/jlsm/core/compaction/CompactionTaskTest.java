package jlsm.core.compaction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import org.junit.jupiter.api.Test;

class CompactionTaskTest {

    private static final Level L0 = Level.L0;
    private static final Level L1 = Level.L0.next();

    private static SSTableMetadata dummyMeta() {
        MemorySegment key = Arena.ofAuto().allocate(1);
        return new SSTableMetadata(
                1L,
                Path.of("/tmp/test.sst"),
                L0,
                key,
                key,
                SequenceNumber.ZERO,
                SequenceNumber.ZERO,
                0L,
                0L);
    }

    @Test
    void nullSourceSSTablesRejected() {
        assertThrows(NullPointerException.class, () -> new CompactionTask(null, L0, L1));
    }

    @Test
    void nullSourceLevelRejected() {
        assertThrows(NullPointerException.class, () -> new CompactionTask(List.of(dummyMeta()), null, L1));
    }

    @Test
    void nullTargetLevelRejected() {
        assertThrows(NullPointerException.class, () -> new CompactionTask(List.of(dummyMeta()), L0, null));
    }

    @Test
    void emptySourceSSTablesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CompactionTask(List.of(), L0, L1));
    }

    @Test
    void targetLevelLessThanSourceLevelRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionTask(List.of(dummyMeta()), L1, L0));
    }

    @Test
    void sameLevelSourceAndTargetAccepted() {
        assertDoesNotThrow(() -> new CompactionTask(List.of(dummyMeta()), L0, L0));
    }

    @Test
    void sourceListIsDefensivelyCopied() {
        var mutable = new java.util.ArrayList<SSTableMetadata>();
        mutable.add(dummyMeta());
        CompactionTask task = new CompactionTask(mutable, L0, L1);
        mutable.clear();
        assertEquals(1, task.sourceSSTables().size());
    }
}
