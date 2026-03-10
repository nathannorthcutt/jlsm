package jlsm.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.tree.LsmTree;
import jlsm.core.tree.PrefetchHint;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Non-instantiable namespace class containing concrete {@link VectorIndex} implementations backed
 * by an {@link LsmTree}.
 *
 * <ul>
 *   <li>{@link IvfFlat} — Inverted File with Flat re-scoring. Uses three key namespaces within
 *       one {@link LsmTree}: centroid coordinates {@code [0x00]}, posting-list entries
 *       {@code [0x01]}, and reverse-lookup entries {@code [0x02]}.</li>
 *   <li>{@link Hnsw} — Hierarchical Navigable Small World graph. Uses doc-id bytes as node keys,
 *       {@code [0xFE]} for the entry point, and {@code [0xFF][docId]} for soft-deletes.</li>
 * </ul>
 *
 * <p>All similarity computations are accelerated with {@code FloatVector.SPECIES_PREFERRED} from
 * {@code jdk.incubator.vector}, with a scalar tail loop for dimensions not covered by the SIMD
 * width.
 *
 * <p>Obtain instances via {@link #ivfFlatBuilder()} and {@link #hnswBuilder()}.
 */
public final class LsmVectorIndex {

    // Preferred SIMD species for float computation
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private LsmVectorIndex() {
        throw new UnsupportedOperationException("utility class");
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /** Returns a builder for an {@link IvfFlat} vector index backed by an {@link LsmTree}. */
    public static <D> IvfFlat.Builder<D> ivfFlatBuilder() {
        return new IvfFlat.Builder<>();
    }

    /** Returns a builder for an {@link Hnsw} vector index backed by an {@link LsmTree}. */
    public static <D> Hnsw.Builder<D> hnswBuilder() {
        return new Hnsw.Builder<>();
    }

    // -----------------------------------------------------------------------
    // Float encoding helpers
    // -----------------------------------------------------------------------

    static byte[] encodeFloats(float[] floats) {
        assert floats != null : "floats must not be null";
        byte[] bytes = new byte[floats.length * 4];
        for (int i = 0; i < floats.length; i++) {
            int bits = Float.floatToRawIntBits(floats[i]);
            bytes[i * 4]     = (byte) (bits >>> 24);
            bytes[i * 4 + 1] = (byte) (bits >>> 16);
            bytes[i * 4 + 2] = (byte) (bits >>> 8);
            bytes[i * 4 + 3] = (byte) bits;
        }
        return bytes;
    }

    static float[] decodeFloats(byte[] bytes, int dimensions) {
        assert bytes != null : "bytes must not be null";
        assert bytes.length == dimensions * 4 : "byte count mismatch";
        float[] floats = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            int bits = ((bytes[i * 4] & 0xFF) << 24)
                     | ((bytes[i * 4 + 1] & 0xFF) << 16)
                     | ((bytes[i * 4 + 2] & 0xFF) << 8)
                     |  (bytes[i * 4 + 3] & 0xFF);
            floats[i] = Float.intBitsToFloat(bits);
        }
        return floats;
    }

    // -----------------------------------------------------------------------
    // SIMD similarity functions
    // -----------------------------------------------------------------------

    /** Returns the dot product of two float vectors using SIMD + scalar tail. */
    static float dotProduct(float[] a, float[] b) {
        assert a != null && b != null : "vectors must not be null";
        assert a.length == b.length : "vectors must have the same length";
        int i = 0, ub = SPECIES.loopBound(a.length);
        var acc = FloatVector.zero(SPECIES);
        for (; i < ub; i += SPECIES.length()) {
            acc = FloatVector.fromArray(SPECIES, a, i)
                    .fma(FloatVector.fromArray(SPECIES, b, i), acc);
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    /** Returns the cosine similarity in [−1, 1]; 0 if either vector has zero norm. */
    static float cosine(float[] a, float[] b) {
        float dot  = dotProduct(a, b);
        float denom = (float) Math.sqrt(dotProduct(a, a) * dotProduct(b, b));
        return denom == 0.0f ? 0.0f : dot / denom;
    }

    /** Returns the negated Euclidean distance ≤ 0; closer = less negative = higher score. */
    static float euclidean(float[] a, float[] b) {
        assert a.length == b.length : "vectors must have the same length";
        int i = 0, ub = SPECIES.loopBound(a.length);
        var acc = FloatVector.zero(SPECIES);
        for (; i < ub; i += SPECIES.length()) {
            var diff = FloatVector.fromArray(SPECIES, a, i)
                    .sub(FloatVector.fromArray(SPECIES, b, i));
            acc = diff.fma(diff, acc);
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++) { float d = a[i] - b[i]; sum += d * d; }
        return -(float) Math.sqrt(sum);
    }

    /** Dispatches to the appropriate similarity function; higher = more similar for all. */
    static float score(float[] a, float[] b, SimilarityFunction fn) {
        return switch (fn) {
            case COSINE      -> cosine(a, b);
            case DOT_PRODUCT -> dotProduct(a, b);
            case EUCLIDEAN   -> euclidean(a, b);
        };
    }

    // -----------------------------------------------------------------------
    // Byte encoding helpers
    // -----------------------------------------------------------------------

    private static int writeInt(byte[] buf, int off, int v) {
        buf[off++] = (byte) (v >>> 24);
        buf[off++] = (byte) (v >>> 16);
        buf[off++] = (byte) (v >>> 8);
        buf[off++] = (byte) v;
        return off;
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8) |  (buf[off + 3] & 0xFF);
    }

    // -----------------------------------------------------------------------
    // Shared result type
    // -----------------------------------------------------------------------

    private record ScoredCandidate(byte[] docIdBytes, float score) {}

    // -----------------------------------------------------------------------
    // Abstract builder base
    // -----------------------------------------------------------------------

    private abstract static class AbstractBuilder<D, B extends AbstractBuilder<D, B>> {

        LsmTree lsmTree;
        MemorySerializer<D> docIdSerializer;
        int dimensions; // 0 = not set
        SimilarityFunction similarityFunction;

        @SuppressWarnings("unchecked")
        public B lsmTree(LsmTree lsmTree) {
            this.lsmTree = Objects.requireNonNull(lsmTree, "lsmTree must not be null");
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B docIdSerializer(MemorySerializer<D> docIdSerializer) {
            this.docIdSerializer = Objects.requireNonNull(docIdSerializer,
                    "docIdSerializer must not be null");
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B dimensions(int dimensions) {
            if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be > 0, got: " + dimensions);
            this.dimensions = dimensions;
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B similarityFunction(SimilarityFunction fn) {
            this.similarityFunction = Objects.requireNonNull(fn,
                    "similarityFunction must not be null");
            return (B) this;
        }

        void validateBase() {
            Objects.requireNonNull(lsmTree, "lsmTree must not be null");
            Objects.requireNonNull(docIdSerializer, "docIdSerializer must not be null");
            if (dimensions <= 0) {
                throw new IllegalArgumentException("dimensions must be > 0");
            }
            Objects.requireNonNull(similarityFunction, "similarityFunction must not be null");
        }
    }

    // -----------------------------------------------------------------------
    // IvfFlat implementation
    // -----------------------------------------------------------------------

    /**
     * Inverted File Flat (IVF-Flat) vector index.
     *
     * <h2>Key namespaces within the backing {@link LsmTree}</h2>
     * <pre>
     *   [0x00][4-byte BE centroid_id]               → dim×4 bytes (centroid float coords)
     *   [0x01][4-byte BE centroid_id][doc_id_bytes] → dim×4 bytes (vector float coords)
     *   [0x02][doc_id_bytes]                        → 4-byte BE centroid_id (reverse lookup)
     * </pre>
     *
     * <h2>Clustering strategy</h2>
     * The first {@code numClusters} indexed vectors become centroids. Subsequent vectors are
     * assigned to the nearest centroid (using the configured {@link SimilarityFunction}).
     */
    public static final class IvfFlat<D> implements VectorIndex.IvfFlat<D> {

        // Key namespace prefix bytes
        private static final byte CENTROID_PREFIX = 0x00;
        private static final byte POSTING_PREFIX  = 0x01;
        private static final byte REVERSE_PREFIX  = 0x02;

        // Scan bounds: [CENTROID_PREFIX, POSTING_PREFIX) covers all centroid keys
        private static final MemorySegment CENTROID_SCAN_START =
                MemorySegment.ofArray(new byte[]{CENTROID_PREFIX});
        private static final MemorySegment CENTROID_SCAN_END =
                MemorySegment.ofArray(new byte[]{POSTING_PREFIX});

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;
        private final int dimensions;
        private final SimilarityFunction similarityFunction;
        private final int numClusters;
        private final int nprobe;

        private IvfFlat(LsmTree lsmTree, MemorySerializer<D> docIdSerializer,
                         int dimensions, SimilarityFunction similarityFunction,
                         int numClusters, int nprobe) {
            assert lsmTree != null;
            assert docIdSerializer != null;
            assert dimensions > 0;
            assert similarityFunction != null;
            assert numClusters > 0;
            assert nprobe > 0;
            this.lsmTree = lsmTree;
            this.docIdSerializer = docIdSerializer;
            this.dimensions = dimensions;
            this.similarityFunction = similarityFunction;
            this.numClusters = numClusters;
            this.nprobe = nprobe;
        }

        @Override
        public void index(D docId, float[] vector) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(vector, "vector must not be null");
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "vector.length=" + vector.length + " != dimensions=" + dimensions);
            }

            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            byte[] vectorBytes = encodeFloats(vector);

            int centroidId = assignCentroid(vector);

            // Store posting: [0x01][centroid_id][docId] → vector
            lsmTree.put(MemorySegment.ofArray(postingKey(centroidId, docIdBytes)),
                        MemorySegment.ofArray(vectorBytes));

            // Store reverse lookup: [0x02][docId] → centroid_id (4 bytes BE)
            lsmTree.put(MemorySegment.ofArray(reverseKey(docIdBytes)),
                        MemorySegment.ofArray(encodeCentroidId(centroidId)));
        }

        @Override
        public void remove(D docId) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");

            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            byte[] revKey = reverseKey(docIdBytes);

            Optional<MemorySegment> revOpt = lsmTree.get(MemorySegment.ofArray(revKey));
            if (revOpt.isEmpty()) return; // not indexed

            int centroidId = decodeCentroidId(revOpt.get().toArray(ValueLayout.JAVA_BYTE), 0);

            lsmTree.delete(MemorySegment.ofArray(postingKey(centroidId, docIdBytes)));
            lsmTree.delete(MemorySegment.ofArray(revKey));
        }

        @Override
        public List<VectorIndex.SearchResult<D>> search(float[] query, int topK) throws IOException {
            Objects.requireNonNull(query, "query must not be null");
            if (query.length != dimensions) {
                throw new IllegalArgumentException(
                        "query.length=" + query.length + " != dimensions=" + dimensions);
            }
            if (topK <= 0) throw new IllegalArgumentException("topK must be > 0");

            // Load all centroids
            List<float[]> centroidVecs = new ArrayList<>();
            List<Integer> centroidIds = new ArrayList<>();
            Iterator<Entry> centIt = lsmTree.scan(CENTROID_SCAN_START, CENTROID_SCAN_END);
            while (centIt.hasNext()) {
                Entry entry = centIt.next();
                if (!(entry instanceof Entry.Put put)) continue;
                byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                if (key.length != 5 || key[0] != CENTROID_PREFIX) continue;
                int cid = decodeCentroidId(key, 1);
                float[] cVec = decodeFloats(put.value().toArray(ValueLayout.JAVA_BYTE), dimensions);
                centroidVecs.add(cVec);
                centroidIds.add(cid);
            }

            if (centroidVecs.isEmpty()) return List.of();

            // Score centroids, pick top nprobe
            int actualNprobe = Math.min(nprobe, centroidVecs.size());
            int[] nearestCentroidIds = topNCentroids(query, centroidVecs, centroidIds, actualNprobe);

            // Accumulate candidates from posting lists (min-heap to evict worst when over topK)
            PriorityQueue<ScoredCandidate> heap = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredCandidate::score)); // min at head for eviction

            for (int cid : nearestCentroidIds) {
                byte[] postStart = postingKey(cid, new byte[0]);
                byte[] postEnd = postingEndKey(cid);

                Iterator<Entry> postIt = lsmTree.scan(
                        MemorySegment.ofArray(postStart), MemorySegment.ofArray(postEnd));

                while (postIt.hasNext()) {
                    Entry entry = postIt.next();
                    if (!(entry instanceof Entry.Put put)) continue; // skip tombstones

                    byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                    if (key.length <= 5 || key[0] != POSTING_PREFIX) continue;

                    byte[] docIdBytes = Arrays.copyOfRange(key, 5, key.length);
                    float[] vec = decodeFloats(put.value().toArray(ValueLayout.JAVA_BYTE), dimensions);
                    float s = score(query, vec, similarityFunction);

                    heap.add(new ScoredCandidate(docIdBytes, s));
                    if (heap.size() > topK) heap.poll(); // evict worst
                }
            }

            // Drain heap in descending order
            List<ScoredCandidate> sorted = new ArrayList<>(heap);
            sorted.sort((a, b) -> Float.compare(b.score(), a.score()));

            List<VectorIndex.SearchResult<D>> results = new ArrayList<>(sorted.size());
            for (ScoredCandidate c : sorted) {
                D id = docIdSerializer.deserialize(MemorySegment.ofArray(c.docIdBytes()));
                results.add(new VectorIndex.SearchResult<>(id, c.score()));
            }
            return results;
        }

        @Override
        public void close() throws IOException {
            lsmTree.close();
        }

        // --- Key construction helpers ---

        private static byte[] centroidKey(int centroidId) {
            return new byte[]{
                CENTROID_PREFIX,
                (byte) (centroidId >>> 24), (byte) (centroidId >>> 16),
                (byte) (centroidId >>> 8),  (byte) centroidId
            };
        }

        private static byte[] postingKey(int centroidId, byte[] docIdBytes) {
            byte[] key = new byte[5 + docIdBytes.length];
            key[0] = POSTING_PREFIX;
            key[1] = (byte) (centroidId >>> 24); key[2] = (byte) (centroidId >>> 16);
            key[3] = (byte) (centroidId >>> 8);  key[4] = (byte) centroidId;
            System.arraycopy(docIdBytes, 0, key, 5, docIdBytes.length);
            return key;
        }

        private static byte[] postingEndKey(int centroidId) {
            long next = (centroidId & 0xFFFFFFFFL) + 1L;
            if (next > 0xFFFFFFFFL) {
                return new byte[]{REVERSE_PREFIX}; // overflow → next prefix byte
            }
            return new byte[]{
                POSTING_PREFIX,
                (byte) (next >>> 24), (byte) (next >>> 16),
                (byte) (next >>> 8),  (byte) next
            };
        }

        private static byte[] reverseKey(byte[] docIdBytes) {
            byte[] key = new byte[1 + docIdBytes.length];
            key[0] = REVERSE_PREFIX;
            System.arraycopy(docIdBytes, 0, key, 1, docIdBytes.length);
            return key;
        }

        private static byte[] encodeCentroidId(int centroidId) {
            return new byte[]{
                (byte) (centroidId >>> 24), (byte) (centroidId >>> 16),
                (byte) (centroidId >>> 8),  (byte) centroidId
            };
        }

        private static int decodeCentroidId(byte[] bytes, int offset) {
            return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                 | ((bytes[offset + 2] & 0xFF) << 8) |  (bytes[offset + 3] & 0xFF);
        }

        // --- Centroid assignment ---

        /**
         * Returns the centroid ID to assign to a new vector. Creates a new centroid if fewer than
         * {@code numClusters} centroids exist; otherwise assigns to the most similar existing one.
         */
        private int assignCentroid(float[] vector) throws IOException {
            List<float[]> centroids = new ArrayList<>();
            List<Integer> centroidIdList = new ArrayList<>();

            Iterator<Entry> it = lsmTree.scan(CENTROID_SCAN_START, CENTROID_SCAN_END);
            while (it.hasNext()) {
                Entry entry = it.next();
                if (!(entry instanceof Entry.Put put)) continue;
                byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                if (key.length != 5 || key[0] != CENTROID_PREFIX) continue;
                int cid = decodeCentroidId(key, 1);
                float[] cVec = decodeFloats(put.value().toArray(ValueLayout.JAVA_BYTE), dimensions);
                centroids.add(cVec);
                centroidIdList.add(cid);
            }

            if (centroids.size() < numClusters) {
                int newCid = centroids.size(); // sequential 0-based centroid IDs
                lsmTree.put(MemorySegment.ofArray(centroidKey(newCid)),
                            MemorySegment.ofArray(encodeFloats(vector)));
                return newCid;
            }

            return nearestCentroid(vector, centroids, centroidIdList);
        }

        private int nearestCentroid(float[] vector, List<float[]> centroids, List<Integer> ids) {
            int best = ids.get(0);
            float bestScore = score(vector, centroids.get(0), similarityFunction);
            for (int i = 1; i < centroids.size(); i++) {
                float s = score(vector, centroids.get(i), similarityFunction);
                if (s > bestScore) { bestScore = s; best = ids.get(i); }
            }
            return best;
        }

        private int[] topNCentroids(float[] query, List<float[]> centroids,
                                     List<Integer> ids, int n) {
            float[] scores = new float[centroids.size()];
            for (int i = 0; i < centroids.size(); i++) {
                scores[i] = score(query, centroids.get(i), similarityFunction);
            }
            Integer[] indices = new Integer[centroids.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Float.compare(scores[b], scores[a])); // descending

            int[] result = new int[n];
            for (int i = 0; i < n; i++) result[i] = ids.get(indices[i]);
            return result;
        }

        // -----------------------------------------------------------------------
        // Builder
        // -----------------------------------------------------------------------

        /** Builder for {@link LsmVectorIndex.IvfFlat}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            private int numClusters = 256;
            private int nprobe = 8;

            public Builder<D> numClusters(int n) {
                if (n <= 0) throw new IllegalArgumentException("numClusters must be > 0");
                this.numClusters = n;
                return this;
            }

            public Builder<D> nprobe(int n) {
                if (n <= 0) throw new IllegalArgumentException("nprobe must be > 0");
                this.nprobe = n;
                return this;
            }

            public VectorIndex.IvfFlat<D> build() {
                validateBase();
                return new LsmVectorIndex.IvfFlat<>(lsmTree, docIdSerializer, dimensions,
                        similarityFunction, numClusters, nprobe);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hnsw implementation
    // -----------------------------------------------------------------------

    /**
     * Hierarchical Navigable Small World (HNSW) graph vector index.
     *
     * <h2>Key namespaces within the backing {@link LsmTree}</h2>
     * <pre>
     *   [doc_id_bytes]          → node value (see below) — node data
     *   [0xFE]                  → [4-byte BE max_layer][doc_id_bytes] — entry point
     *   [0xFF][doc_id_bytes]    → empty segment — soft-delete tombstone
     * </pre>
     *
     * <h2>Node value encoding</h2>
     * <pre>
     *   [4-byte BE docIdBytesLen]
     *   [4-byte BE layerCount]
     *   for each layer l = 0..layerCount-1:
     *     [4-byte BE neighborCount_l]
     *     [neighborCount_l × docIdBytesLen bytes]
     *   [dim × 4 bytes] — big-endian IEEE-754 float vector
     * </pre>
     *
     * <h2>Soft-delete</h2>
     * {@link #remove} writes a soft-delete key {@code [0xFF][docId]}. During search,
     * soft-deleted nodes are skipped in result collection.
     */
    public static final class Hnsw<D> implements VectorIndex.Hnsw<D> {

        private static final byte[] ENTRY_POINT_KEY = new byte[]{(byte) 0xFE};
        private static final byte SOFT_DELETE_PREFIX = (byte) 0xFF;

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;
        private final int dimensions;
        private final SimilarityFunction similarityFunction;
        private final int M;
        private final int efConstruction;
        private final int efSearch;
        private final Random random = new Random();

        private Hnsw(LsmTree lsmTree, MemorySerializer<D> docIdSerializer,
                      int dimensions, SimilarityFunction similarityFunction,
                      int M, int efConstruction, int efSearch) {
            assert lsmTree != null;
            assert docIdSerializer != null;
            assert dimensions > 0;
            assert similarityFunction != null;
            assert M > 0;
            assert efConstruction > 0;
            assert efSearch > 0;
            this.lsmTree = lsmTree;
            this.docIdSerializer = docIdSerializer;
            this.dimensions = dimensions;
            this.similarityFunction = similarityFunction;
            this.M = M;
            this.efConstruction = efConstruction;
            this.efSearch = efSearch;
        }

        @Override
        public void index(D docId, float[] vector) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(vector, "vector must not be null");
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "vector.length=" + vector.length + " != dimensions=" + dimensions);
            }

            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            int newLevel = randomLevel();

            EntryPoint ep = readEntryPoint();

            if (ep == null) {
                // First node: create empty layer list and set as entry point
                List<List<byte[]>> layers = new ArrayList<>();
                for (int l = 0; l <= newLevel; l++) layers.add(new ArrayList<>());
                lsmTree.put(MemorySegment.ofArray(docIdBytes),
                            MemorySegment.ofArray(encodeNode(docIdBytes, layers, vector)));
                lsmTree.put(MemorySegment.ofArray(ENTRY_POINT_KEY),
                            MemorySegment.ofArray(encodeEntryPoint(docIdBytes, newLevel)));
                return;
            }

            int maxLayer = ep.maxLayer();
            byte[] currentEp = ep.docIdBytes();

            // Phase 1: traverse from maxLayer down to newLevel+1, greedy (ef=1)
            for (int lc = maxLayer; lc > newLevel; lc--) {
                currentEp = greedySearch1(currentEp, vector, lc);
            }

            // Phase 2: from min(newLevel, maxLayer) down to 0, collect neighbors
            int startLevel = Math.min(newLevel, maxLayer);
            List<List<byte[]>> newNodeLayers = new ArrayList<>();
            for (int l = 0; l <= newLevel; l++) newNodeLayers.add(new ArrayList<>());

            for (int lc = startLevel; lc >= 0; lc--) {
                List<ScoredCandidate> candidates = searchLayer(currentEp, vector, efConstruction, lc);
                List<byte[]> selectedNeighbors = selectNeighbors(candidates, M);
                newNodeLayers.set(lc, selectedNeighbors);

                // Update currentEp for next (lower) layer: best candidate in this layer
                if (!candidates.isEmpty()) {
                    ScoredCandidate best = candidates.stream()
                            .max(Comparator.comparingDouble(ScoredCandidate::score))
                            .orElseThrow();
                    currentEp = best.docIdBytes();
                }

                // Bidirectional neighbor updates
                for (byte[] nbId : selectedNeighbors) {
                    Optional<MemorySegment> nbOpt = lsmTree.get(MemorySegment.ofArray(nbId));
                    if (nbOpt.isEmpty()) continue;

                    DecodedNode nbNode = decodeNode(nbOpt.get().toArray(ValueLayout.JAVA_BYTE));
                    if (lc >= nbNode.layerNeighbors().size()) continue;

                    List<List<byte[]>> updatedLayers = new ArrayList<>(nbNode.layerNeighbors());
                    List<byte[]> updatedNbrs = new ArrayList<>(updatedLayers.get(lc));
                    updatedNbrs.add(docIdBytes);

                    if (updatedNbrs.size() > M) {
                        updatedNbrs = trimToM(nbNode.vector(), updatedNbrs, M);
                    }

                    updatedLayers.set(lc, updatedNbrs);
                    lsmTree.put(MemorySegment.ofArray(nbId),
                                MemorySegment.ofArray(encodeNode(nbId, updatedLayers, nbNode.vector())));
                }
            }

            // Write new node
            lsmTree.put(MemorySegment.ofArray(docIdBytes),
                        MemorySegment.ofArray(encodeNode(docIdBytes, newNodeLayers, vector)));

            // Promote entry point if this node has a higher level
            if (newLevel > maxLayer) {
                lsmTree.put(MemorySegment.ofArray(ENTRY_POINT_KEY),
                            MemorySegment.ofArray(encodeEntryPoint(docIdBytes, newLevel)));
            }
        }

        @Override
        public void remove(D docId) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            // Soft delete: write tombstone at [0xFF][docId]
            lsmTree.put(MemorySegment.ofArray(softDeleteKey(docIdBytes)), MemorySegment.NULL);
        }

        @Override
        public List<VectorIndex.SearchResult<D>> search(float[] query, int topK) throws IOException {
            Objects.requireNonNull(query, "query must not be null");
            if (query.length != dimensions) {
                throw new IllegalArgumentException(
                        "query.length=" + query.length + " != dimensions=" + dimensions);
            }
            if (topK <= 0) throw new IllegalArgumentException("topK must be > 0");

            EntryPoint ep = readEntryPoint();
            if (ep == null) return List.of();

            // Optional prefetch hint for the entry point
            if (lsmTree instanceof PrefetchHint ph) {
                ph.prefetch(List.of(MemorySegment.ofArray(ep.docIdBytes())));
            }

            byte[] currentEp = ep.docIdBytes();

            // Phase 1: from maxLayer down to 1, greedy
            for (int lc = ep.maxLayer(); lc > 0; lc--) {
                currentEp = greedySearch1(currentEp, query, lc);
            }

            // Phase 2: layer 0 with efSearch
            List<ScoredCandidate> candidates = searchLayer(currentEp, query, efSearch, 0);

            // Sort descending, filter soft-deleted, take topK
            candidates.sort((a, b) -> Float.compare(b.score(), a.score()));

            List<VectorIndex.SearchResult<D>> results = new ArrayList<>();
            for (ScoredCandidate c : candidates) {
                if (results.size() >= topK) break;
                if (isSoftDeleted(c.docIdBytes())) continue;
                D id = docIdSerializer.deserialize(MemorySegment.ofArray(c.docIdBytes()));
                results.add(new VectorIndex.SearchResult<>(id, c.score()));
            }
            return results;
        }

        @Override
        public void close() throws IOException {
            lsmTree.close();
        }

        // --- HNSW helpers ---

        private int randomLevel() {
            // Floor(-ln(U[0,1]) / ln(M)) — standard HNSW level assignment
            double mL = 1.0 / Math.log(M);
            int level = (int) (-Math.log(Math.max(1e-10, random.nextDouble())) * mL);
            return Math.min(level, 16); // cap to prevent degenerate cases
        }

        private record EntryPoint(byte[] docIdBytes, int maxLayer) {}

        private EntryPoint readEntryPoint() throws IOException {
            Optional<MemorySegment> opt = lsmTree.get(MemorySegment.ofArray(ENTRY_POINT_KEY));
            if (opt.isEmpty()) return null;
            byte[] value = opt.get().toArray(ValueLayout.JAVA_BYTE);
            int maxLayer = readInt(value, 0);
            byte[] docIdBytes = Arrays.copyOfRange(value, 4, value.length);
            return new EntryPoint(docIdBytes, maxLayer);
        }

        private static byte[] encodeEntryPoint(byte[] docIdBytes, int maxLayer) {
            byte[] value = new byte[4 + docIdBytes.length];
            writeInt(value, 0, maxLayer);
            System.arraycopy(docIdBytes, 0, value, 4, docIdBytes.length);
            return value;
        }

        /**
         * Greedily finds the single nearest node to {@code query} at {@code layer}, starting from
         * {@code entryDocId}. Returns the docId of the nearest found node.
         */
        private byte[] greedySearch1(byte[] entryDocId, float[] query, int layer) throws IOException {
            byte[] currentId = entryDocId;
            float currentScore = scoreNode(currentId, query);

            boolean improved = true;
            while (improved) {
                improved = false;
                for (byte[] nbId : getNodeNeighbors(currentId, layer)) {
                    float s = scoreNode(nbId, query);
                    if (s > currentScore) {
                        currentScore = s;
                        currentId = nbId;
                        improved = true;
                    }
                }
            }
            return currentId;
        }

        /**
         * Collects up to {@code ef} best candidates at {@code layer} starting from
         * {@code entryDocId}, using a best-first search with bounded candidate set.
         */
        private List<ScoredCandidate> searchLayer(byte[] entryDocId, float[] query,
                                                   int ef, int layer) throws IOException {
            Set<ByteBuffer> visited = new HashSet<>();
            visited.add(ByteBuffer.wrap(entryDocId));

            float entryScore = scoreNode(entryDocId, query);

            // Expansion frontier: max-heap by score (expand best first)
            PriorityQueue<ScoredCandidate> frontier = new PriorityQueue<>(
                    (a, b) -> Float.compare(b.score(), a.score()));
            // Result set: min-heap by score (evict worst when over ef)
            PriorityQueue<ScoredCandidate> results = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredCandidate::score));

            ScoredCandidate entry = new ScoredCandidate(entryDocId, entryScore);
            frontier.add(entry);
            results.add(entry);

            while (!frontier.isEmpty()) {
                ScoredCandidate best = frontier.poll();
                float worstResult = results.isEmpty() ? Float.NEGATIVE_INFINITY
                                                       : results.peek().score();

                // No candidate can improve results
                if (best.score() < worstResult && results.size() >= ef) break;

                for (byte[] nbId : getNodeNeighbors(best.docIdBytes(), layer)) {
                    ByteBuffer nbBuf = ByteBuffer.wrap(nbId);
                    if (!visited.add(nbBuf)) continue; // already visited

                    float nbScore = scoreNode(nbId, query);
                    float currentWorst = results.isEmpty() ? Float.NEGATIVE_INFINITY
                                                           : results.peek().score();

                    if (results.size() < ef || nbScore > currentWorst) {
                        ScoredCandidate nb = new ScoredCandidate(nbId, nbScore);
                        frontier.add(nb);
                        results.add(nb);
                        if (results.size() > ef) results.poll();
                    }
                }
            }

            return new ArrayList<>(results);
        }

        private List<byte[]> selectNeighbors(List<ScoredCandidate> candidates, int maxM) {
            return candidates.stream()
                    .sorted((a, b) -> Float.compare(b.score(), a.score()))
                    .limit(maxM)
                    .map(ScoredCandidate::docIdBytes)
                    .collect(Collectors.toList());
        }

        /**
         * Trims {@code neighbors} to at most {@code maxM}, keeping the {@code maxM} most similar
         * to {@code centerVec} according to the configured {@link SimilarityFunction}.
         */
        private List<byte[]> trimToM(float[] centerVec, List<byte[]> neighbors,
                                      int maxM) throws IOException {
            List<ScoredCandidate> scored = new ArrayList<>();
            for (byte[] nbId : neighbors) {
                Optional<MemorySegment> nbOpt = lsmTree.get(MemorySegment.ofArray(nbId));
                if (nbOpt.isEmpty()) continue;
                DecodedNode nbNode = decodeNode(nbOpt.get().toArray(ValueLayout.JAVA_BYTE));
                float s = score(centerVec, nbNode.vector(), similarityFunction);
                scored.add(new ScoredCandidate(nbId, s));
            }
            return selectNeighbors(scored, maxM);
        }

        private float scoreNode(byte[] docIdBytes, float[] query) throws IOException {
            Optional<MemorySegment> opt = lsmTree.get(MemorySegment.ofArray(docIdBytes));
            if (opt.isEmpty()) return Float.NEGATIVE_INFINITY;
            DecodedNode node = decodeNode(opt.get().toArray(ValueLayout.JAVA_BYTE));
            return score(query, node.vector(), similarityFunction);
        }

        private List<byte[]> getNodeNeighbors(byte[] docIdBytes, int layer) throws IOException {
            Optional<MemorySegment> opt = lsmTree.get(MemorySegment.ofArray(docIdBytes));
            if (opt.isEmpty()) return List.of();
            DecodedNode node = decodeNode(opt.get().toArray(ValueLayout.JAVA_BYTE));
            if (layer >= node.layerNeighbors().size()) return List.of();
            return node.layerNeighbors().get(layer);
        }

        private boolean isSoftDeleted(byte[] docIdBytes) throws IOException {
            return lsmTree.get(MemorySegment.ofArray(softDeleteKey(docIdBytes))).isPresent();
        }

        private static byte[] softDeleteKey(byte[] docIdBytes) {
            byte[] key = new byte[1 + docIdBytes.length];
            key[0] = SOFT_DELETE_PREFIX;
            System.arraycopy(docIdBytes, 0, key, 1, docIdBytes.length);
            return key;
        }

        // --- Node encoding/decoding ---

        private record DecodedNode(List<List<byte[]>> layerNeighbors, float[] vector) {}

        private static byte[] encodeNode(byte[] docIdBytes, List<List<byte[]>> layerNeighbors,
                                          float[] vector) {
            int docIdLen = docIdBytes.length;
            int layerCount = layerNeighbors.size();
            int neighborBytes = layerNeighbors.stream()
                    .mapToInt(l -> 4 + l.size() * docIdLen).sum();
            int totalSize = 4 + 4 + neighborBytes + vector.length * 4;

            byte[] buf = new byte[totalSize];
            int off = 0;
            off = writeInt(buf, off, docIdLen);
            off = writeInt(buf, off, layerCount);
            for (List<byte[]> neighbors : layerNeighbors) {
                off = writeInt(buf, off, neighbors.size());
                for (byte[] nb : neighbors) {
                    System.arraycopy(nb, 0, buf, off, docIdLen);
                    off += docIdLen;
                }
            }
            byte[] vectorBytes = encodeFloats(vector);
            System.arraycopy(vectorBytes, 0, buf, off, vectorBytes.length);
            return buf;
        }

        private static DecodedNode decodeNode(byte[] bytes) {
            int off = 0;
            int docIdLen = readInt(bytes, off); off += 4;
            int layerCount = readInt(bytes, off); off += 4;
            List<List<byte[]>> layers = new ArrayList<>(layerCount);
            for (int l = 0; l < layerCount; l++) {
                int neighborCount = readInt(bytes, off); off += 4;
                List<byte[]> neighbors = new ArrayList<>(neighborCount);
                for (int n = 0; n < neighborCount; n++) {
                    neighbors.add(Arrays.copyOfRange(bytes, off, off + docIdLen));
                    off += docIdLen;
                }
                layers.add(neighbors);
            }
            int vecBytes = bytes.length - off;
            float[] vector = decodeFloats(Arrays.copyOfRange(bytes, off, bytes.length),
                    vecBytes / 4);
            return new DecodedNode(layers, vector);
        }

        // -----------------------------------------------------------------------
        // Builder
        // -----------------------------------------------------------------------

        /** Builder for {@link LsmVectorIndex.Hnsw}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            private int M = 16;
            private int efConstruction = 200;
            private int efSearch = 50;

            public Builder<D> M(int M) {
                if (M <= 0) throw new IllegalArgumentException("M must be > 0");
                this.M = M;
                return this;
            }

            public Builder<D> efConstruction(int ef) {
                if (ef <= 0) throw new IllegalArgumentException("efConstruction must be > 0");
                this.efConstruction = ef;
                return this;
            }

            public Builder<D> efSearch(int ef) {
                if (ef <= 0) throw new IllegalArgumentException("efSearch must be > 0");
                this.efSearch = ef;
                return this;
            }

            public VectorIndex.Hnsw<D> build() {
                validateBase();
                return new LsmVectorIndex.Hnsw<>(lsmTree, docIdSerializer, dimensions,
                        similarityFunction, M, efConstruction, efSearch);
            }
        }
    }
}
