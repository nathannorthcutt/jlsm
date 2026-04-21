package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MembershipView} — liveMemberCount, isMember, hasQuorum, Comparable, equals.
 */
class MembershipViewTest {

    private static final NodeAddress A1 = new NodeAddress("n1", "h1", 8080);
    private static final NodeAddress A2 = new NodeAddress("n2", "h2", 8080);
    private static final NodeAddress A3 = new NodeAddress("n3", "h3", 8080);
    private static final Instant NOW = Instant.now();

    @Test
    void emptyView() {
        var view = new MembershipView(0, Set.of(), NOW);
        assertEquals(0, view.liveMemberCount());
        assertEquals(0, view.epoch());
        assertFalse(view.isMember(A1));
    }

    @Test
    void liveMemberCountFiltersStates() {
        var members = Set.of(new Member(A1, MemberState.ALIVE, 0),
                new Member(A2, MemberState.SUSPECTED, 0), new Member(A3, MemberState.DEAD, 0));
        var view = new MembershipView(1, members, NOW);
        assertEquals(1, view.liveMemberCount());
    }

    @Test
    void isMemberFindsPresent() {
        var view = new MembershipView(0, Set.of(new Member(A1, MemberState.ALIVE, 0)), NOW);
        assertTrue(view.isMember(A1));
    }

    @Test
    void isMemberReturnsFalseForAbsent() {
        var view = new MembershipView(0, Set.of(new Member(A1, MemberState.ALIVE, 0)), NOW);
        assertFalse(view.isMember(A2));
    }

    // @spec engine.clustering.R17,R82 — isMember must report DEAD as non-member
    @Test
    void isMemberExcludesDeadMembers() {
        var view = new MembershipView(0, Set.of(new Member(A1, MemberState.DEAD, 0)), NOW);
        assertFalse(view.isMember(A1));
    }

    // @spec engine.clustering.R17 — SUSPECTED counts as a current member
    @Test
    void isMemberIncludesSuspectedMembers() {
        var view = new MembershipView(0, Set.of(new Member(A1, MemberState.SUSPECTED, 0)), NOW);
        assertTrue(view.isMember(A1));
    }

    // @spec engine.clustering.R82 — isKnown exposes DEAD records distinctly from isMember
    @Test
    void isKnownIncludesDeadMembers() {
        var view = new MembershipView(0, Set.of(new Member(A1, MemberState.DEAD, 0)), NOW);
        assertTrue(view.isKnown(A1));
        assertFalse(view.isMember(A1));
    }

    @Test
    void isMemberNullThrows() {
        var view = new MembershipView(0, Set.of(), NOW);
        assertThrows(NullPointerException.class, () -> view.isMember(null));
    }

    @Test
    void isKnownNullThrows() {
        var view = new MembershipView(0, Set.of(), NOW);
        assertThrows(NullPointerException.class, () -> view.isKnown(null));
    }

    @Test
    void hasQuorumAllAlive() {
        var members = Set.of(new Member(A1, MemberState.ALIVE, 0),
                new Member(A2, MemberState.ALIVE, 0), new Member(A3, MemberState.ALIVE, 0));
        var view = new MembershipView(0, members, NOW);
        assertTrue(view.hasQuorum(75));
        assertTrue(view.hasQuorum(100));
    }

    @Test
    void hasQuorumWithSuspected() {
        var members = Set.of(new Member(A1, MemberState.ALIVE, 0),
                new Member(A2, MemberState.ALIVE, 0), new Member(A3, MemberState.SUSPECTED, 0));
        var view = new MembershipView(0, members, NOW);
        // 2 of 3 alive = 66.7%, quorum at 75% should fail
        assertFalse(view.hasQuorum(75));
        // but quorum at 50% should pass
        assertTrue(view.hasQuorum(50));
    }

    // @spec engine.clustering.R16 — DEAD members must not factor into quorum calculation
    @Test
    void hasQuorumExcludesDeadFromDenominator() {
        // 2 ALIVE + 1 DEAD: under R16, denominator is 2 (excluding DEAD), not 3.
        // 2/2 = 100%, so quorum at 75% and at 100% must both pass.
        var members = Set.of(new Member(A1, MemberState.ALIVE, 0),
                new Member(A2, MemberState.ALIVE, 0), new Member(A3, MemberState.DEAD, 0));
        var view = new MembershipView(0, members, NOW);
        assertTrue(view.hasQuorum(75));
        assertTrue(view.hasQuorum(100));
    }

    @Test
    void hasQuorumAllDeadReturnsFalse() {
        var members = Set.of(new Member(A1, MemberState.DEAD, 0),
                new Member(A2, MemberState.DEAD, 0));
        var view = new MembershipView(0, members, NOW);
        assertFalse(view.hasQuorum(1));
    }

    @Test
    void hasQuorumEmptyViewReturnsFalse() {
        var view = new MembershipView(0, Set.of(), NOW);
        assertFalse(view.hasQuorum(1));
    }

    @Test
    void hasQuorumInvalidPercentThrows() {
        var view = new MembershipView(0, Set.of(), NOW);
        assertThrows(IllegalArgumentException.class, () -> view.hasQuorum(0));
        assertThrows(IllegalArgumentException.class, () -> view.hasQuorum(101));
    }

    @Test
    void comparableByEpoch() {
        var view1 = new MembershipView(1, Set.of(), NOW);
        var view2 = new MembershipView(2, Set.of(), NOW);
        assertTrue(view1.compareTo(view2) < 0);
        assertTrue(view2.compareTo(view1) > 0);
        assertEquals(0, view1.compareTo(new MembershipView(1, Set.of(), NOW)));
    }

    @Test
    void negativeEpochThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MembershipView(-1, Set.of(), NOW));
    }

    @Test
    void nullMembersThrows() {
        assertThrows(NullPointerException.class, () -> new MembershipView(0, null, NOW));
    }

    @Test
    void nullTimestampThrows() {
        assertThrows(NullPointerException.class, () -> new MembershipView(0, Set.of(), null));
    }

    @Test
    void membersSetIsImmutable() {
        var view = new MembershipView(0, Set.of(new Member(A1, MemberState.ALIVE, 0)), NOW);
        assertThrows(UnsupportedOperationException.class,
                () -> view.members().add(new Member(A2, MemberState.ALIVE, 0)));
    }

    @Test
    void equalityAndHashCode() {
        var members = Set.of(new Member(A1, MemberState.ALIVE, 0));
        var a = new MembershipView(1, members, NOW);
        var b = new MembershipView(1, members, NOW);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequalityOnDifferentEpoch() {
        var a = new MembershipView(1, Set.of(), NOW);
        var b = new MembershipView(2, Set.of(), NOW);
        assertNotEquals(a, b);
    }
}
