package jlsm.vector;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.tree.LsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared-state adversarial tests for IVF/HNSW constructs.
 * Test methods are added by prove-fix subagents for confirmed findings.
 */
class SharedStateAdversarialTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    private static final MemorySerializer<Long> LONG_DOC_ID_SERIALIZER = new MemorySerializer<>() {
        @Override
        public MemorySegment serialize(Long value) {
            byte[] bytes = new byte[8];
            long v = value;
            for (int i = 7; i >= 0; i--) {
                bytes[i] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public Long deserialize(MemorySegment segment) {
            byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            long v = 0L;
            for (byte b : bytes)
                v = (v << 8) | (b & 0xFFL);
            return v;
        }
    };

    private LsmTree buildTree(long flushThreshold) throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory(
                        (SSTableWriterFactory) (id, level, path) -> new TrieSSTableWriter(id, level,
                                path, PassthroughBloomFilter.factory()))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader.open(path,
                        PassthroughBloomFilter.deserializer()))
                .bloomDeserializer(PassthroughBloomFilter.deserializer())
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThreshold).build();
    }

    // Finding: F-R5.shared_state.1.1
    // Bug: Non-atomic index() allows concurrent re-index of the same docId to leave
    //      orphaned posting entries under old centroids that are never cleaned up.
    // Correct behavior: After concurrent index() calls for the same docId complete,
    //                   exactly one posting entry should exist across all centroids.
    // Fix location: IvfFlat.index() — the read-modify-write of reverse lookup and
    //               posting entries needs synchronization on the docId.
    // Regression watch: Synchronization must not deadlock or serialize unrelated docIds.
    @Test
    void test_ivfFlat_index_concurrentReindexSameDocId_noOrphanedPostings() throws Exception {
        int dims = 4;
        int numClusters = 3;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .numClusters(numClusters)
                .nprobe(numClusters)
                .build();

        // Seed 3 centroids with distinct vectors so they are well-separated
        index.index(100L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        index.index(101L, new float[]{0.0f, 1.0f, 0.0f, 0.0f});
        index.index(102L, new float[]{0.0f, 0.0f, 1.0f, 0.0f});

        // Target docId that will be concurrently re-indexed
        long targetDocId = 42L;

        // Pre-index the target so reverse lookup exists
        index.index(targetDocId, new float[]{0.5f, 0.5f, 0.0f, 0.0f});

        int numThreads = 4;
        int iterations = 50;
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        // Each thread re-indexes the same docId with vectors that map to different centroids
        float[][] vectors = {
                {1.0f, 0.0f, 0.0f, 0.0f},  // near centroid 0
                {0.0f, 1.0f, 0.0f, 0.0f},  // near centroid 1
                {0.0f, 0.0f, 1.0f, 0.0f},  // near centroid 2
                {0.5f, 0.5f, 0.0f, 0.0f},  // near centroid 0 or 1
        };

        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < numThreads; t++) {
            int threadIdx = t;
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        index.index(targetDocId, vectors[threadIdx]);
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.join(30_000);
        }

        assertTrue(errors.isEmpty(),
                "Concurrent index() threw exceptions: " + errors);

        // Count posting entries for targetDocId across ALL centroids
        byte[] targetDocIdBytes = LONG_DOC_ID_SERIALIZER.serialize(targetDocId)
                .toArray(ValueLayout.JAVA_BYTE);

        // Scan all posting entries: keys starting with 0x01
        MemorySegment postingScanStart = MemorySegment.ofArray(new byte[]{0x01});
        MemorySegment postingScanEnd = MemorySegment.ofArray(new byte[]{0x02});

        int postingCount = 0;
        Iterator<Entry> it = tree.scan(postingScanStart, postingScanEnd);
        while (it.hasNext()) {
            Entry entry = it.next();
            if (!(entry instanceof Entry.Put))
                continue;
            byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
            // Posting key format: [0x01][4-byte centroid_id][docId bytes]
            if (key.length < 5)
                continue;
            byte[] docIdInKey = Arrays.copyOfRange(key, 5, key.length);
            if (Arrays.equals(docIdInKey, targetDocIdBytes)) {
                postingCount++;
            }
        }

        assertEquals(1, postingCount,
                "Expected exactly 1 posting for docId " + targetDocId
                        + " but found " + postingCount
                        + " — orphaned postings exist from concurrent re-index race");

        index.close();
    }

    // Finding: F-R5.shared_state.1.2
    // Bug: remove() does not acquire the per-docId striped lock, so a concurrent
    //      index() can complete between remove()'s reverse-lookup read and its
    //      delete calls, leaving an orphaned posting that is undeletable.
    // Correct behavior: After remove() returns, no posting entry for the docId
    //                   should exist in any centroid's posting list.
    // Fix location: IvfFlat.remove() — wrap the reverse-lookup read + deletes
    //               inside synchronized(lockFor(docIdBytes))
    // Regression watch: Must use the same lock stripe as index() to prevent the race.
    @Test
    void test_ivfFlat_remove_concurrentIndexLeaveGhostPosting() throws Exception {
        int dims = 4;
        int numClusters = 3;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .numClusters(numClusters)
                .nprobe(numClusters)
                .build();

        // Seed 3 centroids with orthogonal vectors
        index.index(100L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        index.index(101L, new float[]{0.0f, 1.0f, 0.0f, 0.0f});
        index.index(102L, new float[]{0.0f, 0.0f, 1.0f, 0.0f});

        long targetDocId = 77L;
        byte[] targetDocIdBytes = LONG_DOC_ID_SERIALIZER.serialize(targetDocId)
                .toArray(ValueLayout.JAVA_BYTE);

        int iterations = 200;
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> errors = new ArrayList<>();

        // Repeatedly: one thread indexes the docId, another removes it concurrently.
        // After both finish each round, we check invariants.
        for (int round = 0; round < iterations; round++) {
            // Pre-index the target docId
            index.index(targetDocId, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

            CyclicBarrier roundBarrier = new CyclicBarrier(2);

            // Thread A: re-index with a vector near a different centroid
            Thread indexer = Thread.ofVirtual().start(() -> {
                try {
                    roundBarrier.await();
                    index.index(targetDocId, new float[]{0.0f, 1.0f, 0.0f, 0.0f});
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });

            // Thread B: remove the docId
            Thread remover = Thread.ofVirtual().start(() -> {
                try {
                    roundBarrier.await();
                    index.remove(targetDocId);
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });

            indexer.join(10_000);
            remover.join(10_000);

            if (!errors.isEmpty()) break;

            // After both threads complete, count postings for the target docId.
            // There should be either 0 (remove won) or 1 (index won) — never >1.
            MemorySegment postingScanStart = MemorySegment.ofArray(new byte[]{0x01});
            MemorySegment postingScanEnd = MemorySegment.ofArray(new byte[]{0x02});

            int postingCount = 0;
            Iterator<Entry> it = tree.scan(postingScanStart, postingScanEnd);
            while (it.hasNext()) {
                Entry entry = it.next();
                if (!(entry instanceof Entry.Put)) continue;
                byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                if (key.length < 5) continue;
                byte[] docIdInKey = Arrays.copyOfRange(key, 5, key.length);
                if (Arrays.equals(docIdInKey, targetDocIdBytes)) {
                    postingCount++;
                }
            }

            // Check reverse lookup presence
            MemorySegment revKey = MemorySegment.ofArray(
                    new byte[]{0x02, 0, 0, 0, 0, 0, 0, 0, 77});
            // Build the proper reverse key
            byte[] fullRevKey = new byte[1 + targetDocIdBytes.length];
            fullRevKey[0] = 0x02;
            System.arraycopy(targetDocIdBytes, 0, fullRevKey, 1, targetDocIdBytes.length);
            var revLookup = tree.get(MemorySegment.ofArray(fullRevKey));

            if (revLookup.isEmpty()) {
                // remove() won — there should be 0 postings
                assertEquals(0, postingCount,
                        "Round " + round + ": reverse lookup deleted but "
                                + postingCount + " ghost posting(s) remain — "
                                + "orphaned posting from concurrent remove()/index() race");
            } else {
                // index() won — there should be exactly 1 posting
                assertEquals(1, postingCount,
                        "Round " + round + ": reverse lookup present but "
                                + postingCount + " postings found (expected 1)");
            }

            // Clean up for next round
            index.remove(targetDocId);
        }

        assertTrue(errors.isEmpty(),
                "Concurrent index()/remove() threw exceptions: " + errors);

        index.close();
    }

    // Finding: F-R5.shared_state.1.3
    // Bug: NaN score in nearestCentroid causes incorrect centroid assignment. When the
    //      first centroid's score is NaN (via dot product overflow with finite inputs),
    //      bestScore is NaN and all subsequent comparisons return false (IEEE 754),
    //      so the method always returns centroid 0 regardless of actual similarity.
    // Correct behavior: NaN scores should be treated as worst-possible, and the centroid
    //                   with the highest finite score should be selected.
    // Fix location: IvfFlat.nearestCentroid (LsmVectorIndex.java) — comparison logic
    // Regression watch: Fix must not change behavior for normal (finite) scores
    @Test
    void test_nearestCentroid_nanScoreFirstCentroid_selectsBestFiniteCentroid() throws Exception {
        int dims = 4;
        int numClusters = 2;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .numClusters(numClusters)
                .nprobe(numClusters)
                .build();

        // Centroid 0: [MAX_VALUE, -MAX_VALUE, 0, 0]
        // dot(query, centroid0) = MAX_VALUE*MAX_VALUE + MAX_VALUE*(-MAX_VALUE) = +Inf + (-Inf) = NaN
        index.index(200L, new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f, 0.0f});

        // Centroid 1: [1, 0, 0, 0]
        // dot(query, centroid1) = MAX_VALUE*1 + MAX_VALUE*0 + 0 + 0 = MAX_VALUE (finite, positive)
        index.index(201L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        // Query vector that triggers NaN against centroid 0 but has clear affinity to centroid 1
        float[] query = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, 0.0f, 0.0f};
        long targetDocId = 300L;
        index.index(targetDocId, query);

        // Verify the target was assigned to centroid 1 (the one with finite positive score),
        // not centroid 0 (which produces NaN score).
        byte[] targetDocIdBytes = LONG_DOC_ID_SERIALIZER.serialize(targetDocId)
                .toArray(ValueLayout.JAVA_BYTE);

        // Check which centroid has the posting for targetDocId
        MemorySegment postingScanStart = MemorySegment.ofArray(new byte[]{0x01});
        MemorySegment postingScanEnd = MemorySegment.ofArray(new byte[]{0x02});

        int assignedCentroid = -1;
        Iterator<Entry> it = tree.scan(postingScanStart, postingScanEnd);
        while (it.hasNext()) {
            Entry entry = it.next();
            if (!(entry instanceof Entry.Put)) continue;
            byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
            if (key.length < 5) continue;
            byte[] docIdInKey = Arrays.copyOfRange(key, 5, key.length);
            if (Arrays.equals(docIdInKey, targetDocIdBytes)) {
                assignedCentroid = ((key[1] & 0xFF) << 24) | ((key[2] & 0xFF) << 16)
                        | ((key[3] & 0xFF) << 8) | (key[4] & 0xFF);
            }
        }

        assertNotEquals(-1, assignedCentroid,
                "Target docId should have a posting entry");
        assertEquals(1, assignedCentroid,
                "Vector should be assigned to centroid 1 (finite score " + Float.MAX_VALUE
                        + ") not centroid 0 (NaN score from dot product overflow)");

        index.close();
    }

    // Finding: F-R5.shared_state.1.4
    // Bug: NaN scores in topNCentroids waste nprobe budget on invalid centroids.
    //      Float.compare treats NaN as greater than all finite values, so NaN-scored
    //      centroids sort first in descending order and consume the nprobe budget.
    // Correct behavior: NaN-scored centroids should be ranked last (worst), so that
    //                   the nprobe budget is spent on centroids with finite scores.
    // Fix location: IvfFlat.topNCentroids sort comparator (LsmVectorIndex.java)
    // Regression watch: Fix must preserve correct descending order for finite scores.
    @Test
    void test_topNCentroids_nanScores_finiteScoreCentroidsSelectedFirst() throws Exception {
        int dims = 4;
        // 3 centroids but nprobe=1 — only the top-ranked centroid is probed
        int numClusters = 3;
        int nprobe = 1;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .numClusters(numClusters)
                .nprobe(nprobe)
                .build();

        // Centroid 0: triggers NaN via dot product overflow with the search query
        // dot(query, c0) = MAX_VALUE*MAX_VALUE + (-MAX_VALUE)*MAX_VALUE = +Inf + (-Inf) = NaN
        // But centroid 0 has a FINITE score against the target vector {0,0,0,1}:
        // dot({0,0,0,1}, c0) = 0 + 0 + 0 + 0 = 0
        index.index(500L, new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f, 0.0f});

        // Centroid 1: triggers NaN via dot product overflow with the search query
        // dot(query, c1) = (-MAX_VALUE)*MAX_VALUE + MAX_VALUE*MAX_VALUE = (-Inf) + (+Inf) = NaN
        // Also finite score against target: dot({0,0,0,1}, c1) = 0
        index.index(501L, new float[]{-Float.MAX_VALUE, Float.MAX_VALUE, 0.0f, 0.0f});

        // Centroid 2: has a clear finite positive score with the search query
        // dot(query, c2) = 0 + 0 + 0 + MAX_VALUE = MAX_VALUE (finite)
        // Also has best score against target: dot({0,0,0,1}, c2) = 1.0
        index.index(502L, new float[]{0.0f, 0.0f, 0.0f, 1.0f});

        // Target doc: vector {0, 0, 0, 1} — nearest to centroid 2 (score=1.0 vs 0.0 for others)
        // nearestCentroid is already fixed to handle NaN, so assignment is correct.
        long targetDocId = 600L;
        index.index(targetDocId, new float[]{0.0f, 0.0f, 0.0f, 1.0f});

        // Query: {MAX_VALUE, MAX_VALUE, 0, MAX_VALUE}
        // dot(query, c0) = MAX_VALUE*MAX_VALUE + MAX_VALUE*(-MAX_VALUE) + 0 + 0 = +Inf-Inf = NaN
        // dot(query, c1) = MAX_VALUE*(-MAX_VALUE) + MAX_VALUE*MAX_VALUE + 0 + 0 = -Inf+Inf = NaN
        // dot(query, c2) = 0 + 0 + 0 + MAX_VALUE*1 = MAX_VALUE (finite, positive)
        float[] query = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, 0.0f, Float.MAX_VALUE};

        // With nprobe=1, only the top-ranked centroid is probed.
        // Bug: NaN centroids sort first (Float.compare puts NaN > all) → centroid 0 or 1
        //      is probed → target not found (target is under centroid 2).
        // Correct: centroid 2 (finite score MAX_VALUE) should be probed → target found.
        List<? extends VectorIndex.SearchResult<Long>> results = index.search(query, 10);

        // The target doc should be found because centroid 2 should be probed
        boolean found = results.stream().anyMatch(r -> r.docId().equals(targetDocId));
        assertTrue(found,
                "Target docId " + targetDocId + " should be found when searching with nprobe=1. "
                        + "NaN-scored centroids consumed the nprobe budget, skipping the "
                        + "centroid with a finite score where the target lives. "
                        + "Got results: " + results);

        index.close();
    }

    // Finding: F-R5.shared_state.1.5
    // Bug: assignCentroid uses max(existingIds)+1 as new centroid ID — two concurrent
    //      index() calls that both see centroids.size() < numClusters compute the same
    //      newCid and one silently overwrites the other's centroid vector.
    // Correct behavior: Each centroid creation must produce a unique centroid ID; after
    //                   numClusters centroids are created, exactly numClusters distinct
    //                   centroid entries should exist in the tree.
    // Fix location: IvfFlat.assignCentroid — centroid creation needs synchronization
    // Regression watch: Lock must not deadlock with per-docId locks in index()/remove()
    @Test
    void test_assignCentroid_concurrentCreation_noDuplicateCentroidIds() throws Exception {
        int dims = 4;
        // Use a large cluster count so many threads can race into the creation branch
        int numClusters = 16;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.IvfFlat<Long> index = LsmVectorIndex.<Long>ivfFlatBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .numClusters(numClusters)
                .nprobe(numClusters)
                .build();

        // Launch numClusters threads simultaneously, each indexing a unique docId
        // with a distinct vector. Each should create a new centroid (since none exist yet).
        int numThreads = numClusters;
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < numThreads; t++) {
            int threadIdx = t;
            float[] vec = new float[dims];
            vec[threadIdx % dims] = 1.0f + threadIdx; // distinct vectors
            long docId = 1000L + threadIdx;

            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    index.index(docId, vec);
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.join(30_000);
        }

        assertTrue(errors.isEmpty(),
                "Concurrent index() threw exceptions: " + errors);

        // Count distinct centroid entries in the tree
        MemorySegment centroidScanStart = MemorySegment.ofArray(new byte[]{0x00});
        MemorySegment centroidScanEnd = MemorySegment.ofArray(new byte[]{0x01});

        int centroidCount = 0;
        Iterator<Entry> it = tree.scan(centroidScanStart, centroidScanEnd);
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry instanceof Entry.Put) {
                centroidCount++;
            }
        }

        assertEquals(numClusters, centroidCount,
                "Expected " + numClusters + " distinct centroids but found " + centroidCount
                        + " — concurrent assignCentroid() calls produced duplicate centroid IDs, "
                        + "silently overwriting centroid vectors");

        index.close();
    }

    // Finding: F-R5.shared_state.2.1
    // Bug: Entry point read-then-write race in concurrent index() calls — two threads
    //      both read the entry point, both compute newLevel > maxLayer, and both write
    //      a new entry point. The second write silently overwrites the first, orphaning
    //      the first thread's upper-layer structure.
    // Correct behavior: After concurrent index() calls complete, the entry point's
    //                   maxLayer must be >= the maximum layer count of any node in the index.
    // Fix location: Hnsw.index() — the entry point read + conditional promotion at
    //               lines 867, 945-948 must be atomic.
    // Regression watch: Synchronization must not deadlock or serialize the entire index() path.
    @Test
    void test_hnsw_index_concurrentEntryPointPromotion_noLostPromotions() throws Exception {
        int dims = 4;
        // maxConnections=2 → mL = 1/ln(2) ≈ 1.44, making higher levels more probable
        int maxConnections = 2;

        // Use reflection to replace the Random with a ThreadLocal-controlled one
        // that assigns different levels to different threads.
        var hnswField = LsmVectorIndex.Hnsw.class.getDeclaredField("random");
        hnswField.setAccessible(true);

        // randomLevel() = floor(-ln(max(1e-10, r.nextDouble())) * mL)
        // mL = 1/ln(2) ≈ 1.4427
        // For level 0: nextDouble > 0.5
        // For level ~7: nextDouble ≈ 0.004 → -ln(0.004)*1.4427 ≈ 7.96
        // For level ~2: nextDouble ≈ 0.2  → -ln(0.2)*1.4427  ≈ 2.32

        var threadLevel = new ThreadLocal<Double>();

        // Run many rounds. Each round creates a fresh index, seeds one level-0 node,
        // then races two threads: one with level ~7, one with level ~2.
        // The dangerous interleave: level-7 thread writes EP first, then level-2
        // thread overwrites with maxLayer=2, orphaning layers 3-7 on the first node.
        int outerRounds = 50;
        List<Throwable> errors = new ArrayList<>();

        for (int round = 0; round < outerRounds; round++) {
            // Rebuild a fresh tree + index for each round to avoid accumulation effects
            LsmTree roundTree = buildTree(1024 * 1024);
            VectorIndex.Hnsw<Long> roundIndex = LsmVectorIndex.<Long>hnswBuilder()
                    .lsmTree(roundTree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                    .dimensions(dims)
                    .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                    .maxConnections(maxConnections)
                    .efConstruction(4)
                    .efSearch(4)
                    .build();

            // Replace random
            var roundRiggedRandom = new java.util.Random() {
                @Override
                public double nextDouble() {
                    Double val = threadLevel.get();
                    return val != null ? val : 0.9;
                }
            };
            hnswField.set(roundIndex, roundRiggedRandom);

            // Seed with level 0
            threadLevel.set(0.9); // level 0
            roundIndex.index(0L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

            int numThreads = 2;
            CyclicBarrier barrier = new CyclicBarrier(numThreads);
            List<Thread> threads = new ArrayList<>();

            // Thread 0: high level (level ~8)
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    threadLevel.set(0.004); // level ~7-8
                    barrier.await();
                    roundIndex.index(1L, new float[]{0.0f, 1.0f, 0.0f, 0.0f});
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                }
            }));

            // Thread 1: low level (level ~2)
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    threadLevel.set(0.2); // level ~2
                    barrier.await();
                    roundIndex.index(2L, new float[]{0.0f, 0.0f, 1.0f, 0.0f});
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                }
            }));

            for (Thread t : threads) t.join(30_000);
            if (!errors.isEmpty()) break;

            // Check invariant
            byte[] entryPointKey = new byte[]{(byte) 0xFE};
            Optional<MemorySegment> epOpt = roundTree.get(MemorySegment.ofArray(entryPointKey));
            assertTrue(epOpt.isPresent(), "Round " + round + ": EP should exist");
            byte[] epValue = epOpt.get().toArray(ValueLayout.JAVA_BYTE);
            int epMaxLayer = ((epValue[0] & 0xFF) << 24) | ((epValue[1] & 0xFF) << 16)
                    | ((epValue[2] & 0xFF) << 8) | (epValue[3] & 0xFF);

            int maxNodeLayerIdx = 0;
            for (long docId = 0; docId <= 2; docId++) {
                byte[] docIdBytes = LONG_DOC_ID_SERIALIZER.serialize(docId)
                        .toArray(ValueLayout.JAVA_BYTE);
                Optional<MemorySegment> nodeOpt = roundTree.get(MemorySegment.ofArray(docIdBytes));
                if (nodeOpt.isEmpty()) continue;
                byte[] nodeBytes = nodeOpt.get().toArray(ValueLayout.JAVA_BYTE);
                int layerCount = ((nodeBytes[4] & 0xFF) << 24) | ((nodeBytes[5] & 0xFF) << 16)
                        | ((nodeBytes[6] & 0xFF) << 8) | (nodeBytes[7] & 0xFF);
                if (layerCount - 1 > maxNodeLayerIdx) maxNodeLayerIdx = layerCount - 1;
            }

            assertTrue(epMaxLayer >= maxNodeLayerIdx,
                    "Round " + round + ": Entry point maxLayer (" + epMaxLayer
                            + ") < highest node layer index (" + maxNodeLayerIdx
                            + ") — entry point promotion lost in concurrent index() race");

            roundIndex.close();
        }

        assertTrue(errors.isEmpty(),
                "Concurrent index() threw exceptions: " + errors);
    }


    // Finding: F-R5.shared_state.2.2
    // Bug: Concurrent index() calls produce lost bidirectional edge updates.
    //      Two threads both read a shared neighbor's node, each appends itself,
    //      and the second write silently overwrites the first — losing the first
    //      thread's backlink from the shared neighbor.
    // Correct behavior: After concurrent index() calls complete, every selected
    //                   neighbor's stored neighbor list must contain backlinks to
    //                   ALL nodes that selected it — no lost edges.
    // Fix location: Hnsw.index() bidirectional neighbor update (lines 920-941)
    //               — the read-modify-write on neighbor nodes needs per-node locking.
    // Regression watch: Lock granularity must be per-neighbor-node, not global,
    //                   to avoid serializing all concurrent index() calls.
    @Test
    void test_hnsw_index_concurrentBidirectionalEdgeUpdate_noLostEdges() throws Exception {
        int dims = 4;
        // Large maxConnections so trimming doesn't remove edges — isolates the
        // lost-update race from trimming behavior.
        int maxConnections = 32;

        // Run multiple rounds to increase the chance of hitting the race window.
        int outerRounds = 30;
        int concurrentNodes = 8; // threads per round
        List<Throwable> errors = new ArrayList<>();

        for (int round = 0; round < outerRounds; round++) {
            LsmTree roundTree = buildTree(1024 * 1024);
            VectorIndex.Hnsw<Long> roundIndex = LsmVectorIndex.<Long>hnswBuilder()
                    .lsmTree(roundTree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                    .dimensions(dims)
                    .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                    .maxConnections(maxConnections)
                    .efConstruction(64)
                    .efSearch(64)
                    .build();

            // Force all nodes to level 0 via a rigged Random that always returns
            // high nextDouble() values (producing level 0).
            var randomField = LsmVectorIndex.Hnsw.class.getDeclaredField("random");
            randomField.setAccessible(true);
            randomField.set(roundIndex, new java.util.Random() {
                @Override
                public double nextDouble() {
                    return 0.9; // level 0
                }
            });

            // Seed a single "hub" node at docId=0. All concurrent nodes will
            // connect to this hub because it is the only existing node.
            roundIndex.index(0L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

            // Now index concurrentNodes simultaneously. Each is at level 0, and
            // the hub is the only neighbor they can discover. Each thread will
            // read the hub's node, add itself to the hub's layer-0 neighbors,
            // and write the hub back. The race: two threads read the same
            // pre-update hub, both append, second write clobbers the first.
            CyclicBarrier barrier = new CyclicBarrier(concurrentNodes);
            List<Thread> threads = new ArrayList<>();

            for (int t = 0; t < concurrentNodes; t++) {
                long docId = 10L + t;
                // Give each node a slightly different vector (all similar to hub)
                float[] vec = new float[]{0.9f, 0.1f * t, 0.0f, 0.0f};
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        roundIndex.index(docId, vec);
                    } catch (Exception e) {
                        synchronized (errors) { errors.add(e); }
                    }
                }));
            }

            for (Thread t : threads) t.join(30_000);
            if (!errors.isEmpty()) break;

            // Verify: the hub node (docId=0) must have backlinks to ALL
            // concurrentNodes nodes in its layer-0 neighbor list.
            byte[] hubDocIdBytes = LONG_DOC_ID_SERIALIZER.serialize(0L)
                    .toArray(ValueLayout.JAVA_BYTE);
            Optional<MemorySegment> hubOpt = roundTree.get(
                    MemorySegment.ofArray(hubDocIdBytes));
            assertTrue(hubOpt.isPresent(),
                    "Round " + round + ": hub node should exist");

            byte[] hubBytes = hubOpt.get().toArray(ValueLayout.JAVA_BYTE);
            // Decode: [4-byte docIdLen][4-byte layerCount][layer0: 4-byte count + neighbors...]
            int docIdLen = ((hubBytes[0] & 0xFF) << 24) | ((hubBytes[1] & 0xFF) << 16)
                    | ((hubBytes[2] & 0xFF) << 8) | (hubBytes[3] & 0xFF);
            int layerCount = ((hubBytes[4] & 0xFF) << 24) | ((hubBytes[5] & 0xFF) << 16)
                    | ((hubBytes[6] & 0xFF) << 8) | (hubBytes[7] & 0xFF);
            assertTrue(layerCount >= 1,
                    "Round " + round + ": hub should have at least layer 0");

            int layer0Count = ((hubBytes[8] & 0xFF) << 24) | ((hubBytes[9] & 0xFF) << 16)
                    | ((hubBytes[10] & 0xFF) << 8) | (hubBytes[11] & 0xFF);

            // Collect neighbor docIds from layer 0
            Set<Long> hubNeighborDocIds = new java.util.HashSet<>();
            int off = 12;
            for (int n = 0; n < layer0Count; n++) {
                byte[] nbId = Arrays.copyOfRange(hubBytes, off, off + docIdLen);
                hubNeighborDocIds.add(LONG_DOC_ID_SERIALIZER.deserialize(
                        MemorySegment.ofArray(nbId)));
                off += docIdLen;
            }

            // All concurrently-indexed nodes should appear as neighbors of the hub
            Set<Long> expectedDocIds = new java.util.HashSet<>();
            for (int t = 0; t < concurrentNodes; t++) {
                expectedDocIds.add(10L + t);
            }

            Set<Long> missing = expectedDocIds.stream()
                    .filter(id -> !hubNeighborDocIds.contains(id))
                    .collect(Collectors.toSet());

            assertTrue(missing.isEmpty(),
                    "Round " + round + ": hub node lost backlinks to " + missing.size()
                            + " of " + concurrentNodes + " nodes. Missing docIds: "
                            + missing + ". Hub has " + layer0Count + " neighbors: "
                            + hubNeighborDocIds
                            + " — concurrent read-modify-write race dropped edges.");

            roundIndex.close();
        }

        assertTrue(errors.isEmpty(),
                "Concurrent index() threw exceptions: " + errors);
    }

    // Finding: F-R5.shared_state.2.6
    // Bug: Soft-delete tombstone cleared before node write creates visibility window —
    //      during re-index, tombstone is deleted (line 870) before the new node is written
    //      (line 909), so a concurrent search sees the old (stale) node data as non-deleted.
    // Correct behavior: The soft-delete tombstone should remain until the new node data
    //                   has been written, so searches never see stale pre-removal vector data.
    // Fix location: Hnsw.index() — move lsmTree.delete(softDeleteKey) after the early
    //               node write (line 909-910) or after the final node rewrite (line 955-956).
    // Regression watch: Tombstone must still be cleared during re-index so the new node
    //                   is visible to subsequent searches.
    @Test
    void test_hnsw_index_reindexTombstoneVisibilityWindow_noStaleData() throws Exception {
        int dims = 4;
        int maxConnections = 4;

        // The old vector and the new vector are intentionally very different
        // so we can detect which one a search returns.
        float[] oldVector = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        float[] newVector = new float[]{0.0f, 0.0f, 0.0f, 1.0f};

        // Query that is similar to oldVector but dissimilar to newVector.
        // dot(query, oldVector) = 1.0, dot(query, newVector) = 0.0
        float[] queryForOld = new float[]{1.0f, 0.0f, 0.0f, 0.0f};

        int rounds = 200;
        AtomicReference<String> violation = new AtomicReference<>();

        for (int round = 0; round < rounds && violation.get() == null; round++) {
            LsmTree roundTree = buildTree(1024 * 1024);
            VectorIndex.Hnsw<Long> roundIndex = LsmVectorIndex.<Long>hnswBuilder()
                    .lsmTree(roundTree)
                    .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                    .dimensions(dims)
                    .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                    .maxConnections(maxConnections)
                    .efConstruction(16)
                    .efSearch(16)
                    .build();

            // Force all nodes to level 0 so structure is simple
            var randomField = LsmVectorIndex.Hnsw.class.getDeclaredField("random");
            randomField.setAccessible(true);
            randomField.set(roundIndex, new java.util.Random() {
                @Override
                public double nextDouble() {
                    return 0.9; // level 0
                }
            });

            long targetDocId = 42L;

            // Step 1: index the target with oldVector, then remove it
            roundIndex.index(targetDocId, oldVector);
            roundIndex.remove(targetDocId);

            // At this point: old node data is in the tree, soft-delete tombstone exists.
            // A search should NOT return targetDocId (it's soft-deleted).

            // Step 2: race re-index (with newVector) against repeated searches
            AtomicBoolean reindexDone = new AtomicBoolean(false);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Throwable> errors = new ArrayList<>();

            // Thread A: re-index the target with newVector
            Thread indexer = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    roundIndex.index(targetDocId, newVector);
                    reindexDone.set(true);
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                }
            });

            // Thread B: search repeatedly during the re-index window
            Thread searcher = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    while (!reindexDone.get()) {
                        List<? extends VectorIndex.SearchResult<Long>> results =
                                roundIndex.search(queryForOld, 10);
                        for (var r : results) {
                            if (r.docId().equals(targetDocId)) {
                                // Found the target doc in search results during re-index.
                                // If the score matches the OLD vector, the search saw
                                // stale data through the tombstone visibility window.
                                // dot(queryForOld, oldVector) = 1.0
                                // dot(queryForOld, newVector) = 0.0
                                if (r.score() > 0.5f) {
                                    violation.set("Round " + ": search returned targetDocId="
                                            + targetDocId + " with score=" + r.score()
                                            + " matching OLD vector during re-index. "
                                            + "Tombstone was cleared before new node was written, "
                                            + "exposing stale pre-removal data.");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                }
            });

            indexer.join(10_000);
            searcher.join(10_000);

            assertTrue(errors.isEmpty(),
                    "Round " + round + ": exceptions during test: " + errors);

            roundIndex.close();
        }

        assertNull(violation.get(), violation.get());
    }

    // Finding: F-R1.shared_state.3.2
    // Bug: No upper-bound validation on dimensions — Integer.MAX_VALUE accepted by setter
    //      and validateBase(), causing integer overflow in downstream byte allocation
    //      (dimensions * 4 for FLOAT32, dimensions * 2 for FLOAT16) and
    //      OutOfMemoryError / NegativeArraySizeException at runtime.
    // Correct behavior: dimensions() setter should reject values that would overflow
    //                   when multiplied by the largest bytesPerComponent (4 for FLOAT32).
    // Fix location: AbstractBuilder.dimensions() setter (LsmVectorIndex.java:311-315)
    // Regression watch: Upper bound must be <= Integer.MAX_VALUE / 4 to prevent overflow
    //                   in all precision modes.
    @Test
    void test_abstractBuilder_dimensions_integerMaxValue_rejectsOverflowProneDimensions() {
        // Integer.MAX_VALUE * 4 overflows int, producing a negative value.
        // The builder should reject this at configuration time, not at runtime.
        assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.<Long>ivfFlatBuilder().dimensions(Integer.MAX_VALUE),
                "dimensions(Integer.MAX_VALUE) should throw IllegalArgumentException "
                        + "because dimensions * 4 (FLOAT32 byte allocation) overflows int");
    }

    @Test
    void test_abstractBuilder_dimensions_halfMaxValue_rejectsOverflowProneDimensions() {
        // Integer.MAX_VALUE / 2 is still too large: * 4 overflows for FLOAT32.
        int halfMax = Integer.MAX_VALUE / 2;
        assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.<Long>ivfFlatBuilder().dimensions(halfMax),
                "dimensions(Integer.MAX_VALUE / 2) should throw IllegalArgumentException "
                        + "because dimensions * 4 (FLOAT32 byte allocation) overflows int");
    }

    // Finding: F-R1.shared_state.3.3
    // Bug: nprobe can exceed numClusters in IvfFlat.Builder — semantically invalid state
    // Correct behavior: build() should throw IllegalArgumentException when nprobe > numClusters
    // Fix location: IvfFlat.Builder.build() or IvfFlat constructor — add cross-field validation
    // Regression watch: Existing tests that set nprobe == numClusters must still pass
    @Test
    void test_ivfFlatBuilder_nprobeExceedsNumClusters_rejectsInvalidState() throws IOException {
        LsmTree tree = buildTree(1024 * 1024);

        // numClusters=1, nprobe=100 — nprobe far exceeds numClusters
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LsmVectorIndex.<Long>ivfFlatBuilder()
                        .lsmTree(tree)
                        .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .dimensions(4)
                        .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                        .numClusters(1)
                        .nprobe(100)
                        .build(),
                "build() should reject nprobe > numClusters as semantically invalid");

        assertTrue(ex.getMessage().contains("nprobe"),
                "Error message should mention nprobe, got: " + ex.getMessage());
    }

    // Finding: F-R5.shared_state.4.1
    // Bug: HNSW searchLayer entry node bypasses NaN check, corrupts both frontier and results heaps
    // Correct behavior: NaN-component vectors must be rejected at index() so they never become
    //                   entry points whose NaN score corrupts searchLayer heap ordering
    // Fix location: Hnsw.index() — validateFiniteComponents() call rejects NaN vectors;
    //               searchLayer — Float.isFinite(entryScore) guard on entry node;
    //               ScoredCandidate compact constructor — rejects non-finite scores
    // Regression watch: All three defense layers must remain; removing input validation alone
    //                   would re-expose the searchLayer corruption path
    @Test
    void test_hnsw_searchLayer_nanEntryNode_rejectedAtIndexBoundary() throws Exception {
        int dims = 4;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .maxConnections(4)
                .efConstruction(8)
                .efSearch(8)
                .build();

        // Index a valid node first — this becomes the entry point
        index.index(1L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        // Attempt to index a vector with NaN components.
        // Before the fix, this would succeed and if this node became the entry point,
        // scoreNode would return NaN, which was added to both frontier and results
        // heaps without checking, corrupting Float.compare-based ordering.
        float[] nanVector = new float[]{Float.NaN, 1.0f, 0.0f, 0.0f};
        var ex = assertThrows(IllegalArgumentException.class,
                () -> index.index(2L, nanVector),
                "HNSW index() must reject vectors with NaN components to prevent "
                        + "searchLayer entry node from producing NaN scores that corrupt heaps");

        assertTrue(ex.getMessage().contains("NaN"),
                "Error message should mention NaN, got: " + ex.getMessage());

        // Verify the valid entry point is still searchable — the rejected NaN vector
        // did not corrupt any shared state
        List<VectorIndex.SearchResult<Long>> results = index.search(
                new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 1);
        assertEquals(1, results.size(),
                "Search should still return the valid vector after NaN rejection");
        assertEquals(1L, results.getFirst().docId(),
                "Search should return the correct docId");

        index.close();
    }

    // Finding: F-R5.shared_state.4.5
    // Bug: EntryPoint record accepts negative maxLayer without validation.
    //      If stored entry point data is corrupted to contain a negative maxLayer,
    //      readEntryPoint() silently returns it. In index(), startLevel becomes
    //      negative, skipping all layers, producing a disconnected node.
    // Correct behavior: readEntryPoint() should reject a negative maxLayer with
    //                   an IllegalStateException (corrupt stored data).
    // Fix location: Hnsw.EntryPoint compact constructor (LsmVectorIndex.java:1062)
    // Regression watch: Must not break normal entry point reads with maxLayer >= 0.
    @Test
    void test_hnsw_entryPoint_negativeMaxLayer_throwsOnCorruptData() throws Exception {
        int dims = 4;

        LsmTree tree = buildTree(1024 * 1024);
        VectorIndex.Hnsw<Long> index = LsmVectorIndex.<Long>hnswBuilder()
                .lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER)
                .dimensions(dims)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT)
                .maxConnections(4)
                .efConstruction(16)
                .efSearch(16)
                .build();

        // Index one node normally to establish the graph
        index.index(1L, new float[]{1.0f, 0.0f, 0.0f, 0.0f});

        // Corrupt the entry point by writing a negative maxLayer directly to the tree.
        // Entry point key is 0xFE; value format is [4-byte BE maxLayer][docIdBytes].
        byte[] epKey = new byte[]{(byte) 0xFE};
        byte[] docIdBytes = LONG_DOC_ID_SERIALIZER.serialize(1L)
                .toArray(ValueLayout.JAVA_BYTE);
        // Encode maxLayer = -1 in big-endian
        byte[] corruptValue = new byte[4 + docIdBytes.length];
        int negativeLayer = -1;
        corruptValue[0] = (byte) (negativeLayer >>> 24);
        corruptValue[1] = (byte) (negativeLayer >>> 16);
        corruptValue[2] = (byte) (negativeLayer >>> 8);
        corruptValue[3] = (byte) negativeLayer;
        System.arraycopy(docIdBytes, 0, corruptValue, 4, docIdBytes.length);
        tree.put(MemorySegment.ofArray(epKey), MemorySegment.ofArray(corruptValue));

        // Attempting to index a new node with the corrupted entry point should fail
        // with an exception indicating corrupt data, NOT silently produce a
        // disconnected node.
        assertThrows(IllegalStateException.class,
                () -> index.index(2L, new float[]{0.0f, 1.0f, 0.0f, 0.0f}),
                "Negative maxLayer from corrupted entry point should throw "
                        + "IllegalStateException, not silently create a disconnected node");

        index.close();
    }

    @Test
    void test_abstractBuilder_dimensions_maxSafeValue_accepted() {
        // Integer.MAX_VALUE / 4 is the largest safe value for FLOAT32 (dimensions * 4 fits int).
        int maxSafe = Integer.MAX_VALUE / 4;
        // Should NOT throw — this is the boundary of acceptable values.
        assertDoesNotThrow(
                () -> LsmVectorIndex.<Long>ivfFlatBuilder().dimensions(maxSafe),
                "dimensions(Integer.MAX_VALUE / 4) should be accepted — "
                        + "it is the largest value where dimensions * 4 does not overflow");
    }

}
