package jlsm.core.json.internal;

import java.util.Objects;

/**
 * Tier 3 (scalar) structural scanner for JSON input.
 *
 * <p>
 * Scans the input byte-by-byte, identifying structural characters ({@code { } [ ] , :}) and quote
 * boundaries while tracking backslash-parity to correctly handle escaped characters within string
 * literals.
 *
 * @spec F15.R20 — tier 3 scalar structural scanning
 */
public final class ScalarStage1 {

    /**
     * Scans the input bytes and returns an array of positions where structural characters occur
     * (outside of string literals), plus string-boundary quotes.
     *
     * <p>
     * Structural characters are: {@code { } [ ] , :} and unescaped {@code "}.
     *
     * @param input the JSON input bytes; must not be null
     * @return an array of byte positions of structural characters, in ascending order
     * @throws NullPointerException if input is null
     */
    public static int[] scan(byte[] input) {
        Objects.requireNonNull(input, "input must not be null");

        final int len = input.length;
        if (len == 0) {
            return new int[0];
        }

        // Worst case: every byte is structural
        int[] buffer = new int[len];
        int count = 0;
        boolean inString = false;

        for (int i = 0; i < len; i++) {
            byte b = input[i];

            if (inString) {
                if (b == '\\') {
                    // Skip the next byte (it's escaped)
                    i++;
                } else if (b == '"') {
                    // End of string — this quote is structural
                    buffer[count++] = i;
                    inString = false;
                }
                // All other bytes inside a string are skipped
            } else {
                switch (b) {
                    case '"' -> {
                        buffer[count++] = i;
                        inString = true;
                    }
                    case '{', '}', '[', ']', ':', ',' -> buffer[count++] = i;
                    default -> {
                        // Whitespace and value characters — not structural
                    }
                }
            }
        }

        // Trim to actual size
        if (count == len) {
            return buffer;
        }
        int[] result = new int[count];
        System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }

    private ScalarStage1() {
        // Static utility class
    }
}
