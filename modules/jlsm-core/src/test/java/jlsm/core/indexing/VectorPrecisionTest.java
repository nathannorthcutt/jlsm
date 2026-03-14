package jlsm.core.indexing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VectorPrecisionTest {

    @Test
    void bytesPerComponent_float32_returns4() {
        assertEquals(4, VectorPrecision.FLOAT32.bytesPerComponent());
    }

    @Test
    void bytesPerComponent_float16_returns2() {
        assertEquals(2, VectorPrecision.FLOAT16.bytesPerComponent());
    }
}
