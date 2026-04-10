package jlsm.core.json;

/**
 * Unchecked exception thrown when JSON parsing fails.
 *
 * <p>
 * Carries the byte offset within the input where the error was detected and a human-readable
 * description of the problem. The offset is best-effort: it points to the approximate location but
 * may not be exact for all error types.
 *
 * @spec F15.R24 — unchecked exception with byte offset and description
 */
public final class JsonParseException extends RuntimeException {

    private final long offset;

    /**
     * Creates a new parse exception.
     *
     * @param message a description of the parse error; must not be null
     * @param offset the byte offset in the input where the error was detected; negative if unknown
     */
    public JsonParseException(String message, long offset) {
        super(formatMessage(message, offset));
        this.offset = offset;
    }

    /**
     * Creates a new parse exception with a cause.
     *
     * @param message a description of the parse error; must not be null
     * @param offset the byte offset in the input where the error was detected; negative if unknown
     * @param cause the underlying cause
     */
    public JsonParseException(String message, long offset, Throwable cause) {
        super(formatMessage(message, offset), cause);
        this.offset = offset;
    }

    /**
     * Returns the byte offset in the input where the error was detected.
     *
     * @return the byte offset, or a negative value if unknown
     */
    public long offset() {
        return offset;
    }

    private static String formatMessage(String message, long offset) {
        if (offset >= 0) {
            return message + " (at byte offset " + offset + ")";
        }
        return message;
    }
}
