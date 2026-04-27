package jlsm.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NodeAddress} record validation and value semantics.
 *
 * @spec engine.clustering.R1 — node address = (nodeId, host, port); nodeId non-null/non-empty, host
 *       non-null/non-empty, port in [1, 65535]
 */
class NodeAddressTest {

    @Test
    void validConstruction() {
        var addr = new NodeAddress("node-1", "localhost", 8080);
        assertEquals("node-1", addr.nodeId());
        assertEquals("localhost", addr.host());
        assertEquals(8080, addr.port());
    }

    @Test
    void nullNodeIdThrows() {
        assertThrows(NullPointerException.class, () -> new NodeAddress(null, "localhost", 8080));
    }

    @Test
    void nullHostThrows() {
        assertThrows(NullPointerException.class, () -> new NodeAddress("n1", null, 8080));
    }

    @Test
    void emptyNodeIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> new NodeAddress("", "localhost", 8080));
    }

    @Test
    void emptyHostThrows() {
        assertThrows(IllegalArgumentException.class, () -> new NodeAddress("n1", "", 8080));
    }

    @Test
    void portZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new NodeAddress("n1", "localhost", 0));
    }

    @Test
    void portNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new NodeAddress("n1", "localhost", -1));
    }

    @Test
    void portAbove65535Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeAddress("n1", "localhost", 65536));
    }

    @Test
    void portBoundaryMin() {
        var addr = new NodeAddress("n1", "h", 1);
        assertEquals(1, addr.port());
    }

    @Test
    void portBoundaryMax() {
        var addr = new NodeAddress("n1", "h", 65535);
        assertEquals(65535, addr.port());
    }

    @Test
    void equalityAndHashCode() {
        var a = new NodeAddress("n1", "host", 80);
        var b = new NodeAddress("n1", "host", 80);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityOnDifferentFields() {
        var a = new NodeAddress("n1", "host", 80);
        assertNotEquals(a, new NodeAddress("n2", "host", 80));
        assertNotEquals(a, new NodeAddress("n1", "other", 80));
        assertNotEquals(a, new NodeAddress("n1", "host", 81));
    }
}
