package jlsm.core.json.internal;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

import java.util.Objects;

/**
 * Tier 2 (Vector API) structural scanner for JSON input.
 *
 * <p>
 * Uses {@code jdk.incubator.vector.ByteVector} for SIMD-accelerated character classification.
 * Processes input in lane-width chunks and falls back to scalar for the tail.
 *
 * @spec F15.R20 — tier 2 Vector API structural scanning
 * @spec F15.R23 — requires jdk.incubator.vector
 */
public final class VectorStage1 {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int LANE_COUNT = SPECIES.length();

    // Broadcast vectors for each structural character
    private static final ByteVector V_LBRACE = ByteVector.broadcast(SPECIES, (byte) '{');
    private static final ByteVector V_RBRACE = ByteVector.broadcast(SPECIES, (byte) '}');
    private static final ByteVector V_LBRACKET = ByteVector.broadcast(SPECIES, (byte) '[');
    private static final ByteVector V_RBRACKET = ByteVector.broadcast(SPECIES, (byte) ']');
    private static final ByteVector V_COLON = ByteVector.broadcast(SPECIES, (byte) ':');
    private static final ByteVector V_COMMA = ByteVector.broadcast(SPECIES, (byte) ',');
    private static final ByteVector V_QUOTE = ByteVector.broadcast(SPECIES, (byte) '"');
    private static final ByteVector V_BACKSLASH = ByteVector.broadcast(SPECIES, (byte) '\\');

    /**
     * Scans the input bytes using the Vector API and returns an array of positions where structural
     * characters occur (outside of string literals).
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

        int[] buffer = new int[len];
        int count = 0;
        boolean inString = false;
        int i = 0;

        // Process full vector-width chunks
        final int vectorLimit = len - (len % LANE_COUNT);
        while (i < vectorLimit) {
            ByteVector chunk = ByteVector.fromArray(SPECIES, input, i);

            // Find positions of interesting characters using SIMD comparison
            VectorMask<Byte> quotesMask = chunk.eq(V_QUOTE);
            VectorMask<Byte> backslashMask = chunk.eq(V_BACKSLASH);
            VectorMask<Byte> structuralMask = chunk.eq(V_LBRACE).or(chunk.eq(V_RBRACE))
                    .or(chunk.eq(V_LBRACKET)).or(chunk.eq(V_RBRACKET)).or(chunk.eq(V_COLON))
                    .or(chunk.eq(V_COMMA));

            VectorMask<Byte> interestingMask = quotesMask.or(backslashMask).or(structuralMask);

            if (!interestingMask.anyTrue()) {
                // No interesting characters in this chunk — skip
                i += LANE_COUNT;
                continue;
            }

            // Fall back to scalar processing for this chunk since we need
            // to track string state correctly with backslash escapes
            for (int j = 0; j < LANE_COUNT; j++) {
                byte b = input[i + j];
                if (inString) {
                    if (b == '\\') {
                        j++; // Skip escaped byte
                    } else if (b == '"') {
                        buffer[count++] = i + j;
                        inString = false;
                    }
                } else {
                    switch (b) {
                        case '"' -> {
                            buffer[count++] = i + j;
                            inString = true;
                        }
                        case '{', '}', '[', ']', ':', ',' -> buffer[count++] = i + j;
                        default -> {
                            /* whitespace/value chars */ }
                    }
                }
            }
            i += LANE_COUNT;
        }

        // Scalar tail
        for (; i < len; i++) {
            byte b = input[i];
            if (inString) {
                if (b == '\\') {
                    i++;
                } else if (b == '"') {
                    buffer[count++] = i;
                    inString = false;
                }
            } else {
                switch (b) {
                    case '"' -> {
                        buffer[count++] = i;
                        inString = true;
                    }
                    case '{', '}', '[', ']', ':', ',' -> buffer[count++] = i;
                    default -> {
                        /* whitespace/value chars */ }
                }
            }
        }

        if (count == len) {
            return buffer;
        }
        int[] result = new int[count];
        System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }

    private VectorStage1() {
        // Static utility class
    }
}
