package jlsm.cache;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for data-transformation concerns in StripedBlockCache — fidelity of the
 * {@code (sstableId, blockOffset) -> splitmix64Hash -> stripe} routing discriminant.
 */
class DataTransformationAdversarialTest {

    // Finding: F-R1.data_transformation.1.1
    // Bug: splitmix64Hash combines inputs as `sstableId * G + blockOffset` (linear in
    // both inputs). Because G = 0x9E3779B97F4A7C15L is odd (invertible mod 2^64),
    // any two pairs (s1,o1) != (s2,o2) satisfying
    // (s1 - s2) * G ≡ (o2 - o1) (mod 2^64)
    // collapse to the same combined input, and — since the three splitmix64 stages
    // are bijective — produce identical 64-bit hashes and the same stripe index.
    // Concrete collision: (0, 0) and (1, 0x61C8864680B583EBL) both reduce to
    // combined = 0 (mod 2^64)
    // before the finalizer, violating the card's "full 64-bit avalanche of the pair"
    // guarantee.
    // Correct behavior: two distinct (sstableId, blockOffset) pairs must produce distinct
    // splitmix64Hash outputs (specifically, these two chosen pairs).
    // Fix location: StripedBlockCache.splitmix64Hash — the input-combine step at line 139
    // needs a non-linear combination (e.g. pre-mix one input through the splitmix64
    // finalizer before the golden-ratio combine).
    // Regression watch: any algorithmic change will break the R11 pin test
    // `StripedBlockCacheTest.stripeIndexMatchesSplitmix64StaffordReference`.
    @Test
    void test_splitmix64Hash_algebraicPreImageCollision_producesDistinctHashes() throws Exception {
        // Access the private static splitmix64Hash(long, long) by reflection.
        Method m = StripedBlockCache.class.getDeclaredMethod("splitmix64Hash", long.class,
                long.class);
        m.setAccessible(true);

        // Attack pair derived from the finding:
        // s1=0, o1=0 => combined = 0*G + 0 = 0
        // s2=1, o2=0x61C8864680B583EBL => combined = G + 0x61C8864680B583EBL = 2^64 (mod 2^64) = 0
        // G = 0x9E3779B97F4A7C15L; 0x61C8864680B583EBL = -G mod 2^64 (both offsets are
        // non-negative, so this pair is reachable via the public API which asserts
        // blockOffset >= 0).
        long hashA = (long) m.invoke(null, 0L, 0L);
        long hashB = (long) m.invoke(null, 1L, 0x61C8864680B583EBL);

        assertNotEquals(hashA, hashB,
                "splitmix64Hash must avalanche the (sstableId, blockOffset) pair, not just "
                        + "the linearly combined 64-bit mixture. Pairs (0,0) and "
                        + "(1, 0x61C8864680B583EBL) collide because (s1-s2)*G ≡ o2-o1 mod 2^64 "
                        + "— the combine rule is linear and invertible.");
    }
}
