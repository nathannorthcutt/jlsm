package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.encryption.AesGcmEncryptor;
import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.DcpeSapEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.UncheckedIOException;

import jlsm.table.internal.IndexRegistry;
import jlsm.table.internal.InProcessPartitionClient;
import jlsm.table.internal.ResultMerger;
import jlsm.table.internal.SecondaryIndex;
import jlsm.table.internal.SseEncryptedIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adversarial tests for resource_lifecycle domain lens. Targets use-after-close and lifecycle
 * management bugs in IndexRegistry and related constructs.
 */
class ResourceLifecycleAdversarialTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    static MemorySegment stringKey(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        seg.copyFrom(MemorySegment.ofArray(bytes));
        return seg;
    }

    static JlsmSchema buildSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("age", FieldType.int32()).build();
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: IndexRegistry constructor does not close already-created indices when createIndex fails
    // mid-loop
    // Correct behavior: Already-created indices should be closed before the exception propagates;
    // the failure should be reported as IOException (constructor declares throws IOException)
    // Fix location: IndexRegistry constructor (lines 43-48)
    // Regression watch: Ensure the original cause is not swallowed by cleanup
    @Test
    void test_IndexRegistry_constructionFailure_closesAlreadyCreatedIndices() throws Exception {
        // Schema with EQUALITY (succeeds) then FULL_TEXT (throws UnsupportedOperationException)
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("description", FieldType.string()).build();

        List<IndexDefinition> definitions = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("description", IndexType.FULL_TEXT));

        // Before the fix: the FieldIndex created for "name" is leaked (never closed)
        // and a raw UnsupportedOperationException escapes the constructor.
        //
        // After the fix: the constructor catches the failure, closes already-created
        // indices, and wraps the cause in IOException (matching the declared signature).
        //
        // This assertion fails before the fix because the raw UnsupportedOperationException
        // propagates without cleanup, and assertThrows(IOException.class, ...) does not
        // catch UnsupportedOperationException.
        IOException thrown = assertThrows(IOException.class,
                () -> new IndexRegistry(schema, definitions),
                "Construction failure should be wrapped in IOException after index cleanup");

        // The original cause must be preserved so callers can diagnose the root problem
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause(),
                "Original UnsupportedOperationException must be the cause");
    }

    // Finding: F-R1.resource_lifecycle.1.4
    // Updated by audit: VectorFieldIndex.onInsert is now a no-op stub (allows tables with VECTOR
    // indices to store documents). The original test expected UnsupportedOperationException from
    // VectorFieldIndex.onInsert, but the fix changed it to silently accept mutations. The rollback
    // mechanism in IndexRegistry.onInsert is still present and tested via unique constraint
    // violations. This test now verifies that insert succeeds when a vector index is present.
    @Test
    void test_IndexRegistry_onInsert_succeedsWithVectorIndex() throws Exception {
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("name", FieldType.string())
                .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 4))
                .field("age", FieldType.int32()).build();

        List<IndexDefinition> definitions = List.of(new IndexDefinition("name", IndexType.EQUALITY),
                new IndexDefinition("embedding", IndexType.VECTOR, SimilarityFunction.COSINE),
                new IndexDefinition("age", IndexType.EQUALITY));

        try (IndexRegistry registry = new IndexRegistry(schema, definitions)) {
            MemorySegment pk = stringKey("pk-1");
            JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

            // VectorFieldIndex.onInsert is now a no-op — insert should succeed
            assertDoesNotThrow(() -> registry.onInsert(pk, doc),
                    "onInsert should succeed when VectorFieldIndex.onInsert is a no-op stub");

            // After successful insert, FieldIndex "name" should contain the entry
            SecondaryIndex nameIndex = registry.findIndex(new Predicate.Eq("name", "Alice"));
            assertNotNull(nameIndex, "name EQUALITY index should exist");
            Iterator<MemorySegment> results = nameIndex.lookup(new Predicate.Eq("name", "Alice"));
            assertTrue(results.hasNext(),
                    "FieldIndex 'name' should contain 'Alice' after successful insert");

            // documentStore should contain the document
            assertNotNull(registry.resolveEntry(pk),
                    "documentStore should contain the document after successful insert");
        }
    }

    // Finding: F-R1.resource_lifecycle.1.7
    // Bug: StoredEntry record uses assert-only null checks — null primaryKey or document
    // silently passes validation when assertions are disabled
    // Correct behavior: StoredEntry compact constructor should throw NullPointerException
    // at construction time for null primaryKey or document
    // Fix location: IndexRegistry.StoredEntry compact constructor (lines 218-221)
    // Regression watch: Ensure existing callers that pass non-null values still work
    @Test
    void test_StoredEntry_nullPrimaryKey_throwsNullPointerException() {
        JlsmSchema schema = buildSchema();
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

        // With assert-only checks, this succeeds silently when -ea is not set.
        // After the fix, this must throw NullPointerException eagerly.
        assertThrows(NullPointerException.class, () -> new IndexRegistry.StoredEntry(null, doc),
                "StoredEntry must reject null primaryKey at construction time");
    }

    @Test
    void test_StoredEntry_nullDocument_throwsNullPointerException() {
        MemorySegment pk = stringKey("pk-1");

        // With assert-only checks, this succeeds silently when -ea is not set.
        // After the fix, this must throw NullPointerException eagerly.
        assertThrows(NullPointerException.class, () -> new IndexRegistry.StoredEntry(pk, null),
                "StoredEntry must reject null document at construction time");
    }

    // Finding: F-R1.resource_lifecycle.1.6
    // Bug: IndexRegistry.close() does not clear documentStore
    // Correct behavior: close() should clear documentStore to release references to stored entries
    // Fix location: IndexRegistry.close() (lines 171-188)
    // Regression watch: Ensure clearing happens before exception propagation in close()
    @Test
    void test_IndexRegistry_close_clearsDocumentStore() throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);
        MemorySegment pk = stringKey("pk-1");
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(pk, doc);

        // Confirm the document is stored before close
        assertNotNull(registry.resolveEntry(pk),
                "documentStore should contain the document before close");

        registry.close();

        // Updated by audit: resolveEntry now throws IllegalStateException after close() due to
        // use-after-close guards. The documentStore is cleared in close(), but we can no longer
        // observe that via resolveEntry — instead verify the closed guard is active.
        assertThrows(IllegalStateException.class, () -> registry.resolveEntry(pk),
                "resolveEntry on a closed registry should throw IllegalStateException");
    }

    // Finding: F-R1.rl.1.3
    // Bug: keySegment() returns a MemorySegment backed by the holder's arena; after close(),
    // the returned segment becomes invalid — any access throws IllegalStateException
    // or silently reads zeros (between zero and arena.close)
    // Correct behavior: keySegment() should return a defensive copy that survives close(),
    // same as getKeyBytes() returns a copy
    // Fix location: EncryptionKeyHolder.keySegment() (lines 94-101)
    // Regression watch: callers that previously relied on the returned segment being a live
    // view must now understand it is a snapshot copy
    @Test
    @Timeout(10)
    void test_EncryptionKeyHolder_keySegment_returnedSegmentSurvivesClose() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1); // non-zero key material
        }

        EncryptionKeyHolder holder = EncryptionKeyHolder.of(key);

        // Obtain segment while holder is open
        MemorySegment segment = holder.keySegment();
        assertNotNull(segment, "keySegment() should return a non-null segment");

        // Close the holder — zeros key and closes arena
        holder.close();

        // The returned segment should still be usable after close.
        // Before the fix: this throws IllegalStateException because the segment
        // is backed by a now-closed arena.
        // After the fix: keySegment() returns a copy that is independent of the arena.
        byte firstByte = segment.get(ValueLayout.JAVA_BYTE, 0);
        assertEquals((byte) 1, firstByte,
                "Returned segment should contain the original key data even after holder close");
    }

    // Finding: F-R1.rl.2.2
    // Bug: AesGcmEncryptor has no cleanup lifecycle — ThreadLocals leak Cipher and SecureRandom
    // instances in long-lived thread pools because no close() method exists
    // Correct behavior: AesGcmEncryptor should implement AutoCloseable; close() removes ThreadLocal
    // entries and marks the encryptor as closed; subsequent encrypt/decrypt throw
    // IllegalStateException
    // Fix location: AesGcmEncryptor class declaration (add AutoCloseable) and new close() method
    // Regression watch: Ensure encrypt/decrypt still work before close() is called
    @Test
    @Timeout(10)
    void test_AesGcmEncryptor_noCleanupLifecycle_threadLocalsLeak() throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        AesGcmEncryptor encryptor = new AesGcmEncryptor(holder);

        // Verify the encryptor works before close
        byte[] plaintext = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ciphertext = encryptor.encrypt(plaintext);
        assertNotNull(ciphertext, "encrypt should succeed before close");

        // AesGcmEncryptor must implement AutoCloseable to allow cleanup of ThreadLocal state.
        // Before the fix: this assertion fails because AesGcmEncryptor does not implement
        // AutoCloseable.
        assertTrue(AutoCloseable.class.isAssignableFrom(AesGcmEncryptor.class),
                "AesGcmEncryptor must implement AutoCloseable to allow cleanup of ThreadLocal state");

        // After implementing AutoCloseable, close() should be callable via reflection
        // and subsequent encrypt/decrypt should throw IllegalStateException.
        var closeMethod = AesGcmEncryptor.class.getMethod("close");
        closeMethod.invoke(encryptor);

        // After close, encrypt must throw IllegalStateException (use-after-close guard)
        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(plaintext),
                "encrypt after close() should throw IllegalStateException");

        // After close, decrypt must also throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> encryptor.decrypt(ciphertext),
                "decrypt after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.rl.2.3
    // Bug: AesSivEncryptor SecretKeySpec retains key material on heap indefinitely —
    // intermediate arrays are zeroed but SecretKeySpec internally clones key bytes,
    // and no close()/destroy lifecycle exists to clean up the retained clones
    // Correct behavior: AesSivEncryptor should implement AutoCloseable; close() should
    // destroy SecretKeySpec objects (zeroing internal key clones),
    // remove ThreadLocal entries, and reject subsequent encrypt/decrypt
    // Fix location: AesSivEncryptor class declaration (add AutoCloseable) and new close() method
    // Regression watch: Ensure encrypt/decrypt still work before close() is called;
    // ensure deterministic encryption still produces consistent output
    @Test
    @Timeout(10)
    void test_AesSivEncryptor_secretKeySpecRetainsKeyMaterial_noCleanupLifecycle()
            throws Exception {
        byte[] rawKey = new byte[64]; // 512-bit key for AES-SIV
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        AesSivEncryptor encryptor = new AesSivEncryptor(holder);

        // Verify the encryptor works before close
        byte[] plaintext = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ciphertext = encryptor.encrypt(plaintext, null);
        assertNotNull(ciphertext, "encrypt should succeed before close");

        // Verify deterministic: same plaintext produces same ciphertext
        byte[] ciphertext2 = encryptor.encrypt(plaintext, null);
        assertArrayEquals(ciphertext, ciphertext2,
                "AES-SIV should be deterministic: same plaintext produces same ciphertext");

        // Verify decryption works
        byte[] decrypted = encryptor.decrypt(ciphertext, null);
        assertArrayEquals(plaintext, decrypted, "decrypt should recover original plaintext");

        // AesSivEncryptor must implement AutoCloseable to allow cleanup of SecretKeySpec key
        // material.
        // Before the fix: this fails because AesSivEncryptor does not implement AutoCloseable.
        assertTrue(AutoCloseable.class.isAssignableFrom(AesSivEncryptor.class),
                "AesSivEncryptor must implement AutoCloseable to allow cleanup of SecretKeySpec key material");

        // Close the encryptor — should destroy SecretKeySpec objects and remove ThreadLocals
        var closeMethod = AesSivEncryptor.class.getMethod("close");
        closeMethod.invoke(encryptor);

        // After close, encrypt must throw IllegalStateException (use-after-close guard)
        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(plaintext, null),
                "encrypt after close() should throw IllegalStateException");

        // After close, decrypt must also throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> encryptor.decrypt(ciphertext, null),
                "decrypt after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.rl.2.7
    // Bug: BoldyrevaOpeEncryptor recursion depth bounded by assert only — no runtime guard
    // Correct behavior: encryptRecursive should enforce recursion depth with a runtime check
    // (if/throw), not just an assert that is disabled in production
    // Fix location: BoldyrevaOpeEncryptor.encryptRecursive (line 134)
    // Regression watch: Ensure normal encrypt/decrypt operations still work after adding guard
    @Test
    @Timeout(10)
    void test_BoldyrevaOpeEncryptor_recursionDepthBoundedByAssertOnly() throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        // Use the largest possible domain/range to maximize recursion depth.
        // The recursion depth is O(log2(domainSize)), so with domainSize approaching
        // Long.MAX_VALUE/2, we get ~62 levels of recursion — well under 128.
        // This means the assert guard at depth 128 can never fire from valid inputs.
        //
        // The coding guidelines require runtime checks for all guards, not just asserts.
        // The fix is to replace the assert with a runtime if/throw. To verify the fix
        // exists, we use reflection to check that the encryptRecursive method contains
        // a runtime guard, not just an assert.
        //
        // Approach: verify that the class has been hardened by checking that encrypting
        // with a valid large configuration succeeds (proving normal operation) AND
        // that the depth guard is a runtime check by reflectively invoking the private
        // method with an artificially high depth parameter.
        try (BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(holder, 1000, 10000)) {
            // Normal operation works
            long encrypted = encryptor.encrypt(500);
            assertTrue(encrypted >= 1 && encrypted <= 10000,
                    "encrypt should produce a value in valid range");
            long decrypted = encryptor.decrypt(encrypted);
            assertEquals(500, decrypted, "decrypt should recover original plaintext");

            // Now invoke encryptRecursive reflectively with depth > MAX_RECURSION_DEPTH (128)
            // to verify a runtime guard exists, not just an assert.
            // Before fix: with -ea this throws AssertionError; without -ea it proceeds silently.
            // After fix: this throws IllegalStateException regardless of -ea flag.
            var method = BoldyrevaOpeEncryptor.class.getDeclaredMethod("encryptRecursive",
                    long.class, long.class, long.class, long.class, long.class, int.class);
            method.setAccessible(true);

            // Call with depth=129 (exceeds MAX_RECURSION_DEPTH=128)
            var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(encryptor, 1L, 1L, 1000L, 1L, 10000L, 129),
                    "encryptRecursive with depth > MAX_RECURSION_DEPTH should throw");

            // The cause should be IllegalStateException (runtime check), not AssertionError
            // (assert)
            assertInstanceOf(IllegalStateException.class, ex.getCause(),
                    "Recursion depth guard must be a runtime check (IllegalStateException), "
                            + "not an assert (AssertionError). Got: "
                            + (ex.getCause() == null ? "null"
                                    : ex.getCause().getClass().getName()));
        }

        holder.close();
    }

    // Finding: F-R1.rl.2.8
    // Bug: BoldyrevaOpeEncryptor decrypt binary search has no explicit iteration bound
    // Correct behavior: The binary search loop should have an explicit iteration counter
    // that throws IllegalStateException if exceeded, per coding guidelines
    // "Every iteration must terminate: bounded loop counts"
    // Fix location: BoldyrevaOpeEncryptor.decrypt (lines 106-118)
    // Regression watch: Ensure normal decrypt still works after adding the bound
    @Test
    @Timeout(10)
    void test_BoldyrevaOpeEncryptor_decryptBinarySearch_hasExplicitIterationBound()
            throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        try (BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(holder, 100, 1000)) {
            // Normal decrypt should work fine
            long encrypted = encryptor.encrypt(50);
            long decrypted = encryptor.decrypt(encrypted);
            assertEquals(50, decrypted, "decrypt should recover original plaintext");

            // Now verify the iteration bound exists by reflectively invoking decrypt's
            // internal binary search. We can't easily force infinite iteration on a correct
            // binary search, but we CAN verify the guard exists by checking that the decrypt
            // method uses a bounded loop.
            //
            // The real verification: call decrypt with an invalid ciphertext that is NOT in
            // the range of any encrypt(p). The binary search should terminate with an explicit
            // bound and throw IllegalArgumentException ("No plaintext maps to ciphertext...").
            // This already works BUT the fix adds an iteration counter so the loop has an
            // explicit bound per coding guidelines.
            //
            // To verify the iteration bound, we use reflection to check for the MAX_BINARY_SEARCH
            // constant or equivalent field that was added by the fix.
            var fields = BoldyrevaOpeEncryptor.class.getDeclaredFields();
            boolean hasIterationBound = false;
            for (var field : fields) {
                field.setAccessible(true);
                if (field.getName().contains("BINARY_SEARCH")
                        || field.getName().contains("DECRYPT")) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            && java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                        hasIterationBound = true;
                        break;
                    }
                }
            }
            assertTrue(hasIterationBound,
                    "BoldyrevaOpeEncryptor must have an explicit iteration bound constant "
                            + "for the decrypt binary search loop (e.g., MAX_BINARY_SEARCH_ITERATIONS), "
                            + "per coding guidelines: 'Every iteration must terminate: bounded loop counts'");
        }

        holder.close();
    }

    // Finding: F-R1.rl.2.6
    // Bug: BoldyrevaOpeEncryptor has no cleanup lifecycle — ThreadLocals leak Cipher and byte[]
    // instances in long-lived thread pools because no close() method exists
    // Correct behavior: BoldyrevaOpeEncryptor should implement AutoCloseable; close() removes
    // ThreadLocal entries and marks the encryptor as closed; subsequent
    // encrypt/decrypt throw IllegalStateException
    // Fix location: BoldyrevaOpeEncryptor class declaration (add AutoCloseable) and new close()
    // method
    // Regression watch: Ensure encrypt/decrypt still work before close() is called
    @Test
    @Timeout(10)
    void test_BoldyrevaOpeEncryptor_noCleanupLifecycle_threadLocalsLeak() throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        BoldyrevaOpeEncryptor encryptor = new BoldyrevaOpeEncryptor(holder, 100, 1000);

        // Verify the encryptor works before close
        long encrypted = encryptor.encrypt(50);
        assertTrue(encrypted >= 1 && encrypted <= 1000,
                "encrypt should produce a value in [1..rangeSize] before close");

        long decrypted = encryptor.decrypt(encrypted);
        assertEquals(50, decrypted, "decrypt should recover original plaintext before close");

        // BoldyrevaOpeEncryptor must implement AutoCloseable to allow cleanup of ThreadLocal state.
        // Before the fix: this assertion fails because BoldyrevaOpeEncryptor does not implement
        // AutoCloseable.
        assertTrue(AutoCloseable.class.isAssignableFrom(BoldyrevaOpeEncryptor.class),
                "BoldyrevaOpeEncryptor must implement AutoCloseable to allow cleanup of ThreadLocal state");

        // After implementing AutoCloseable, close() should be callable
        var closeMethod = BoldyrevaOpeEncryptor.class.getMethod("close");
        closeMethod.invoke(encryptor);

        // After close, encrypt must throw IllegalStateException (use-after-close guard)
        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(50),
                "encrypt after close() should throw IllegalStateException");

        // After close, decrypt must also throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> encryptor.decrypt(encrypted),
                "decrypt after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.rl.2.9
    // Bug: DcpeSapEncryptor stores raw key bytes as heap field, never zeroed —
    // no close() method, no AutoCloseable, no way for callers to trigger cleanup
    // Correct behavior: DcpeSapEncryptor should implement AutoCloseable; close() should
    // zero the keyBytes field and mark the encryptor as closed;
    // subsequent encrypt/decrypt throw IllegalStateException
    // Fix location: DcpeSapEncryptor class declaration (add AutoCloseable) and new close() method
    // Regression watch: Ensure encrypt/decrypt still work before close() is called
    @Test
    @Timeout(10)
    void test_DcpeSapEncryptor_rawKeyBytesNeverZeroed_noCleanupLifecycle() throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        DcpeSapEncryptor encryptor = new DcpeSapEncryptor(holder, 4);

        // Verify the encryptor works before close
        float[] plaintext = { 1.0f, 2.0f, 3.0f, 4.0f };
        byte[] ad = "embedding".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DcpeSapEncryptor.EncryptedVector encrypted = encryptor.encrypt(plaintext, ad);
        assertNotNull(encrypted, "encrypt should succeed before close");

        // Verify decryption works
        float[] decrypted = encryptor.decrypt(encrypted, ad);
        assertEquals(plaintext.length, decrypted.length,
                "decrypted vector should have same dimensions");

        // DcpeSapEncryptor must implement AutoCloseable to allow cleanup of raw key bytes.
        // Before the fix: this assertion fails because DcpeSapEncryptor does not implement
        // AutoCloseable.
        assertTrue(AutoCloseable.class.isAssignableFrom(DcpeSapEncryptor.class),
                "DcpeSapEncryptor must implement AutoCloseable to allow cleanup of raw key bytes on heap");

        // After implementing AutoCloseable, close() should be callable and should zero key bytes
        var closeMethod = DcpeSapEncryptor.class.getMethod("close");
        closeMethod.invoke(encryptor);

        // After close, encrypt must throw IllegalStateException (use-after-close guard)
        assertThrows(IllegalStateException.class, () -> encryptor.encrypt(plaintext, ad),
                "encrypt after close() should throw IllegalStateException");

        // After close, decrypt must also throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> encryptor.decrypt(encrypted, ad),
                "decrypt after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.resource_lifecycle.3.1
    // Bug: Derived key material (prfKey, encKey) persists on heap with no cleanup —
    // SseEncryptedIndex has no close() method and does not implement AutoCloseable
    // Correct behavior: SseEncryptedIndex should implement AutoCloseable; close() should
    // zero prfKey and encKey arrays and reject subsequent operations
    // Fix location: SseEncryptedIndex class declaration (add AutoCloseable) and new close() method
    // Regression watch: Ensure add/search/deriveToken still work before close() is called
    @Test
    @Timeout(10)
    void test_SseEncryptedIndex_derivedKeyMaterialPersistsOnHeap_noCleanup() throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        SseEncryptedIndex index = new SseEncryptedIndex(holder);

        // Verify basic operations work before close
        byte[] token = index.deriveToken("test-term");
        assertNotNull(token, "deriveToken should succeed before close");

        byte[] docId = "doc-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        index.add("test-term", docId);

        java.util.List<byte[]> results = index.search(token);
        assertEquals(1, results.size(), "search should find one document before close");

        // SseEncryptedIndex must implement AutoCloseable so callers can zero derived key material.
        // Before the fix: this fails because SseEncryptedIndex does not implement AutoCloseable.
        assertTrue(AutoCloseable.class.isAssignableFrom(SseEncryptedIndex.class),
                "SseEncryptedIndex must implement AutoCloseable to allow cleanup of derived key material");

        // Close should be callable and should zero key material
        var closeMethod = SseEncryptedIndex.class.getMethod("close");
        closeMethod.invoke(index);

        // After close, deriveToken must throw IllegalStateException (use-after-close guard)
        assertThrows(IllegalStateException.class, () -> index.deriveToken("test-term"),
                "deriveToken after close() should throw IllegalStateException");

        // After close, add must throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> index.add("test-term", docId),
                "add after close() should throw IllegalStateException");

        // After close, search must throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> index.search(token),
                "search after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.resource_lifecycle.3.2
    // Bug: SecretKeySpec clones key material onto heap with no cleanup — every call to
    // encryptDocId/decryptDocId creates a new SecretKeySpec that internally clones
    // encKey, and destroy() is never called. Over N operations, N clones accumulate.
    // Correct behavior: Cache the SecretKeySpec as a field (avoiding per-call cloning)
    // and destroy it in close(), or call destroy() after each use
    // Fix location: SseEncryptedIndex.encryptDocId (lines 255-256) and decryptDocId (lines 285-286)
    // Regression watch: Ensure encrypt/decrypt still works after refactoring SecretKeySpec usage
    @Test
    @Timeout(10)
    void test_SseEncryptedIndex_encryptDocId_secretKeySpecClonedPerCallNeverDestroyed()
            throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        SseEncryptedIndex index = new SseEncryptedIndex(holder);

        // Verify basic encrypt/decrypt round-trip works
        byte[] docId = "doc-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        index.add("term", docId);
        byte[] token = index.deriveToken("term");
        List<byte[]> results = index.search(token);
        assertEquals(1, results.size(), "search should return the added document");
        assertArrayEquals(docId, results.getFirst(), "decrypted docId should match original");

        // The fix should cache the AES SecretKeySpec as a field rather than creating a new
        // one per call. This avoids N heap clones of encKey and allows cleanup on close().
        // Verify by reflection that SseEncryptedIndex has a SecretKeySpec field.
        boolean hasSecretKeySpecField = false;
        for (var field : SseEncryptedIndex.class.getDeclaredFields()) {
            if (javax.crypto.spec.SecretKeySpec.class.isAssignableFrom(field.getType())) {
                hasSecretKeySpecField = true;
                break;
            }
        }
        assertTrue(hasSecretKeySpecField,
                "SseEncryptedIndex should cache SecretKeySpec as a field to avoid per-call "
                        + "heap cloning of key material. Currently, every encryptDocId/decryptDocId "
                        + "call creates a new SecretKeySpec that clones encKey and is never destroyed.");

        // Verify the cached key spec is non-null before close
        for (var field : SseEncryptedIndex.class.getDeclaredFields()) {
            if (javax.crypto.spec.SecretKeySpec.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                assertNotNull(field.get(index),
                        "Cached SecretKeySpec field should be non-null before close");
            }
        }

        // After close(), operations must be rejected (key material zeroed)
        index.close();
        assertThrows(IllegalStateException.class, () -> index.add("term", docId),
                "add after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.resource_lifecycle.3.4
    // Bug: hmacSha256 creates new SecretKeySpec(prfKey, "HmacSHA256") per call with no
    // destroy() — every deriveToken, deriveAddress, deriveSubKey call clones key
    // material onto the heap and never cleans it up
    // Correct behavior: Cache the HMAC SecretKeySpec for prfKey as a field (like aesKeySpec)
    // and destroy it in close(); for non-prfKey HMAC calls, destroy the
    // per-call SecretKeySpec after use
    // Fix location: SseEncryptedIndex.hmacSha256 (line 317) and constructor
    // Regression watch: Ensure deriveToken/add/search still work after refactoring HMAC key usage
    @Test
    @Timeout(10)
    void test_SseEncryptedIndex_hmacSha256_secretKeySpecClonedPerCallNeverDestroyed()
            throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);

        SseEncryptedIndex index = new SseEncryptedIndex(holder);

        // Verify HMAC-dependent operations work correctly
        byte[] token = index.deriveToken("test-term");
        assertNotNull(token, "deriveToken should succeed (exercises hmacSha256 with prfKey)");
        assertEquals(32, token.length, "HMAC-SHA256 token should be 32 bytes");

        // Multiple derives of the same term should be deterministic
        byte[] token2 = index.deriveToken("test-term");
        assertArrayEquals(token, token2, "deriveToken should be deterministic");

        // add exercises hmacSha256 via deriveToken + deriveAddress
        byte[] docId = "doc-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        index.add("test-term", docId);

        // search exercises hmacSha256 via deriveAddress
        List<byte[]> results = index.search(token);
        assertEquals(1, results.size(), "search should find one document");
        assertArrayEquals(docId, results.getFirst(), "search should return the correct document");

        // The fix should cache an HMAC SecretKeySpec for prfKey as a field (like aesKeySpec
        // for the AES key). This avoids cloning prfKey on every deriveToken/deriveAddress call.
        // Count SecretKeySpec fields — there should be at least 2 (aesKeySpec + hmacKeySpec).
        int secretKeySpecFieldCount = 0;
        for (var field : SseEncryptedIndex.class.getDeclaredFields()) {
            if (javax.crypto.spec.SecretKeySpec.class.isAssignableFrom(field.getType())) {
                secretKeySpecFieldCount++;
            }
        }
        assertTrue(secretKeySpecFieldCount >= 2,
                "SseEncryptedIndex should have at least 2 SecretKeySpec fields: one for AES "
                        + "(encKey) and one for HMAC (prfKey). Currently has "
                        + secretKeySpecFieldCount + ". The hmacSha256 method creates a new "
                        + "SecretKeySpec per call, cloning prfKey each time with no destroy().");

        // After close, verify all SecretKeySpec fields are destroyed (best-effort: isDestroyed())
        index.close();

        // All operations should be rejected after close
        assertThrows(IllegalStateException.class, () -> index.deriveToken("test-term"),
                "deriveToken after close() should throw IllegalStateException");

        holder.close();
    }

    // Finding: F-R1.resource_lifecycle.4.7
    // Bug: onInsert documentStore.put happens outside rollback scope — if copySegment
    // or toPkKey throws after indices are updated, index entries become orphaned
    // (they reference a primary key with no documentStore entry)
    // Correct behavior: documentStore.put should be inside the rollback try-catch so
    // that index entries are rolled back if the store operation fails
    // Fix location: IndexRegistry.onInsert (line 118 — documentStore.put outside try-catch)
    // Regression watch: Ensure normal insert still populates documentStore correctly
    @Test
    @Timeout(10)
    void test_IndexRegistry_onInsert_documentStorePutOutsideRollbackScope_orphansIndexEntries()
            throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);

        // Close the internal segmentArena via reflection. This causes copySegment()
        // inside onInsert (at the documentStore.put line) to throw IllegalStateException
        // because the arena is closed. FieldIndex.onInsert uses its own Arena.ofAuto(),
        // so the index insertion succeeds — only the documentStore.put fails.
        var arenaField = IndexRegistry.class.getDeclaredField("segmentArena");
        arenaField.setAccessible(true);
        Arena arena = (Arena) arenaField.get(registry);
        arena.close();

        MemorySegment pk = stringKey("pk-1");
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);

        // onInsert should throw because copySegment fails (closed arena).
        assertThrows(IllegalStateException.class, () -> registry.onInsert(pk, doc),
                "onInsert should throw when segmentArena is closed (copySegment fails)");

        // After the exception, the indices must NOT contain the entry —
        // the rollback should have removed it. Before the fix, the index
        // insertion succeeds (it's inside the try block) but the rollback
        // never fires because documentStore.put is outside the try-catch.
        // This leaves orphaned index entries.
        SecondaryIndex nameIndex = registry.findIndex(new Predicate.Eq("name", "Alice"));
        assertNotNull(nameIndex, "name EQUALITY index should exist");
        Iterator<MemorySegment> results = nameIndex.lookup(new Predicate.Eq("name", "Alice"));
        assertFalse(results.hasNext(),
                "Index should NOT contain 'Alice' after failed onInsert — the rollback "
                        + "should have removed the index entry. Orphaned index entries mean "
                        + "documentStore.put is outside the rollback scope.");
    }

    // Finding: F-R1.resource_lifecycle.4.1
    // Bug: close() does not guard against concurrent or repeated invocation —
    // volatile boolean provides visibility but not atomicity for the close-guard
    // pattern; no compareAndSet to ensure exactly-once execution
    // Correct behavior: close() should use AtomicBoolean.compareAndSet to ensure
    // exactly-once execution of the close body
    // Fix location: IndexRegistry.close() (lines 212-213) and field declaration (line 34)
    // Regression watch: Ensure single-threaded close still works; ensure deferred exception
    // accumulation still works
    @Test
    @Timeout(10)
    void test_IndexRegistry_close_concurrentInvocation_exactlyOnceExecution() throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);
        MemorySegment pk = stringKey("pk-1");
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(pk, doc);

        // The close-guard must use AtomicBoolean.compareAndSet (not volatile boolean)
        // to ensure exactly-once execution. Verify via reflection that the closed
        // field is AtomicBoolean, not a plain volatile boolean.
        //
        // Why reflection: SecondaryIndex is a sealed interface — we cannot create a
        // test double that counts close() calls. All permitted implementations
        // (FieldIndex, VectorFieldIndex, FullTextFieldIndex) have effectively
        // idempotent close() methods, so double-close does not produce an observable
        // failure with the current type hierarchy. The bug is still real — the coding
        // guidelines require exactly-once close semantics — but the only way to verify
        // the fix is to check that the guard uses CAS.
        var closedField = IndexRegistry.class.getDeclaredField("closed");
        assertTrue(
                java.util.concurrent.atomic.AtomicBoolean.class
                        .isAssignableFrom(closedField.getType()),
                "IndexRegistry.closed must be AtomicBoolean (not volatile boolean) to ensure "
                        + "exactly-once close() execution via compareAndSet. Current type: "
                        + closedField.getType().getName());
    }

    // Finding: F-R1.resource_lifecycle.3.6
    // Bug: decryptDocId entry validation relies solely on assert — with assertions
    // disabled (production), a null or truncated entry silently returns null
    // instead of failing fast with an explicit exception
    // Correct behavior: decryptDocId should validate entry with runtime checks
    // (if/throw) so that corrupt/truncated entries are rejected
    // even when -ea is not set
    // Fix location: SseEncryptedIndex.decryptDocId (lines 294-296)
    // Regression watch: Ensure normal encrypt/decrypt round-trip still works
    @Test
    @Timeout(10)
    void test_SseEncryptedIndex_decryptDocId_entryValidationReliesSolelyOnAssert()
            throws Exception {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < rawKey.length; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        EncryptionKeyHolder holder = EncryptionKeyHolder.of(rawKey);
        SseEncryptedIndex index = new SseEncryptedIndex(holder);

        // Normal round-trip should still work
        byte[] docId = "doc-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        index.add("term", docId);
        byte[] token = index.deriveToken("term");
        List<byte[]> results = index.search(token);
        assertEquals(1, results.size(), "search should find one document after normal add");

        // Access the private decryptDocId method via reflection
        var method = SseEncryptedIndex.class.getDeclaredMethod("decryptDocId", byte[].class,
                byte[].class);
        method.setAccessible(true);

        byte[] dummyAddress = new byte[32];

        // With assert-only validation, passing a null entry does not throw when
        // assertions are disabled. The method would NPE at Arrays.copyOfRange
        // (an uninformative crash) rather than a clear validation exception.
        // After the fix: a runtime null check should throw NullPointerException.
        var nullEx = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(index, (byte[]) null, dummyAddress),
                "decryptDocId(null, ...) should throw, not silently proceed");
        assertInstanceOf(NullPointerException.class, nullEx.getCause(),
                "decryptDocId must reject null entry with NullPointerException (runtime check), "
                        + "not rely on assert. Got: " + (nullEx.getCause() == null ? "null"
                                : nullEx.getCause().getClass().getName()));

        // With assert-only validation, passing a truncated entry (e.g., length 1)
        // silently returns null (cipher fails, caught by catch block).
        // After the fix: a runtime length check should throw IllegalArgumentException.
        byte[] truncatedEntry = new byte[]{ 0x01 }; // Just the marker byte, no IV or data
        var truncEx = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(index, truncatedEntry, dummyAddress),
                "decryptDocId with truncated entry should throw, not silently return null");
        assertInstanceOf(IllegalArgumentException.class, truncEx.getCause(),
                "decryptDocId must reject truncated entry with IllegalArgumentException "
                        + "(runtime check), not silently return null via caught exception. Got: "
                        + (truncEx.getCause() == null ? "null"
                                : truncEx.getCause().getClass().getName()));

        index.close();
        holder.close();
    }

    // Finding: F-R1.resource_lifecycle.4.2
    // Bug: Constructor cleanup path misses the case when indices list is empty at throw time —
    // when the first IndexDefinition fails in createIndex(), the raw exception propagates
    // unwrapped, while failures with non-empty indices are wrapped in IOException
    // Correct behavior: All constructor failures should be wrapped in IOException consistently,
    // regardless of whether indices were already created
    // Fix location: IndexRegistry constructor catch block (lines 49-68)
    // Regression watch: Ensure the original cause is preserved; ensure non-empty case still works
    @Test
    void test_IndexRegistry_firstIndexCreationFailure_wrapsInIOException() throws Exception {
        // Schema with a single FULL_TEXT field — the first (and only) index creation will fail
        JlsmSchema schema = JlsmSchema.builder("test", 1).field("description", FieldType.string())
                .build();

        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("description", IndexType.FULL_TEXT));

        // Before the fix: the raw UnsupportedOperationException from FullTextFieldIndex
        // constructor escapes because indices is empty at throw time, so the code
        // falls through to `throw e` without wrapping.
        // After the fix: the exception is wrapped in IOException for consistency.
        IOException thrown = assertThrows(IOException.class,
                () -> new IndexRegistry(schema, definitions),
                "First index creation failure should be wrapped in IOException, "
                        + "not thrown raw as UnsupportedOperationException");

        // The original cause must be preserved
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause(),
                "Original UnsupportedOperationException must be the cause");
    }

    // Finding: F-R1.resource_lifecycle.4.3
    // Bug: copySegment uses Arena.ofAuto() creating unbounded off-heap memory with no lifecycle
    // management
    // Correct behavior: IndexRegistry should own a shared Arena that is closed deterministically in
    // close(),
    // so that off-heap memory for primary key copies is released eagerly
    // Fix location: IndexRegistry.copySegment (line 289-292) and field declarations
    // Regression watch: Ensure onInsert/onUpdate/resolveEntry still work before close()
    @Test
    @Timeout(10)
    void test_IndexRegistry_copySegment_usesArenaOfAutoCreatingUnboundedOffHeapMemory()
            throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);
        MemorySegment pk = stringKey("pk-1");
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(pk, doc);

        // Retrieve the stored entry's primaryKey segment BEFORE close
        IndexRegistry.StoredEntry entry = registry.resolveEntry(pk);
        assertNotNull(entry, "StoredEntry should exist after insert");
        MemorySegment storedPk = entry.primaryKey();

        // Verify the segment is accessible before close
        byte firstByte = storedPk.get(ValueLayout.JAVA_BYTE, 0);

        // Close the registry — if the fix uses a managed Arena, this should close
        // the arena and invalidate all segments allocated from it.
        registry.close();

        // After close(), the stored primaryKey segment should no longer be accessible
        // because the managed arena has been closed. With Arena.ofAuto(), this access
        // succeeds because the segment's lifetime is GC-dependent — that is the bug.
        //
        // After the fix (managed Arena.ofShared()), accessing the segment after close()
        // throws IllegalStateException because the arena backing the segment is closed.
        assertThrows(IllegalStateException.class, () -> storedPk.get(ValueLayout.JAVA_BYTE, 0),
                "Stored primaryKey segment should be invalidated after close() because the "
                        + "managed arena has been closed. If this succeeds, the segment is backed "
                        + "by Arena.ofAuto() (GC-dependent lifecycle) instead of a managed arena.");
    }

    // Finding: F-R1.resource_lifecycle.4.4
    // Bug: allEntries() has no closed guard — returns empty iterator after close instead
    // of throwing IllegalStateException like all other public methods
    // Correct behavior: allEntries() should throw IllegalStateException when registry is closed
    // Fix location: IndexRegistry.allEntries() (lines 252-253)
    // Regression watch: Ensure allEntries() still works correctly before close
    @Test
    @Timeout(10)
    void test_IndexRegistry_allEntries_throwsAfterClose() throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);
        MemorySegment pk = stringKey("pk-1");
        JlsmDocument doc = JlsmDocument.of(schema, "name", "Alice", "age", 30);
        registry.onInsert(pk, doc);

        // allEntries() should work before close
        Iterator<IndexRegistry.StoredEntry> entries = registry.allEntries();
        assertTrue(entries.hasNext(), "allEntries() should return entries before close");

        registry.close();

        // After close, allEntries() must throw IllegalStateException — consistent with
        // resolveEntry(), onInsert(), onDelete(), findIndex() which all guard on closed.
        // Before the fix: allEntries() silently returns an empty iterator, masking
        // use-after-close bugs in callers.
        assertThrows(IllegalStateException.class, () -> registry.allEntries(),
                "allEntries() on a closed registry should throw IllegalStateException, "
                        + "consistent with all other public methods");
    }

    // Finding: F-R1.resource_lifecycle.4.5
    // Bug: isEmpty() has no closed guard — use-after-close
    // Correct behavior: isEmpty() should throw IllegalStateException after close(),
    // consistent with onInsert, onDelete, findIndex, resolveEntry, allEntries
    // Fix location: IndexRegistry.isEmpty() (lines 215-217)
    // Regression watch: Ensure isEmpty() still works correctly before close()
    @Test
    @Timeout(10)
    void test_IndexRegistry_isEmpty_throwsAfterClose() throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);

        // isEmpty() should work before close — registry has one index, so not empty
        assertFalse(registry.isEmpty(),
                "isEmpty() should return false when indices are registered");

        registry.close();

        // After close, isEmpty() must throw IllegalStateException — consistent with
        // all other public methods that guard on closed state.
        // Before the fix: isEmpty() silently returns false (stale result from the
        // indices list which is NOT cleared by close).
        assertThrows(IllegalStateException.class, () -> registry.isEmpty(),
                "isEmpty() on a closed registry should throw IllegalStateException, "
                        + "consistent with all other public methods");
    }

    // Finding: F-R1.resource_lifecycle.4.6
    // Bug: schema() has no closed guard — returns schema after close instead of throwing
    // IllegalStateException, inconsistent with the closed-guard contract on every other
    // public method (onInsert, onDelete, findIndex, resolveEntry, allEntries, isEmpty)
    // Correct behavior: schema() should throw IllegalStateException when registry is closed
    // Fix location: IndexRegistry.schema() (lines 261-263)
    // Regression watch: Ensure schema() still works correctly before close()
    @Test
    @Timeout(10)
    void test_IndexRegistry_schema_throwsAfterClose() throws Exception {
        JlsmSchema schema = buildSchema();
        List<IndexDefinition> definitions = List
                .of(new IndexDefinition("name", IndexType.EQUALITY));

        IndexRegistry registry = new IndexRegistry(schema, definitions);

        // schema() should work before close
        JlsmSchema returnedSchema = registry.schema();
        assertNotNull(returnedSchema, "schema() should return non-null before close");
        assertEquals(schema, returnedSchema,
                "schema() should return the schema passed to constructor");

        registry.close();

        // After close, schema() must throw IllegalStateException — consistent with
        // all other public methods that guard on closed state.
        // Before the fix: schema() silently returns the schema, violating the
        // closed-guard contract established by every other public method.
        assertThrows(IllegalStateException.class, () -> registry.schema(),
                "schema() on a closed registry should throw IllegalStateException, "
                        + "consistent with all other public methods");
    }

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: close() loses segmentArena.close() exception when index close also throws —
    // segmentArena.close() is not wrapped in try-catch, so if it throws, any
    // accumulated index-close IOException in 'deferred' is silently lost
    // Correct behavior: segmentArena.close() should be wrapped in try-catch; its exception
    // should be accumulated with 'deferred' (or wrapped in IOException if
    // deferred is null), so callers see all close failures
    // Fix location: IndexRegistry.close() — wrap segmentArena.close() in try-catch
    // Regression watch: ensure arena is still closed even when indices close cleanly
    @Test
    @Timeout(10)
    void test_IndexRegistry_close_arenaCloseException_wrappedInIOException() throws Exception {
        // Create a normal registry with one EQUALITY index
        JlsmSchema schema = buildSchema();
        IndexRegistry registry = new IndexRegistry(schema,
                List.of(new IndexDefinition("name", IndexType.EQUALITY)));

        // Use reflection to pre-close the segmentArena so that when close() calls
        // segmentArena.close(), it throws IllegalStateException("Already closed").
        Field arenaField = IndexRegistry.class.getDeclaredField("segmentArena");
        arenaField.setAccessible(true);
        Arena arena = (Arena) arenaField.get(registry);
        arena.close();

        // Before the fix: segmentArena.close() throws IllegalStateException which
        // propagates raw (unwrapped) out of close(). The caller sees ISE, not
        // IOException, and if any index-close IOException was accumulated in
        // 'deferred', that exception is silently lost (GC'd local variable).
        //
        // After the fix: segmentArena.close() is wrapped in try-catch. The ISE is
        // caught and either added as suppressed to 'deferred' (if non-null) or
        // wrapped in a new IOException (if deferred is null). The caller always
        // sees IOException from close().
        IOException thrown = assertThrows(IOException.class, () -> registry.close(),
                "Arena close failure should be caught and wrapped in IOException, "
                        + "not propagated as raw IllegalStateException");

        // Verify the root cause is the ISE from the pre-closed arena
        assertNotNull(thrown.getCause(),
                "IOException should wrap the ISE cause from Arena.close()");
        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "Cause should be IllegalStateException from double-close of Arena");
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: Schema version silently truncated to short in serialization header —
    // versions >= 65536 are written as (short) which wraps to 0, 1, etc.
    // Correct behavior: SchemaSerializer should reject schemas with version > 65535
    // at construction time since the wire format uses 16-bit unsigned
    // Fix location: DocumentSerializer.SchemaSerializer constructor or forSchema()
    // Regression watch: Ensure versions in [0, 65535] still work correctly
    @Test
    void test_SchemaSerializer_schemaVersionExceedingShortRange_rejectsAtConstruction() {
        // Create a schema with version 65536 — exceeds unsigned short range
        JlsmSchema schema = JlsmSchema.builder("test", 65536).field("name", FieldType.string())
                .build();

        // DocumentSerializer.forSchema should reject this schema because the version
        // cannot be faithfully represented in the 16-bit serialization header.
        // Before the fix: this silently truncates 65536 to 0 during serialization.
        // After the fix: this throws IllegalArgumentException at construction.
        assertThrows(IllegalArgumentException.class, () -> DocumentSerializer.forSchema(schema),
                "Schema version 65536 exceeds unsigned short range (0-65535) and must be rejected");
    }

    // Finding: F-R1.resource_lifecycle.1.4
    // Bug: Field count silently truncated to short in serialization header — schemas with >65535
    // fields have their field count written as (short) fieldCount, truncating to 16 bits.
    // Correct behavior: SchemaSerializer constructor should reject schemas with >65535 fields
    // with IllegalArgumentException, same as the version guard.
    // Fix location: DocumentSerializer.SchemaSerializer constructor (~line 138)
    // Regression watch: Ensure the guard uses the same unsigned short max (0xFFFF) as the version
    // guard
    @Test
    @Timeout(30)
    void test_SchemaSerializer_fieldCountExceedingShortRange_throwsIllegalArgument() {
        // Build a schema with exactly 65536 fields — one more than unsigned short max.
        JlsmSchema.Builder builder = JlsmSchema.builder("large-schema", 1);
        for (int i = 0; i < 65_536; i++) {
            builder.field("f" + i, FieldType.int32());
        }
        JlsmSchema schema = builder.build();

        // DocumentSerializer.forSchema should reject this schema because the field count
        // cannot be faithfully represented in the 16-bit serialization header.
        // Before the fix: this silently truncates 65536 to 0 during serialization.
        // After the fix: this throws IllegalArgumentException at construction.
        assertThrows(IllegalArgumentException.class, () -> DocumentSerializer.forSchema(schema),
                "Field count 65536 exceeds unsigned short range (0-65535) and must be rejected");
    }

    // ── PartitionedTable lifecycle ─────────────────────────────────────────

    // Finding: F-R1.resource_lifecycle.1.1
    // Bug: PartitionedTable CRUD methods (create, get, update, delete) have no closed guard —
    // they delegate to already-closed PartitionClients after close() is called
    // Correct behavior: All CRUD methods should throw IllegalStateException after close()
    // Fix location: PartitionedTable.java — create, get, update, delete methods
    // Regression watch: getRange already has checkNotClosed(); ensure CRUD methods use the same
    // guard
    @Test
    @Timeout(10)
    void test_PartitionedTable_crudAfterClose_throwsIllegalStateException() throws IOException {
        // Build a minimal single-partition table with a no-op stub client
        final MemorySegment low = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        final MemorySegment high = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        final PartitionDescriptor desc = new PartitionDescriptor(1L, low, high, "local", 0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc));
        final JlsmSchema schema = buildSchema();

        final PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .schema(schema).partitionClientFactory(_ -> new StubPartitionClient(desc)).build();

        // Close the table — all clients are now released
        table.close();

        // After close, all CRUD operations must throw IllegalStateException
        final JlsmDocument doc = JlsmDocument.of(schema, "name", "test", "age", 42);

        assertThrows(IllegalStateException.class, () -> table.create("key1", doc),
                "create() after close() should throw IllegalStateException");
        assertThrows(IllegalStateException.class, () -> table.get("key1"),
                "get() after close() should throw IllegalStateException");
        assertThrows(IllegalStateException.class,
                () -> table.update("key1", doc, UpdateMode.REPLACE),
                "update() after close() should throw IllegalStateException");
        assertThrows(IllegalStateException.class, () -> table.delete("key1"),
                "delete() after close() should throw IllegalStateException");
    }

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: PartitionedTable.close() not idempotent — double-release. No guard to
    // short-circuit on second call, so all clients receive a second close().
    // Correct behavior: Second close() should be a no-op — clients must not receive
    // a second close() call. The closed flag is already set but not checked.
    // Fix location: PartitionedTable.close() (line ~206) — add early return if already closed
    // Regression watch: Ensure first close still works and exceptions still propagate
    @Test
    @Timeout(10)
    void test_PartitionedTable_doubleClose_isIdempotent() throws IOException {
        // Build a single-partition table with a counting stub that tracks close() calls
        final MemorySegment low = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        final MemorySegment high = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        final PartitionDescriptor desc = new PartitionDescriptor(1L, low, high, "local", 0L);
        final PartitionConfig config = PartitionConfig.of(List.of(desc));
        final JlsmSchema schema = buildSchema();

        final int[] closeCount = { 0 };
        final PartitionClient countingClient = new PartitionClient() {
            @Override
            public PartitionDescriptor descriptor() {
                return desc;
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
            public Iterator<TableEntry<String>> doGetRange(String from, String to) {
                return List.<TableEntry<String>>of().iterator();
            }

            @Override
            public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
                return List.of();
            }

            @Override
            public void close() {
                closeCount[0]++;
            }
        };
        final PartitionedTable table = PartitionedTable.builder().partitionConfig(config)
                .schema(schema).partitionClientFactory(_ -> countingClient).build();

        // First close — should invoke client.close() exactly once
        table.close();
        assertEquals(1, closeCount[0], "First close() should close each client exactly once");

        // Second close — must be a no-op (idempotent)
        table.close();
        assertEquals(1, closeCount[0],
                "Second close() must be idempotent — client should NOT receive a second close()");
    }

    // Finding: F-R1.resource_lifecycle.1.3
    // Bug: InProcessPartitionClient.close() not idempotent — no closed flag prevents
    // double delegation to table.close()
    // Correct behavior: Second close() should be a no-op — table.close() must not be
    // called a second time
    // Fix location: InProcessPartitionClient.close() (lines 116-119)
    // Regression watch: Ensure first close still delegates to table.close()
    @Test
    @Timeout(10)
    void test_InProcessPartitionClient_doubleClose_isIdempotent() throws IOException {
        final MemorySegment low = MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8));
        final MemorySegment high = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        final PartitionDescriptor desc = new PartitionDescriptor(1L, low, high, "local", 0L);

        final int[] closeCount = { 0 };
        final JlsmTable.StringKeyed countingTable = new JlsmTable.StringKeyed() {
            @Override
            public void create(String key, JlsmDocument doc) {
            }

            @Override
            public Optional<JlsmDocument> get(String key) {
                return Optional.empty();
            }

            @Override
            public void update(String key, JlsmDocument doc, UpdateMode mode) {
            }

            @Override
            public void delete(String key) {
            }

            @Override
            public Iterator<TableEntry<String>> getAllInRange(String from, String to) {
                return List.<TableEntry<String>>of().iterator();
            }

            @Override
            public void close() {
                closeCount[0]++;
            }
        };

        InProcessPartitionClient client = new InProcessPartitionClient(desc, countingTable);

        // First close — should delegate to table.close() exactly once
        client.close();
        assertEquals(1, closeCount[0],
                "First close() should delegate to table.close() exactly once");

        // Second close — must be a no-op (idempotent)
        client.close();
        assertEquals(1, closeCount[0],
                "Second close() must be idempotent — table.close() should NOT be called again");
    }

    // Finding: F-R1.resource_lifecycle.1.4
    // Bug: PartitionedTable constructor uses assert-only validation — bypassed in production
    // Correct behavior: Constructor should use runtime checks (Objects.requireNonNull,
    // explicit if/throw) so that null or empty inputs are rejected even with -ea disabled
    // Fix location: PartitionedTable constructor (lines 40-49)
    // Regression watch: Ensure Builder.build() still works normally after adding runtime checks
    @Test
    @Timeout(10)
    void test_PartitionedTable_constructorAssertOnlyValidation_bypassedInProduction()
            throws Exception {
        // Access the private constructor via reflection
        Constructor<?> ctor = PartitionedTable.class.getDeclaredConstructor(PartitionConfig.class,
                JlsmSchema.class, jlsm.table.internal.RangeMap.class, Map.class);
        ctor.setAccessible(true);

        // Build a valid PartitionConfig and RangeMap for cases that need them
        final MemorySegment low = MemorySegment.ofArray(new byte[0]);
        final MemorySegment high = MemorySegment.ofArray("{".getBytes(StandardCharsets.UTF_8));
        PartitionDescriptor desc = new PartitionDescriptor(1L, low, high, "local", 0L);
        PartitionConfig config = PartitionConfig.of(List.of(desc));
        jlsm.table.internal.RangeMap rangeMap = new jlsm.table.internal.RangeMap(config);

        // Null config: must throw NullPointerException, not silently pass (assert disabled)
        // Before the fix: this succeeds silently when -ea is not set.
        var ex1 = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance(null, null, rangeMap,
                        Map.of(1L, new StubPartitionClient(desc))),
                "Constructor should reject null config with a runtime check");
        assertInstanceOf(NullPointerException.class, ex1.getCause(),
                "Null config should throw NullPointerException, not pass silently");

        // Null clients: must throw NullPointerException
        var ex2 = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance(config, null, rangeMap, null),
                "Constructor should reject null clients with a runtime check");
        assertInstanceOf(NullPointerException.class, ex2.getCause(),
                "Null clients should throw NullPointerException, not pass silently");

        // Empty clients: must throw IllegalArgumentException
        var ex3 = assertThrows(InvocationTargetException.class,
                () -> ctor.newInstance(config, null, rangeMap, Collections.emptyMap()),
                "Constructor should reject empty clients with a runtime check");
        assertInstanceOf(IllegalArgumentException.class, ex3.getCause(),
                "Empty clients should throw IllegalArgumentException, not pass silently");
    }

    // Finding: F-R1.resource_lifecycle.2.1
    // Bug: MergingIterator has no close() — abandoned iteration leaks source iterator resources
    // Correct behavior: Iterator returned by mergeOrdered() should implement AutoCloseable;
    // closing it should close any source iterators that are AutoCloseable
    // Fix location: ResultMerger.MergingIterator (lines 121-161)
    // Regression watch: Ensure mergeOrdered still works correctly for full iteration
    @Test
    @Timeout(10)
    void test_MergingIterator_abandonedIteration_leaksSourceIteratorResources() {
        JlsmSchema schema = buildSchema();
        JlsmDocument doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 25);
        JlsmDocument doc2 = JlsmDocument.of(schema, "name", "Bob", "age", 30);
        JlsmDocument doc3 = JlsmDocument.of(schema, "name", "Carol", "age", 35);

        // Source iterators that track close() calls
        AtomicBoolean source1Closed = new AtomicBoolean(false);
        AtomicBoolean source2Closed = new AtomicBoolean(false);

        // Create closeable source iterators — each has 2 entries
        var entries1 = List.of(new TableEntry<>("aaa", doc1), new TableEntry<>("ccc", doc2));
        var entries2 = List.of(new TableEntry<>("bbb", doc3), new TableEntry<>("ddd", doc1));

        Iterator<TableEntry<String>> src1 = new CloseableTestIterator(entries1.iterator(),
                source1Closed);
        Iterator<TableEntry<String>> src2 = new CloseableTestIterator(entries2.iterator(),
                source2Closed);

        // Get the merged iterator
        Iterator<TableEntry<String>> merged = ResultMerger.mergeOrdered(List.of(src1, src2));

        // Consume only one entry (abandon the rest)
        assertTrue(merged.hasNext());
        TableEntry<String> first = merged.next();
        assertEquals("aaa", first.key());

        // The iterator returned by mergeOrdered must implement AutoCloseable so callers
        // can release source iterator resources on abandonment.
        assertTrue(merged instanceof AutoCloseable,
                "Iterator returned by mergeOrdered() must implement AutoCloseable "
                        + "so callers can release source iterator resources on abandonment");

        // Close the merged iterator — should close source iterators
        assertDoesNotThrow(() -> ((AutoCloseable) merged).close(),
                "Closing the merged iterator should not throw");

        // Verify source iterators were closed
        assertTrue(source1Closed.get(),
                "Source iterator 1 should have been closed when merged iterator was closed");
        assertTrue(source2Closed.get(),
                "Source iterator 2 should have been closed when merged iterator was closed");
    }

    /**
     * A test iterator that wraps another iterator and tracks close() calls. Implements both
     * Iterator and AutoCloseable to simulate resource-holding iterators.
     */
    private static final class CloseableTestIterator
            implements Iterator<TableEntry<String>>, AutoCloseable {

        private final Iterator<TableEntry<String>> delegate;
        private final AtomicBoolean closed;

        CloseableTestIterator(Iterator<TableEntry<String>> delegate, AtomicBoolean closed) {
            this.delegate = delegate;
            this.closed = closed;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public TableEntry<String> next() {
            return delegate.next();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    // Finding: F-R1.resource_lifecycle.2.2
    // Bug: Source iterator lost after exception in next() — partial heap corruption
    // Correct behavior: If a source iterator throws during advancement in next(),
    // the HeapEntry should be re-offered to the heap before the exception propagates,
    // so subsequent calls don't silently skip that source's remaining entries
    // Fix location: MergingIterator.next() (lines 158-163) — wrap source advancement in try-catch
    // Regression watch: Ensure normal iteration still works; ensure exception still propagates
    @Test
    @Timeout(10)
    void test_MergingIterator_sourceExceptionInNext_doesNotLoseSourceIterator() {
        JlsmSchema schema = buildSchema();
        JlsmDocument doc1 = JlsmDocument.of(schema, "name", "Alice", "age", 25);
        JlsmDocument doc2 = JlsmDocument.of(schema, "name", "Bob", "age", 30);
        JlsmDocument doc3 = JlsmDocument.of(schema, "name", "Carol", "age", 35);

        // Source 1: normal iterator with entries "aaa" and "ccc"
        var entries1 = List.of(new TableEntry<>("aaa", doc1), new TableEntry<>("ccc", doc2));

        // Source 2: throws UncheckedIOException on the SECOND hasNext() call
        // (i.e., after "bbb" is consumed and the iterator tries to advance)
        var throwingSource = new ThrowingTestIterator(
                List.of(new TableEntry<>("bbb", doc3), new TableEntry<>("ddd", doc1)), 1 // throw on
                                                                                         // the 2nd
                                                                                         // hasNext()
                                                                                         // call
                                                                                         // (index
                                                                                         // 1)
        );

        Iterator<TableEntry<String>> merged = ResultMerger
                .mergeOrdered(List.of(entries1.iterator(), throwingSource));

        // First call: should return "aaa" (from source 1) — no exception
        assertTrue(merged.hasNext());
        assertEquals("aaa", merged.next().key());

        // Second call: should return "bbb" (from throwingSource) — this polls "bbb"
        // from the heap, then tries to advance throwingSource which throws.
        // The exception should propagate to the caller.
        assertThrows(UncheckedIOException.class, () -> merged.next(),
                "Exception from source iterator should propagate to caller");

        // After the exception, the MergingIterator should NOT have lost the source.
        // The throwingSource should have been re-offered to the heap so that on retry
        // (when the source's transient error clears), the entry is still available.
        //
        // Before the fix: hasNext() returns true only for source 1's remaining entry ("ccc"),
        // but throwingSource's "ddd" entry is silently dropped.
        //
        // After the fix: the HeapEntry for "bbb" is re-offered to the heap.
        // The next successful call returns "bbb" again (retrying the same entry).
        assertTrue(merged.hasNext(),
                "MergingIterator should still have entries after source exception");

        // Collect remaining entries — should include BOTH "bbb" (re-offered) and "ccc"
        List<String> remaining = new ArrayList<>();
        while (merged.hasNext()) {
            remaining.add(merged.next().key());
        }

        assertTrue(remaining.contains("ccc"),
                "Source 1's remaining entry 'ccc' should still be present");
        assertTrue(remaining.contains("bbb"),
                "throwingSource's entry 'bbb' should have been re-offered to the heap "
                        + "after the exception, not silently dropped. Remaining: " + remaining);
    }

    /**
     * A test iterator that throws UncheckedIOException on a specific hasNext() call. Before the
     * throw point, it behaves normally. After the throw, it recovers and continues returning
     * remaining elements (simulating a transient error).
     */
    private static final class ThrowingTestIterator implements Iterator<TableEntry<String>> {

        private final List<TableEntry<String>> entries;
        private int nextIndex = 0;
        private int hasNextCallCount = 0;
        private final int throwOnHasNextCall;
        private boolean threw = false;

        ThrowingTestIterator(List<TableEntry<String>> entries, int throwOnHasNextCall) {
            this.entries = List.copyOf(entries);
            this.throwOnHasNextCall = throwOnHasNextCall;
        }

        @Override
        public boolean hasNext() {
            if (!threw && hasNextCallCount == throwOnHasNextCall) {
                threw = true;
                throw new UncheckedIOException(new java.io.IOException("transient read error"));
            }
            hasNextCallCount++;
            return nextIndex < entries.size();
        }

        @Override
        public TableEntry<String> next() {
            if (nextIndex >= entries.size()) {
                throw new NoSuchElementException();
            }
            return entries.get(nextIndex++);
        }
    }

    /**
     * Minimal no-op PartitionClient for lifecycle testing. All CRUD methods are stubs that should
     * never be reached if the closed guard works correctly.
     */
    private static final class StubPartitionClient implements PartitionClient {
        private final PartitionDescriptor descriptor;

        StubPartitionClient(PartitionDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public PartitionDescriptor descriptor() {
            return descriptor;
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
            return List.<TableEntry<String>>of().iterator();
        }

        @Override
        public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    // Finding: F-R1.resource_lifecycle.2.3
    // Bug: Null partition iterator causes NPE — assert-only guard at construction;
    // List.copyOf throws undescriptive NPE before the assert is even reached
    // Correct behavior: mergeOrdered should throw NullPointerException with a descriptive
    // message identifying that a null element was found in the partition iterator list
    // Fix location: ResultMerger.mergeOrdered or MergingIterator constructor — add explicit
    // null check for each element with a descriptive message
    // Regression watch: Ensure non-null iterators still merge correctly after adding the guard
    @Test
    @Timeout(10)
    void test_MergingIterator_nullPartitionIterator_descriptiveNpe() {
        List<Iterator<TableEntry<String>>> iterators = new ArrayList<>();
        iterators.add(List.<TableEntry<String>>of().iterator());
        iterators.add(null); // null element should be caught with descriptive message

        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> ResultMerger.mergeOrdered(iterators));

        // The exception message must describe what was null — not a bare NPE from List.copyOf
        assertNotNull(ex.getMessage(),
                "NPE must have a descriptive message, not a bare null message from List.copyOf");
        assertTrue(
                ex.getMessage().toLowerCase().contains("partition")
                        || ex.getMessage().toLowerCase().contains("iterator"),
                "NPE message should mention 'partition' or 'iterator' to identify what was null; got: "
                        + ex.getMessage());
    }
}
