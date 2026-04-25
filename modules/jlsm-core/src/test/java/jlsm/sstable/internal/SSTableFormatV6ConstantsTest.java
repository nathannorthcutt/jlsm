package jlsm.sstable.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies the v6 SSTable magic constant — distinct from MAGIC_V5 (R1) and equal to the
 * spec-mandated value {@code 0x4A4C534D53535406L}. The high bytes spell "JLSMSST" with the trailing
 * version byte {@code 0x06}.
 *
 * @spec sstable.footer-encryption-scope.R1
 */
final class SSTableFormatV6ConstantsTest {

    @Test
    void magicV6HasSpecMandatedValue() {
        assertEquals(0x4A4C534D53535406L, SSTableFormat.MAGIC_V6);
    }

    @Test
    void magicV6IsDistinctFromMagicV5() {
        // covers: R1 — v6 magic must be a distinct value from v5 magic
        assertNotEquals(SSTableFormat.MAGIC_V5, SSTableFormat.MAGIC_V6);
    }
}
