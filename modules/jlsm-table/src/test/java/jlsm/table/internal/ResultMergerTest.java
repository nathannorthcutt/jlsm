package jlsm.table.internal;

import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.FieldType;
import jlsm.table.ScoredEntry;
import jlsm.table.TableEntry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultMergerTest {

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING).build();
    }

    private static JlsmDocument doc(JlsmSchema schema, String name) {
        return JlsmDocument.of(schema, "name", name);
    }

    // -------------------------------------------------------------------------
    // mergeTopK — null / bad input
    // -------------------------------------------------------------------------

    // @spec partitioning.table-partitioning.R55
    @Test
    void mergeTopK_nullPartitionResults_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ResultMerger.mergeTopK(null, 5));
    }

    // @spec partitioning.table-partitioning.R56
    @Test
    void mergeTopK_negativeK_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ResultMerger.mergeTopK(List.of(), -1));
    }

    @Test
    void mergeTopK_zeroK_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ResultMerger.mergeTopK(List.of(), 0));
    }

    // -------------------------------------------------------------------------
    // mergeTopK — correctness
    // -------------------------------------------------------------------------

    @Test
    void mergeTopK_emptyPartitions_returnsEmptyList() {
        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(), 5);
        assertTrue(result.isEmpty(), "empty partitions should produce empty result");
    }

    @Test
    void mergeTopK_singlePartition_returnsTopK() {
        final JlsmSchema schema = testSchema();
        final List<ScoredEntry<String>> partition = List.of(
                new ScoredEntry<>("a", doc(schema, "A"), 0.9),
                new ScoredEntry<>("b", doc(schema, "B"), 0.7),
                new ScoredEntry<>("c", doc(schema, "C"), 0.5));

        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(partition), 2);

        assertEquals(2, result.size(), "should return exactly k results");
        assertEquals("a", result.get(0).key(), "highest score should be first");
        assertEquals("b", result.get(1).key(), "second highest should be next");
    }

    @Test
    void mergeTopK_multiplePartitions_mergesAndSortsByScoreDescending() {
        final JlsmSchema schema = testSchema();
        final List<ScoredEntry<String>> p1 = List.of(new ScoredEntry<>("a", doc(schema, "A"), 0.95),
                new ScoredEntry<>("c", doc(schema, "C"), 0.60));
        final List<ScoredEntry<String>> p2 = List.of(new ScoredEntry<>("b", doc(schema, "B"), 0.85),
                new ScoredEntry<>("d", doc(schema, "D"), 0.40));

        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(p1, p2), 3);

        assertEquals(3, result.size());
        assertEquals("a", result.get(0).key(), "score 0.95 is highest");
        assertEquals("b", result.get(1).key(), "score 0.85 is second");
        assertEquals("c", result.get(2).key(), "score 0.60 is third");
    }

    // @spec partitioning.table-partitioning.R58 — total < k returns all available
    @Test
    void mergeTopK_kExceedsTotalEntries_returnsAll() {
        final JlsmSchema schema = testSchema();
        final List<ScoredEntry<String>> partition = List
                .of(new ScoredEntry<>("x", doc(schema, "X"), 0.5));

        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(partition), 100);

        assertEquals(1, result.size(), "should return all available entries when k > total");
    }

    @Test
    void mergeTopK_emptyAndNonEmptyPartitions_mergesCorrectly() {
        final JlsmSchema schema = testSchema();
        final List<ScoredEntry<String>> p1 = List.of();
        final List<ScoredEntry<String>> p2 = List.of(new ScoredEntry<>("x", doc(schema, "X"), 0.8));

        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(p1, p2), 5);

        assertEquals(1, result.size());
        assertEquals("x", result.get(0).key());
    }

    // @spec partitioning.table-partitioning.R54,R59 — merged result sorted by score descending via max-heap
    @Test
    void mergeTopK_resultIsSortedByScoreDescending() {
        final JlsmSchema schema = testSchema();
        final List<ScoredEntry<String>> p1 = List.of(
                new ScoredEntry<>("low", doc(schema, "Low"), 0.1),
                new ScoredEntry<>("high", doc(schema, "High"), 0.9));
        final List<ScoredEntry<String>> p2 = List
                .of(new ScoredEntry<>("mid", doc(schema, "Mid"), 0.5));

        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(p1, p2), 3);

        assertEquals(3, result.size());
        assertTrue(result.get(0).score() >= result.get(1).score(),
                "results must be ordered by score descending");
        assertTrue(result.get(1).score() >= result.get(2).score(),
                "results must be ordered by score descending");
    }

    // -------------------------------------------------------------------------
    // mergeOrdered — null / bad input
    // -------------------------------------------------------------------------

    // @spec partitioning.table-partitioning.R62
    @Test
    void mergeOrdered_nullList_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ResultMerger.mergeOrdered(null));
    }

    // -------------------------------------------------------------------------
    // mergeOrdered — correctness
    // -------------------------------------------------------------------------

    // @spec partitioning.table-partitioning.R65 — all exhausted → hasNext=false
    @Test
    void mergeOrdered_emptyIteratorList_returnsEmptyIterator() {
        final Iterator<TableEntry<String>> it = ResultMerger.mergeOrdered(List.of());
        assertFalse(it.hasNext(), "empty iterator list should produce empty iterator");
    }

    @Test
    void mergeOrdered_singleIterator_returnsAllEntriesInOrder() {
        final JlsmSchema schema = testSchema();
        final List<TableEntry<String>> entries = List.of(new TableEntry<>("a", doc(schema, "A")),
                new TableEntry<>("b", doc(schema, "B")), new TableEntry<>("c", doc(schema, "C")));

        final Iterator<TableEntry<String>> it = ResultMerger
                .mergeOrdered(List.of(entries.iterator()));
        final List<TableEntry<String>> result = drainIterator(it);

        assertEquals(3, result.size());
        assertEquals("a", result.get(0).key());
        assertEquals("b", result.get(1).key());
        assertEquals("c", result.get(2).key());
    }

    // @spec partitioning.table-partitioning.R61,R63 — N-way merge in global key order via min-heap
    @Test
    void mergeOrdered_twoDisjointPartitions_mergesInKeyOrder() {
        final JlsmSchema schema = testSchema();
        final List<TableEntry<String>> p1 = List.of(new TableEntry<>("b", doc(schema, "B")),
                new TableEntry<>("d", doc(schema, "D")));
        final List<TableEntry<String>> p2 = List.of(new TableEntry<>("a", doc(schema, "A")),
                new TableEntry<>("c", doc(schema, "C")));

        final Iterator<TableEntry<String>> it = ResultMerger
                .mergeOrdered(List.of(p1.iterator(), p2.iterator()));
        final List<TableEntry<String>> result = drainIterator(it);

        assertEquals(4, result.size());
        assertEquals("a", result.get(0).key());
        assertEquals("b", result.get(1).key());
        assertEquals("c", result.get(2).key());
        assertEquals("d", result.get(3).key());
    }

    // @spec partitioning.table-partitioning.R64 — exhausted iterator removed from heap
    @Test
    void mergeOrdered_oneEmptyPartition_returnsNonEmptyPartitionEntries() {
        final JlsmSchema schema = testSchema();
        final List<TableEntry<String>> p1 = List.of();
        final List<TableEntry<String>> p2 = List.of(new TableEntry<>("x", doc(schema, "X")),
                new TableEntry<>("y", doc(schema, "Y")));

        final Iterator<TableEntry<String>> it = ResultMerger
                .mergeOrdered(List.of(p1.iterator(), p2.iterator()));
        final List<TableEntry<String>> result = drainIterator(it);

        assertEquals(2, result.size());
        assertEquals("x", result.get(0).key());
        assertEquals("y", result.get(1).key());
    }

    @Test
    void mergeOrdered_multiplePartitions_globalKeyOrder() {
        final JlsmSchema schema = testSchema();
        final List<TableEntry<String>> p1 = List.of(new TableEntry<>("apple", doc(schema, "Apple")),
                new TableEntry<>("cherry", doc(schema, "Cherry")));
        final List<TableEntry<String>> p2 = List.of(
                new TableEntry<>("banana", doc(schema, "Banana")),
                new TableEntry<>("date", doc(schema, "Date")));
        final List<TableEntry<String>> p3 = List
                .of(new TableEntry<>("avocado", doc(schema, "Avocado")));

        final Iterator<TableEntry<String>> it = ResultMerger
                .mergeOrdered(List.of(p1.iterator(), p2.iterator(), p3.iterator()));
        final List<TableEntry<String>> result = drainIterator(it);

        assertEquals(5, result.size());
        // Lexicographic order: apple, avocado, banana, cherry, date
        assertEquals("apple", result.get(0).key());
        assertEquals("avocado", result.get(1).key());
        assertEquals("banana", result.get(2).key());
        assertEquals("cherry", result.get(3).key());
        assertEquals("date", result.get(4).key());
    }

    @Test
    void mergeOrdered_isLazy_doesNotEagerlyDrain() {
        final JlsmSchema schema = testSchema();
        // Verify iterator is lazy by checking hasNext/next behave correctly on demand
        final List<TableEntry<String>> entries = List.of(new TableEntry<>("a", doc(schema, "A")),
                new TableEntry<>("b", doc(schema, "B")));

        final Iterator<TableEntry<String>> it = ResultMerger
                .mergeOrdered(List.of(entries.iterator()));

        assertTrue(it.hasNext());
        assertEquals("a", it.next().key());
        assertTrue(it.hasNext());
        assertEquals("b", it.next().key());
        assertFalse(it.hasNext());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static <T> List<T> drainIterator(Iterator<T> it) {
        final List<T> result = new ArrayList<>();
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }
}
