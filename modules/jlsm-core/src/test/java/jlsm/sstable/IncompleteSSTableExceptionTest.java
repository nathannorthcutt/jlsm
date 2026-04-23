package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * TDD tests for {@link IncompleteSSTableException}.
 *
 * <p>
 * All tests are expected to fail because the stub constructor throws
 * {@link UnsupportedOperationException}. Once implemented, these tests define the contract: the
 * exception stores detected magic, actual file size, expected minimum size, and a diagnostic detail
 * that appears in the message.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R40
 */
class IncompleteSSTableExceptionTest {

    @Test
    void constructorStoresFields() {
        var ex = new IncompleteSSTableException("0x4A4C534D53535405", 42L, 112L, "too small");
        assertEquals("0x4A4C534D53535405", ex.detectedMagic());
        assertEquals(42L, ex.actualFileSize());
        assertEquals(112L, ex.expectedMinimumSize());
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("too small"),
                "message should contain the diagnostic detail: " + msg);
    }

    @Test
    void messageContainsAllFields() {
        var ex = new IncompleteSSTableException("no-magic", 5L, 8L, "file too short for magic");
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("no-magic"), "message should contain detected magic: " + msg);
        assertTrue(msg.contains("5"), "message should contain actual file size: " + msg);
        assertTrue(msg.contains("8"), "message should contain expected minimum size: " + msg);
        assertTrue(msg.contains("file too short for magic"),
                "message should contain diagnostic detail: " + msg);
    }

    @Test
    void noMagicSentinelAccepted() {
        var ex = new IncompleteSSTableException("no-magic", 0L, 0L, "empty");
        assertEquals("no-magic", ex.detectedMagic());
    }

    @Test
    void zeroActualFileSizeAccepted() {
        var ex = assertDoesNotThrow(
                () -> new IncompleteSSTableException("no-magic", 0L, 0L, "empty"));
        assertEquals(0L, ex.actualFileSize());
    }

    @Test
    void nullDetectedMagicThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new IncompleteSSTableException(null, 0L, 0L, "x"));
    }

    @Test
    void nullDiagnosticDetailThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new IncompleteSSTableException("no-magic", 0L, 0L, null));
    }

    @Test
    void extendsIOException() {
        var ex = new IncompleteSSTableException("no-magic", 0L, 0L, "x");
        assertInstanceOf(IOException.class, ex);
    }

    @Test
    void distinctFromCorruptSectionException() {
        var ex = new IncompleteSSTableException("no-magic", 0L, 0L, "x");
        assertFalse(CorruptSectionException.class.isAssignableFrom(ex.getClass()),
                "IncompleteSSTableException must not extend CorruptSectionException");
    }
}
