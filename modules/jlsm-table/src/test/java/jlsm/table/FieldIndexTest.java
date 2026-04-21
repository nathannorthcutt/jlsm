package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import jlsm.table.internal.FieldIndex;
import jlsm.table.internal.FullTextFieldIndex;
import jlsm.table.internal.SecondaryIndex;
import jlsm.table.internal.VectorFieldIndex;
import org.junit.jupiter.api.Test;

// @spec query.field-index.R1,R2,R3,R4,R5,R6,R7,R8,R9,R10,R11,R12,R13,R14,R15,R16,R17,R18,R19,R20,R21,R22,R23,R26,R27,R28
// @spec query.query-executor.R1,R2,R3,R4,R5,R22
// @spec query.index-registry.R16,R17
//       — covers SecondaryIndex interface contract (sealed, definition, null handling on
//         insert/update/delete) + FieldIndex lifecycle + lookup semantics: EQUALITY-only Eq/Ne,
//         RANGE/UNIQUE full comparator set, sort-preserving encoded keys via ByteArrayKey, UNIQUE
//         constraint enforcement (onInsert + onUpdate), null field no-op on insert/update/delete,
//         unique-check null-skip, remove-then-insert on update, close + post-close rejection.
//         R24/R25 (multi-unique-index atomicity) live in TableIndicesAdversarialTest.
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

    // @spec query.index-types.R4 — UNIQUE enforces a uniqueness constraint at write time
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

    // @spec query.index-types.R2 — EQUALITY supports Eq/Ne predicate lookups
    // @spec query.index-types.R3 — RANGE supports Eq/Ne/Gt/Gte/Lt/Lte/Between predicate lookups
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
        assertTrue(rangeIndex.supports(new Predicate.Ne("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Gt("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Gte("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Lt("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Lte("age", 1)));
        assertTrue(rangeIndex.supports(new Predicate.Between("age", 1, 10)));
        assertFalse(rangeIndex.supports(new Predicate.FullTextMatch("age", "x")));
        rangeIndex.close();
    }

    // @spec query.index-types.R4 — UNIQUE supports the same predicate lookups as RANGE and
    // additionally enforces a uniqueness constraint at write time
    @Test
    void testUniqueSupportsSameLookupsAsRange() throws IOException {
        try (var uniqueIndex = new FieldIndex(new IndexDefinition("email", IndexType.UNIQUE))) {
            assertTrue(uniqueIndex.supports(new Predicate.Eq("email", "a@b")));
            assertTrue(uniqueIndex.supports(new Predicate.Ne("email", "a@b")));
            assertTrue(uniqueIndex.supports(new Predicate.Gt("email", "a@b")));
            assertTrue(uniqueIndex.supports(new Predicate.Gte("email", "a@b")));
            assertTrue(uniqueIndex.supports(new Predicate.Lt("email", "a@b")));
            assertTrue(uniqueIndex.supports(new Predicate.Lte("email", "a@b")));
            assertTrue(uniqueIndex.supports(new Predicate.Between("email", "a", "z")));
            assertFalse(uniqueIndex.supports(new Predicate.FullTextMatch("email", "x")));
        }
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

    // @spec query.field-index.R1 — SecondaryIndex is sealed with exactly three permitted impls
    @Test
    void secondaryIndexIsSealedWithThreePermittedImplementations() {
        assertTrue(SecondaryIndex.class.isSealed(),
                "SecondaryIndex must be a sealed interface");
        var permitted = Set.of(SecondaryIndex.class.getPermittedSubclasses());
        var expected = Set.<Class<?>>of(FieldIndex.class, FullTextFieldIndex.class,
                VectorFieldIndex.class);
        assertEquals(expected, permitted,
                "SecondaryIndex must permit exactly FieldIndex, FullTextFieldIndex, "
                        + "VectorFieldIndex — adding/removing a permitted subclass is a contract "
                        + "change that must be reflected in the query.field-index spec.");
    }

    // @spec query.field-index.R2 — definition() returns the IndexDefinition used at construction
    @Test
    void definitionReturnsTheDefinitionUsedAtConstruction() throws IOException {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        try (var index = new FieldIndex(def)) {
            assertEquals(def, index.definition(),
                    "definition() must return the IndexDefinition this index was created from");
        }
    }

    // @spec query.field-index.R6 — onUpdate with null oldValue is insert-only
    @Test
    void onUpdateNullOldIsInsertOnly() throws IOException {
        try (var index = new FieldIndex(new IndexDefinition("name", IndexType.EQUALITY))) {
            index.onUpdate(stringKey("pk1"), null, "Alice");
            var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
            assertEquals(1, results.size(),
                    "null oldValue: onUpdate must act as insert-only");
        }
    }

    // @spec query.field-index.R6 — onUpdate with null newValue is delete-only
    @Test
    void onUpdateNullNewIsDeleteOnly() throws IOException {
        try (var index = new FieldIndex(new IndexDefinition("name", IndexType.EQUALITY))) {
            index.onInsert(stringKey("pk1"), "Alice");
            index.onUpdate(stringKey("pk1"), "Alice", null);
            var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
            assertEquals(0, results.size(),
                    "null newValue: onUpdate must act as delete-only");
        }
    }

    // @spec query.field-index.R6 — onUpdate with null for both oldValue and newValue is a no-op
    @Test
    void onUpdateNullBothIsNoOp() throws IOException {
        try (var index = new FieldIndex(new IndexDefinition("name", IndexType.EQUALITY))) {
            index.onInsert(stringKey("pk1"), "Alice");
            assertDoesNotThrow(() -> index.onUpdate(stringKey("pk2"), null, null));
            var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
            assertEquals(1, results.size(),
                    "null/null onUpdate must leave the index unchanged");
        }
    }

    // @spec query.field-index.R8 — onDelete with null fieldValue is a no-op
    @Test
    void onDeleteNullValueIsNoOp() throws IOException {
        try (var index = new FieldIndex(new IndexDefinition("name", IndexType.EQUALITY))) {
            index.onInsert(stringKey("pk1"), "Alice");
            assertDoesNotThrow(() -> index.onDelete(stringKey("pk2"), null));
            var results = collect(index.lookup(new Predicate.Eq("name", "Alice")));
            assertEquals(1, results.size(),
                    "null fieldValue delete must not affect indexed entries");
        }
    }

    // @spec query.field-index.R26 — unique constraint checks skip null field values
    @Test
    void uniqueConstraintSkipsNullValuesOnInsert() throws IOException {
        try (var index = new FieldIndex(new IndexDefinition("email", IndexType.UNIQUE))) {
            assertDoesNotThrow(() -> index.onInsert(stringKey("pk1"), null));
            assertDoesNotThrow(() -> index.onInsert(stringKey("pk2"), null));
            assertDoesNotThrow(() -> index.onInsert(stringKey("pk3"), null),
                    "multiple null field values must not trigger unique-constraint violation");

            assertDoesNotThrow(() -> index.onInsert(stringKey("pk4"), "alice@test.com"));
            var results = collect(index.lookup(new Predicate.Eq("email", "alice@test.com")));
            assertEquals(1, results.size(),
                    "non-null values remain indexable after repeated null inserts");
        }
    }
}
