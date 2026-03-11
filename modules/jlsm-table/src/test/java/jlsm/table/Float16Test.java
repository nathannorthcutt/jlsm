package jlsm.table;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Float16Test {

    private static final float HALF_EPSILON = 0.001f; // tolerance for float16 precision

    @Test
    void roundTrip_positiveFloat() {
        float original = 3.14f;
        short bits = Float16.fromFloat(original);
        float recovered = Float16.toFloat(bits);
        assertEquals(original, recovered, HALF_EPSILON, "positive float roundtrip");
    }

    @Test
    void roundTrip_negativeFloat() {
        float original = -2.5f;
        short bits = Float16.fromFloat(original);
        float recovered = Float16.toFloat(bits);
        assertEquals(original, recovered, HALF_EPSILON, "negative float roundtrip");
    }

    @Test
    void roundTrip_zero() {
        assertEquals(0.0f, Float16.toFloat(Float16.fromFloat(0.0f)), 0.0f);
    }

    @Test
    void roundTrip_positiveInfinity() {
        assertEquals(Float.POSITIVE_INFINITY,
                Float16.toFloat(Float16.fromFloat(Float.POSITIVE_INFINITY)));
    }

    @Test
    void roundTrip_negativeInfinity() {
        assertEquals(Float.NEGATIVE_INFINITY,
                Float16.toFloat(Float16.fromFloat(Float.NEGATIVE_INFINITY)));
    }

    @Test
    void roundTrip_nan() {
        assertTrue(Float.isNaN(Float16.toFloat(Float16.fromFloat(Float.NaN))));
    }

    @Test
    void fromFloat_minSubnormal() {
        // Minimum positive subnormal half-precision value ~ 5.96e-8
        short minSubnormal = (short) 0x0001;
        float val = Float16.toFloat(minSubnormal);
        assertTrue(val > 0.0f && val < 1e-6f, "min subnormal should be very small positive");
        // Round-trip from that value should give same bits
        short recovered = Float16.fromFloat(val);
        assertEquals(minSubnormal, recovered, "min subnormal round trip bits");
    }
}
