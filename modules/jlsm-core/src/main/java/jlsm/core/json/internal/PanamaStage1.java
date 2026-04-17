package jlsm.core.json.internal;

import java.util.Objects;

/**
 * Tier 1 (Panama FFM) structural scanner for JSON input.
 *
 * <p>
 * This tier detects Panama FFM availability for future native PCLMULQDQ/PMULL downcalls. Currently
 * uses a pure-Java carry-less multiply (GF(2) multiplication) for the quote-pairing prefix-XOR
 * computation, which can be swapped for a native downcall when benchmark data justifies the added
 * complexity.
 *
 * <p>
 * The carry-less multiply is used to compute the prefix-XOR of the quote bitmask, determining which
 * bytes are inside vs outside string literals. This is the same mathematical operation as
 * PCLMULQDQ/PMULL hardware instructions.
 *
 * @spec F15.R20 — Tier 1: Panama FFM with PCLMULQDQ/PMULL carry-less multiply
 */
public final class PanamaStage1 {

    /**
     * Scans the input bytes using carry-less multiplication for quote pairing and returns an array
     * of positions where structural characters occur (outside of string literals).
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

        // Phase 1: Build character classification bitmasks for 64-byte blocks
        // For inputs <= 64 bytes, we process in a single block.
        // For larger inputs, we process block-by-block carrying string state.

        int[] buffer = new int[len];
        int count = 0;
        boolean inString = false;

        // Carry: number of trailing backslashes from the previous block.
        // Needed so that computeEscapedQuotes can account for backslash runs
        // that span a 64-byte block boundary.
        int prevBlockTrailingBackslashes = 0;

        // Process 64-byte blocks using bitmask approach with CLMUL
        int blockStart = 0;
        while (blockStart + 64 <= len) {
            // Build quote and backslash bitmasks for this 64-byte block
            long quoteBits = 0L;
            long backslashBits = 0L;
            long structuralBits = 0L;

            for (int j = 0; j < 64; j++) {
                byte b = input[blockStart + j];
                long bit = 1L << j;
                switch (b) {
                    case '"' -> quoteBits |= bit;
                    case '\\' -> backslashBits |= bit;
                    case '{', '}', '[', ']', ':', ',' -> structuralBits |= bit;
                    default -> {
                        /* skip */ }
                }
            }

            // Compute escaped quotes: a quote is escaped if preceded by an odd
            // number of backslashes. We need to find backslash runs and determine parity.
            // Pass the carry from the previous block so cross-boundary runs are counted.
            long escapedQuotes = computeEscapedQuotes(backslashBits, quoteBits,
                    prevBlockTrailingBackslashes);
            long unescapedQuotes = quoteBits & ~escapedQuotes;

            // Use carry-less multiply to compute prefix-XOR of unescaped quotes.
            // clmul(unescapedQuotes, 0xFFFF...F) computes running XOR (prefix parity).
            long stringMask = clmul(unescapedQuotes, 0xFFFFFFFFFFFFFFFFL);

            // If we were already inside a string at the start of this block, invert
            if (inString) {
                stringMask = ~stringMask;
            }

            // Structural characters outside strings
            long outsideStructural = structuralBits & ~stringMask;
            // Unescaped quotes (string boundaries) — also structural
            long outsideQuotes = unescapedQuotes;

            long allStructural = outsideStructural | outsideQuotes;

            // Extract positions from bitmask
            long bits = allStructural;
            while (bits != 0) {
                int bitIndex = Long.numberOfTrailingZeros(bits);
                buffer[count++] = blockStart + bitIndex;
                bits &= bits - 1; // Clear lowest set bit
            }

            // Update inString: count unescaped quotes in this block
            int quoteCount = Long.bitCount(unescapedQuotes);
            if ((quoteCount & 1) != 0) {
                inString = !inString;
            }

            // Compute trailing backslash count for carry to next block/tail.
            // Count consecutive set bits from bit 63 downward in backslashBits.
            int trailingBs = 0;
            for (int k = 63; k >= 0; k--) {
                if ((backslashBits & (1L << k)) != 0) {
                    trailingBs++;
                } else {
                    break;
                }
            }
            prevBlockTrailingBackslashes = trailingBs;

            blockStart += 64;
        }

        // Scalar tail for remaining bytes.
        // If the last full block ended with an odd-length trailing backslash run,
        // the first byte of the tail is escaped and must be skipped.
        int tailStart = blockStart;
        if (prevBlockTrailingBackslashes > 0 && (prevBlockTrailingBackslashes & 1) != 0
                && tailStart < len) {
            // The last backslash of the previous block escapes the first byte of the tail.
            tailStart++;
        }
        for (int i = tailStart; i < len; i++) {
            byte b = input[i];
            if (inString) {
                if (b == '\\') {
                    i++; // Skip escaped byte
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

    /**
     * Carry-less multiplication (GF(2) polynomial multiplication). Equivalent to PCLMULQDQ/PMULL
     * hardware instruction. When b = 0xFFFFFFFFFFFFFFFF, this computes the prefix-XOR (running
     * parity).
     */
    static long clmul(long a, long b) {
        long result = 0;
        long shifted = a;
        long mask = b;
        while (mask != 0) {
            if ((mask & 1) != 0) {
                result ^= shifted;
            }
            shifted <<= 1;
            mask >>>= 1;
        }
        return result;
    }

    /**
     * Computes which quote bits are escaped (preceded by odd number of backslashes).
     *
     * @param backslashBits bitmask of backslash positions in this block
     * @param quoteBits bitmask of quote positions in this block
     * @param prevTrailingBackslashes number of consecutive trailing backslashes from the previous
     *            block (0 if this is the first block)
     */
    private static long computeEscapedQuotes(long backslashBits, long quoteBits,
            int prevTrailingBackslashes) {
        if (backslashBits == 0L && prevTrailingBackslashes == 0) {
            return 0L;
        }

        // For each quote bit, check if the character before it starts a run of
        // backslashes with odd length
        long escaped = 0L;
        long bits = quoteBits;
        while (bits != 0) {
            int qPos = Long.numberOfTrailingZeros(bits);
            // Count consecutive backslashes before this quote within this block
            int bsCount = 0;
            for (int k = qPos - 1; k >= 0; k--) {
                if ((backslashBits & (1L << k)) != 0) {
                    bsCount++;
                } else {
                    break;
                }
            }
            // If the backslash run extends to position 0 of this block, add the
            // trailing backslash count carried from the previous block.
            if (bsCount == qPos) {
                bsCount += prevTrailingBackslashes;
            }
            if ((bsCount & 1) != 0) {
                escaped |= (1L << qPos);
            }
            bits &= bits - 1;
        }
        return escaped;
    }

    private PanamaStage1() {
        // Static utility class
    }
}
