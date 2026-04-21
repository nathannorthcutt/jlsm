package jlsm.core.compression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ZstdNativeBindings} tier detection and native availability.
 *
 * <p>
 * These tests verify the tier detection mechanism works regardless of whether native libzstd is
 * actually present in the test environment. The tier must be a valid enum value and detection must
 * be deterministic (cached at class-load time).
 */
// @spec compression.zstd-dictionary.R4,R4a,R5
class ZstdNativeBindingsTest {

    @Test
    void activeTierReturnsValidEnumValue() {
        ZstdNativeBindings.Tier tier = ZstdNativeBindings.activeTier();
        assertNotNull(tier, "activeTier() must not return null");
    }

    @Test
    void activeTierIsDeterministic() {
        ZstdNativeBindings.Tier first = ZstdNativeBindings.activeTier();
        ZstdNativeBindings.Tier second = ZstdNativeBindings.activeTier();
        assertSame(first, second, "activeTier() must return the same cached tier on every call");
    }

    @Test
    void isNativeAvailableMatchesTier() {
        boolean nativeAvailable = ZstdNativeBindings.isNativeAvailable();
        ZstdNativeBindings.Tier tier = ZstdNativeBindings.activeTier();
        assertEquals(tier == ZstdNativeBindings.Tier.NATIVE, nativeAvailable,
                "isNativeAvailable() must return true iff tier is NATIVE");
    }

    @Test
    void tierEnumHasThreeValues() {
        ZstdNativeBindings.Tier[] values = ZstdNativeBindings.Tier.values();
        assertEquals(3, values.length, "Tier enum must have exactly 3 values");
    }

    @Test
    void nativeTierMethodHandlesAccessibleWhenNativeAvailable() {
        if (!ZstdNativeBindings.isNativeAvailable()) {
            return; // skip when native unavailable
        }
        // If native is available, all handle accessors must return non-null
        assertNotNull(ZstdNativeBindings.compress2());
        assertNotNull(ZstdNativeBindings.decompress());
        assertNotNull(ZstdNativeBindings.compressBound());
        assertNotNull(ZstdNativeBindings.isError());
        assertNotNull(ZstdNativeBindings.getErrorName());
        assertNotNull(ZstdNativeBindings.createCCtx());
        assertNotNull(ZstdNativeBindings.freeCCtx());
        assertNotNull(ZstdNativeBindings.createDCtx());
        assertNotNull(ZstdNativeBindings.freeDCtx());
        assertNotNull(ZstdNativeBindings.createCDict());
        assertNotNull(ZstdNativeBindings.freeCDict());
        assertNotNull(ZstdNativeBindings.createDDict());
        assertNotNull(ZstdNativeBindings.freeDDict());
        assertNotNull(ZstdNativeBindings.compressUsingCDict());
        assertNotNull(ZstdNativeBindings.decompressUsingDDict());
        assertNotNull(ZstdNativeBindings.trainFromBuffer());
    }

    // @spec compression.zstd-codec.R1 — tier detection must select PURE_JAVA_DECOMPRESSOR when
    // native is
    // unavailable; DEFLATE_FALLBACK is reserved for cases where the pure-Java decoder itself
    // is not loadable, which cannot happen in this codebase (pure-Java class is always on the
    // classpath).
    @Test
    void tierIsPureJavaDecompressorWhenNativeUnavailable() {
        if (ZstdNativeBindings.isNativeAvailable()) {
            return; // skip — Tier 1 active; the fallback-selection path is not exercised
        }
        assertEquals(ZstdNativeBindings.Tier.PURE_JAVA_DECOMPRESSOR,
                ZstdNativeBindings.activeTier(),
                "when native is unavailable, tier must fall through to pure-Java decompressor, not Deflate");
    }

    @Test
    void methodHandlesThrowWhenNativeUnavailable() {
        if (ZstdNativeBindings.isNativeAvailable()) {
            return; // skip when native available
        }
        assertThrows(IllegalStateException.class, ZstdNativeBindings::compress2);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::decompress);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::compressBound);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::isError);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::getErrorName);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::createCCtx);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::freeCCtx);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::createDCtx);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::freeDCtx);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::createCDict);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::freeCDict);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::createDDict);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::freeDDict);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::compressUsingCDict);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::decompressUsingDDict);
        assertThrows(IllegalStateException.class, ZstdNativeBindings::trainFromBuffer);
    }
}
