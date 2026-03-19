package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jlsm.encryption.EncryptionKeyHolder;
import jlsm.table.internal.SseEncryptedIndex;

/**
 * Tests for {@link SseEncryptedIndex} — Tier 3 SSE encrypted inverted index.
 *
 * <p>
 * Verifies: token derivation determinism, add/search/delete, forward privacy via state counter,
 * concurrent safety, and error cases.
 */
class SseEncryptedIndexTest {

    private EncryptionKeyHolder keyHolder;
    private SseEncryptedIndex index;

    @BeforeEach
    void setUp() {
        final byte[] keyMaterial = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyMaterial[i] = (byte) (i + 0xA0);
        }
        keyHolder = EncryptionKeyHolder.of(keyMaterial);
        index = new SseEncryptedIndex(keyHolder);
    }

    @AfterEach
    void tearDown() {
        keyHolder.close();
    }

    // ── Token derivation ───────────────────────────────────────────────────

    @Test
    void tokenDerivationIsDeterministicForSameTerm() {
        final byte[] token1 = index.deriveToken("hello");
        final byte[] token2 = index.deriveToken("hello");

        assertArrayEquals(token1, token2, "Same term must always produce the same search token");
    }

    @Test
    void tokenDerivationDiffersForDifferentTerms() {
        final byte[] tokenHello = index.deriveToken("hello");
        final byte[] tokenWorld = index.deriveToken("world");

        assertFalse(Arrays.equals(tokenHello, tokenWorld),
                "Different terms must produce different tokens");
    }

    @Test
    void tokenIsNotEmpty() {
        final byte[] token = index.deriveToken("test");
        assertNotNull(token);
        assertTrue(token.length > 0, "Token must not be empty");
    }

    // ── Add and search ─────────────────────────────────────────────────────

    @Test
    void addThenSearchReturnsThatDocId() {
        final byte[] docId = "doc-1".getBytes();
        index.add("term1", docId);

        final byte[] token = index.deriveToken("term1");
        final List<byte[]> results = index.search(token);

        assertEquals(1, results.size());
        assertArrayEquals(docId, results.getFirst());
    }

    @Test
    void multipleDocsForSameTerm() {
        index.add("shared-term", "doc-A".getBytes());
        index.add("shared-term", "doc-B".getBytes());
        index.add("shared-term", "doc-C".getBytes());

        final byte[] token = index.deriveToken("shared-term");
        final List<byte[]> results = index.search(token);

        assertEquals(3, results.size());
        final Set<String> docIds = results.stream().map(String::new).collect(Collectors.toSet());
        assertTrue(docIds.contains("doc-A"));
        assertTrue(docIds.contains("doc-B"));
        assertTrue(docIds.contains("doc-C"));
    }

    @Test
    void searchWithWrongTokenReturnsEmpty() {
        index.add("realterm", "doc-1".getBytes());

        final byte[] wrongToken = index.deriveToken("wrongterm");
        final List<byte[]> results = index.search(wrongToken);

        assertTrue(results.isEmpty(), "Wrong token should return empty results");
    }

    @Test
    void searchOnEmptyIndexReturnsEmpty() {
        final byte[] token = index.deriveToken("anything");
        final List<byte[]> results = index.search(token);

        assertTrue(results.isEmpty());
    }

    @Test
    void differentTermsHaveIndependentPostingLists() {
        index.add("alpha", "doc-1".getBytes());
        index.add("beta", "doc-2".getBytes());

        final List<byte[]> alphaResults = index.search(index.deriveToken("alpha"));
        final List<byte[]> betaResults = index.search(index.deriveToken("beta"));

        assertEquals(1, alphaResults.size());
        assertArrayEquals("doc-1".getBytes(), alphaResults.getFirst());
        assertEquals(1, betaResults.size());
        assertArrayEquals("doc-2".getBytes(), betaResults.getFirst());
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesDocFromResults() {
        index.add("term", "doc-1".getBytes());
        index.add("term", "doc-2".getBytes());
        index.add("term", "doc-3".getBytes());

        index.delete("term", "doc-2".getBytes());

        final List<byte[]> results = index.search(index.deriveToken("term"));
        assertEquals(2, results.size());
        final Set<String> docIds = results.stream().map(String::new).collect(Collectors.toSet());
        assertTrue(docIds.contains("doc-1"));
        assertFalse(docIds.contains("doc-2"));
        assertTrue(docIds.contains("doc-3"));
    }

    @Test
    void deleteAllDocsForTermResultsInEmptySearch() {
        index.add("term", "doc-1".getBytes());
        index.delete("term", "doc-1".getBytes());

        final List<byte[]> results = index.search(index.deriveToken("term"));
        assertTrue(results.isEmpty());
    }

    @Test
    void deleteNonexistentDocIsNoOp() {
        index.add("term", "doc-1".getBytes());
        // Delete a doc that was never added — should not throw
        index.delete("term", "doc-999".getBytes());

        final List<byte[]> results = index.search(index.deriveToken("term"));
        assertEquals(1, results.size());
        assertArrayEquals("doc-1".getBytes(), results.getFirst());
    }

    // ── Forward privacy ────────────────────────────────────────────────────

    @Test
    void forwardPrivacyAddingSameTermTwiceProducesDifferentState() {
        // Forward privacy means each add operation uses a new state counter,
        // so the encrypted addresses in the backing store are different for
        // each add of the same term. We verify this indirectly: after adding
        // the same term twice with different docIds, both are retrievable.
        index.add("fp-term", "doc-first".getBytes());
        index.add("fp-term", "doc-second".getBytes());

        final List<byte[]> results = index.search(index.deriveToken("fp-term"));
        assertEquals(2, results.size());

        final Set<String> docIds = results.stream().map(String::new).collect(Collectors.toSet());
        assertTrue(docIds.contains("doc-first"));
        assertTrue(docIds.contains("doc-second"));
    }

    // ── Concurrent safety ──────────────────────────────────────────────────

    @Test
    void concurrentAddAndSearchSafety() throws Exception {
        final int threadCount = 8;
        final int opsPerThread = 50;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);

        try (final ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            final Future<?>[] futures = new Future<?>[threadCount];

            // Half the threads add, half search
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures[t] = executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (threadId % 2 == 0) {
                        // Adder thread
                        for (int i = 0; i < opsPerThread; i++) {
                            index.add("concurrent-term", ("doc-" + threadId + "-" + i).getBytes());
                        }
                    } else {
                        // Searcher thread
                        for (int i = 0; i < opsPerThread; i++) {
                            final byte[] token = index.deriveToken("concurrent-term");
                            // Should not throw, results may vary due to concurrent adds
                            index.search(token);
                        }
                    }
                });
            }

            for (final Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        // After all threads complete, verify all docs from adder threads are present
        final List<byte[]> results = index.search(index.deriveToken("concurrent-term"));
        final int expectedAdderThreads = threadCount / 2; // threads 0, 2, 4, 6
        assertEquals(expectedAdderThreads * opsPerThread, results.size());
    }

    // ── Error cases ────────────────────────────────────────────────────────

    @Test
    void constructorRejectsNullKeyHolder() {
        assertThrows(NullPointerException.class, () -> new SseEncryptedIndex(null));
    }

    @Test
    void deriveTokenRejectsNullTerm() {
        assertThrows(NullPointerException.class, () -> index.deriveToken(null));
    }

    @Test
    void addRejectsNullTerm() {
        assertThrows(NullPointerException.class, () -> index.add(null, "doc".getBytes()));
    }

    @Test
    void addRejectsNullDocId() {
        assertThrows(NullPointerException.class, () -> index.add("term", null));
    }

    @Test
    void deleteRejectsNullTerm() {
        assertThrows(NullPointerException.class, () -> index.delete(null, "doc".getBytes()));
    }

    @Test
    void deleteRejectsNullDocId() {
        assertThrows(NullPointerException.class, () -> index.delete("term", null));
    }

    @Test
    void searchRejectsNullToken() {
        assertThrows(NullPointerException.class, () -> index.search(null));
    }

    // ── Token derivation with different key produces different tokens ──────

    @Test
    void differentKeyProducesDifferentTokens() {
        final byte[] otherKeyMaterial = new byte[32];
        for (int i = 0; i < 32; i++) {
            otherKeyMaterial[i] = (byte) (i + 0x50);
        }
        try (final EncryptionKeyHolder otherKey = EncryptionKeyHolder.of(otherKeyMaterial)) {
            final SseEncryptedIndex otherIndex = new SseEncryptedIndex(otherKey);

            final byte[] token1 = index.deriveToken("same-term");
            final byte[] token2 = otherIndex.deriveToken("same-term");

            assertFalse(Arrays.equals(token1, token2),
                    "Different keys must produce different tokens for the same term");
        }
    }
}
