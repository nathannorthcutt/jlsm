package jlsm.core.indexing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests — Round 4. Targets F20: SearchResult record missing defensive validation.
 */
class VectorIndexAdversarialTest {

    // =======================================================================
    // F20: SearchResult record should reject null docId and NaN score
    // =======================================================================

    @Test
    void searchResult_nullDocId_throwsNPE() {
        // F20: SearchResult is a public record. Per project rules (code-quality.md),
        // all public constructors must validate inputs eagerly. A null docId is never
        // a valid search result.
        assertThrows(NullPointerException.class, () -> new VectorIndex.SearchResult<>(null, 1.0f),
                "SearchResult should reject null docId");
    }

    @Test
    void searchResult_nanScore_throwsIAE() {
        // F20: NaN scores corrupt Float.compare-based ordering (KB pattern
        // nan-score-ordering-corruption). SearchResult should reject NaN at
        // construction as a defense-in-depth measure.
        assertThrows(IllegalArgumentException.class,
                () -> new VectorIndex.SearchResult<>("doc1", Float.NaN),
                "SearchResult should reject NaN score");
    }

    @Test
    void searchResult_validInputs_constructsSuccessfully() {
        // F20 control: Valid inputs should be accepted without exception.
        VectorIndex.SearchResult<String> result = new VectorIndex.SearchResult<>("doc1", 0.95f);
        assertEquals("doc1", result.docId());
        assertEquals(0.95f, result.score(), 0.0f);
    }

    @Test
    void searchResult_infiniteScore_accepted() {
        // F20 control: Infinity scores have valid Float.compare ordering
        // (positive infinity > all finite values), so they should be accepted.
        assertDoesNotThrow(() -> new VectorIndex.SearchResult<>("doc1", Float.POSITIVE_INFINITY),
                "SearchResult should accept infinite scores (valid ordering)");
        assertDoesNotThrow(() -> new VectorIndex.SearchResult<>("doc1", Float.NEGATIVE_INFINITY),
                "SearchResult should accept negative infinite scores (valid ordering)");
    }

    @Test
    void searchResult_zeroAndNegativeScore_accepted() {
        // F20 control: Zero and negative scores are valid (e.g., Euclidean returns ≤ 0).
        assertDoesNotThrow(() -> new VectorIndex.SearchResult<>("doc1", 0.0f));
        assertDoesNotThrow(() -> new VectorIndex.SearchResult<>("doc1", -1.0f));
    }
}
