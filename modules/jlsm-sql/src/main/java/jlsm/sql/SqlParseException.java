package jlsm.sql;

/**
 * Thrown when a SQL string cannot be parsed or validated.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Carries the character position in the input where the error was detected</li>
 * <li>Message includes context about what was expected vs. found</li>
 * <li>Checked exception — callers must handle parse failures explicitly</li>
 * </ul>
 */
public final class SqlParseException extends Exception {

    private final int position;

    /**
     * @param message human-readable description of the parse error
     * @param position zero-based character offset in the SQL string where the error occurred, or -1
     *            if position is unknown
     */
    public SqlParseException(String message, int position) {
        super(message);
        this.position = position;
    }

    /**
     * @param message human-readable description of the parse error
     * @param position zero-based character offset in the SQL string
     * @param cause underlying exception, if any
     */
    public SqlParseException(String message, int position, Throwable cause) {
        super(message, cause);
        this.position = position;
    }

    /**
     * @return zero-based character offset where the error was detected, or -1 if unknown
     */
    public int position() {
        return position;
    }
}
