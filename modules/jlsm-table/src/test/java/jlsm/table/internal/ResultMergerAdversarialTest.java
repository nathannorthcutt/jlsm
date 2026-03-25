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
     * Finding RM-2 (updated after SE-1 fix): NaN scores are now rejected at ScoredEntry
     * construction. This test verifies the defense-in-depth: NaN cannot reach mergeTopK because
     * ScoredEntry rejects it at the source.
     */
    @Test
    void scoredEntry_rejectsNaN_preventingMergeCorruption() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredEntry<>("nan", doc("NaN"), Double.NaN),
                "ScoredEntry must reject NaN — prevents NaN from reaching mergeTopK");
    }

    // --- RM-3: mergeTopK with null inner list ---

    /**
     * Finding RM-3: Null element in partitionResults only checked by assert, not runtime
     * validation. Should throw a descriptive exception, not raw NPE from addAll(null).
     */
    @Test
    void mergeTopK_nullInnerList_throwsDescriptiveException() {
        final List<List<ScoredEntry<String>>> input = new ArrayList<>();
        input.add(List.of(new ScoredEntry<>("a", doc("A"), 0.5)));
        input.add(null);

        // Should throw NPE or IAE with a message about null partition result
        final var ex = assertThrows(NullPointerException.class,
                () -> ResultMerger.mergeTopK(input, 5));
        assertNotNull(ex.getMessage(), "exception should have a descriptive message, not null");
        assertTrue(
                ex.getMessage().toLowerCase().contains("partition")
                        || ex.getMessage().toLowerCase().contains("null"),
                "exception message should indicate which partition result was null");
    }
}
