package jlsm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AllocationTrackingTest {

    @Test
    void testAllocationTrackingValues() {
        var values = AllocationTracking.values();
        assertEquals(3, values.length);
        assertEquals(AllocationTracking.OFF, values[0]);
        assertEquals(AllocationTracking.CALLER_TAG, values[1]);
        assertEquals(AllocationTracking.FULL_STACK, values[2]);
    }

    @Test
    void testAllocationTrackingValueOf() {
        assertEquals(AllocationTracking.OFF, AllocationTracking.valueOf("OFF"));
        assertEquals(AllocationTracking.CALLER_TAG, AllocationTracking.valueOf("CALLER_TAG"));
        assertEquals(AllocationTracking.FULL_STACK, AllocationTracking.valueOf("FULL_STACK"));
    }
}
