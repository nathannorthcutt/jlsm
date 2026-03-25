package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.QueryExecutor;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for table-indices-and-queries audit round 2. Targets two unfixed findings from
 * round 1 spec analysis: decodeKey hardcodes String, and scan-and-filter unchecked compareTo.
 */
class TableIndicesRound2AdversarialTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED
            .withOrder(ByteOrder.BIG_ENDIAN);

    private static MemorySegment longKey(long value) {
        MemorySegment seg = Arena.ofAuto().allocate(8);
        seg.set(BE_LONG, 0, value ^ Long.MIN_VALUE);
        return seg;
    }

    private static <T> List<T> collect(Iterator<T> it) {
        var result = new ArrayList<T>();
        it.forEachRemaining(result::add);
        return result;
    }

    // ── DECODE-KEY-ALWAYS-STRING (IMPL-RISK) ─────────────────────────────
    // Finding: QueryExecutor.decodeKey always interprets primary key bytes as
    // UTF-8 String. For Long-keyed tables, this produces garbage String keys
    // that throw ClassCastException when the caller accesses them as Long.

    @Test
    void testQueryExecutorDecodeKeyHardcodesString() throws IOException {
        // Setup: schema with a name field, equality index on name
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        // Insert with a Long-encoded primary key (simulating LongKeyedTable)
        var doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(longKey(42L), doc);

        // Execute with a Long key decoder (the fix: 3-arg constructor)
        java.util.function.Function<MemorySegment, Long> longDecoder = pk -> pk.get(BE_LONG, 0)
                ^ Long.MIN_VALUE;
        var executor = new QueryExecutor<Long>(schema, registry, longDecoder);
        var results = collect(executor.execute(new Predicate.Eq("name", "Alice")));

        assertEquals(1, results.size(), "Should find one matching entry");

        // The key should be decodable as Long without ClassCastException.
        // Before the fix, decodeKey hardcoded String interpretation, causing
        // ClassCastException when caller accessed the key as Long.
        assertDoesNotThrow(() -> {
            Long key = results.getFirst().key();
            assertEquals(42L, key, "Decoded Long key should match the original value");
        }, "QueryExecutor<Long> with Long key decoder should return correct Long keys. "
                + "Before fix: decodeKey(MemorySegment) hardcoded new String(bytes, UTF_8)");
    }

    // ── SCAN-FILTER-UNCHECKED-COMPARETO (IMPL-RISK) ─────────────────────
    // Finding: QueryExecutor.matchesPredicate does unchecked compareTo calls
    // that throw ClassCastException when field value type differs from predicate
    // value type (e.g., Integer field value vs Long predicate value).

    @Test
    void testScanAndFilterGtTypeMismatch() throws IOException {
        // Setup: INT32 field with no index → forces scan-and-filter path
        var schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()).build();
        var registry = new IndexRegistry(schema, List.of());

        var doc = JlsmDocument.of(schema, "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = new QueryExecutor<String>(schema, registry);

        // Pass Long value for INT32 field — scan-and-filter will do
        // Integer(30).compareTo(Long(25L)) → ClassCastException
        assertDoesNotThrow(() -> collect(executor.execute(new Predicate.Gt("age", 25L))),
                "Scan-and-filter Gt with Long predicate on INT32 field should not throw "
                        + "ClassCastException. Integer.compareTo(Long) is unchecked.");

        registry.close();
    }

    @Test
    void testScanAndFilterGteTypeMismatch() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()).build();
        var registry = new IndexRegistry(schema, List.of());

        var doc = JlsmDocument.of(schema, "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = new QueryExecutor<String>(schema, registry);
        assertDoesNotThrow(() -> collect(executor.execute(new Predicate.Gte("age", 25L))),
                "Scan-and-filter Gte with Long predicate on INT32 field should not crash");

        registry.close();
    }

    @Test
    void testScanAndFilterLtTypeMismatch() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()).build();
        var registry = new IndexRegistry(schema, List.of());

        var doc = JlsmDocument.of(schema, "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = new QueryExecutor<String>(schema, registry);
        assertDoesNotThrow(() -> collect(executor.execute(new Predicate.Lt("age", 50L))),
                "Scan-and-filter Lt with Long predicate on INT32 field should not crash");

        registry.close();
    }

    @Test
    void testScanAndFilterLteTypeMismatch() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()).build();
        var registry = new IndexRegistry(schema, List.of());

        var doc = JlsmDocument.of(schema, "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = new QueryExecutor<String>(schema, registry);
        assertDoesNotThrow(() -> collect(executor.execute(new Predicate.Lte("age", 50L))),
                "Scan-and-filter Lte with Long predicate on INT32 field should not crash");

        registry.close();
    }

    @Test
    void testScanAndFilterBetweenTypeMismatch() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()).build();
        var registry = new IndexRegistry(schema, List.of());

        var doc = JlsmDocument.of(schema, "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = new QueryExecutor<String>(schema, registry);

        // Between with Long bounds on INT32 field
        assertDoesNotThrow(() -> collect(executor.execute(new Predicate.Between("age", 20L, 40L))),
                "Scan-and-filter Between with Long bounds on INT32 field should not crash");

        registry.close();
    }

    // ── SCAN-FILTER-TYPE-MISMATCH semantic correctness ──────────────────
    // After fix: mismatched types should produce empty results (no match),
    // not exceptions.

    @Test
    void testScanAndFilterTypeMismatchReturnsNoResults() throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("age", FieldType.int32()).build();
        var registry = new IndexRegistry(schema, List.of());

        var doc = JlsmDocument.of(schema, "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = new QueryExecutor<String>(schema, registry);

        // Long predicate on INT32 field — types don't match, should return empty
        var results = collect(executor.execute(new Predicate.Gt("age", 25L)));
        assertEquals(0, results.size(),
                "Type-mismatched predicates should return empty results, not crash. "
                        + "Integer(30) and Long(25) are incomparable.");

        registry.close();
    }
}
