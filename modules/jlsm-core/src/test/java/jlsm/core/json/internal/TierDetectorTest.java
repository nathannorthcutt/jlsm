package jlsm.core.json.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TierDetector} — tier selection at class-load time.
 */
class TierDetectorTest {

    @Test
    void tierIsOneOfThreeValidValues() {
        int tier = TierDetector.TIER;
        assertTrue(tier >= 1 && tier <= 3, "TIER must be 1, 2, or 3 but was " + tier);
    }

    @Test
    void detectTierDoesNotThrow() {
        // detectTier must never throw — it catches all exceptions internally
        assertDoesNotThrow(TierDetector::detectTier);
    }

    @Test
    void detectTierReturnsConsistentResult() {
        // Multiple calls should return the same tier
        int tier1 = TierDetector.detectTier();
        int tier2 = TierDetector.detectTier();
        assertEquals(tier1, tier2, "detectTier must return consistent results");
    }

    @Test
    void tierConstantsAreDistinct() {
        assertNotEquals(TierDetector.TIER_1_PANAMA, TierDetector.TIER_2_VECTOR);
        assertNotEquals(TierDetector.TIER_2_VECTOR, TierDetector.TIER_3_SCALAR);
        assertNotEquals(TierDetector.TIER_1_PANAMA, TierDetector.TIER_3_SCALAR);
    }
}
