package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Member} record validation.
 */
class MemberTest {

    private static final NodeAddress ADDR = new NodeAddress("n1", "localhost", 8080);

    @Test
    void validConstruction() {
        var member = new Member(ADDR, MemberState.ALIVE, 0);
        assertEquals(ADDR, member.address());
        assertEquals(MemberState.ALIVE, member.state());
        assertEquals(0, member.incarnation());
    }

    @Test
    void nullAddressThrows() {
        assertThrows(NullPointerException.class, () -> new Member(null, MemberState.ALIVE, 0));
    }

    @Test
    void nullStateThrows() {
        assertThrows(NullPointerException.class, () -> new Member(ADDR, null, 0));
    }

    @Test
    void negativeIncarnationThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Member(ADDR, MemberState.ALIVE, -1));
    }

    @Test
    void zeroIncarnationAllowed() {
        var member = new Member(ADDR, MemberState.ALIVE, 0);
        assertEquals(0, member.incarnation());
    }

    @Test
    void equalityAndHashCode() {
        var a = new Member(ADDR, MemberState.ALIVE, 1);
        var b = new Member(ADDR, MemberState.ALIVE, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityOnDifferentState() {
        var a = new Member(ADDR, MemberState.ALIVE, 1);
        var b = new Member(ADDR, MemberState.SUSPECTED, 1);
        assertNotEquals(a, b);
    }
}
