package jlsm.sstable;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link CorruptBlockException}.
 *
 * <p>
 * All tests are expected to fail because the stub constructor throws
 * {@link UnsupportedOperationException}. Once implemented, these tests define the contract: the
 * exception must store block index, expected checksum, and actual checksum, and expose them via
 * accessors.
 * </p>
 */
// @spec sstable.v3-format-upgrade.R9 — CorruptBlockException contract: extends IOException, carries
// 3 fields + message
class CorruptBlockExceptionTest {

    @Test
    void constructorStoresFields() {
        var ex = new CorruptBlockException(7, 0xCAFEBABE, 0xDEADBEEF);
        assertEquals(7, ex.blockIndex());
        assertEquals(0xCAFEBABE, ex.expectedChecksum());
        assertEquals(0xDEADBEEF, ex.actualChecksum());
    }

    @Test
    void messageFormatContainsDiagnosticInfo() {
        var ex = new CorruptBlockException(3, 0x0000ABCD, 0x00001234);
        String msg = ex.getMessage();
        assertNotNull(msg);
        // Message should contain the block index
        assertTrue(msg.contains("3"), "message should contain block index");
        // Message should contain hex-formatted checksums for diagnostics
        assertTrue(msg.toLowerCase().contains("abcd"),
                "message should contain expected checksum in hex");
        assertTrue(msg.toLowerCase().contains("1234"),
                "message should contain actual checksum in hex");
    }

    @Test
    void negativeChecksumValuesAccepted() {
        // CRC32C can produce values that appear negative as signed int.
        // 0xDEADBEEF = -559038737 as signed int.
        int negativeSigned = 0xDEADBEEF;
        assertTrue(negativeSigned < 0, "precondition: value is negative as signed int");

        var ex = new CorruptBlockException(0, negativeSigned, -1);
        assertEquals(negativeSigned, ex.expectedChecksum());
        assertEquals(-1, ex.actualChecksum());
    }

    @Test
    void zeroAndMaxBlockIndex() {
        var atZero = new CorruptBlockException(0, 100, 200);
        assertEquals(0, atZero.blockIndex());

        var atMax = new CorruptBlockException(Integer.MAX_VALUE, 100, 200);
        assertEquals(Integer.MAX_VALUE, atMax.blockIndex());
    }

    @Test
    void extendsIOException() {
        var ex = new CorruptBlockException(0, 0, 0);
        assertInstanceOf(IOException.class, ex);
    }
}
