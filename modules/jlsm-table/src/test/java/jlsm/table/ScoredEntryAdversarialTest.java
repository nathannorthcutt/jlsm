package jlsm.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link ScoredEntry}.
 *
 * <p>
 * Targets finding SE-1 (NaN score construction) from the table-partitioning adversarial audit round
 * 2.
 */
class ScoredEntryAdversarialTest {

    private JlsmDocument minimalDoc() {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
        return JlsmDocument.of(schema, "name", "value");
    }

    // --- SE-1: NAN-SCORE-CONSTRUCTION ---

    /**
     * Finding SE-1: ScoredEntry allows Double.NaN at construction. The contract states "higher =
     * more relevant" which is meaningless for NaN. NaN should be rejected at construction to
     * prevent undefined ordering in any downstream consumer.
     */
    // @spec partitioning.table-partitioning.R25 — reject NaN score
    @Test
    void constructor_nanScore_throwsIllegalArgumentException() {
        var doc = minimalDoc();
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredEntry<>("key", doc, Double.NaN),
                "ScoredEntry should reject NaN score — ordering is undefined for NaN");
    }

    /**
     * SE-1: Positive and negative infinity should be accepted — they have well-defined ordering
     * semantics.
     */
    @Test
    void constructor_infinityScores_areAccepted() {
        var doc = minimalDoc();
        assertDoesNotThrow(() -> new ScoredEntry<>("key", doc, Double.POSITIVE_INFINITY));
        assertDoesNotThrow(() -> new ScoredEntry<>("key", doc, Double.NEGATIVE_INFINITY));
    }
}
