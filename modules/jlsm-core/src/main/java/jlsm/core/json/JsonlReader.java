package jlsm.core.json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Streaming JSONL (newline-delimited JSON) reader.
 *
 * <p>
 * Reads JSON values one line at a time from an {@link InputStream}, producing a lazy {@link Stream}
 * of {@link JsonValue} instances. Memory usage is constant per line regardless of stream size.
 *
 * <p>
 * Error handling is configurable:
 * <ul>
 * <li>{@link ErrorMode#FAIL_FAST} — throws {@link JsonParseException} on the first malformed
 * line</li>
 * <li>{@link ErrorMode#SKIP_ON_ERROR} — skips malformed lines and optionally reports them via a
 * {@link Consumer} callback</li>
 * </ul>
 *
 * <p>
 * Blank lines (empty or whitespace-only) are silently skipped in both modes.
 *
 * <p>
 * Implements {@link AutoCloseable}; closing the reader closes the underlying input stream.
 *
 * @spec F15.R30 — reads JSON Lines from InputStream, one value per non-empty line
 * @spec F15.R31 — stream() returns Stream of JsonValue
 * @spec F15.R32 — no accumulation across lines, O(line) memory
 * @spec F15.R33 — fail-fast and skip-on-error modes
 * @spec F15.R34 — error handler for skipped lines
 * @spec F15.R35 — AutoCloseable, closes underlying stream
 * @spec F15.R53 — stream() callable at most once
 * @spec F15.R54 — close() releases all internal resources
 */
public final class JsonlReader implements AutoCloseable {

    /**
     * Error handling mode for malformed JSONL lines.
     */
    public enum ErrorMode {
        /** Throw {@link JsonParseException} on the first malformed line. */
        FAIL_FAST,
        /** Skip malformed lines, optionally reporting via callback. */
        SKIP_ON_ERROR
    }

    /**
     * Error information for a malformed JSONL line.
     *
     * @param lineNumber the 1-based line number where the error occurred
     * @param line the raw line text
     * @param cause the parse exception
     */
    public record ParseError(long lineNumber, String line, JsonParseException cause) {

        /** Creates a parse error record. */
        public ParseError {
            Objects.requireNonNull(line, "line must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }

    private final InputStream input;
    private final ErrorMode errorMode;
    private final Consumer<ParseError> errorCallback;
    private boolean streamCalled;
    private BufferedReader buffered;

    /**
     * Creates a JSONL reader with FAIL_FAST error mode and no callback.
     *
     * @param input the input stream; must not be null
     * @throws NullPointerException if input is null
     */
    public JsonlReader(InputStream input) {
        this(input, ErrorMode.FAIL_FAST, null);
    }

    /**
     * Creates a JSONL reader with configurable error handling.
     *
     * @param input the input stream; must not be null
     * @param errorMode the error handling mode; must not be null
     * @param errorCallback optional callback for error reporting in SKIP_ON_ERROR mode; may be null
     * @throws NullPointerException if input or errorMode is null
     */
    public JsonlReader(InputStream input, ErrorMode errorMode, Consumer<ParseError> errorCallback) {
        this.input = Objects.requireNonNull(input, "input must not be null");
        this.errorMode = Objects.requireNonNull(errorMode, "errorMode must not be null");
        this.errorCallback = errorCallback;
    }

    /**
     * Returns a lazy {@link Stream} of {@link JsonValue} instances, one per non-blank line in the
     * JSONL input.
     *
     * <p>
     * The stream is lazy: lines are read and parsed on demand. Closing the stream does not close
     * this reader; use {@link #close()} for that.
     *
     * @return a lazy stream of parsed JSON values
     */
    public Stream<JsonValue> stream() {
        if (streamCalled) {
            throw new IllegalStateException("stream() has already been called — "
                    + "JsonlReader is single-use because the underlying InputStream is stateful");
        }
        streamCalled = true;

        this.buffered = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        final var buffered = this.buffered;

        Spliterator<JsonValue> spliterator = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {

            private long lineNumber = 0;

            @Override
            public boolean tryAdvance(Consumer<? super JsonValue> action) {
                String line;
                while (true) {
                    try {
                        line = buffered.readLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    if (line == null) {
                        return false; // end of stream
                    }
                    lineNumber++;
                    if (line.isBlank()) {
                        continue; // skip blank lines
                    }
                    try {
                        JsonValue value = JsonParser.parse(line);
                        action.accept(value);
                        return true;
                    } catch (JsonParseException e) {
                        if (errorMode == ErrorMode.FAIL_FAST) {
                            throw e;
                        }
                        // SKIP_ON_ERROR — invoke callback if present
                        assert errorMode == ErrorMode.SKIP_ON_ERROR;
                        if (errorCallback != null) {
                            errorCallback.accept(new ParseError(lineNumber, line, e));
                        }
                        // continue to next line
                    }
                }
            }
        };

        return StreamSupport.stream(spliterator, false);
    }

    /**
     * Closes the underlying input stream.
     *
     * @throws java.io.IOException if an I/O error occurs
     */
    @Override
    public void close() throws Exception {
        try {
            if (buffered != null) {
                buffered.close();
            }
        } finally {
            input.close();
        }
    }
}
