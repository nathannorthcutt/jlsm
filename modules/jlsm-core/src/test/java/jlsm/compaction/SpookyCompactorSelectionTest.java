package jlsm.compaction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import jlsm.core.compaction.CompactionTask;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpookyCompactorSelectionTest {

    private SpookyCompactor compactor;

    @BeforeEach
    void setUp() {
        compactor = SpookyCompactor.builder().idSupplier(() -> 1L)
                .pathFn((id, level) -> Path.of("/tmp/sst-" + id + ".sst")).build();
    }

    private static MemorySegment key(String s) {
        return MemorySegment.ofArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static SSTableMetadata meta(String smallest, String largest, long sizeBytes) {
        return meta(smallest, largest, sizeBytes, Level.L0);
    }

    private static SSTableMetadata meta(String smallest, String largest, long sizeBytes,
            Level level) {
        return new SSTableMetadata(0L, Path.of("/tmp/fake.sst"), level, key(smallest), key(largest),
                SequenceNumber.ZERO, SequenceNumber.ZERO, sizeBytes, 1L);
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    @Test
    void nullInputRejected() {
        assertThrows(NullPointerException.class, () -> compactor.selectCompaction(null));
    }

    // -------------------------------------------------------------------------
    // All-empty levels → no compaction
    // -------------------------------------------------------------------------

    @Test
    void allEmptyLevelsReturnsEmpty() {
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(List.of(), List.of()));
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyLevelListReturnsEmpty() {
        Optional<CompactionTask> result = compactor.selectCompaction(List.of());
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Bootstrap: only L0 populated
    // -------------------------------------------------------------------------

    @Test
    void l0OnlyBootstrapTask() {
        List<SSTableMetadata> l0 = List.of(meta("a", "c", 100), meta("d", "f", 100));
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(l0));
        assertTrue(result.isPresent());
        CompactionTask task = result.get();
        assertEquals(2, task.sourceSSTables().size());
        assertEquals(Level.L0, task.sourceLevel());
        assertEquals(new Level(1), task.targetLevel());
    }

    @Test
    void l0OnlyWithEmptyL1BootstrapTask() {
        List<SSTableMetadata> l0 = List.of(meta("a", "z", 500));
        List<SSTableMetadata> l1 = List.of();
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(l0, l1));
        assertTrue(result.isPresent());
        CompactionTask task = result.get();
        assertEquals(Level.L0, task.sourceLevel());
        assertEquals(new Level(1), task.targetLevel());
    }

    // -------------------------------------------------------------------------
    // L0 + L1: no overlap → no compaction
    // -------------------------------------------------------------------------

    @Test
    void l1FilesNoOverlapWithUpperLevelsReturnsEmpty() {
        List<SSTableMetadata> l0 = List.of(meta("a", "b", 100, Level.L0));
        List<SSTableMetadata> l1 = List.of(meta("d", "f", 200, new Level(1)),
                meta("g", "h", 200, new Level(1)));
        // L1 is bottom, no upper-level files overlap either L1 file
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(l0, l1));
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // L0 + L1 with overlap → highest-score group
    // -------------------------------------------------------------------------

    @Test
    void l0OverlappingL1ReturnsBestGroup() {
        List<SSTableMetadata> l0 = List.of(meta("c", "e", 300, Level.L0)); // overlaps L1 file [b,f]
        List<SSTableMetadata> l1 = List.of(meta("a", "b", 50, new Level(1)), // no overlap with l0
                meta("b", "f", 100, new Level(1)), // overlaps l0
                meta("g", "z", 50, new Level(1))); // no overlap with l0
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(l0, l1));
        assertTrue(result.isPresent());
        CompactionTask task = result.get();
        // Group contains L1 file [b,f] + overlapping L0 file [c,e]
        assertEquals(2, task.sourceSSTables().size());
        assertEquals(Level.L0, task.sourceLevel());
        assertEquals(new Level(1), task.targetLevel());
    }

    // -------------------------------------------------------------------------
    // Three-level scenario
    // -------------------------------------------------------------------------

    @Test
    void threeLevelCorrectGroupAndSourceLevel() {
        List<SSTableMetadata> l0 = List.of(meta("b", "d", 400, Level.L0));
        List<SSTableMetadata> l1 = List.of(meta("a", "e", 200, new Level(1)));
        List<SSTableMetadata> l2 = List.of(meta("a", "z", 100, new Level(2))); // bottom file
        // Bottom is L2. File [a,z] overlaps L1 file [a,e] and L0 file [b,d].
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(l0, l1, l2));
        assertTrue(result.isPresent());
        CompactionTask task = result.get();
        assertEquals(3, task.sourceSSTables().size());
        assertEquals(Level.L0, task.sourceLevel());
        assertEquals(new Level(2), task.targetLevel());
    }

    // -------------------------------------------------------------------------
    // No upper files overlap any bottom file → empty
    // -------------------------------------------------------------------------

    @Test
    void noUpperFilesOverlapAnyBottomFileReturnsEmpty() {
        List<SSTableMetadata> l0 = List.of(meta("x", "z", 500, Level.L0));
        List<SSTableMetadata> l1 = List.of(meta("a", "c", 100, new Level(1)));
        // L1 is bottom; l0 file [x,z] does not overlap L1 file [a,c]
        Optional<CompactionTask> result = compactor.selectCompaction(List.of(l0, l1));
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void builderRequiresIdSupplier() {
        assertThrows(IllegalStateException.class,
                () -> SpookyCompactor.builder().pathFn((id, level) -> Path.of("/tmp/x")).build());
    }

    @Test
    void builderRequiresPathFn() {
        assertThrows(IllegalStateException.class,
                () -> SpookyCompactor.builder().idSupplier(() -> 1L).build());
    }
}
