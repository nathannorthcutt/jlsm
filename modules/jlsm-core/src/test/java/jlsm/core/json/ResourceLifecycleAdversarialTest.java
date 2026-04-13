package jlsm.core.json;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for resource lifecycle concerns in the JSON/JSONL layer.
 */
class ResourceLifecycleAdversarialTest {

    // Finding: F-R1.resource_lifecycle.2.2
    // Bug: If flush() throws in close(), output.close() is never called — the stream leaks
    // Correct behavior: output.close() must be called even when flush() throws
    // Fix location: JsonlWriter.close() — wrap flush+close in try-finally
    // Regression watch: The IOException from flush() must still propagate to the caller
    @Test
    void test_JsonlWriter_close_flushFailureStillClosesStream() throws Exception {
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        OutputStream failingFlush = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        JsonlWriter writer = new JsonlWriter(failingFlush);

        // close() should propagate the flush IOException...
        IOException thrown = assertThrows(IOException.class, writer::close);
        assertEquals("disk full", thrown.getMessage());

        // ...but the underlying stream must still be closed
        assertTrue(streamClosed.get(), "output.close() must be called even when flush() throws");
    }
}
