package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.DcpeSapEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import java.util.ArrayList;
import jlsm.table.internal.FieldIndex;
import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.QueryExecutor;
import jlsm.table.internal.RangeMap;
import jlsm.table.internal.SecondaryIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adversarial tests for shared-state concerns in the table module.
 */
class SharedStateAdversarialTest {

    private <K> TableQuery<K> newQuery() {
        try {
            var constructor = TableQuery.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot create TableQuery for testing", e);
        }
    }

    private static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    // Finding: F-R1.shared_state.1.1
    // Bug: addPredicate uses assert-only null guard — null predicate silently corrupts root
    // Correct behavior: addPredicate should throw NullPointerException for null predicate
    // Fix location: TableQuery.java:101 — replace assert with Objects.requireNonNull
    // Regression watch: ensure existing predicate-building tests still pass after fix
    @Test
    void test_addPredicate_nullGuard_throwsNpeInsteadOfCorruptingRoot() {
        TableQuery<String> q = newQuery();
        assertThrows(NullPointerException.class, () -> q.addPredicate(null),
                "addPredicate(null) must throw NullPointerException, not silently set root to null");
    }

    // Finding: F-R1.shared_state.1.3
    // Bug: FieldClause constructor validates query and fieldName with assert only
    // Correct behavior: FieldClause constructor should throw NullPointerException for null query or
    // fieldName
    // Fix location: TableQuery.java:127-128 — replace assert with Objects.requireNonNull
    // Regression watch: ensure where()/and()/or() still work correctly after fix
    @Test
    void test_FieldClause_constructor_nullQuery_throwsNpeInsteadOfAssert() {
        assertThrows(NullPointerException.class,
                () -> new TableQuery.FieldClause<String>(null, "field"),
                "FieldClause(null, fieldName) must throw NullPointerException, not rely on assert");
    }

    @Test
    void test_FieldClause_constructor_nullFieldName_throwsNpeInsteadOfAssert() {
        TableQuery<String> q = newQuery();
        assertThrows(NullPointerException.class, () -> new TableQuery.FieldClause<>(q, null),
                "FieldClause(query, null) must throw NullPointerException, not rely on assert");
    }

    // Finding: F-R1.shared_state.2.4
    // Bug: Phase-2 delete has no rollback — if second index's onDelete throws,
    // first index has already removed the entry but documentStore still has it
    // Correct behavior: If an index onDelete fails mid-way, already-deleted indices
    // should be rolled back (re-inserted) to maintain consistency
    // Fix location: IndexRegistry.java onDelete method (lines ~169-177)
    // Regression watch: Ensure normal (non-failing) deletes still work after adding rollback
    @SuppressWarnings("unchecked")
    @Test
    void test_IndexRegistry_onDelete_partialFailureRollsBackAlreadyDeletedIndices()
            throws Exception {
        // Setup: schema with two indexed string fields
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("email", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("email", IndexType.EQUALITY));

        var registry = new IndexRegistry(schema, defs);

        var pk1 = stringKey("pk1");
        var doc = JlsmDocument.of(schema, "name", "Alice", "email", "alice@test.com");
        registry.onInsert(pk1, doc);

        // Close the second index — its onDelete will throw IllegalStateException
        var indicesField = IndexRegistry.class.getDeclaredField("indices");
        indicesField.setAccessible(true);
        var indices = (List<SecondaryIndex>) indicesField.get(registry);
        var secondIndex = indices.get(1);
        secondIndex.close();

        // Attempt delete: should throw because second index is closed
        assertThrows(IllegalStateException.class, () -> registry.onDelete(pk1, doc),
                "Second index is closed, so onDelete should throw");

        // After the failed delete, verify consistency:
        // The first index (name) should still have "Alice" because
        // the rollback should have re-inserted it
        var nameIndex = registry.findIndex(new Predicate.Eq("name", "Alice"));
        assertNotNull(nameIndex, "Should find name index");

        var aliceLookup = nameIndex.lookup(new Predicate.Eq("name", "Alice"));
        assertTrue(aliceLookup.hasNext(),
                "After failed delete with rollback, the first index should still contain "
                        + "'Alice'. If this fails, the first index removed 'Alice' without rollback "
                        + "— orphaned state where document is in documentStore but not in all indices.");

        // Also verify that documentStore still has the document
        var storedEntry = registry.resolveEntry(pk1);
        assertNotNull(storedEntry,
                "Document should still exist in document store after failed delete");
        assertEquals("Alice", storedEntry.document().getString("name"),
                "documentStore should still have the document after failed delete");

        registry.close();
    }

    // Finding: F-R1.shared_state.2.3
    // Bug: Phase-2 update has no rollback — partial index update creates split state
    // Correct behavior: If the second index's onUpdate throws, all already-updated indices
    // should be rolled back to the old values, preserving consistency
    // Fix location: IndexRegistry.java onUpdate Phase-2 loop (lines ~134-141)
    // Regression watch: Ensure normal (non-failing) updates still work after adding rollback
    @SuppressWarnings("unchecked")
    @Test
    void test_IndexRegistry_onUpdate_partialFailureRollsBackAlreadyUpdatedIndices()
            throws Exception {
        // Setup: schema with two indexed string fields
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("email", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("email", IndexType.EQUALITY));

        var registry = new IndexRegistry(schema, defs);

        var pk1 = stringKey("pk1");
        var oldDoc = JlsmDocument.of(schema, "name", "Alice", "email", "alice@test.com");
        registry.onInsert(pk1, oldDoc);

        // Use reflection to close the second index, simulating an index failure
        var indicesField = IndexRegistry.class.getDeclaredField("indices");
        indicesField.setAccessible(true);
        var indices = (List<SecondaryIndex>) indicesField.get(registry);
        var secondIndex = indices.get(1);

        // Close the second index — its onUpdate will throw IllegalStateException
        secondIndex.close();

        // Attempt update: name changes from "Alice" to "Charlie"
        var newDoc = JlsmDocument.of(schema, "name", "Charlie", "email", "charlie@test.com");
        assertThrows(IllegalStateException.class, () -> registry.onUpdate(pk1, oldDoc, newDoc),
                "Second index is closed, so onUpdate should throw");

        // After the failed update, verify consistency:
        // The first index (name) should NOT have the new value "Charlie"
        // because the rollback should have restored it to "Alice"
        var nameIndex = registry.findIndex(new Predicate.Eq("name", "Alice"));
        assertNotNull(nameIndex, "Should find name index");

        var aliceLookup = nameIndex.lookup(new Predicate.Eq("name", "Alice"));
        assertTrue(aliceLookup.hasNext(),
                "After failed update with rollback, the first index should still contain "
                        + "'Alice' (the old value). If this fails, the first index was updated to 'Charlie' "
                        + "without rollback — split state.");

        // Also verify that documentStore still has the old document
        var storedEntry = registry.resolveEntry(pk1);
        assertNotNull(storedEntry, "Document should still exist in document store");
        assertEquals("Alice", storedEntry.document().getString("name"),
                "documentStore should still have the old document after failed update");

        registry.close();
    }

    // Finding: F-R1.shared_state.2.5
    // Bug: findIndex and resolveEntry have no closed guard — queries execute against closed
    // registry
    // Correct behavior: findIndex and resolveEntry should throw IllegalStateException when closed
    // Fix location: IndexRegistry.java findIndex() (~line 197) and resolveEntry() (~line 234)
    // Regression watch: ensure QueryExecutor paths that call findIndex/resolveEntry still work on
    // open registry
    @Test
    void test_IndexRegistry_findIndexAndResolveEntry_throwWhenClosed() throws Exception {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var pk1 = stringKey("pk1");
        var doc = JlsmDocument.of(schema, "name", "Alice");
        registry.onInsert(pk1, doc);

        // Close the registry
        registry.close();

        // findIndex should throw IllegalStateException on a closed registry
        assertThrows(IllegalStateException.class,
                () -> registry.findIndex(new Predicate.Eq("name", "Alice")),
                "findIndex must throw IllegalStateException on a closed registry");

        // resolveEntry should throw IllegalStateException on a closed registry
        assertThrows(IllegalStateException.class, () -> registry.resolveEntry(pk1),
                "resolveEntry must throw IllegalStateException on a closed registry");
    }

    // Finding: F-R1.shared_state.2.7
    // Bug: QueryExecutor.extractFieldValue silently returns null for ArrayType, VectorType,
    // ObjectType
    // Correct behavior: extractFieldValue should throw UnsupportedOperationException for complex
    // types
    // that cannot be meaningfully compared in scan-and-filter mode
    // Fix location: QueryExecutor.java extractFieldValue (~line 245) — add explicit branches for
    // complex types
    // Regression watch: ensure Primitive and BoundedString fields still work in scan-and-filter
    @Test
    void test_QueryExecutor_extractFieldValue_throwsForArrayType() throws Exception {
        var schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.string())).build();

        var defs = List.<IndexDefinition>of(); // no indices — forces scan-and-filter
        var registry = new IndexRegistry(schema, defs);

        var pk = stringKey("pk1");
        var doc = JlsmDocument.of(schema, "tags", new Object[]{ "java", "lsm" });
        registry.onInsert(pk, doc);

        var executor = QueryExecutor.forStringKeys(schema, registry);

        // Eq predicate on an array field should throw UnsupportedOperationException,
        // not silently return empty results
        assertThrows(UnsupportedOperationException.class,
                () -> executor.execute(new Predicate.Eq("tags", "java")),
                "extractFieldValue must throw UnsupportedOperationException for ArrayType, "
                        + "not silently return null causing empty results");

        registry.close();
    }

    // Finding: F-R1.shared_state.2.6
    // Bug: QueryExecutor two-arg constructor performs unchecked cast to K, so
    // QueryExecutor<Long> silently constructs but the keyDecoder returns String
    // Correct behavior: The two-arg constructor should only produce QueryExecutor<String>,
    // enforced by making it a static factory that returns QueryExecutor<String>
    // Fix location: QueryExecutor.java:67-70 — replace two-arg constructor with static factory
    // Regression watch: existing callers of the two-arg constructor must still compile
    @Test
    void test_QueryExecutor_twoArgConstructor_preventsNonStringKeyType() throws Exception {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var pk = stringKey("pk1");
        var doc = JlsmDocument.of(schema, "name", "Alice");
        registry.onInsert(pk, doc);

        // The two-arg constructor should return QueryExecutor<String>, not allow
        // arbitrary K. If the fix is correct, this returns a QueryExecutor<String>.
        var executor = QueryExecutor.forStringKeys(schema, registry);

        // Verify it works — execute returns a result with String key
        var it = executor.execute(new Predicate.Eq("name", "Alice"));
        assertTrue(it.hasNext(), "Should find Alice");
        TableEntry<String> entry = it.next();
        // Key must be a String, not a deferred ClassCastException
        assertEquals("pk1", entry.key(),
                "Key should be decoded as String by the string-keyed factory");

        registry.close();
    }

    // Finding: F-R1.shared_state.3.4
    // Bug: ByteArrayKey record exposes mutable byte[] via data() accessor
    // Correct behavior: Mutating the array returned by data() must not affect the key's
    // compareTo, equals, or hashCode — the record must make a defensive copy
    // Fix location: FieldIndex.java:321 — add compact constructor with Arrays.copyOf
    // Regression watch: ensure encodeKey and SseEncryptedIndex construction still work
    @SuppressWarnings("unchecked")
    @Test
    void test_ByteArrayKey_dataAccessor_mutationDoesNotCorruptTreeMapKey() throws Exception {
        // Setup: create a FieldIndex and insert an entry so entries map is populated
        var definition = new IndexDefinition("name", IndexType.EQUALITY);
        var fieldType = FieldType.string();
        var index = new FieldIndex(definition, fieldType);

        var pk = stringKey("pk1");
        index.onInsert(pk, "Alice");

        // Use reflection to access the internal entries map
        var entriesField = FieldIndex.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        var entries = (NavigableMap<?, ?>) entriesField.get(index);

        // Get the first key from the map
        var firstKey = entries.firstKey();

        // Use reflection to call data() on the ByteArrayKey
        var dataMethod = firstKey.getClass().getMethod("data");
        dataMethod.setAccessible(true);
        byte[] keyData = (byte[]) dataMethod.invoke(firstKey);

        // Verify the entry is currently findable
        var lookupBefore = index.lookup(new Predicate.Eq("name", "Alice"));
        assertTrue(lookupBefore.hasNext(),
                "Sanity check: entry should be findable before mutation");

        // Mutate the byte[] returned by the data() accessor
        for (int i = 0; i < keyData.length; i++) {
            keyData[i] = (byte) 0xFF;
        }

        // After mutating the key's internal data, the TreeMap's ordering invariant
        // is broken. A lookup for "Alice" should still find the entry, but won't
        // because the key's bytes have changed.
        var lookupAfter = index.lookup(new Predicate.Eq("name", "Alice"));
        assertTrue(lookupAfter.hasNext(),
                "After mutating the byte[] returned by ByteArrayKey.data(), the TreeMap "
                        + "must still find the entry for 'Alice'. If this fails, the record exposes "
                        + "mutable internal state — the data() accessor returned a reference to the "
                        + "backing array, and mutating it broke the TreeMap's ordering invariant.");

        index.close();
    }

    // Finding: F-R1.shared_state.3.2
    // Bug: assert-only closed guard allows operations on closed index
    // Correct behavior: onInsert/onUpdate/onDelete/lookup must throw IllegalStateException after
    // close()
    // Fix location: FieldIndex.java:80, 100, 110, 120 — replace assert with runtime if/throw
    // Regression watch: ensure close() is still idempotent and normal operations work before close
    @Test
    void test_FieldIndex_closedGuard_throwsIseAfterClose() throws Exception {
        var definition = new IndexDefinition("name", IndexType.EQUALITY);
        var fieldType = FieldType.string();
        var index = new FieldIndex(definition, fieldType);

        var pk = stringKey("pk1");
        index.onInsert(pk, "Alice");

        // Close the index
        index.close();

        // All mutation and query operations must throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> index.onInsert(stringKey("pk2"), "Bob"),
                "onInsert must throw IllegalStateException on closed index");

        assertThrows(IllegalStateException.class,
                () -> index.onUpdate(stringKey("pk1"), "Alice", "Charlie"),
                "onUpdate must throw IllegalStateException on closed index");

        assertThrows(IllegalStateException.class, () -> index.onDelete(stringKey("pk1"), "Alice"),
                "onDelete must throw IllegalStateException on closed index");

        assertThrows(IllegalStateException.class,
                () -> index.lookup(new Predicate.Eq("name", "Alice")),
                "lookup must throw IllegalStateException on closed index");
    }

    // Finding: F-R1.shared_state.3.2 (strategy 2)
    // Bug: assert-only closed guard allows checkUnique on closed index
    // Correct behavior: checkUnique must also throw IllegalStateException after close()
    // Fix location: FieldIndex.java checkUnique method
    // Regression watch: ensure checkUnique works before close
    @Test
    void test_FieldIndex_checkUnique_throwsIseAfterClose() throws Exception {
        var definition = new IndexDefinition("email", IndexType.UNIQUE);
        var fieldType = FieldType.string();
        var index = new FieldIndex(definition, fieldType);

        index.onInsert(stringKey("pk1"), "alice@test.com");
        index.close();

        // checkUnique is package-private but called by IndexRegistry before onInsert.
        // If it lacks a closed guard, it silently succeeds on a closed index,
        // bypassing uniqueness enforcement. With no closed guard, checkUnique
        // reads the now-cleared entries map and returns "no duplicate" — allowing
        // a subsequent onInsert to add an entry that would have been rejected.
        var method = FieldIndex.class.getDeclaredMethod("checkUnique", Object.class);
        method.setAccessible(true);
        try {
            method.invoke(index, "bob@test.com");
            fail("checkUnique must throw IllegalStateException on closed index, "
                    + "but it returned normally — closed guard is missing");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            assertInstanceOf(IllegalStateException.class, ite.getCause(),
                    "checkUnique must throw IllegalStateException on closed index, got: "
                            + ite.getCause());
        }
    }

    // Finding: F-R1.shared_state.3.6
    // Bug: schemaFieldType null causes Short values to encode as INT16 instead of FLOAT16,
    // producing wrong sort order for negative float16 values in range queries
    // Correct behavior: When schemaFieldType is null and a Short is encountered, FieldIndex
    // should throw IllegalStateException instead of silently using INT16 encoding
    // Fix location: FieldIndex.java inferFieldType — Short case should throw when no schema type
    // Regression watch: Ensure Short values still work with explicit FLOAT16 schema field type
    @Test
    void test_FieldIndex_singleArgConstructor_shortValueThrowsWithoutSchemaType() throws Exception {
        // The single-arg constructor sets schemaFieldType = null.
        // When a Short value is inserted, inferFieldType maps it to INT16,
        // but the value might represent FLOAT16 — the ambiguity is silent.
        var definition = new IndexDefinition("temp", IndexType.RANGE);
        var index = new FieldIndex(definition); // single-arg: schemaFieldType = null

        short negTwo = Float16.fromFloat(-2.0f);

        // Inserting a Short without schema type should throw because Short is ambiguous
        assertThrows(IllegalStateException.class, () -> index.onInsert(stringKey("pk1"), negTwo),
                "FieldIndex with null schemaFieldType must reject Short values because "
                        + "Short is ambiguous between INT16 and FLOAT16. Silent INT16 encoding "
                        + "produces wrong sort order for FLOAT16 negative values.");

        index.close();
    }

    // Finding: F-R1.shared_state.5.1
    // Bug: Assert-only invariant guards on encryptRecursive allow corrupted state to flow
    // into PRF buffer operations when dLo > dHi or rLo > rHi
    // Correct behavior: encryptRecursive must throw IllegalStateException at runtime (not
    // just via assert) when dLo > dHi or rLo > rHi, preventing corrupted state from
    // flowing into sampleHypergeometric and producing garbage ciphertexts
    // Fix location: BoldyrevaOpeEncryptor.java:142-143 — replace assert with runtime if/throw
    // Regression watch: ensure normal encrypt/decrypt round-trips still work after adding guards
    @Test
    void test_encryptRecursive_assertOnlyInvariantGuard_throwsIseForCorruptedDomainBounds()
            throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        var keyHolder = EncryptionKeyHolder.of(keyBytes);
        var encryptor = new BoldyrevaOpeEncryptor(keyHolder, 100, 10_000);

        // Access the private encryptRecursive method via reflection
        Method encryptRecursive = BoldyrevaOpeEncryptor.class.getDeclaredMethod("encryptRecursive",
                long.class, long.class, long.class, long.class, long.class, int.class);
        encryptRecursive.setAccessible(true);

        // Call with dLo > dHi (corrupted domain bounds, as would happen if
        // sampleHypergeometric returned dLeftCount > dSize)
        // dLo=50, dHi=10 violates the dLo <= dHi invariant
        try {
            encryptRecursive.invoke(encryptor, 50L, 50L, 10L, 1L, 10_000L, 0);
            fail("encryptRecursive must throw IllegalStateException when dLo > dHi, "
                    + "but it returned normally — assert-only guard is ineffective without -ea");
        } catch (InvocationTargetException ite) {
            assertInstanceOf(IllegalStateException.class, ite.getCause(),
                    "encryptRecursive must throw IllegalStateException for dLo > dHi, got: "
                            + ite.getCause());
        }

        // Call with rLo > rHi (corrupted range bounds)
        // rLo=5000, rHi=100 violates rLo <= rHi invariant
        try {
            encryptRecursive.invoke(encryptor, 50L, 1L, 100L, 5000L, 100L, 0);
            fail("encryptRecursive must throw IllegalStateException when rLo > rHi, "
                    + "but it returned normally — assert-only guard is ineffective without -ea");
        } catch (InvocationTargetException ite) {
            assertInstanceOf(IllegalStateException.class, ite.getCause(),
                    "encryptRecursive must throw IllegalStateException for rLo > rHi, got: "
                            + ite.getCause());
        }

        encryptor.close();
        keyHolder.close();
    }

    // Finding: F-R1.shared_state.5.2
    // Bug: Assert-only parameter validation in sampleHypergeometric allows silent NaN/Infinity
    // in threshold computation when popN == 0
    // Correct behavior: sampleHypergeometric must throw IllegalArgumentException at runtime
    // when popN <= 0, not rely on assert which is disabled in production
    // Fix location: BoldyrevaOpeEncryptor.java:187-189 — replace asserts with runtime if/throw
    // Regression watch: ensure normal encrypt/decrypt round-trips still work after adding guards
    @Test
    void test_sampleHypergeometric_assertOnlyValidation_throwsForZeroPopulation() throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        var keyHolder = EncryptionKeyHolder.of(keyBytes);
        var encryptor = new BoldyrevaOpeEncryptor(keyHolder, 100, 10_000);

        // Access the private sampleHypergeometric method via reflection
        Method sampleHypergeometric = BoldyrevaOpeEncryptor.class.getDeclaredMethod(
                "sampleHypergeometric", long.class, long.class, long.class, long.class, long.class,
                long.class, long.class);
        sampleHypergeometric.setAccessible(true);

        // Call with popN = 0 (zero population).
        // With assertions disabled, this reaches the sampling loop where
        // threshold = (double) successes / (double) population = 0.0/0.0 = NaN.
        // The comparison coin < NaN is always false, so selected stays 0 —
        // silently wrong result instead of an exception.
        try {
            sampleHypergeometric.invoke(encryptor, 0L, // popN = 0 — invalid
                    0L, // succK = 0
                    0L, // draws = 0
                    1L, // dLo
                    100L, // dHi
                    1L, // rLo
                    10_000L); // rHi
            fail("sampleHypergeometric must throw IllegalArgumentException when popN == 0, "
                    + "but it returned normally — assert-only guard is ineffective without -ea");
        } catch (InvocationTargetException ite) {
            assertInstanceOf(IllegalArgumentException.class, ite.getCause(),
                    "sampleHypergeometric must throw IllegalArgumentException for popN == 0, got: "
                            + ite.getCause());
        }

        encryptor.close();
        keyHolder.close();
    }

    // Finding: F-R1.shared_state.5.4
    // Bug: PRF seed collisions due to lossy long-to-int folding in prfSeed — many distinct
    // long tuples produce the same 4-int tuple after XOR folding, causing identical AES seeds
    // Correct behavior: Two distinct (dLo, dHi, rLo, rHi) tuples that differ only in high bits
    // must produce different encrypt() outputs
    // Fix location: BoldyrevaOpeEncryptor.java prfSeed (~line 232) — use full 8 bytes per long
    // Regression watch: ensure encrypt/decrypt round-trip still works after changing PRF layout
    @Test
    void test_prfSeed_longToIntFolding_distinctHighBitsTupleProduceDifferentCiphertexts()
            throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        var keyHolder = EncryptionKeyHolder.of(keyBytes);

        // Use a domain large enough that the recursive bisection will pass
        // (dLo, dHi, rLo, rHi) tuples with high-bit-only differences to prfSeed.
        //
        // Key insight: dLo=0 and dLo=0x0000_0001_0000_0001L both fold to int 0
        // via (x ^ (x >>> 32)). If we can construct two encryptors whose recursion
        // paths hit tuples that differ only in high bits, we get colliding PRF seeds.
        //
        // Simplest approach: use reflection to call prfSeed directly with two
        // distinct long-tuples that XOR-fold to the same int-tuple.
        var encryptor = new BoldyrevaOpeEncryptor(keyHolder, 100, 10_000);

        Method prfSeedMethod = BoldyrevaOpeEncryptor.class.getDeclaredMethod("prfSeed", long.class,
                long.class, long.class, long.class);
        prfSeedMethod.setAccessible(true);

        // These two tuples XOR-fold to the same 4-int tuple under (int)(x ^ (x >>> 32)):
        // Tuple A: (0L, 0L, 0L, 0L) -> ints (0, 0, 0, 0)
        // Tuple B: (0x0000_0001_0000_0001L, 0x0000_0001_0000_0001L,
        // 0x0000_0001_0000_0001L, 0x0000_0001_0000_0001L) -> ints (0, 0, 0, 0)
        long collider = 0x0000_0001_0000_0001L;

        long seedA = (long) prfSeedMethod.invoke(encryptor, 0L, 0L, 0L, 0L);
        long seedB = (long) prfSeedMethod.invoke(encryptor, collider, collider, collider, collider);

        assertNotEquals(seedA, seedB,
                "prfSeed must produce different seeds for distinct (dLo, dHi, rLo, rHi) tuples. "
                        + "Both (0,0,0,0) and (0x0000_0001_0000_0001, ...) folded to the same int tuple "
                        + "via lossy (int)(x ^ (x >>> 32)) — this means distinct tree nodes get identical "
                        + "PRF seeds, producing correlated hypergeometric coin flips.");

        encryptor.close();
        keyHolder.close();
    }

    // Finding: F-R1.shared_state.3.1
    // Bug: onUpdate loses entry when onInsert fails UNIQUE constraint
    // Correct behavior: If onInsert throws DuplicateKeyException, the old entry should be restored
    // Fix location: FieldIndex.java:100-112 — onUpdate must catch DuplicateKeyException and
    // re-insert old
    // Regression watch: normal onUpdate with UNIQUE index must still work
    @Test
    void test_FieldIndex_onUpdate_uniqueConstraintFailureRestoresOldEntry() throws Exception {
        // Setup: UNIQUE index on "email"
        var definition = new IndexDefinition("email", IndexType.UNIQUE);
        var fieldType = FieldType.string();
        var index = new FieldIndex(definition, fieldType);

        var pk1 = stringKey("pk1");
        var pk2 = stringKey("pk2");

        // Insert two entries with different emails
        index.onInsert(pk1, "alice@test.com");
        index.onInsert(pk2, "bob@test.com");

        // Attempt to update pk1's email to "bob@test.com" which violates UNIQUE
        assertThrows(DuplicateKeyException.class,
                () -> index.onUpdate(pk1, "alice@test.com", "bob@test.com"),
                "onUpdate should throw DuplicateKeyException when newValue violates UNIQUE");

        // After the failed update, pk1 should still be findable via the OLD value.
        // Bug: removeEntry deleted "alice@test.com" for pk1 before onInsert threw,
        // so pk1 is now orphaned from the index.
        var lookup = index.lookup(new Predicate.Eq("email", "alice@test.com"));
        assertTrue(lookup.hasNext(),
                "After failed UNIQUE update, the old entry 'alice@test.com' for pk1 must "
                        + "still be in the index. If this fails, the old entry was removed but the new "
                        + "entry was never added — pk1 is silently lost from the index.");
    }

    // Finding: F-R1.shared_state.6.2
    // Bug: keyBytes used only in constructor but retained as field — key material persists
    // in cleartext on the heap until close() is called, widening the exposure window
    // Correct behavior: keyBytes should be zeroed at the end of the constructor since
    // scaleFactor and seedRng are the only derived values needed post-construction
    // Fix location: DcpeSapEncryptor.java:43-51 — zero keyBytes after deriving scaleFactor and
    // seedRng
    // Regression watch: ensure encrypt/decrypt round-trip still works after zeroing keyBytes in
    // constructor
    @Test
    void test_DcpeSapEncryptor_keyBytesZeroedAfterConstruction_notRetainedUnnecessarily()
            throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        var keyHolder = EncryptionKeyHolder.of(keyBytes);
        var encryptor = new DcpeSapEncryptor(keyHolder, 128);

        // Use reflection to access the keyBytes field
        var keyBytesField = DcpeSapEncryptor.class.getDeclaredField("keyBytes");
        keyBytesField.setAccessible(true);
        byte[] storedKeyBytes = (byte[]) keyBytesField.get(encryptor);

        // After construction, keyBytes should already be zeroed because the constructor
        // only needs them to derive scaleFactor and seed the RNG. Retaining non-zero
        // key material widens the exposure window unnecessarily.
        boolean allZero = true;
        for (byte b : storedKeyBytes) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        assertTrue(allZero,
                "keyBytes must be zeroed immediately after constructor derives scaleFactor and "
                        + "seedRng. The field retains sensitive key material with no post-construction "
                        + "purpose — widening the exposure window until close() is called.");

        // Verify encrypt/decrypt still works after key material is zeroed
        float[] plaintext = new float[128];
        for (int i = 0; i < plaintext.length; i++) {
            plaintext[i] = (float) i / 128.0f;
        }
        var encrypted = encryptor.encrypt(plaintext);
        float[] decrypted = encryptor.decrypt(encrypted.values(), encrypted.seed());
        for (int i = 0; i < plaintext.length; i++) {
            assertEquals(plaintext[i], decrypted[i], 0.01f,
                    "Encrypt/decrypt round-trip must still work after keyBytes zeroed in constructor");
        }

        encryptor.close();
        keyHolder.close();
    }

    // Finding: F-R1.shared_state.7.6
    // Bug: DocumentAccess.setAccessor has TOCTOU race on double-set guard — two concurrent
    // callers can both read accessor==null and both write, silently overwriting the first
    // Correct behavior: Exactly one call to setAccessor must succeed; all others must throw
    // IllegalStateException, even under concurrent invocation
    // Fix location: DocumentAccess.java:62-69 — replace volatile check-then-set with CAS
    // Regression watch: JlsmDocument static init must still register the accessor successfully
    @Test
    @Timeout(30)
    void test_DocumentAccess_setAccessor_concurrentDoubleSetRejected() throws Exception {
        // We need to test that concurrent setAccessor calls are properly guarded.
        // The accessor is already set by JlsmDocument's static init, so we use reflection
        // to reset it, then race multiple threads calling setAccessor simultaneously.
        var accessorField = jlsm.table.internal.DocumentAccess.class.getDeclaredField("accessor");
        accessorField.setAccessible(true);

        // Save the original accessor so we can restore it
        var originalAccessor = accessorField.get(null);

        try {
            int threadCount = 8;

            // Run many trials with multiple threads to increase chance of hitting the race
            for (int trial = 0; trial < 500; trial++) {
                // Reset to null before each trial
                accessorField.set(null, null);

                var barrier = new CyclicBarrier(threadCount);
                var successes = new AtomicInteger(0);
                var failures = new AtomicInteger(0);

                Thread[] threads = new Thread[threadCount];
                for (int i = 0; i < threadCount; i++) {
                    final int idx = i;
                    threads[i] = Thread.ofPlatform().start(() -> {
                        var acc = new jlsm.table.internal.DocumentAccess.Accessor() {
                            @Override
                            public Object[] values(JlsmDocument doc) {
                                return null;
                            }

                            @Override
                            public JlsmDocument create(JlsmSchema schema, Object[] values) {
                                return null;
                            }

                            @Override
                            public boolean isPreEncrypted(JlsmDocument doc) {
                                return false;
                            }

                            @Override
                            public String toString() {
                                return "accessor-" + idx;
                            }
                        };
                        try {
                            barrier.await();
                            jlsm.table.internal.DocumentAccess.setAccessor(acc);
                            successes.incrementAndGet();
                        } catch (IllegalStateException _) {
                            failures.incrementAndGet();
                        } catch (Exception _) {
                        }
                    });
                }

                for ( Thread t : threads) {
                    t.join(5000);
                }

                if (successes.get() > 1) {
                    // Multiple threads succeeded — race was hit! The double-set guard failed.
                    fail(successes.get() + " concurrent setAccessor calls succeeded on trial "
                            + trial + " — the double-set guard has a TOCTOU race. Multiple threads "
                            + "read accessor==null and all wrote. Expected exactly 1 success and "
                            + (threadCount - 1) + " IllegalStateExceptions.");
                    return;
                }
            }

            // If we never hit the race in 500 trials with 8 threads, the test is
            // inconclusive via timing alone. Fall back to structural proof:
            // verify that the method uses a non-atomic check-then-set pattern by
            // confirming that no synchronization or CAS is used.
            // Read setAccessor bytecode characteristics via reflection:
            // If the method is NOT synchronized, the TOCTOU is structurally present.
            var setAccessorMethod = jlsm.table.internal.DocumentAccess.class.getDeclaredMethod(
                    "setAccessor", jlsm.table.internal.DocumentAccess.Accessor.class);
            int modifiers = setAccessorMethod.getModifiers();
            assertTrue(java.lang.reflect.Modifier.isSynchronized(modifiers),
                    "DocumentAccess.setAccessor must be synchronized (or use CAS) to prevent "
                            + "TOCTOU race on the double-set guard. The method currently uses a volatile "
                            + "read-then-write which is not atomic — two concurrent callers can both read "
                            + "accessor==null and both proceed to write, with the second silently "
                            + "overwriting the first.");

        } finally {
            // Restore the original accessor so other tests work
            accessorField.set(null, originalAccessor);
        }
    }

    // Finding: F-R1.shared_state.1.7
    // Bug: JlsmDocument.of allows duplicate field names, last-write-wins silently
    // Correct behavior: JlsmDocument.of should throw IllegalArgumentException when
    // the same field name appears more than once in nameValuePairs
    // Fix location: JlsmDocument.java:93-108 — add duplicate field name detection
    // Regression watch: ensure single-use field names still work normally
    @Test
    void test_JlsmDocument_of_duplicateFieldNames_throwsInsteadOfSilentOverwrite() {
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();

        // Passing "name" twice — the second value silently overwrites the first
        assertThrows(IllegalArgumentException.class,
                () -> JlsmDocument.of(schema, "name", "Alice", "name", "Bob"),
                "JlsmDocument.of must throw IllegalArgumentException when the same field "
                        + "name appears more than once. Currently the second value silently overwrites "
                        + "the first with no error — the caller has no way to know 'Alice' was discarded.");
    }

    // Finding: F-R1.shared_state.7.8
    // Bug: JlsmDocument.of does not defensive-copy Object[] values for ArrayType fields
    // Correct behavior: Mutating the original Object[] after passing it to of() must not
    // affect the document's internal state — getArray should return the original values
    // Fix location: JlsmDocument.java defensiveCopyIfVector (~line 519) — add ArrayType branch
    // Regression watch: ensure getArray still returns a clone on output (existing behavior)
    @Test
    void test_JlsmDocument_of_arrayFieldNotDefensiveCopied_callerMutationCorruptsDocument() {
        var schema = JlsmSchema.builder("test", 1)
                .field("tags", FieldType.arrayOf(FieldType.string())).build();

        Object[] tags = { "java", "lsm" };
        var doc = JlsmDocument.of(schema, "tags", tags);

        // Mutate the original array after document creation
        tags[0] = "MUTATED";

        // The document should still see the original values, not the mutation
        Object[] retrieved = doc.getArray("tags");
        assertEquals("java", retrieved[0],
                "After mutating the original Object[] passed to JlsmDocument.of(), "
                        + "getArray must still return 'java' — not 'MUTATED'. The document "
                        + "stores the caller's array reference without defensive copy, so "
                        + "external mutation corrupts the document's internal state.");
    }

    // Finding: F-R1.shared_state.8.3
    // Bug: Deserialized schema version is read but discarded — no type compatibility validation
    // Correct behavior: Data written by a NEWER schema (writeVersion > reader schema version) must
    // throw IllegalArgumentException — the reader cannot safely interpret fields it doesn't know.
    // Data written by an OLDER schema (writeVersion <= reader schema version) is allowed for
    // forward-compatible evolution where newer schemas only add fields at the end.
    // Fix location: DocumentSerializer.java:326 — validate writeVersion <= schema.version()
    // Regression watch: Same-version and older-version round-trips must still work
    @Test
    void test_deserialize_futureSchemaVersion_throwsInsteadOfSilentCorruption() {
        // Schema v1: field "value" is INT64
        var schemaV1 = JlsmSchema.builder("test", 1).field("value", FieldType.int64()).build();

        // Schema v2: field "value" is INT64 + new field "extra" is STRING
        var schemaV2 = JlsmSchema.builder("test", 2).field("value", FieldType.int64())
                .field("extra", FieldType.string()).build();

        // Serialize a document with schema v2 (the newer schema)
        var serializerV2 = DocumentSerializer.forSchema(schemaV2);
        var doc = JlsmDocument.of(schemaV2, "value", 42L, "extra", "hello");
        MemorySegment serialized = serializerV2.serialize(doc);

        // Attempt to deserialize v2 bytes with v1 serializer — the reader is older
        // and cannot safely interpret fields from a newer schema version.
        var serializerV1 = DocumentSerializer.forSchema(schemaV1);
        assertThrows(IllegalArgumentException.class, () -> serializerV1.deserialize(serialized),
                "Deserializing bytes written with schema version 2 using a schema version 1 "
                        + "reader must throw IllegalArgumentException — the reader cannot safely "
                        + "interpret data from a newer schema it doesn't understand.");
    }

    // Companion to F-R1.shared_state.8.3: verify forward-compatible reads are allowed
    @Test
    void test_deserialize_olderSchemaVersion_allowedForForwardCompatibility() {
        // Schema v1: field "value" is INT64
        var schemaV1 = JlsmSchema.builder("test", 1).field("value", FieldType.int64()).build();

        // Schema v2: same prefix field + new field added at end
        var schemaV2 = JlsmSchema.builder("test", 2).field("value", FieldType.int64())
                .field("extra", FieldType.string()).build();

        // Serialize with older schema v1
        var serializerV1 = DocumentSerializer.forSchema(schemaV1);
        var doc = JlsmDocument.of(schemaV1, "value", 42L);
        MemorySegment serialized = serializerV1.serialize(doc);

        // Deserialize v1 bytes with v2 serializer — forward-compatible read should work
        var serializerV2 = DocumentSerializer.forSchema(schemaV2);
        JlsmDocument result = serializerV2.deserialize(serialized);
        assertEquals(42L, result.getLong("value"),
                "Forward-compatible read: v1 data deserialized by v2 reader should preserve existing fields");
    }

    // Finding: F-R1.shared_state.8.5
    // Bug: readVarInt overflow guard is assert-only -- crafted input with all continuation
    // bits set causes AssertionError (with -ea) or infinite loop/garbage (without -ea)
    // instead of a clear runtime exception
    // Correct behavior: readVarInt must throw IllegalArgumentException with a descriptive
    // message when the varint encoding exceeds 5 bytes (the maximum for a 32-bit int)
    // Fix location: DocumentSerializer.java readVarInt (~line 1067) — replace assert with
    // runtime if/throw
    // Regression watch: ensure normal varint decoding for STRING fields still works
    @Test
    @Timeout(10)
    void test_readVarInt_overflowGuard_throwsIaeNotAssertionError() throws Exception {
        // Access the private static readVarInt method via reflection
        // Cursor is a private inner class — find it by name
        Class<?> cursorClass = null;
        for (Class<?> inner : DocumentSerializer.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("Cursor")) {
                cursorClass = inner;
                break;
            }
        }
        assertNotNull(cursorClass, "Could not find DocumentSerializer.Cursor inner class");

        Method readVarInt = DocumentSerializer.class.getDeclaredMethod("readVarInt", byte[].class,
                cursorClass);
        readVarInt.setAccessible(true);

        // Create a Cursor positioned at 0
        var cursorCtor = cursorClass.getDeclaredConstructor(byte[].class, int.class);
        cursorCtor.setAccessible(true);

        // Craft a buffer where all bytes have the continuation bit set (0x80).
        // A valid varint for int32 uses at most 5 bytes. This buffer has 10 bytes
        // all with 0x80, so the loop never terminates within 5 bytes.
        byte[] corruptBuf = new byte[10];
        for (int i = 0; i < corruptBuf.length; i++) {
            corruptBuf[i] = (byte) 0x80;
        }
        Object cursor = cursorCtor.newInstance(corruptBuf, 0);

        // readVarInt must throw IllegalArgumentException (runtime check),
        // NOT AssertionError (assert-only check)
        try {
            readVarInt.invoke(null, corruptBuf, cursor);
            fail("readVarInt must throw for varint with >5 continuation bytes, "
                    + "but it returned normally — overflow guard is missing entirely");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            assertFalse(cause instanceof AssertionError,
                    "readVarInt must throw IllegalArgumentException (runtime check) for "
                            + "varint overflow, not AssertionError (assert-only). Assert guards are "
                            + "disabled in production, leaving the overflow undetected.");
            assertInstanceOf(IllegalArgumentException.class, cause,
                    "readVarInt must throw IllegalArgumentException for corrupt varint data "
                            + "with overflow beyond 5 bytes, got: " + cause);
        }
    }

    // Finding: F-R1.shared_state.1.1
    // Bug: extractFieldValue returns raw internal float[] reference for VectorType fields
    // via DocumentAccess.get().values(document)[idx] — caller mutation corrupts document
    // Correct behavior: extractFieldValue must return a defensive copy of the vector array,
    // so that mutation by the caller (index code) does not corrupt the document's state
    // Fix location: IndexRegistry.java extractFieldValue (~line 587) — clone the vector array
    // Regression watch: ensure vector index insertion still receives correct values
    @Test
    void test_extractFieldValue_vectorType_leaksInternalArrayReference() throws Exception {
        var schema = JlsmSchema.builder("test", 1)
                .vectorField("embedding", FieldType.Primitive.FLOAT32, 3).build();

        float[] vector = { 1.0f, 2.0f, 3.0f };
        var doc = JlsmDocument.of(schema, "embedding", vector);

        // Call extractFieldValue via reflection (it's private)
        var method = IndexRegistry.class.getDeclaredMethod("extractFieldValue", JlsmDocument.class,
                String.class);
        method.setAccessible(true);

        // Need an IndexRegistry instance to call the method on
        var defs = List.<IndexDefinition>of();
        var registry = new IndexRegistry(schema, defs);

        Object extracted = method.invoke(registry, doc, "embedding");
        assertInstanceOf(float[].class, extracted,
                "extractFieldValue should return float[] for FLOAT32 vector");

        float[] extractedVector = (float[]) extracted;

        // Mutate the extracted array — simulating what an index insertion path might do
        extractedVector[0] = 999.0f;
        extractedVector[1] = 999.0f;
        extractedVector[2] = 999.0f;

        // The document's internal state must NOT be corrupted by the mutation
        float[] fromDocument = doc.getFloat32Vector("embedding");
        assertEquals(1.0f, fromDocument[0],
                "After mutating the float[] returned by extractFieldValue, the document's "
                        + "getFloat32Vector must still return the original value 1.0f. If this fails, "
                        + "extractFieldValue leaked the internal array reference — caller mutation "
                        + "corrupted the document's state. extractFieldValue must return a defensive copy.");

        registry.close();
    }

    // Finding: F-R1.shared_state.2.4
    // Bug: onUpdate documentStore.put is outside the rollback try/catch scope — when
    // copySegment fails (e.g., arena closed) after all index updates succeed, indices
    // reflect NEW values but documentStore still holds the OLD entry
    // Correct behavior: If copySegment/toPkKey throws after index updates, the index
    // updates must be rolled back so indices and documentStore remain consistent
    // Fix location: IndexRegistry.java:184-185 — move documentStore.put inside the
    // try/catch rollback scope (lines 156-182)
    // Regression watch: Ensure normal (non-failing) updates still work after the move
    @SuppressWarnings("unchecked")
    @Test
    void test_IndexRegistry_onUpdate_copySegmentFailureRollsBackIndexUpdates() throws Exception {
        // Setup: schema with one indexed string field
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var pk1 = stringKey("pk1");
        var oldDoc = JlsmDocument.of(schema, "name", "Alice");
        registry.onInsert(pk1, oldDoc);

        // Verify Alice is indexed
        var nameIndex = registry.findIndex(new Predicate.Eq("name", "Alice"));
        assertNotNull(nameIndex, "Sanity: should find name index");
        assertTrue(nameIndex.lookup(new Predicate.Eq("name", "Alice")).hasNext(),
                "Sanity: Alice should be in the index");

        // Close the segmentArena via reflection — this will cause copySegment to throw
        // IllegalStateException("Already closed") when onUpdate tries to copy the
        // primary key into the arena.
        var arenaField = IndexRegistry.class.getDeclaredField("segmentArena");
        arenaField.setAccessible(true);
        var arena = (Arena) arenaField.get(registry);
        arena.close();

        // Attempt update: name changes from "Alice" to "Bob"
        var newDoc = JlsmDocument.of(schema, "name", "Bob");
        assertThrows(IllegalStateException.class, () -> registry.onUpdate(pk1, oldDoc, newDoc),
                "copySegment should throw because segmentArena is closed");

        // After the failed update, verify index consistency:
        // The index should still have "Alice" (old value), NOT "Bob" (new value).
        // If documentStore.put is outside the rollback scope, the index update to "Bob"
        // succeeded but was never rolled back — creating index/store inconsistency.
        var aliceLookup = nameIndex.lookup(new Predicate.Eq("name", "Alice"));
        assertTrue(aliceLookup.hasNext(),
                "After failed onUpdate (copySegment threw), the index must still contain "
                        + "'Alice' (the old value). If this fails, all index updates succeeded and "
                        + "were NOT rolled back when copySegment failed — the documentStore.put call "
                        + "at line 184 is outside the rollback try/catch scope, so the indices now "
                        + "reflect 'Bob' while documentStore still holds 'Alice'.");

        // Also verify "Bob" is NOT in the index — it should have been rolled back
        var bobLookup = nameIndex.lookup(new Predicate.Eq("name", "Bob"));
        assertFalse(bobLookup.hasNext(),
                "After failed onUpdate, 'Bob' must NOT be in the index — the rollback "
                        + "should have reverted the index update back to 'Alice'.");
    }

    // Finding: F-R1.shared_state.9.1
    // Bug: close() allows concurrent operations to proceed on partially-torn-down state
    // Correct behavior: onInsert that races with close() must either complete fully before
    // close tears down state, or be rejected with IllegalStateException("Registry is closed")
    // — never an uncontrolled crash from torn-down internals (closed arena, closed index)
    // Fix location: IndexRegistry.java close() and onInsert() — need mutual exclusion
    // or re-check of closed flag around critical sections
    // Regression watch: ensure close() is still idempotent and normal inserts still work
    @Test
    @Timeout(10)
    void test_IndexRegistry_close_rejectsConcurrentInsertOnPartiallyTornDownState()
            throws Exception {
        // Strategy: hammer a registry with concurrent inserts while closing it.
        // Without synchronization between close() and onInsert(), the TOCTOU race
        // between closed.get() and the actual mutation means inserts can slip through
        // and hit torn-down state: closed Arena (segmentArena.allocate throws),
        // closed indices (idx.onInsert throws), or cleared documentStore (ghost entry).
        // The fix must ensure every concurrent insert either completes fully before
        // close() tears down, or is rejected with IllegalStateException("Registry is closed").
        int races = 500;
        int inserterCount = 8;
        int uncontrolledErrors = 0;
        int ghostEntries = 0;

        for (int i = 0; i < races; i++) {
            var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();
            var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
            var registry = new IndexRegistry(schema, defs);

            var barrier = new CyclicBarrier(inserterCount + 1);
            var errorsThisRound = new AtomicInteger(0);

            var inserters = new Thread[inserterCount];
            for (int t = 0; t < inserterCount; t++) {
                final int tid = t;
                final int iter = i;
                inserters[t] = Thread.ofPlatform().start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        var pk = stringKey("pk-" + iter + "-" + tid);
                        var doc = JlsmDocument.of(schema, "name", "V-" + iter + "-" + tid);
                        registry.onInsert(pk, doc);
                    } catch (IllegalStateException e) {
                        if (!"Registry is closed".equals(e.getMessage())) {
                            // IllegalStateException from Arena.close or FieldIndex.close,
                            // not from the closed guard — uncontrolled crash
                            errorsThisRound.incrementAndGet();
                        }
                    } catch (Exception _) {
                        errorsThisRound.incrementAndGet();
                    }
                });
            }

            var closer = Thread.ofPlatform().start(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    registry.close();
                } catch (Exception _) {
                }
            });

            closer.join(5000);
            for (var inserter : inserters)
                inserter.join(5000);

            uncontrolledErrors += errorsThisRound.get();

            // Check for ghost entries after close
            var docStoreField = IndexRegistry.class.getDeclaredField("documentStore");
            docStoreField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var docStore = (java.util.Map<?, ?>) docStoreField.get(registry);
            if (!docStore.isEmpty()) {
                ghostEntries++;
            }
        }

        int total = uncontrolledErrors + ghostEntries;
        assertEquals(0, total, "Detected " + total + " bug manifestations across " + races
                + " races (" + uncontrolledErrors + " uncontrolled errors, " + ghostEntries
                + " ghost entries). onInsert() racing with close() must either complete "
                + "cleanly or be rejected with IllegalStateException('Registry is closed')."
                + " The TOCTOU gap between closed.get() and the mutation allows operations "
                + "on partially torn-down state (closed arena, closed indices, cleared store).");
    }

    // Finding: F-R1.shared_state.2.5
    // Bug: onDelete documentStore.remove is outside the rollback try/catch scope — if
    // documentStore.remove throws after all index deletions succeed, indices have removed
    // entries but documentStore still has the document, creating an orphaned document
    // Correct behavior: documentStore.remove must be inside the try/catch rollback scope
    // so that if it throws, index deletions are rolled back (re-inserted)
    // Fix location: IndexRegistry.java:220 — move documentStore.remove inside the try block
    // Regression watch: ensure normal (non-failing) deletes still work after the move
    @SuppressWarnings("unchecked")
    @Test
    void test_IndexRegistry_onDelete_documentStoreRemoveFailureRollsBackIndexDeletions()
            throws Exception {
        // Setup: schema with one indexed string field
        var schema = JlsmSchema.builder("test", 1).field("name", FieldType.string()).build();

        var defs = List.of(new IndexDefinition("name", IndexType.EQUALITY));
        var registry = new IndexRegistry(schema, defs);

        var pk1 = stringKey("pk1");
        var doc = JlsmDocument.of(schema, "name", "Alice");
        registry.onInsert(pk1, doc);

        // Verify Alice is indexed before the delete attempt
        var nameIndex = registry.findIndex(new Predicate.Eq("name", "Alice"));
        assertNotNull(nameIndex, "Sanity: should find name index");
        assertTrue(nameIndex.lookup(new Predicate.Eq("name", "Alice")).hasNext(),
                "Sanity: Alice should be in the index");

        // Swap the documentStore with one that throws on remove().
        // This simulates any failure in the documentStore.remove(toPkKey(...)) path
        // AFTER all index deletions have already succeeded.
        var docStoreField = IndexRegistry.class.getDeclaredField("documentStore");
        docStoreField.setAccessible(true);
        var originalStore = (Map<Object, Object>) docStoreField.get(registry);

        var throwingStore = new ConcurrentHashMap<>(originalStore) {
            @Override
            public Object remove(Object key) {
                throw new RuntimeException("Simulated documentStore.remove failure");
            }
        };
        docStoreField.set(registry, throwingStore);

        // Attempt delete: should throw because documentStore.remove fails
        assertThrows(RuntimeException.class, () -> registry.onDelete(pk1, doc),
                "documentStore.remove should throw the simulated failure");

        // Restore original store so we can query
        docStoreField.set(registry, originalStore);

        // After the failed delete, verify index consistency:
        // The index should still have "Alice" because the rollback should have
        // re-inserted the entry after documentStore.remove failed.
        var aliceLookup = nameIndex.lookup(new Predicate.Eq("name", "Alice"));
        assertTrue(aliceLookup.hasNext(),
                "After failed onDelete (documentStore.remove threw), the index must still "
                        + "contain 'Alice'. If this fails, all index deletions succeeded and were NOT "
                        + "rolled back when documentStore.remove failed — the documentStore.remove call "
                        + "is outside the rollback try/catch scope, so indices have removed 'Alice' "
                        + "while documentStore still holds the document (orphaned document).");

        // Also verify documentStore still has the entry (it was never removed because remove threw)
        var storedEntry = registry.resolveEntry(pk1);
        assertNotNull(storedEntry,
                "Document should still exist in document store after failed delete");
        assertEquals("Alice", storedEntry.document().getString("name"),
                "documentStore should still have the document after failed delete");

        registry.close();
    }

    // Finding: F-R1.shared_state.1.3
    // Bug: Builder passes mutable LinkedHashMap to constructor — clients map is not defensively
    // copied
    // Correct behavior: The clients map should be wrapped in Collections.unmodifiableMap so
    // post-construction mutation is impossible
    // Fix location: PartitionedTable.java constructor — wrap clients in Collections.unmodifiableMap
    // Regression watch: ensure all CRUD routing still works after wrapping
    @SuppressWarnings("unchecked")
    @Test
    void test_PartitionedTable_clientsMap_isUnmodifiableAfterConstruction() throws Exception {
        // Build a PartitionedTable with one partition
        final PartitionDescriptor desc = new PartitionDescriptor(1L,
                MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8)), "local", 0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc));

        final PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .partitionClientFactory(d -> new StubPartitionClient()).build();

        // Access the internal clients map via reflection
        var clientsField = PartitionedTable.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        var clientsMap = (Map<Long, PartitionClient>) clientsField.get(table);

        // Attempting to mutate the map should throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class,
                () -> clientsMap.put(99L, new StubPartitionClient()),
                "clients map must be unmodifiable after construction — "
                        + "mutable map allows post-construction corruption of routing state");

        table.close();
    }

    /**
     * Minimal stub PartitionClient for testing PartitionedTable construction.
     */
    private static class StubPartitionClient implements PartitionClient {
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
        public PartitionDescriptor descriptor() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    // Finding: F-R1.shared_state.2.1
    // Bug: overlapping() uses O(P) linear scan instead of O(log P) binary search
    // Correct behavior: overlapping() should use binary search on sorted contiguous
    // descriptors, achieving O(log P) as documented in the class Javadoc
    // Fix location: RangeMap.java:111-129 — replace linear scan with binary search
    // Regression watch: ensure overlapping() still returns correct results for all edge cases
    @Test
    @Timeout(30)
    void test_RangeMap_overlapping_usesSublinearAlgorithm() {
        // Create a RangeMap with 10,000 contiguous partitions.
        // Each partition covers a 4-byte key range: [i*4, (i+1)*4).
        int partitionCount = 10_000;
        var descs = new ArrayList<PartitionDescriptor>();
        for (int i = 0; i < partitionCount; i++) {
            byte[] low = intToBytes(i * 4);
            byte[] high = intToBytes((i + 1) * 4);
            descs.add(new PartitionDescriptor(i, MemorySegment.ofArray(low),
                    MemorySegment.ofArray(high), "node-0", 1L));
        }
        var config = PartitionConfig.of(descs);
        var rangeMap = new RangeMap(config);

        // Warmup: call overlapping a few times to stabilize JIT
        for (int w = 0; w < 100; w++) {
            rangeMap.overlapping(MemorySegment.ofArray(intToBytes(5000 * 4)),
                    MemorySegment.ofArray(intToBytes(5001 * 4)));
        }

        // Measure: call overlapping() 10,000 times for a narrow 2-partition range
        // in the middle of the keyspace. With O(log P) ~14 comparisons each,
        // this should complete in well under 1 second. With O(P) ~10,000
        // comparisons each, we get 100M comparisons — measurably slower.
        int iterations = 10_000;
        var fromKey = MemorySegment.ofArray(intToBytes(5000 * 4));
        var toKey = MemorySegment.ofArray(intToBytes(5002 * 4));

        long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var result = rangeMap.overlapping(fromKey, toKey);
            assertEquals(2, result.size(),
                    "Narrow range [5000*4, 5002*4) should overlap exactly 2 partitions");
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        // Budget: O(log P) with 10K partitions and 10K iterations should complete
        // in well under 500ms. O(P) with 10K partitions and 10K iterations produces
        // 100M comparisons — typically 1-5 seconds. We use 500ms as a conservative
        // threshold that O(log P) easily meets.
        assertTrue(elapsedMs < 500, "overlapping() with " + partitionCount + " partitions x "
                + iterations + " iterations took " + elapsedMs
                + "ms — expected < 500ms for O(log P). " + "Linear scan (O(P)) produces "
                + ((long) partitionCount * iterations)
                + " comparisons, violating the O(log P) contract documented in RangeMap Javadoc.");
    }

    // Finding: F-R1.shared_state.2.2
    // Bug: RangeMap constructor uses assert-only guard for non-empty descriptors (line 43).
    // With assertions disabled, an empty PartitionConfig silently creates a broken RangeMap
    // whose routeKey always throws a misleading "Key is below all partition ranges" error.
    // Correct behavior: RangeMap constructor should throw IllegalArgumentException for empty
    // descriptors with a clear message, regardless of assertion settings.
    // Fix location: RangeMap.java:43 — add runtime check before the assert
    // Regression watch: ensure normal (non-empty) RangeMap construction still works
    @Test
    void test_RangeMap_constructor_emptyDescriptors_throwsIaeInsteadOfAssert() throws Exception {
        // Create a PartitionConfig with empty descriptors by bypassing the constructor's
        // assert guard via sun.misc.Unsafe.allocateInstance (simulates deserialization bypass).
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var emptyConfig = (PartitionConfig) unsafe.allocateInstance(PartitionConfig.class);

        // Set the private descriptors field to an empty list
        var descriptorsField = PartitionConfig.class.getDeclaredField("descriptors");
        descriptorsField.setAccessible(true);
        descriptorsField.set(emptyConfig, List.of());

        // Verify our setup: config.descriptors() returns empty list
        assertTrue(emptyConfig.descriptors().isEmpty(),
                "Test setup: emptyConfig should have empty descriptors");

        // RangeMap's constructor should reject empty descriptors with a runtime check
        var ex = assertThrows(IllegalArgumentException.class, () -> new RangeMap(emptyConfig),
                "RangeMap constructor must throw IllegalArgumentException for empty descriptors, "
                        + "not silently accept them (assert-only guard is disabled in production)");
        assertTrue(ex.getMessage().contains("at least one partition"),
                "Exception message should indicate the descriptors list needs at least one partition");
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{ (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
                (byte) value };
    }

}
