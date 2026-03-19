package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EncryptionSpec} sealed interface: all 5 variants, capability methods, factory
 * methods, and the NONE constant.
 */
class EncryptionSpecTest {

    // ── NONE constant ────────────────────────────────────────────────────

    @Test
    void none_constant_isNoneInstance() {
        assertInstanceOf(EncryptionSpec.None.class, EncryptionSpec.NONE);
    }

    @Test
    void none_factory_returnsSameConstant() {
        assertSame(EncryptionSpec.NONE, EncryptionSpec.none());
    }

    // ── None variant capabilities ────────────────────────────────────────

    @Test
    void none_supportsAllCapabilities() {
        final EncryptionSpec spec = EncryptionSpec.NONE;
        assertTrue(spec.supportsEquality());
        assertTrue(spec.supportsRange());
        assertTrue(spec.supportsKeywordSearch());
        assertTrue(spec.supportsPhraseSearch());
        assertTrue(spec.supportsSseSearch());
        assertTrue(spec.supportsANN());
    }

    // ── Deterministic variant ────────────────────────────────────────────

    @Test
    void deterministic_factory_returnsDeterministicInstance() {
        assertInstanceOf(EncryptionSpec.Deterministic.class, EncryptionSpec.deterministic());
    }

    @Test
    void deterministic_supportsEqualityAndKeyword() {
        final EncryptionSpec spec = EncryptionSpec.deterministic();
        assertTrue(spec.supportsEquality());
        assertTrue(spec.supportsKeywordSearch());
    }

    @Test
    void deterministic_doesNotSupportRangeOrPhraseOrSseOrAnn() {
        final EncryptionSpec spec = EncryptionSpec.deterministic();
        assertFalse(spec.supportsRange());
        assertFalse(spec.supportsPhraseSearch());
        assertFalse(spec.supportsSseSearch());
        assertFalse(spec.supportsANN());
    }

    // ── OrderPreserving variant ──────────────────────────────────────────

    @Test
    void orderPreserving_factory_returnsOrderPreservingInstance() {
        assertInstanceOf(EncryptionSpec.OrderPreserving.class, EncryptionSpec.orderPreserving());
    }

    @Test
    void orderPreserving_supportsEqualityAndRange() {
        final EncryptionSpec spec = EncryptionSpec.orderPreserving();
        assertTrue(spec.supportsEquality());
        assertTrue(spec.supportsRange());
    }

    @Test
    void orderPreserving_doesNotSupportKeywordOrPhraseOrSseOrAnn() {
        final EncryptionSpec spec = EncryptionSpec.orderPreserving();
        assertFalse(spec.supportsKeywordSearch());
        assertFalse(spec.supportsPhraseSearch());
        assertFalse(spec.supportsSseSearch());
        assertFalse(spec.supportsANN());
    }

    // ── DistancePreserving variant ───────────────────────────────────────

    @Test
    void distancePreserving_factory_returnsDistancePreservingInstance() {
        assertInstanceOf(EncryptionSpec.DistancePreserving.class,
                EncryptionSpec.distancePreserving());
    }

    @Test
    void distancePreserving_supportsAnnOnly() {
        final EncryptionSpec spec = EncryptionSpec.distancePreserving();
        assertTrue(spec.supportsANN());
        assertFalse(spec.supportsEquality());
        assertFalse(spec.supportsRange());
        assertFalse(spec.supportsKeywordSearch());
        assertFalse(spec.supportsPhraseSearch());
        assertFalse(spec.supportsSseSearch());
    }

    // ── Opaque variant ───────────────────────────────────────────────────

    @Test
    void opaque_factory_returnsOpaqueInstance() {
        assertInstanceOf(EncryptionSpec.Opaque.class, EncryptionSpec.opaque());
    }

    @Test
    void opaque_supportsNothing() {
        final EncryptionSpec spec = EncryptionSpec.opaque();
        assertFalse(spec.supportsEquality());
        assertFalse(spec.supportsRange());
        assertFalse(spec.supportsKeywordSearch());
        assertFalse(spec.supportsPhraseSearch());
        assertFalse(spec.supportsSseSearch());
        assertFalse(spec.supportsANN());
    }

    // ── Sealed hierarchy ─────────────────────────────────────────────────

    @Test
    void sealedHierarchy_exhaustiveSwitch() {
        // Verify all 5 variants are recognized by switch
        final EncryptionSpec[] all = { new EncryptionSpec.None(),
                new EncryptionSpec.Deterministic(), new EncryptionSpec.OrderPreserving(),
                new EncryptionSpec.DistancePreserving(), new EncryptionSpec.Opaque() };
        for (final EncryptionSpec spec : all) {
            final String label = switch (spec) {
                case EncryptionSpec.None _ -> "none";
                case EncryptionSpec.Deterministic _ -> "deterministic";
                case EncryptionSpec.OrderPreserving _ -> "orderPreserving";
                case EncryptionSpec.DistancePreserving _ -> "distancePreserving";
                case EncryptionSpec.Opaque _ -> "opaque";
            };
            assertNotNull(label);
        }
    }
}
