package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jlsm.table.internal.FieldIndex;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.QueryExecutor;
import jlsm.table.internal.RangeMap;
import jlsm.table.internal.VectorFieldIndex;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for dispatch_routing domain lens findings.
 */
class DispatchRoutingAdversarialTest {

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: FieldIndex.lookup() silently returns empty iterator for unsupported predicates
    // (FullTextMatch, VectorNearest, And, Or) via default branch instead of failing fast
    // Correct behavior: lookup() should throw UnsupportedOperationException for predicates
    // the index cannot handle, so dispatch errors are detected immediately
    // Fix location: FieldIndex.java:138 — default branch of switch in lookup()
    // Regression watch: Ensure supports() is still checked before lookup() in QueryExecutor;
    // the throw is a safety net, not the primary gate
    @Test
    void test_FieldIndex_lookup_unsupportedPredicate_throwsInsteadOfSilentEmpty()
            throws IOException {
        var def = new IndexDefinition("name", IndexType.EQUALITY);
        var index = new FieldIndex(def);

        index.onInsert(stringKey("pk1"), "Alice");

        // FullTextMatch is not supported by an EQUALITY index — lookup must not silently
        // return empty; it must throw to signal a dispatch error.
        var fullTextPredicate = new Predicate.FullTextMatch("name", "Alice");
        assertThrows(UnsupportedOperationException.class, () -> index.lookup(fullTextPredicate),
                "lookup() with unsupported FullTextMatch predicate should throw, not return empty");

        index.close();
    }

    // Finding: F-R1.dispatch_routing.1.3
    // Bug: VectorFieldIndex.supports() returns true for VectorNearest but lookup() throws
    // UnsupportedOperationException — broken supports/lookup contract
    // Correct behavior: supports() must return false while lookup() is unimplemented,
    // so QueryExecutor falls back to scan-and-filter instead of crashing
    // Fix location: VectorFieldIndex.java:75-78 — supports() method
    // Regression watch: When lookup() is implemented, supports() must be updated to return true
    @Test
    void test_VectorFieldIndex_supports_returnsFalseWhileLookupUnimplemented() throws IOException {
        var def = new IndexDefinition("embedding", IndexType.VECTOR,
                jlsm.core.indexing.SimilarityFunction.COSINE);
        var index = new VectorFieldIndex(def);

        var predicate = new Predicate.VectorNearest("embedding", new float[]{ 1.0f, 2.0f }, 5);

        // supports() must return false because lookup() throws UnsupportedOperationException.
        // If supports() returns true, QueryExecutor.executeLeaf() will call lookup() and crash.
        assertFalse(index.supports(predicate),
                "supports() must return false while lookup() is not implemented");

        index.close();
    }

    // Finding: F-R1.dispatch_routing.1.4
    // Bug: QueryExecutor.matchesPredicate() silently returns false for FullTextMatch predicates
    // in scan-and-filter, causing queries to return empty results instead of failing
    // Correct behavior: matchesPredicate() should throw UnsupportedOperationException for
    // FullTextMatch since it requires an index and cannot be evaluated by scan
    // Fix location: QueryExecutor.java:191 — case Predicate.FullTextMatch returns false
    // Regression watch: Ensure index-backed FullTextMatch queries still work when a FULL_TEXT
    // index is present; only the scan-and-filter fallback path should throw
    @Test
    void test_QueryExecutor_scanAndFilter_fullTextMatch_throwsInsteadOfSilentEmpty()
            throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();

        // No FULL_TEXT index — only an EQUALITY index on "name".
        // This forces the FullTextMatch predicate to fall through to scan-and-filter.
        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var predicate = new Predicate.FullTextMatch("name", "Alice");

        // FullTextMatch with no supporting index must throw, not silently return empty.
        assertThrows(UnsupportedOperationException.class, () -> executor.execute(predicate),
                "FullTextMatch in scan-and-filter should throw UnsupportedOperationException");

        registry.close();
    }

    // Finding: F-R1.dispatch_routing.1.5
    // Bug: QueryExecutor.matchesPredicate() silently returns false for VectorNearest predicates
    // in scan-and-filter, causing queries to return empty results instead of failing
    // Correct behavior: matchesPredicate() should throw UnsupportedOperationException for
    // VectorNearest since it requires a VECTOR index and cannot be evaluated by scan
    // Fix location: QueryExecutor.java:195-198 — case Predicate.VectorNearest throws
    // Regression watch: Ensure index-backed VectorNearest queries still work when a VECTOR
    // index is present; only the scan-and-filter fallback path should throw
    @Test
    void test_QueryExecutor_scanAndFilter_vectorNearest_throwsInsteadOfSilentEmpty()
            throws IOException {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("embedding", FieldType.Primitive.FLOAT32).build();

        // No VECTOR index — only an EQUALITY index on "name".
        // This forces the VectorNearest predicate to fall through to scan-and-filter.
        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var doc = JlsmDocument.of(schema, "name", "Alice", "embedding", 1.0f);
        registry.onInsert(stringKey("pk1"), doc);

        var executor = QueryExecutor.forStringKeys(schema, registry);
        var predicate = new Predicate.VectorNearest("embedding", new float[]{ 1.0f, 2.0f }, 5);

        // VectorNearest with no supporting index must throw, not silently return empty.
        assertThrows(UnsupportedOperationException.class, () -> executor.execute(predicate),
                "VectorNearest in scan-and-filter should throw UnsupportedOperationException");

        registry.close();
    }

    // Finding: F-R1.dispatch_routing.1.6
    // Bug: matchesPredicate() returns false for And/Or predicates instead of recursively
    // evaluating children — dead code today but silently wrong if dispatch changes
    // Correct behavior: And should return true only when all children match;
    // Or should return true when any child matches
    // Fix location: QueryExecutor.java — case Predicate.And _, Predicate.Or _ -> false
    // Regression watch: Ensure executePredicate() still handles And/Or at the top level
    @Test
    void test_QueryExecutor_matchesPredicate_andOrBranch_evaluatesChildrenRecursively()
            throws Exception {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        var pk = stringKey("pk1");
        registry.onInsert(pk, doc);

        var executor = QueryExecutor.forStringKeys(schema, registry);

        // Build a compound And predicate whose children both match the inserted document.
        var andPredicate = new Predicate.And(
                List.of(new Predicate.Eq("name", "Alice"), new Predicate.Eq("age", 30)));

        // Use reflection to invoke the private matchesPredicate method directly,
        // bypassing executePredicate which intercepts And/Or before they reach this path.
        Method matchesPredicate = QueryExecutor.class.getDeclaredMethod("matchesPredicate",
                IndexRegistry.StoredEntry.class, Predicate.class);
        matchesPredicate.setAccessible(true);

        var entry = new IndexRegistry.StoredEntry(pk, doc);
        boolean result = (boolean) matchesPredicate.invoke(executor, entry, andPredicate);

        // The And predicate's children both match — matchesPredicate must return true.
        // Bug: currently returns false unconditionally for And/Or.
        assertTrue(result,
                "matchesPredicate must recursively evaluate And children, not return false");

        registry.close();
    }

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: validateType VectorType else-branch assumes FLOAT16 without explicit check —
    // if a VectorType with an unsupported elementType reaches validateType, it silently
    // applies FLOAT16 validation instead of throwing
    // Correct behavior: else-branch should explicitly check for FLOAT16 and throw on unknown
    // Fix location: JlsmDocument.java:475 — else branch in VectorType case
    // Regression watch: Ensure FLOAT16 and FLOAT32 validation still works correctly
    @Test
    void test_validateType_vectorType_unsupportedElementType_throwsInsteadOfAssumingFloat16()
            throws Exception {
        // Use jdk.internal.misc.Unsafe to allocate a VectorType without running the
        // constructor, then set fields directly. This bypasses the compact constructor's
        // elementType restriction, simulating what happens if the constraint is relaxed.
        var unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
        var getUnsafe = unsafeClass.getMethod("getUnsafe");
        var unsafe = getUnsafe.invoke(null);

        // allocateInstance creates an object without running any constructor
        var allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        var badVectorType = (FieldType.VectorType) allocateInstance.invoke(unsafe,
                FieldType.VectorType.class);

        // objectFieldOffset(Class, String) — available in jdk.internal.misc.Unsafe
        var objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Class.class,
                String.class);
        var putReference = unsafeClass.getMethod("putReference", Object.class, long.class,
                Object.class);
        var putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);

        long etOffset = (long) objectFieldOffset.invoke(unsafe, FieldType.VectorType.class,
                "elementType");
        putReference.invoke(unsafe, badVectorType, etOffset, FieldType.Primitive.INT32);

        long dimOffset = (long) objectFieldOffset.invoke(unsafe, FieldType.VectorType.class,
                "dimensions");
        putInt.invoke(unsafe, badVectorType, dimOffset, 2);

        // Verify our bypass worked
        assert badVectorType.elementType() == FieldType.Primitive.INT32
                : "Expected INT32 but got " + badVectorType.elementType();
        assert badVectorType.dimensions() == 2;

        // Invoke the private validateType method directly
        var validateType = JlsmDocument.class.getDeclaredMethod("validateType", String.class,
                FieldType.class, Object.class);
        validateType.setAccessible(true);

        // Pass a short[] (what FLOAT16 expects) — the bug would silently validate this
        // as FLOAT16 even though the elementType is INT32
        short[] data = new short[]{ 0x3C00, 0x4000 };

        // Should throw because INT32 is not a recognized VectorType elementType.
        // Currently the else-branch silently assumes FLOAT16 and accepts the short[].
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> validateType.invoke(null, "vec", badVectorType, data));
        assertInstanceOf(AssertionError.class, ex.getCause(),
                "validateType should throw AssertionError for unsupported VectorType elementType");
    }

    // Finding: F-R1.dispatch_routing.1.4
    // Bug: validateType ArrayType recursion has no depth limit — StackOverflowError on deeply
    // nested ArrayType
    // Correct behavior: validateType should throw IllegalArgumentException when nesting depth
    // exceeds a safe limit
    // Fix location: JlsmDocument.java:450-457 — ArrayType case in validateType
    // Regression watch: Ensure shallow ArrayType nesting (1-3 levels) still validates correctly
    @Test
    void test_validateType_arrayType_deepNesting_throwsInsteadOfStackOverflow() {
        // Build a deeply nested ArrayType: ArrayType(ArrayType(ArrayType(...Primitive.INT32...)))
        // Depth of 2000 will overflow the stack on unguarded recursion.
        FieldType nested = FieldType.Primitive.INT32;
        for (int i = 0; i < 2000; i++) {
            nested = new FieldType.ArrayType(nested);
        }

        // Build correspondingly nested Object[] value — only need one element per level.
        Object value = 42;
        for (int i = 0; i < 2000; i++) {
            value = new Object[]{ value };
        }

        final FieldType finalType = nested;
        final Object finalValue = value;

        // Must throw a clean exception (IllegalArgumentException), NOT StackOverflowError.
        // StackOverflowError would crash the JVM without recovery.
        assertThrows(IllegalArgumentException.class, () -> {
            // Use JlsmSchema + JlsmDocument.of to exercise validateType via the public API.
            var schema = JlsmSchema.builder("test", 1).field("deep", finalType).build();
            JlsmDocument.of(schema, "deep", finalValue);
        }, "Deeply nested ArrayType should throw IllegalArgumentException, not StackOverflowError");
    }

    // Finding: F-R1.dispatch_routing.1.5
    // Bug: defensiveCopyIfVector ArrayType branch does shallow clone only — nested mutable
    // arrays are not copied, so caller retains mutable reference to inner arrays
    // Correct behavior: defensiveCopyIfVector should deep-copy nested Object[] arrays for
    // ArrayType(ArrayType(...)) schemas so the document is truly immutable
    // Fix location: JlsmDocument.java:548-549 — ArrayType branch in defensiveCopyIfVector
    // Regression watch: Ensure shallow ArrayType (single level) still works correctly
    @Test
    void test_defensiveCopyIfVector_nestedArrayType_deepCopiesInnerArrays() {
        // Schema with nested ArrayType: array of arrays of INT32
        var innerType = new FieldType.ArrayType(FieldType.Primitive.INT32);
        var outerType = new FieldType.ArrayType(innerType);

        var schema = JlsmSchema.builder("test", 1).field("matrix", outerType).build();

        // Create a nested Object[] value
        Object[] innerArray = new Object[]{ 42, 99 };
        Object[] outerArray = new Object[]{ innerArray };

        var doc = JlsmDocument.of(schema, "matrix", outerArray);

        // Mutate the caller's inner array AFTER document creation.
        // If defensiveCopyIfVector only shallow-cloned, the document's internal
        // state shares the inner array reference, so this mutation corrupts it.
        innerArray[0] = 9999;

        // Retrieve via getArray (which clones the outer array on read).
        // The inner array element should still be 42 if the write path deep-copied.
        Object[] retrievedOuter = doc.getArray("matrix");
        Object[] retrievedInner = (Object[]) retrievedOuter[0];
        assertEquals(42, retrievedInner[0],
                "Document's inner array must be independent of caller's mutation — "
                        + "defensiveCopyIfVector must deep-copy nested arrays");
    }

    // Finding: F-R1.dispatch_routing.1.1
    // Bug: Assert-only guard on descriptor-to-client dispatch lookup allows NPE in production
    // Correct behavior: clients.get(desc.id()) returning null should throw IllegalStateException
    // with a descriptive message, not rely on assert (disabled in production)
    // Fix location: PartitionedTable.java:136-137 — replace assert with runtime check
    // Regression watch: Ensure normal getRange still works when all descriptors have clients
    @Test
    void test_PartitionedTable_getRange_missingClient_throwsIllegalStateNotAssertionError()
            throws Exception {
        // Build a PartitionConfig with two partitions.
        var lowA = stringKey("A");
        var highA = stringKey("M");
        var lowB = stringKey("M");
        var highB = stringKey("Z");
        var descA = new PartitionDescriptor(1L, lowA, highA, "node-1", 0L);
        var descB = new PartitionDescriptor(2L, lowB, highB, "node-2", 0L);
        var config = PartitionConfig.of(List.of(descA, descB));

        // Build a RangeMap from the config.
        var rangeMap = new RangeMap(config);

        // Build a clients map that is MISSING descriptor 2's entry — simulating
        // an inconsistency between rangeMap and clients.
        Map<Long, PartitionClient> clients = new LinkedHashMap<>();
        // Create a dummy PartitionClient for descriptor 1 only.
        clients.put(1L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descA;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public java.util.Optional<JlsmDocument> doGet(String key) {
                return java.util.Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public java.util.Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });

        // Use reflection to invoke the private constructor, bypassing the builder's
        // consistency enforcement.
        Constructor<PartitionedTable> ctor = PartitionedTable.class.getDeclaredConstructor(
                PartitionConfig.class, JlsmSchema.class, RangeMap.class, Map.class);
        ctor.setAccessible(true);
        var table = ctor.newInstance(config, null, rangeMap, clients);

        // Query a range that spans into partition B (descriptor 2), whose client is missing.
        // With the bug: AssertionError (with -ea) or NPE (without -ea).
        // After the fix: IllegalStateException with a meaningful message.
        assertThrows(IllegalStateException.class, () -> table.getRange("A", "Z"),
                "Missing client for descriptor should throw IllegalStateException, "
                        + "not rely on assert-only guard");
    }

    // Finding: F-R1.dispatch_routing.1.2
    // Bug: No closed-state guard on getRange allows dispatch to closed partition clients
    // Correct behavior: getRange should throw IllegalStateException after close() is called
    // Fix location: PartitionedTable.java — add volatile closed flag, check in getRange
    // Regression watch: Ensure getRange still works normally before close() is called
    @Test
    void test_PartitionedTable_getRange_afterClose_throwsIllegalState() throws Exception {
        // Build a PartitionedTable with one partition via reflection.
        var low = stringKey("A");
        var high = stringKey("Z");
        var desc = new PartitionDescriptor(1L, low, high, "node-1", 0L);
        var config = PartitionConfig.of(List.of(desc));
        var rangeMap = new RangeMap(config);

        Map<Long, PartitionClient> clients = new LinkedHashMap<>();
        clients.put(1L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return desc;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public java.util.Optional<JlsmDocument> doGet(String key) {
                return java.util.Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public java.util.Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });

        Constructor<PartitionedTable> ctor = PartitionedTable.class.getDeclaredConstructor(
                PartitionConfig.class, JlsmSchema.class, RangeMap.class, Map.class);
        ctor.setAccessible(true);
        var table = ctor.newInstance(config, null, rangeMap, clients);

        // Close the table — all partition clients are now closed.
        table.close();

        // Calling getRange after close should throw IllegalStateException,
        // not silently dispatch to closed partition clients.
        assertThrows(IllegalStateException.class, () -> table.getRange("A", "M"),
                "getRange after close() should throw IllegalStateException");
    }

    // Finding: F-R1.dispatch_routing.1.6
    // Bug: IndexRegistry.validate EQUALITY case allows BOOLEAN but RANGE/UNIQUE rejects it —
    // inconsistent dispatch semantics. BOOLEAN has only two values (true/false), making
    // an equality index wasteful. More importantly, the asymmetry means BOOLEAN is silently
    // accepted where it should be rejected for consistency with RANGE/UNIQUE.
    // Correct behavior: EQUALITY should also reject BOOLEAN, consistent with RANGE/UNIQUE
    // Fix location: IndexRegistry.java:423-428 — EQUALITY case should add BOOLEAN check
    // Regression watch: Ensure other primitives (INT32, STRING, etc.) still work with EQUALITY
    @Test
    void test_IndexRegistry_validate_equalityOnBoolean_throwsConsistentWithRangeUnique() {
        var schema = JlsmSchema.builder("test", 1).field("active", FieldType.Primitive.BOOLEAN)
                .build();

        // RANGE on BOOLEAN is rejected — this is the established behavior.
        assertThrows(IllegalArgumentException.class,
                () -> new IndexRegistry(schema,
                        List.of(new IndexDefinition("active", IndexType.RANGE))),
                "RANGE on BOOLEAN should be rejected");

        // UNIQUE on BOOLEAN is rejected — this is the established behavior.
        assertThrows(IllegalArgumentException.class,
                () -> new IndexRegistry(schema,
                        List.of(new IndexDefinition("active", IndexType.UNIQUE))),
                "UNIQUE on BOOLEAN should be rejected");

        // EQUALITY on BOOLEAN should also be rejected for consistency.
        // Bug: currently silently accepted, creating a useless index.
        assertThrows(IllegalArgumentException.class,
                () -> new IndexRegistry(schema,
                        List.of(new IndexDefinition("active", IndexType.EQUALITY))),
                "EQUALITY on BOOLEAN should be rejected — only two possible values "
                        + "make an equality index wasteful, and all other comparison-based "
                        + "index types (RANGE, UNIQUE) already reject BOOLEAN");
    }

    // Finding: F-R1.dispatch_routing.1.3
    // Bug: getRange dispatches full caller range to each partition instead of clipping to
    // partition boundaries — e.g. range [A, Z) spanning P1=[A, M) and P2=[M, Z) sends
    // [A, Z) to both instead of [A, M) to P1 and [M, Z) to P2
    // Correct behavior: each partition should receive the intersection of the query range
    // with its partition boundaries: [max(fromKey, lowKey), min(toKey, highKey))
    // Fix location: PartitionedTable.java:143 — clip fromKey/toKey to descriptor boundaries
    // Regression watch: single-partition queries and exact-boundary queries must still work
    @Test
    void test_PartitionedTable_getRange_clipsRangeToPartitionBoundaries() throws Exception {
        // Two partitions: P1=[A, M), P2=[M, Z)
        var lowA = stringKey("A");
        var highA = stringKey("M");
        var lowB = stringKey("M");
        var highB = stringKey("Z");
        var descA = new PartitionDescriptor(1L, lowA, highA, "node-1", 0L);
        var descB = new PartitionDescriptor(2L, lowB, highB, "node-2", 0L);
        var config = PartitionConfig.of(List.of(descA, descB));
        var rangeMap = new RangeMap(config);

        // Recording partition clients that capture the fromKey/toKey they receive.
        List<String> receivedFromKeys = Collections.synchronizedList(new ArrayList<>());
        List<String> receivedToKeys = Collections.synchronizedList(new ArrayList<>());

        Map<Long, PartitionClient> clients = new LinkedHashMap<>();
        clients.put(1L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descA;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> doGet(String key) {
                return Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                receivedFromKeys.add(fromKey);
                receivedToKeys.add(toKey);
                return Collections.emptyIterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });
        clients.put(2L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descB;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> doGet(String key) {
                return Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                receivedFromKeys.add(fromKey);
                receivedToKeys.add(toKey);
                return Collections.emptyIterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });

        Constructor<PartitionedTable> ctor = PartitionedTable.class.getDeclaredConstructor(
                PartitionConfig.class, JlsmSchema.class, RangeMap.class, Map.class);
        ctor.setAccessible(true);
        var table = ctor.newInstance(config, null, rangeMap, clients);

        // Query range [A, Z) spans both partitions.
        table.getRange("A", "Z");

        // Both partitions should have been dispatched to.
        assertEquals(2, receivedFromKeys.size(), "Both partitions should be dispatched to");

        // Partition 1 [A, M) should receive clipped range [A, M), not [A, Z).
        assertEquals("A", receivedFromKeys.get(0),
                "P1 fromKey should be A (max of query fromKey and partition lowKey)");
        assertEquals("M", receivedToKeys.get(0),
                "P1 toKey should be clipped to M (partition highKey), not Z");

        // Partition 2 [M, Z) should receive clipped range [M, Z), not [A, Z).
        assertEquals("M", receivedFromKeys.get(1),
                "P2 fromKey should be clipped to M (partition lowKey), not A");
        assertEquals("Z", receivedToKeys.get(1),
                "P2 toKey should be Z (min of query toKey and partition highKey)");
    }

    // Finding: F-R1.dispatch_routing.1.4
    // Bug: Partial dispatch failure leaves iterator in inconsistent state — when getRange
    // dispatches to 3 partitions and the 2nd throws IOException, iterators already collected
    // from partition 1 are orphaned (never closed) causing resource leaks
    // Correct behavior: On dispatch failure, getRange should close any already-collected
    // iterators (if they implement Closeable) before propagating the exception
    // Fix location: PartitionedTable.java:136-150 — wrap dispatch loop in try-catch with cleanup
    // Regression watch: Ensure successful getRange across multiple partitions still works
    @Test
    void test_PartitionedTable_getRange_partialDispatchFailure_closesCollectedIterators()
            throws Exception {
        // Three partitions: P1=[A, G), P2=[G, N), P3=[N, Z)
        var lowA = stringKey("A");
        var highA = stringKey("G");
        var lowB = stringKey("G");
        var highB = stringKey("N");
        var lowC = stringKey("N");
        var highC = stringKey("Z");
        var descA = new PartitionDescriptor(1L, lowA, highA, "node-1", 0L);
        var descB = new PartitionDescriptor(2L, lowB, highB, "node-2", 0L);
        var descC = new PartitionDescriptor(3L, lowC, highC, "node-3", 0L);
        var config = PartitionConfig.of(List.of(descA, descB, descC));
        var rangeMap = new RangeMap(config);

        // Track whether partition 1's iterator was closed.
        final AtomicBoolean iteratorClosed = new AtomicBoolean(false);

        // A closeable iterator returned by partition 1. Anonymous classes can only
        // implement one type, so we define a simple abstract class inline.
        abstract class CloseableIterator implements Iterator<TableEntry<String>>, Closeable {
        }
        final CloseableIterator closeableIter = new CloseableIterator() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public TableEntry<String> next() {
                throw new java.util.NoSuchElementException();
            }

            @Override
            public void close() {
                iteratorClosed.set(true);
            }
        };

        Map<Long, PartitionClient> clients = new LinkedHashMap<>();
        // Partition 1: returns a closeable iterator successfully.
        clients.put(1L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descA;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> doGet(String key) {
                return Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                return closeableIter;
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });
        // Partition 2: throws IOException on getRange.
        clients.put(2L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descB;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> doGet(String key) {
                return Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey)
                    throws IOException {
                throw new IOException("simulated partition 2 failure");
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });
        // Partition 3: should never be reached.
        clients.put(3L, new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return descC;
            }

            @Override
            public void doCreate(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> doGet(String key) {
                return Optional.empty();
            }

            @Override
            public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void doDelete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
                return Collections.emptyIterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
            }
        });

        Constructor<PartitionedTable> ctor = PartitionedTable.class.getDeclaredConstructor(
                PartitionConfig.class, JlsmSchema.class, RangeMap.class, Map.class);
        ctor.setAccessible(true);
        var table = ctor.newInstance(config, null, rangeMap, clients);

        // Query spanning all 3 partitions: P1 succeeds (iterator collected), P2 throws.
        // The IOException must propagate, AND P1's closeable iterator must be closed.
        assertThrows(IOException.class, () -> table.getRange("A", "Z"),
                "IOException from partition 2 should propagate to caller");

        assertTrue(iteratorClosed.get(),
                "Iterator from partition 1 must be closed on partial dispatch failure — "
                        + "orphaned iterators leak resources");
    }

    // Finding: F-R1.dispatch_routing.1.8
    // Bug: TableQuery.addPredicate overwrites root when nextMode is NONE and root is already set
    // Correct behavior: Should throw IllegalStateException when a second predicate is added
    // without specifying a combiner (and/or), preventing silent data loss
    // Fix location: TableQuery.java:102 — condition `root == null || nextMode == CombineMode.NONE`
    // Regression watch: Ensure normal chaining via and()/or() still works correctly
    @Test
    void test_TableQuery_addPredicate_secondWhereWithoutCombiner_throwsInsteadOfOverwriting()
            throws Exception {
        var constructor = TableQuery.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        TableQuery<String> query = (TableQuery<String>) constructor.newInstance();

        // First predicate: sets the root.
        query.where("a").eq(1);
        assertNotNull(query.predicate(), "Root should be set after first where()");

        // Second where() without and()/or() — nextMode stays NONE.
        // Bug: the second predicate silently replaces the first.
        // Correct: should throw IllegalStateException because the combiner is missing.
        assertThrows(IllegalStateException.class, () -> query.where("b").eq(2),
                "Adding a second predicate without and()/or() should throw, not silently overwrite");
    }

}
