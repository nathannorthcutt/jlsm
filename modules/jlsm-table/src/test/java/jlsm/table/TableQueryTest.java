package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

// @spec query.table-query.R1,R2,R3,R4,R5,R6,R7
//       — covers TableQuery<K> fluent builder: where() null rejection, FieldClause comparison
//         operators returning TableQuery<K>, and/or chaining into And/Or predicates, predicate()
//         root exposure (null before any predicate is added).
// R8 (execute() returns Iterator) + R9 (unbound execute() throws UOE) covered by
// TableQueryExecutionTest.
class TableQueryTest {

    /**
     * Helper to create a TableQuery. Since the constructor is private and the query is typically
     * obtained from a table, we test via the public API surface that will be available once
     * implemented. For now, we construct directly to test the fluent builder logic.
     */
    private <K> TableQuery<K> newQuery() {
        // TableQuery construction will be wired through the table or a factory.
        // This helper will be updated once the factory/static method is available.
        // For now, attempting construction exercises the stub.
        try {
            var constructor = TableQuery.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            // If the constructor changes signature during implementation, this will
            // guide the Code Writer to expose a factory or test-visible constructor.
            throw new RuntimeException("Cannot create TableQuery for testing", e);
        }
    }

    // ── Single predicate builders ───────────────────────────────────────

    @Test
    void testWhereEqBuildsEqPredicate() {
        TableQuery<String> q = newQuery();
        q.where("name").eq("Alice");
        assertInstanceOf(Predicate.Eq.class, q.predicate());
        var eq = (Predicate.Eq) q.predicate();
        assertEquals("name", eq.field());
        assertEquals("Alice", eq.value());
    }

    @Test
    void testWhereNeBuildsNePredicate() {
        TableQuery<String> q = newQuery();
        q.where("status").ne("deleted");
        assertInstanceOf(Predicate.Ne.class, q.predicate());
        var ne = (Predicate.Ne) q.predicate();
        assertEquals("status", ne.field());
        assertEquals("deleted", ne.value());
    }

    @Test
    void testWhereGtBuildsGtPredicate() {
        TableQuery<String> q = newQuery();
        q.where("age").gt(30);
        assertInstanceOf(Predicate.Gt.class, q.predicate());
    }

    @Test
    void testWhereGteBuildsGtePredicate() {
        TableQuery<String> q = newQuery();
        q.where("age").gte(30);
        assertInstanceOf(Predicate.Gte.class, q.predicate());
    }

    @Test
    void testWhereLtBuildsLtPredicate() {
        TableQuery<String> q = newQuery();
        q.where("age").lt(30);
        assertInstanceOf(Predicate.Lt.class, q.predicate());
    }

    @Test
    void testWhereLteBuildsLtePredicate() {
        TableQuery<String> q = newQuery();
        q.where("age").lte(30);
        assertInstanceOf(Predicate.Lte.class, q.predicate());
    }

    @Test
    void testWhereBetweenBuildsBetweenPredicate() {
        TableQuery<String> q = newQuery();
        q.where("age").between(20, 40);
        assertInstanceOf(Predicate.Between.class, q.predicate());
        var between = (Predicate.Between) q.predicate();
        assertEquals("age", between.field());
        assertEquals(20, between.low());
        assertEquals(40, between.high());
    }

    @Test
    void testWhereFullTextMatchBuildsPredicate() {
        TableQuery<String> q = newQuery();
        q.where("bio").fullTextMatch("java developer");
        assertInstanceOf(Predicate.FullTextMatch.class, q.predicate());
        var ftm = (Predicate.FullTextMatch) q.predicate();
        assertEquals("bio", ftm.field());
        assertEquals("java developer", ftm.query());
    }

    @Test
    void testWhereVectorNearestBuildsPredicate() {
        float[] vec = { 1.0f, 2.0f, 3.0f };
        TableQuery<String> q = newQuery();
        q.where("embedding").vectorNearest(vec, 5);
        assertInstanceOf(Predicate.VectorNearest.class, q.predicate());
        var vn = (Predicate.VectorNearest) q.predicate();
        assertEquals("embedding", vn.field());
        assertEquals(5, vn.topK());
    }

    // ── Chaining (and / or) ─────────────────────────────────────────────

    @Test
    void testAndChainsIntoAndPredicate() {
        TableQuery<String> q = newQuery();
        q.where("age").gt(30).and("status").eq("active");
        assertInstanceOf(Predicate.And.class, q.predicate());
        var and = (Predicate.And) q.predicate();
        assertEquals(2, and.children().size());
        assertInstanceOf(Predicate.Gt.class, and.children().get(0));
        assertInstanceOf(Predicate.Eq.class, and.children().get(1));
    }

    @Test
    void testOrChainsIntoOrPredicate() {
        TableQuery<String> q = newQuery();
        q.where("status").eq("active").or("status").eq("pending");
        assertInstanceOf(Predicate.Or.class, q.predicate());
        var or = (Predicate.Or) q.predicate();
        assertEquals(2, or.children().size());
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    void testPredicateReturnsNullBeforeWhere() {
        TableQuery<String> q = newQuery();
        assertNull(q.predicate(), "No predicates added yet — should be null");
    }

    @Test
    void testWhereRejectsNullField() {
        TableQuery<String> q = newQuery();
        assertThrows(NullPointerException.class, () -> q.where(null));
    }
}
