package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jlsm.table.internal.FieldIndex;
import jlsm.table.internal.FieldValueCodec;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.QueryExecutor;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for table-indices-and-queries audit round 1. Each test targets a specific
 * finding from spec-analysis.md.
 */
class TableIndicesAdversarialTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private static <T> List<T> collect(Iterator<T> it) {
        var result = new ArrayList<T>();
        it.forEachRemaining(result::add);
        return result;
    }

    private static int compareBytewise(MemorySegment a, MemorySegment b) {
        long len = Math.min(a.byteSize(), b.byteSize());
        for (long i = 0; i < len; i++) {
            int cmp = Byte.toUnsignedInt(a.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i))
                    - Byte.toUnsignedInt(b.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i));
            if (cmp != 0)
                return cmp;
        }
        return Long.compare(a.byteSize(), b.byteSize());
    }

    // ── FLOAT16 sort-order mismatch (IMPL-RISK) ─────────────────────────
    // Finding: FieldIndex.inferFieldType maps Short→INT16, but FLOAT16 fields
    // return Short values. INT16 encoding gives wrong sort order for negatives.

    @Test
    void testFloat16SortOrderNegativeValues() {
        // -2.0 should sort before -0.5 in natural order
        short neg2 = Float16.fromFloat(-2.0f);
        short negHalf = Float16.fromFloat(-0.5f);

        // Encode using FLOAT16 (correct) encoding
        MemorySegment neg2Float16 = FieldValueCodec.encode(neg2, FieldType.Primitive.FLOAT16);
        MemorySegment negHalfFloat16 = FieldValueCodec.encode(negHalf, FieldType.Primitive.FLOAT16);

        assertTrue(compareBytewise(neg2Float16, negHalfFloat16) < 0,
                "FLOAT16 encoding: -2.0 should sort before -0.5");

        // Demonstrate the divergence: INT16 encoding gives WRONG order for negative float16
        // This is the bug that FieldIndex.resolveFieldType fixes by using schema FieldType
        // instead of inferring from the Java runtime type (Short → INT16).
        MemorySegment neg2Int16 = FieldValueCodec.encode(neg2, FieldType.Primitive.INT16);
        MemorySegment negHalfInt16 = FieldValueCodec.encode(negHalf, FieldType.Primitive.INT16);

        // INT16 encoding INCORRECTLY puts -0.5 before -2.0 (this is the known divergence)
        assertTrue(compareBytewise(neg2Int16, negHalfInt16) > 0,
                "INT16 encoding should give WRONG sort order for negative float16 values "
                        + "(this documents the divergence that the FieldIndex fix addresses)");
    }

    @Test
    void testFloat16RangeQueryWithNegativeValues() throws IOException {
        // Attack: insert negative float16 values into a RANGE index and verify
        // range queries return correct results. With explicit FLOAT16 FieldType,
        // the encoding uses IEEE 754 sort-preserving encoding instead of INT16.
        var def = new IndexDefinition("temp", IndexType.RANGE);
        var index = new FieldIndex(def, FieldType.Primitive.FLOAT16);

        short neg3 = Float16.fromFloat(-3.0f);
        short neg2 = Float16.fromFloat(-2.0f);
        short neg1 = Float16.fromFloat(-1.0f);
        short pos1 = Float16.fromFloat(1.0f);

        index.onInsert(stringKey("pk1"), neg3);
        index.onInsert(stringKey("pk2"), neg2);
        index.onInsert(stringKey("pk3"), neg1);
        index.onInsert(stringKey("pk4"), pos1);

        // Query: temp > -2.5 should return -2.0, -1.0, 1.0
        short negTwoPointFive = Float16.fromFloat(-2.5f);
        var results = collect(index.lookup(new Predicate.Gt("temp", negTwoPointFive)));
        assertEquals(3, results.size(),
                "Range query 'temp > -2.5' should find 3 entries (-2.0, -1.0, 1.0) "
                        + "but FLOAT16 values encoded as INT16 give wrong sort order");

        index.close();
    }

    // ── Unique index atomicity (IMPL-RISK) ──────────────────────────────
    // Finding: IndexRegistry.onInsert inserts into first unique index before
    // checking second. If second rejects, first has orphan entry.

    @Test
    void testMultipleUniqueIndicesAtomicity() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("email", FieldType.string())
                .field("username", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("email", IndexType.UNIQUE),
                new IndexDefinition("username", IndexType.UNIQUE));

        var registry = new IndexRegistry(schema, defs);

        // Insert first document — email=a@test, username=alice
        var doc1 = JlsmDocument.of(schema, "email", "a@test.com", "username", "alice");
        registry.onInsert(stringKey("pk1"), doc1);

        // Insert second document — different email, same username → should fail
        var doc2 = JlsmDocument.of(schema, "email", "b@test.com", "username", "alice");
        assertThrows(DuplicateKeyException.class, () -> registry.onInsert(stringKey("pk2"), doc2),
                "Should reject duplicate username");

        // After the failed insert, the email index should NOT contain "b@test.com"
        // (the first unique index inserted it before the second unique index rejected)
        var emailIndex = registry.findIndex(new Predicate.Eq("email", "b@test.com"));
        assertNotNull(emailIndex, "Email index should exist");
        var orphanResults = collect(emailIndex.lookup(new Predicate.Eq("email", "b@test.com")));
        assertEquals(0, orphanResults.size(),
                "Email index should not contain orphan entry 'b@test.com' after failed insert "
                        + "— the second unique index (username) rejected the insert, but the first "
                        + "unique index (email) already inserted the entry");

        registry.close();
    }

    // ── VectorNearest mutable array (IMPL-RISK) ─────────────────────────
    // Finding: Predicate.VectorNearest doesn't defensively copy float[].

    @Test
    void testVectorNearestQueryVectorNotDefensivelyCopied() {
        float[] original = { 1.0f, 2.0f, 3.0f };
        var vn = new Predicate.VectorNearest("embedding", original, 5);

        // Mutate the original array after construction
        original[0] = 999.0f;

        // The predicate's queryVector should be unaffected
        float[] stored = vn.queryVector();
        assertEquals(1.0f, stored[0], 0.0f,
                "VectorNearest should defensively copy the queryVector array. "
                        + "Mutating the original after construction corrupted the predicate.");
    }

    @Test
    void testVectorNearestAccessorReturnsDefensiveCopy() {
        float[] original = { 1.0f, 2.0f, 3.0f };
        var vn = new Predicate.VectorNearest("embedding", original, 5);

        // Mutate via the accessor
        vn.queryVector()[0] = 999.0f;

        // The internal state should be unaffected
        assertEquals(1.0f, vn.queryVector()[0], 0.0f,
                "VectorNearest.queryVector() should return a defensive copy. "
                        + "Mutating the returned array corrupted the predicate's internal state.");
    }

    // ── Between mixed types (CONTRACT-GAP) ──────────────────────────────
    // Finding: Between doesn't validate type consistency of low/high.

    // Updated by audit: Between constructor now validates type consistency eagerly at
    // construction time rather than deferring to lookup. The fix moved the guard earlier
    // in the call chain, which is the correct behavior per eager input validation rules.
    @Test
    void testBetweenMixedTypesAtConstructionTime() {
        // Construct Between with Integer low and Long high — should throw IAE eagerly
        assertThrows(IllegalArgumentException.class, () -> new Predicate.Between("age", 20, 40L),
                "Between with mixed types (Integer low, Long high) should throw IAE at construction");
    }

    // ── Between inverted range (CONTRACT-GAP) ───────────────────────────
    // Finding: Between(field, 100, 10) silently returns empty.

    @Test
    void testBetweenInvertedRangeCrashesInTreeMap() throws IOException {
        // Finding: Between doesn't validate low <= high. An inverted range crashes
        // with IAE from TreeMap.subMap (which requires fromKey <= toKey).
        // A fail-fast IAE at Between construction or FieldIndex.lookupBetween would
        // be preferable to an implementation-detail leak from TreeMap.
        var def = new IndexDefinition("age", IndexType.RANGE);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), 25);
        index.onInsert(stringKey("pk2"), 50);
        index.onInsert(stringKey("pk3"), 75);

        // Inverted range: low=100, high=10 — TreeMap.subMap throws IAE
        var between = new Predicate.Between("age", 100, 10);
        assertDoesNotThrow(() -> collect(index.lookup(between)),
                "Between with inverted range (low > high) should not crash "
                        + "— either validate at construction or return empty results gracefully");

        index.close();
    }

    // ── Ne excludes null field values (CONTRACT-GAP) ────────────────────
    // Finding: Ne scan-and-filter skips documents with null fields.

    @Test
    void testNeScanAndFilterExcludesNulls() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();

        // No index on "name" — forces scan-and-filter path
        var registry = new IndexRegistry(schema, List.of());

        var doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        var doc2 = JlsmDocument.of(schema, "name", null, "age", 25); // null name
        var doc3 = JlsmDocument.of(schema, "name", "Bob", "age", 35);

        registry.onInsert(stringKey("pk1"), doc1);
        registry.onInsert(stringKey("pk2"), doc2);
        registry.onInsert(stringKey("pk3"), doc3);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var results = collect(executor.execute(new Predicate.Ne("name", "Alice")));

        // Doc2 has name=null. Is null != "Alice" true or not?
        // SQL says: NULL != X is NULL (falsy) → doc2 excluded
        // Java says: null != "Alice" is true → doc2 included
        // Current implementation: excludes nulls (SQL-like behavior)
        // This test documents the current behavior as a known semantic choice.
        // If the API intends to be SQL-compatible, this is correct.
        // If it intends Java-like semantics, doc2 should be included.
        assertEquals(1, results.size(),
                "Ne scan-and-filter excludes null field values. "
                        + "This documents the current SQL-like null semantics: "
                        + "NULL != 'Alice' is NULL (falsy), so only Bob is returned.");
    }

    // ── Ne via index also excludes nulls (consistency check) ────────────

    @Test
    void testNeIndexBackedExcludesNulls() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        var doc2 = JlsmDocument.of(schema, "name", null, "age", 25);
        var doc3 = JlsmDocument.of(schema, "name", "Bob", "age", 35);

        registry.onInsert(stringKey("pk1"), doc1);
        registry.onInsert(stringKey("pk2"), doc2);
        registry.onInsert(stringKey("pk3"), doc3);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var results = collect(executor.execute(new Predicate.Ne("name", "Alice")));

        // Index-backed Ne should produce the same result as scan-and-filter
        assertEquals(1, results.size(),
                "Index-backed Ne should be consistent with scan-and-filter: "
                        + "only Bob is returned, null-named doc excluded");
    }

    // ── FieldIndex unique constraint on update to same value ────────────
    // Regression: update a unique field to the same value shouldn't throw.

    @Test
    void testUniqueIndexIdempotentUpdate() throws IOException {
        var def = new IndexDefinition("email", IndexType.UNIQUE);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), "alice@test.com");

        // Update pk1's email from alice@test.com to alice@test.com (no change)
        assertDoesNotThrow(
                () -> index.onUpdate(stringKey("pk1"), "alice@test.com", "alice@test.com"),
                "Updating a unique field to the same value should not throw DuplicateKeyException");

        // Verify the entry is still correctly indexed
        var results = collect(index.lookup(new Predicate.Eq("email", "alice@test.com")));
        assertEquals(1, results.size(),
                "Should still have exactly one entry after idempotent update");

        index.close();
    }

    // ── Multiple unique indices atomicity on update (fix-forward) ──────
    // Same atomicity pattern as onInsert, found via fix-forward scan.

    @Test
    void testMultipleUniqueIndicesAtomicityOnUpdate() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("email", FieldType.string())
                .field("username", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("email", IndexType.UNIQUE),
                new IndexDefinition("username", IndexType.UNIQUE));

        var registry = new IndexRegistry(schema, defs);

        // Insert two documents
        var doc1 = JlsmDocument.of(schema, "email", "a@test.com", "username", "alice");
        var doc2 = JlsmDocument.of(schema, "email", "b@test.com", "username", "bob");
        registry.onInsert(stringKey("pk1"), doc1);
        registry.onInsert(stringKey("pk2"), doc2);

        // Update pk1's email to new value but username to bob's value → should fail
        var updatedDoc = JlsmDocument.of(schema, "email", "c@test.com", "username", "bob");
        assertThrows(DuplicateKeyException.class,
                () -> registry.onUpdate(stringKey("pk1"), doc1, updatedDoc),
                "Should reject update that would violate username uniqueness");

        // After the failed update, email index should NOT have "c@test.com"
        var emailIndex = registry.findIndex(new Predicate.Eq("email", "c@test.com"));
        assertNotNull(emailIndex, "Email index should exist");
        var orphanResults = collect(emailIndex.lookup(new Predicate.Eq("email", "c@test.com")));
        assertEquals(0, orphanResults.size(),
                "Email index should not contain orphan entry 'c@test.com' after failed update "
                        + "— username unique violation should roll back all index changes");

        // Original entries should be unchanged
        var emailA = collect(registry.findIndex(new Predicate.Eq("email", "a@test.com"))
                .lookup(new Predicate.Eq("email", "a@test.com")));
        assertEquals(1, emailA.size(), "Original email 'a@test.com' should still be indexed");

        registry.close();
    }

    // ── FieldValueCodec Float32 NaN encoding ────────────────────────────

    @Test
    void testFloat32NaNRoundTrip() {
        // NaN should round-trip correctly through encode/decode
        MemorySegment encoded = FieldValueCodec.encode(Float.NaN, FieldType.Primitive.FLOAT32);
        float decoded = (float) FieldValueCodec.decode(encoded, FieldType.Primitive.FLOAT32);
        assertTrue(Float.isNaN(decoded), "NaN should round-trip through FLOAT32 codec");
    }

    @Test
    void testFloat64NaNRoundTrip() {
        MemorySegment encoded = FieldValueCodec.encode(Double.NaN, FieldType.Primitive.FLOAT64);
        double decoded = (double) FieldValueCodec.decode(encoded, FieldType.Primitive.FLOAT64);
        assertTrue(Double.isNaN(decoded), "NaN should round-trip through FLOAT64 codec");
    }
}
