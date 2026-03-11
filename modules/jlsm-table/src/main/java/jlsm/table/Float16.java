package jlsm.table;

/**
 * Utility class for converting between IEEE 754 single-precision (float) and half-precision
 * (float16) bit representations.
 *
 * <p>
 * Half-precision format: 1 sign bit, 5 exponent bits (bias 15), 10 mantissa bits. Single-precision
 * format: 1 sign bit, 8 exponent bits (bias 127), 23 mantissa bits.
 *
 * <p>
 * Handles all special cases: ±0, ±infinity, NaN, and subnormals in both directions.
 */
public final class Float16 {

    // Half-precision constants
    private static final int HALF_SIGN_MASK = 0x8000;
    private static final int HALF_EXP_MASK = 0x7C00;
    private static final int HALF_MANTISSA_MASK = 0x03FF;
    private static final int HALF_EXP_BIAS = 15;
    private static final int HALF_EXP_MAX = 31; // all ones = infinity/NaN

    // Single-precision constants
    private static final int FLOAT_SIGN_MASK = 0x80000000;
    private static final int FLOAT_EXP_MASK = 0x7F800000;
    private static final int FLOAT_MANTISSA_MASK = 0x007FFFFF;
    private static final int FLOAT_EXP_BIAS = 127;

    private Float16() {
    }

    /**
     * Converts a single-precision float to a half-precision bit pattern.
     *
     * <p>
     * The returned {@code short} contains the IEEE 754 half-precision bits. Overflow (too large for
     * float16) maps to ±infinity. Values too small for normal half-precision map to subnormals or
     * zero.
     *
     * @param f the float to convert
     * @return the half-precision bit pattern as a {@code short}
     */
    public static short fromFloat(float f) {
        final int floatBits = Float.floatToRawIntBits(f);

        final int sign = (floatBits >>> 16) & HALF_SIGN_MASK;
        final int floatExp = (floatBits & FLOAT_EXP_MASK) >>> 23;
        final int floatMantissa = floatBits & FLOAT_MANTISSA_MASK;

        // NaN or Infinity
        if (floatExp == 0xFF) {
            if (floatMantissa != 0) {
                // NaN — preserve a non-zero mantissa (but cap to half precision)
                return (short) (sign | HALF_EXP_MASK | 0x0001);
            } else {
                // ±Infinity
                return (short) (sign | HALF_EXP_MASK);
            }
        }

        // Zero (positive or negative)
        if (floatExp == 0 && floatMantissa == 0) {
            return (short) sign;
        }

        // Compute half exponent (unbiased: floatExp - FLOAT_EXP_BIAS, then re-biased)
        final int halfExp = floatExp - FLOAT_EXP_BIAS + HALF_EXP_BIAS;

        if (halfExp >= HALF_EXP_MAX) {
            // Overflow → ±infinity
            return (short) (sign | HALF_EXP_MASK);
        }

        if (halfExp <= 0) {
            // Result is subnormal in half-precision (or underflows to zero)
            // The implicit leading 1 must be shifted into the mantissa
            // halfExp of 0 means the float value fits as the least significant subnormal
            // shift = 1 - halfExp (number of right-shifts needed)
            final int shift = 1 - halfExp;
            if (shift >= 11) {
                // Completely underflows to zero (shift >= 11 means all mantissa bits lost)
                return (short) sign;
            }
            // Form the full 11-bit mantissa (implicit 1 + 10 explicit bits), then shift right
            final int mantissa = (floatMantissa | 0x00800000) >>> (13 + shift);
            assert mantissa <= HALF_MANTISSA_MASK : "subnormal mantissa overflow";
            return (short) (sign | mantissa);
        }

        // Normal half-precision: round-to-nearest-even
        // The lower 13 bits of the float mantissa are lost; bit 12 is the round bit
        final int halfMantissa = floatMantissa >>> 13;
        final int roundBit = (floatMantissa >>> 12) & 1;
        final int stickyBits = floatMantissa & 0x00000FFF;
        // Round up if: round bit set AND (sticky bits set OR LSB of half mantissa is 1)
        final int roundUp = roundBit & (stickyBits != 0 ? 1 : (halfMantissa & 1));
        int rounded = halfMantissa + roundUp;
        int halfExpFinal = halfExp;
        // Handle mantissa overflow (e.g. 0x3FF + 1 = 0x400)
        if (rounded > HALF_MANTISSA_MASK) {
            rounded = 0;
            halfExpFinal++;
            if (halfExpFinal >= HALF_EXP_MAX) {
                // Overflow to infinity
                return (short) (sign | HALF_EXP_MASK);
            }
        }
        assert halfExpFinal > 0 && halfExpFinal < HALF_EXP_MAX
                : "half exponent out of normal range";
        return (short) (sign | (halfExpFinal << 10) | rounded);
    }

    /**
     * Converts a half-precision bit pattern to a single-precision float.
     *
     * @param bits the half-precision bit pattern
     * @return the equivalent single-precision float
     */
    public static float toFloat(short bits) {
        final int halfBits = bits & 0xFFFF; // treat as unsigned 16-bit

        final int sign = (halfBits & HALF_SIGN_MASK) << 16;
        final int halfExp = (halfBits & HALF_EXP_MASK) >>> 10;
        final int halfMantissa = halfBits & HALF_MANTISSA_MASK;

        if (halfExp == HALF_EXP_MAX) {
            // NaN or Infinity
            if (halfMantissa != 0) {
                // NaN — set high mantissa bit to keep it a quiet NaN
                return Float.intBitsToFloat(sign | FLOAT_EXP_MASK | (halfMantissa << 13));
            } else {
                // ±Infinity
                return Float.intBitsToFloat(sign | FLOAT_EXP_MASK);
            }
        }

        if (halfExp == 0) {
            if (halfMantissa == 0) {
                // ±Zero
                return Float.intBitsToFloat(sign);
            }
            // Subnormal half-precision → normal single-precision
            // Find the leading 1 bit position in the 10-bit mantissa.
            // Initial floatExp = FLOAT_EXP_BIAS - HALF_EXP_BIAS + 1 = 113: the +1 accounts
            // for the fact that the half subnormal mantissa is effectively an integer * 2^-10
            // and we need to absorb the implicit leading 1 into the exponent.
            int mantissa = halfMantissa;
            int floatExp = FLOAT_EXP_BIAS - HALF_EXP_BIAS + 1; // = 113
            // Normalize: shift mantissa left until implicit 1 is consumed
            while ((mantissa & 0x0400) == 0) {
                mantissa <<= 1;
                floatExp--;
            }
            // Clear the implicit 1 bit now that we've encoded it in the exponent
            mantissa &= HALF_MANTISSA_MASK;
            assert floatExp > 0 : "float exponent underflow during subnormal conversion";
            return Float.intBitsToFloat(sign | (floatExp << 23) | (mantissa << 13));
        }

        // Normal half-precision → normal single-precision
        final int floatExp = halfExp + FLOAT_EXP_BIAS - HALF_EXP_BIAS; // re-bias
        assert floatExp > 0 && floatExp < 0xFF : "float exponent out of normal range";
        return Float.intBitsToFloat(sign | (floatExp << 23) | (halfMantissa << 13));
    }
}
