package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * TDD tests for {@link CorruptSectionException}.
 *
 * <p>
 * All tests are expected to fail because the stub constructor throws
 * {@link UnsupportedOperationException}. Once implemented, these tests define the contract: the
 * exception must store section name, expected checksum, and actual checksum, render them as
 * {@code 0x%08X} hex, and expose them via accessors.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R26
 * @spec sstable.end-to-end-integrity.R29
 * @spec sstable.end-to-end-integrity.R31
 * @spec sstable.end-to-end-integrity.R32
 * @spec sstable.end-to-end-integrity.R33
 * @spec sstable.end-to-end-integrity.R42
 */
class CorruptSectionExceptionTest {

    @Test
    void constructorStoresSectionAndChecksums() {
        var ex = new CorruptSectionException(CorruptSectionException.SECTION_FOOTER, 0xCAFEBABE,
                0xDEADBEEF);
        assertEquals(CorruptSectionException.SECTION_FOOTER, ex.sectionName());
        assertEquals(0xCAFEBABE, ex.expectedChecksum());
        assertEquals(0xDEADBEEF, ex.actualChecksum());
    }

    @Test
    void messageContainsSectionName() {
        var ex = new CorruptSectionException("footer", 0, 0);
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("footer"), "message should contain section name");
    }

    @Test
    void messageRendersChecksumsAsEightDigitUppercaseHex() {
        var ex = new CorruptSectionException("footer", 0x000000AB, 0x0000CD12);
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("0x000000AB"),
                "message should contain expected checksum as 0x%08X uppercase: " + msg);
        assertTrue(msg.contains("0x0000CD12"),
                "message should contain actual checksum as 0x%08X uppercase: " + msg);
    }

    @Test
    void negativeChecksumValuesRenderedAsUnsigned() {
        int negativeSigned = 0xDEADBEEF;
        assertTrue(negativeSigned < 0, "precondition: value is negative as signed int");

        var ex = new CorruptSectionException("footer", negativeSigned, -1);
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("0xDEADBEEF"),
                "message should render 0xDEADBEEF as unsigned hex: " + msg);
        assertTrue(msg.contains("0xFFFFFFFF"),
                "message should render -1 as 0xFFFFFFFF unsigned hex: " + msg);
    }

    @Test
    void nullSectionNameThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new CorruptSectionException(null, 0, 0));
    }

    @Test
    void extendsIOException() {
        var ex = new CorruptSectionException("footer", 0, 0);
        assertInstanceOf(IOException.class, ex);
    }

    @Test
    void allSectionConstantsAreAccepted() {
        assertEquals("footer", CorruptSectionException.SECTION_FOOTER);
        assertEquals("compression-map", CorruptSectionException.SECTION_COMPRESSION_MAP);
        assertEquals("dictionary", CorruptSectionException.SECTION_DICTIONARY);
        assertEquals("key-index", CorruptSectionException.SECTION_KEY_INDEX);
        assertEquals("bloom-filter", CorruptSectionException.SECTION_BLOOM_FILTER);
        assertEquals("data", CorruptSectionException.SECTION_DATA);

        String[] sections = { CorruptSectionException.SECTION_FOOTER,
                CorruptSectionException.SECTION_COMPRESSION_MAP,
                CorruptSectionException.SECTION_DICTIONARY,
                CorruptSectionException.SECTION_KEY_INDEX,
                CorruptSectionException.SECTION_BLOOM_FILTER,
                CorruptSectionException.SECTION_DATA, };
        for (String section : sections) {
            var ex = new CorruptSectionException(section, 1, 2);
            assertEquals(section, ex.sectionName(),
                    "sectionName() must return the constant as supplied");
        }
    }

    @Test
    void zeroChecksumsAreValid() {
        var ex = assertDoesNotThrow(() -> new CorruptSectionException("footer", 0, 0));
        assertEquals(0, ex.expectedChecksum());
        assertEquals(0, ex.actualChecksum());
    }
}
