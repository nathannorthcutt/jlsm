package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PredicateTest {

    // ── Eq ───────────────────────────────────────────────────────────────

    @Test
    void testEqStoresFieldAndValue() {
        var eq = new Predicate.Eq("name", "Alice");
        assertEquals("name", eq.field());
        assertEquals("Alice", eq.value());
    }

    @Test
    void testEqRejectsNullField() {
        assertThrows(NullPointerException.class, () -> new Predicate.Eq(null, "v"));
    }

    @Test
    void testEqRejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new Predicate.Eq("f", null));
    }

    // ── Ne ───────────────────────────────────────────────────────────────

    @Test
    void testNeRejectsNullField() {
        assertThrows(NullPointerException.class, () -> new Predicate.Ne(null, "v"));
    }

    // ── Gt / Gte / Lt / Lte ─────────────────────────────────────────────

    @Test
    void testGtGteLtLteRejectNullField() {
        assertThrows(NullPointerException.class, () -> new Predicate.Gt(null, 1));
        assertThrows(NullPointerException.class, () -> new Predicate.Gte(null, 1));
        assertThrows(NullPointerException.class, () -> new Predicate.Lt(null, 1));
        assertThrows(NullPointerException.class, () -> new Predicate.Lte(null, 1));
    }

    @Test
    void testGtGteLtLteRejectNullValue() {
        assertThrows(NullPointerException.class, () -> new Predicate.Gt("f", null));
        assertThrows(NullPointerException.class, () -> new Predicate.Gte("f", null));
        assertThrows(NullPointerException.class, () -> new Predicate.Lt("f", null));
        assertThrows(NullPointerException.class, () -> new Predicate.Lte("f", null));
    }

    // ── Between ─────────────────────────────────────────────────────────

    @Test
    void testBetweenStoresFieldLowHigh() {
        var between = new Predicate.Between("age", 10, 20);
        assertEquals("age", between.field());
        assertEquals(10, between.low());
        assertEquals(20, between.high());
    }

    @Test
    void testBetweenRejectsNullField() {
        assertThrows(NullPointerException.class, () -> new Predicate.Between(null, 1, 2));
    }

    @Test
    void testBetweenRejectsNullLowOrHigh() {
        assertThrows(NullPointerException.class, () -> new Predicate.Between("f", null, 2));
        assertThrows(NullPointerException.class, () -> new Predicate.Between("f", 1, null));
    }

    // ── FullTextMatch ───────────────────────────────────────────────────

    @Test
    void testFullTextMatchRejectsNullField() {
        assertThrows(NullPointerException.class, () -> new Predicate.FullTextMatch(null, "q"));
    }

    @Test
    void testFullTextMatchRejectsNullQuery() {
        assertThrows(NullPointerException.class, () -> new Predicate.FullTextMatch("f", null));
    }

    // ── VectorNearest ───────────────────────────────────────────────────

    @Test
    void testVectorNearestRejectsNullField() {
        assertThrows(NullPointerException.class,
                () -> new Predicate.VectorNearest(null, new float[]{ 1.0f }, 5));
    }

    @Test
    void testVectorNearestRejectsNullVector() {
        assertThrows(NullPointerException.class, () -> new Predicate.VectorNearest("f", null, 5));
    }

    @Test
    void testVectorNearestRejectsNonPositiveTopK() {
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate.VectorNearest("f", new float[]{ 1.0f }, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Predicate.VectorNearest("f", new float[]{ 1.0f }, -1));
    }

    // ── And ─────────────────────────────────────────────────────────────

    @Test
    void testAndRequiresAtLeastTwoChildren() {
        var single = List.<Predicate>of(new Predicate.Eq("f", "v"));
        assertThrows(IllegalArgumentException.class, () -> new Predicate.And(single));
    }

    @Test
    void testOrRequiresAtLeastTwoChildren() {
        var single = List.<Predicate>of(new Predicate.Eq("f", "v"));
        assertThrows(IllegalArgumentException.class, () -> new Predicate.Or(single));
    }

    @Test
    void testAndDefensivelyCopiesChildren() {
        var list = new ArrayList<Predicate>();
        list.add(new Predicate.Eq("a", 1));
        list.add(new Predicate.Eq("b", 2));
        var and = new Predicate.And(list);
        list.add(new Predicate.Eq("c", 3));
        assertEquals(2, and.children().size(), "And should not reflect later mutations");
    }

    @Test
    void testOrDefensivelyCopiesChildren() {
        var list = new ArrayList<Predicate>();
        list.add(new Predicate.Eq("a", 1));
        list.add(new Predicate.Eq("b", 2));
        var or = new Predicate.Or(list);
        list.add(new Predicate.Eq("c", 3));
        assertEquals(2, or.children().size(), "Or should not reflect later mutations");
    }
}
