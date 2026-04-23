package jlsm.sstable.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * TDD tests for the v5 constants added to {@link SSTableFormat}.
 *
 * <p>
 * These tests drive the introduction of {@code MAGIC_V5} and {@code FOOTER_SIZE_V5} — two pure
 * value constants used across the v5 footer codec, VarInt encoder, and integrity checks.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R11
 * @spec sstable.end-to-end-integrity.R12
 * @spec sstable.end-to-end-integrity.R34
 */
class SSTableFormatV5ConstantsTest {

    @Test
    void magicV5ValueIsDefined() {
        assertEquals(0x4A4C534D53535405L, SSTableFormat.MAGIC_V5);
    }

    @Test
    void footerSizeV5Is112() {
        assertEquals(112, SSTableFormat.FOOTER_SIZE_V5);
    }
}
