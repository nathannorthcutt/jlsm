package jlsm.core.json;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Streaming JSONL (newline-delimited JSON) writer.
 *
 * <p>
 * Writes {@link JsonValue} instances one per line to an {@link OutputStream}. Each value is
 * serialized as compact JSON (no extra whitespace) followed by a newline character ({@code \n}).
 *
 * <p>
 * Implements {@link AutoCloseable}; closing the writer flushes and closes the underlying output
 * stream.
 *
 * @spec F15.R36 — compact JSON serialization per line
 * @spec F15.R37 — one JSON value per newline-terminated line
 * @spec F15.R38 — accepts any JsonValue subtype
 * @spec F15.R39 — AutoCloseable with flush + close
 */
public final class JsonlWriter implements AutoCloseable {

    private final OutputStream output;
    private volatile boolean closed;

    /**
     * Creates a JSONL writer.
     *
     * @param output the output stream; must not be null
     * @throws NullPointerException if output is null
     */
    public JsonlWriter(OutputStream output) {
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    /**
     * Writes a single {@link JsonValue} as a compact JSON line followed by a newline.
     *
     * @param value the value to write; must not be null
     * @throws NullPointerException if value is null
     * @throws IOException if an I/O error occurs
     */
    public void write(JsonValue value) throws IOException {
        Objects.requireNonNull(value, "value must not be null");
        String json = JsonWriter.write(value);
        byte[] bytes = (json + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.write(bytes);
        output.flush();
    }

    /**
     * Flushes and closes the underlying output stream.
     *
     * <p>
     * This method is idempotent — subsequent calls after the first are silent no-ops.
     *
     * @throws IOException if an I/O error occurs on the first invocation
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            output.flush();
        } finally {
            output.close();
        }
    }
}
