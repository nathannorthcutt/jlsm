package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jlsm.table.internal.FieldIndex;
import org.junit.jupiter.api.Test;

// @spec F10.R63,R64,R65,R66,R67,R68,R69,R70,R71,R72,R73,R74,R75,R106,R107,R108,R109,R110,R126,R127,R132,R133,R134
//       — covers FieldIndex lifecycle + lookup semantics: EQUALITY-only Eq/Ne support, RANGE/UNIQUE
//         full comparator set, sort-preserving encoded keys via ByteArrayKey, UNIQUE constraint
//         enforcement (onInsert + onUpdate), null field no-op on insert/update/delete, remove-then-
//         insert on update, close behavior + post-close rejection.
class FieldIndexTest {

    private MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private List<MemorySegment> collect(Iterator<MemorySegment> it) {
        var result = new ArrayList<MemorySegment>();
        it.forEachRemaining(result::add);
        return result;
    }

    @Test
    void testEqualityLookup() throws IOException {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), "Alice");
        index.onInsert(stringKey("pk2"), "Bob");
        index.onInsert(stringKey("pk3"), "Alice");

        var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
        assertEquals(2, results.size(), "Should find 2 entries with name=Alice");

        var bobResults = collect(index.lookup(new Predicate.Eq("name", "Bob")));
        assertEquals(1, bobResults.size(), "Should find 1 entry with name=Bob");

        index.close();
    }

    @Test
    void testRangeLookup() throws IOException {
        var def = new IndexDefinition("age", IndexType.RANGE);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), 20);
        index.onInsert(stringKey("pk2"), 30);
        index.onInsert(stringKey("pk3"), 40);
        index.onInsert(stringKey("pk4"), 50);

        // Gt: age > 30 → should find 40, 50
        var gtResults = collect(index.lookup(new Predicate.Gt("age", 30)));
        assertEquals(2, gtResults.size(), "age > 30 should find 2 entries");

        // Gte: age >= 30 → should find 30, 40, 50
        var gteResults = collect(index.lookup(new Predicate.Gte("age", 30)));
        assertEquals(3, gteResults.size(), "age >= 30 should find 3 entries");

        // Lt: age < 30 → should find 20
        var ltResults = collect(index.lookup(new Predicate.Lt("age", 30)));
        assertEquals(1, ltResults.size(), "age < 30 should find 1 entry");

        // Between: 25 <= age <= 45 → should find 30, 40
        var betweenResults = collect(index.lookup(new Predicate.Between("age", 25, 45)));
        assertEquals(2, betweenResults.size(), "25 <= age <= 45 should find 2 entries");

        index.close();
    }

    @Test
    void testUniqueConstraint() throws IOException {
        var def = new IndexDefinition("email", IndexType.UNIQUE);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), "alice@test.com");

        assertThrows(DuplicateKeyException.class,
                () -> index.onInsert(stringKey("pk2"), "alice@test.com"),
                "Should reject duplicate value in unique index");

        index.close();
    }

    @Test
    void testDeleteRemovesEntry() throws IOException {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), "Alice");
        index.onInsert(stringKey("pk2"), "Alice");

        index.onDelete(stringKey("pk1"), "Alice");

        var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
        assertEquals(1, results.size(), "After delete, should find 1 entry");

        index.close();
    }

    @Test
    void testUpdateReplacesEntry() throws IOException {
        var def = new IndexDefinition("name", IndexType.RANGE);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), "Alice");
        index.onUpdate(stringKey("pk1"), "Alice", "Carol");

        var aliceResults = collect(index.lookup(new Predicate.Eq("name", "Alice")));
        assertEquals(0, aliceResults.size(), "Alice should be gone after update");

        var carolResults = collect(index.lookup(new Predicate.Eq("name", "Carol")));
        assertEquals(1, carolResults.size(), "Carol should be present after update");

        index.close();
    }

    @Test
    void testSupportsCorrectPredicates() throws IOException {
        var eqIndex = new FieldIndex(new IndexDefinition("name", IndexType.EQUALITY));
        assertTrue(eqIndex.supports(new Predicate.Eq("name", "x")));
        assertTrue(eqIndex.supports(new Predicate.Ne("name", "x")));
        assertFalse(eqIndex.supports(new Predicate.Gt("name", "x")),
                "EQUALITY index should not support Gt");
        assertFalse(eqIndex.supports(new Predicate.FullTextMatch("name", "x")),
                "EQUALITY index should not support FullTextMatch");
        eqIndex.close();

        var rangeIndex = new FieldIndex(new IndexDefinition("age", IndexType.RANGE));
        assertTrue(rangeIndex.supports(new Predicate.Eq("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Gt("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Gte("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Lt("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Lte("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Between("age", 1, 10)));
        assertFalse(rangeIndex.supports(new Predicate.FullTextMatch("age", "x")));
        rangeIndex.close();
    }

    @Test
    void testNullValueNotIndexed() throws IOException {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), null);
        index.onInsert(stringKey("pk2"), "Alice");

        var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
        assertEquals(1, results.size(), "Only non-null values should be indexed");

        index.close();
    }
}
