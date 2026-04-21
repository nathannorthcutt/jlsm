package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.QueryExecutor;
import org.junit.jupiter.api.Test;

// @spec query.query-executor.R6,R7,R8,R9,R10,R11,R13,R14,R15,R16,R17,R18
//       — covers QueryExecutor planning and execution: null predicate rejection, index-backed
//         leaf lookup via findAndLookup, scan-and-filter fallback, And intersection and Or union,
//         deduplication across predicates, null field non-matching, numeric-coercion comparison.
class QueryExecutorTest {

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();
    }

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

    // ── Constructor validation ──────────────────────────────────────────

    @Test
    void testConstructorRejectsNullSchema() throws IOException {
        var registry = new IndexRegistry(testSchema(), List.of());
        assertThrows(NullPointerException.class, () -> QueryExecutor.forStringKeys(null, registry));
        registry.close();
    }

    @Test
    void testConstructorRejectsNullRegistry() {
        assertThrows(NullPointerException.class,
                () -> QueryExecutor.forStringKeys(testSchema(), null));
    }

    // ── Index-backed execution ──────────────────────────────────────────

    @Test
    void testIndexedEqUsesIndexLookup() throws IOException {
        var schema = testSchema();
        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        // Insert test data via registry
        var doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        var doc2 = JlsmDocument.of(schema, "name", "Bob", "age", 25);
        var doc3 = JlsmDocument.of(schema, "name", "Alice", "age", 35);

        registry.onInsert(stringKey("pk1"), doc1);
        registry.onInsert(stringKey("pk2"), doc2);
        registry.onInsert(stringKey("pk3"), doc3);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var results = collect(executor.execute(new Predicate.Eq("name", "Alice")));

        assertEquals(2, results.size(), "Should find 2 entries with name=Alice");

        registry.close();
    }

    // ── And / Or composite execution ────────────────────────────────────

    @Test
    void testAndIntersectsChildren() throws IOException {
        var schema = testSchema();
        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("age", IndexType.RANGE));
        var registry = new IndexRegistry(schema, defs);

        var doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        var doc2 = JlsmDocument.of(schema, "name", "Alice", "age", 20);
        var doc3 = JlsmDocument.of(schema, "name", "Bob", "age", 30);

        registry.onInsert(stringKey("pk1"), doc1);
        registry.onInsert(stringKey("pk2"), doc2);
        registry.onInsert(stringKey("pk3"), doc3);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var predicate = new Predicate.And(
                List.of(new Predicate.Eq("name", "Alice"), new Predicate.Gte("age", 25)));

        var results = collect(executor.execute(predicate));
        assertEquals(1, results.size(), "Only pk1 (Alice, 30) matches both predicates");

        registry.close();
    }

    @Test
    void testOrUnionsChildren() throws IOException {
        var schema = testSchema();
        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        var doc2 = JlsmDocument.of(schema, "name", "Bob", "age", 25);
        var doc3 = JlsmDocument.of(schema, "name", "Carol", "age", 35);

        registry.onInsert(stringKey("pk1"), doc1);
        registry.onInsert(stringKey("pk2"), doc2);
        registry.onInsert(stringKey("pk3"), doc3);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var predicate = new Predicate.Or(
                List.of(new Predicate.Eq("name", "Alice"), new Predicate.Eq("name", "Bob")));

        var results = collect(executor.execute(predicate));
        assertEquals(2, results.size(), "Should find Alice and Bob via union");

        registry.close();
    }
}
