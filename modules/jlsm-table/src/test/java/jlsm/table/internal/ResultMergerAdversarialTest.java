package jlsm.table.internal;

import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.ScoredEntry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link ResultMerger}.
 */
class ResultMergerAdversarialTest {

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING).build();
    }

    private static JlsmDocument doc(String name) {
        return JlsmDocument.of(testSchema(), "name", name);
    }

    // --- RM-2: mergeTopK with NaN scores ---

    /**
     * Finding RM-2: NaN scores corrupt PriorityQueue ordering — NaN-scored entries
     * are incorrectly ranked above real results.
     * ScoredEntry allows NaN scores; mergeTopK should either reject them or handle correctly.
     */
    @Test
    void mergeTopK_nanScores_doNotCorruptOrdering() {
        final List<ScoredEntry<String>> partition = List.of(
                new ScoredEntry<>("high", doc("High"), 0.9),
                new ScoredEntry<>("nan", doc("NaN"), Double.NaN),
                new ScoredEntry<>("low", doc("Low"), 0.1));

        final List<ScoredEntry<String>> result = ResultMerger.mergeTopK(List.of(partition), 3);

        // NaN should NOT appear above valid scores. Either:
        // (a) NaN entries are filtered out, or
        // (b) NaN entries are ranked last (below all real scores)
        // Currently, Double.compare treats NaN > everything, so reversed comparator puts NaN first.
        // That's incorrect behavior — high (0.9) should be first.
        assertEquals("high", result.get(0).key(),
                "highest real score should be ranked first, not NaN");
        assertFalse(Double.isNaN(result.get(0).score()),
                "first result must not have NaN score");
    }

    // --- RM-3: mergeTopK with null inner list ---

    /**
     * Finding RM-3: Null element in partitionResults only checked by assert, not runtime validation.
     * Should throw a descriptive exception, not raw NPE from addAll(null).
     */
    @Test
    void mergeTopK_nullInnerList_throwsDescriptiveException() {
        final List<List<ScoredEntry<String>>> input = new ArrayList<>();
        input.add(List.of(new ScoredEntry<>("a", doc("A"), 0.5)));
        input.add(null);

        // Should throw NPE or IAE with a message about null partition result
        final var ex = assertThrows(NullPointerException.class,
                () -> ResultMerger.mergeTopK(input, 5));
        assertNotNull(ex.getMessage(),
                "exception should have a descriptive message, not null");
        assertTrue(ex.getMessage().toLowerCase().contains("partition")
                        || ex.getMessage().toLowerCase().contains("null"),
                "exception message should indicate which partition result was null");
    }
}
