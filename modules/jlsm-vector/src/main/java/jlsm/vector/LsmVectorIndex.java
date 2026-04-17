package jlsm.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.tree.LsmTree;
import jlsm.core.tree.PrefetchHint;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Non-instantiable namespace class containing concrete {@link VectorIndex} implementations backed
 * by an {@link LsmTree}.
 *
 * <ul>
 * <li>{@link IvfFlat} — Inverted File with Flat re-scoring. Uses three key namespaces within one
 * {@link LsmTree}: centroid coordinates {@code [0x00]}, posting-list entries {@code [0x01]}, and
 * reverse-lookup entries {@code [0x02]}.</li>
 * <li>{@link Hnsw} — Hierarchical Navigable Small World graph. Uses doc-id bytes as node keys,
 * {@code [0xFE]} for the entry point, and {@code [0xFF][docId]} for soft-deletes.</li>
 * </ul>
 *
 * <p>
 * All similarity computations are accelerated with {@code FloatVector.SPECIES_PREFERRED} from
 * {@code jdk.incubator.vector}, with a scalar tail loop for dimensions not covered by the SIMD
 * width.
 *
 * <p>
 * Obtain instances via {@link #ivfFlatBuilder()} and {@link #hnswBuilder()}.
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
            bytes[i * 4] = (byte) (bits >>> 24);
            bytes[i * 4 + 1] = (byte) (bits >>> 16);
            bytes[i * 4 + 2] = (byte) (bits >>> 8);
            bytes[i * 4 + 3] = (byte) bits;
        }
        return bytes;
    }

    static float[] decodeFloats(byte[] bytes, int dimensions) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        if (bytes.length != dimensions * 4) {
            throw new IllegalArgumentException("byte count mismatch: expected " + (dimensions * 4)
                    + " but got " + bytes.length);
        }
        float[] floats = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            int bits = ((bytes[i * 4] & 0xFF) << 24) | ((bytes[i * 4 + 1] & 0xFF) << 16)
                    | ((bytes[i * 4 + 2] & 0xFF) << 8) | (bytes[i * 4 + 3] & 0xFF);
            floats[i] = Float.intBitsToFloat(bits);
        }
        return floats;
    }

    // -----------------------------------------------------------------------
    // Float16 encoding helpers
    // -----------------------------------------------------------------------

    /**
     * Validates that all components of a float vector are finite (not NaN or Infinity). Non-finite
     * components produce NaN similarity scores, making vectors invisible in search while still
     * consuming storage and participating in index structures.
     *
     * @param vector the float vector to validate; must not be null
     * @throws IllegalArgumentException if any component is NaN or Infinite
     */
    static void validateFiniteComponents(float[] vector) {
        assert vector != null : "vector must not be null";
        for (int i = 0; i < vector.length; i++) {
            float f = vector[i];
            if (!Float.isFinite(f)) {
                throw new IllegalArgumentException("vector component " + i + " is " + f
                        + " — non-finite values produce meaningless similarity"
                        + " scores and corrupt index structures");
            }
        }
    }

    /**
     * Validates that all components of a float vector are representable in float16 without
     * overflow. Finite float32 values with magnitude greater than 65504 overflow to Infinity in
     * float16, which produces NaN scores with cosine similarity and makes vectors invisible in
     * search.
     *
     * @param vector the float vector to validate; must not be null
     * @throws IllegalArgumentException if any component overflows float16
     */
    // @spec F01.R11 — reject finite float32 values with magnitude > 65504 (float16 max)
    // @spec F01.R12 — subnormal flush-to-zero is an inherent property of float16 and accepted
    // @spec F01.R13 — NaN/Infinity input rejected at the index layer (non-finite policy)
    static void validateFloat16Components(float[] vector) {
        assert vector != null : "vector must not be null";
        for (int i = 0; i < vector.length; i++) {
            float f = vector[i];
            if (Float.isNaN(f)) {
                throw new IllegalArgumentException("vector component " + i
                        + " is NaN — NaN values produce meaningless similarity scores"
                        + " and corrupt centroid assignment");
            }
            if (Float.isInfinite(f)) {
                throw new IllegalArgumentException("vector component " + i + " is " + f
                        + " — infinite values produce degenerate similarity"
                        + " scores and corrupt centroid assignment");
            }
            short fp16 = Float.floatToFloat16(f);
            if (!Float.isFinite(Float.float16ToFloat(fp16))) {
                throw new IllegalArgumentException("vector component " + i + " (" + f
                        + ") overflows float16 range (max finite: ±65504)");
            }
        }
    }

    /**
     * Encodes a float array to float16 (IEEE 754 binary16) big-endian bytes. Contract: each float
     * is converted via {@link Float#floatToFloat16(float)} and stored as 2 bytes big-endian. Output
     * length is {@code floats.length * 2}. Side effects: none.
     *
     * @param floats the float array to encode; must not be null
     * @return big-endian float16 byte array of length {@code floats.length * 2}
     */
    // @spec F01.R5 — IEEE 754 binary16 via JDK standard conversion, big-endian, length dim*2
    // @spec F01.R8 — big-endian byte order matches the vector-serialization ADR
    // @spec F01.R9 — no precision marker/header byte; caller-described encoding
    // @spec F01.R18 — float16 posting-list uses exactly dim*2 bytes per vector
    // @spec F01.R26 — uses JDK standard float16 conversion exclusively
    // @spec F01.R28 — stateless; safe to call concurrently
    static byte[] encodeFloat16s(float[] floats) {
        assert floats != null : "floats must not be null";
        byte[] bytes = new byte[floats.length * 2];
        for (int i = 0; i < floats.length; i++) {
            short bits = Float.floatToFloat16(floats[i]);
            bytes[i * 2] = (byte) (bits >>> 8);
            bytes[i * 2 + 1] = (byte) bits;
        }
        return bytes;
    }

    /**
     * Decodes big-endian float16 bytes back to a float array. Contract: each 2-byte pair is read as
     * a big-endian short, converted to float via {@link Float#float16ToFloat(short)}. Input length
     * must equal {@code dimensions * 2}. Side effects: none.
     *
     * @param bytes the float16 byte array; must not be null
     * @param dimensions the expected number of float components
     * @return float array of length {@code dimensions}
     */
    // @spec F01.R6 — IEEE 754 binary16 decoded back to float32 via JDK standard conversion
    // @spec F01.R26 — uses JDK standard float16 conversion exclusively
    // @spec F01.R28 — stateless; safe to call concurrently
    // @spec F01.R35 — validates input length with a runtime check before decoding
    static float[] decodeFloat16s(byte[] bytes, int dimensions) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        if (bytes.length != dimensions * 2) {
            throw new IllegalArgumentException("byte count mismatch: expected " + (dimensions * 2)
                    + " but got " + bytes.length);
        }
        float[] floats = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            short bits = (short) (((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF));
            floats[i] = Float.float16ToFloat(bits);
        }
        return floats;
    }

    /**
     * Encodes a float vector using the specified precision. Contract: dispatches to
     * {@link #encodeFloats(float[])} for FLOAT32 or {@link #encodeFloat16s(float[])} for FLOAT16.
     * Side effects: none.
     *
     * @param floats the float array to encode; must not be null
     * @param precision the target precision; must not be null
     * @return encoded byte array
     */
    // @spec F01.R7 — dispatch by precision: FLOAT32 → dim*4, FLOAT16 → dim*2
    // @spec F01.R28 — stateless; safe to call concurrently
    static byte[] encodeVector(float[] floats, VectorPrecision precision) {
        assert floats != null : "floats must not be null";
        assert precision != null : "precision must not be null";
        return switch (precision) {
            case FLOAT32 -> encodeFloats(floats);
            case FLOAT16 -> encodeFloat16s(floats);
        };
    }

    /**
     * Decodes a vector from bytes using the specified precision, returning float[]. Contract:
     * dispatches to {@link #decodeFloats(byte[], int)} for FLOAT32 or
     * {@link #decodeFloat16s(byte[], int)} for FLOAT16. Side effects: none.
     *
     * @param bytes the encoded byte array; must not be null
     * @param dimensions the expected number of float components
     * @param precision the source precision; must not be null
     * @return float array of length {@code dimensions}
     */
    // @spec F01.R7 — decoding accepts a precision parameter, produces float32 regardless
    // @spec F01.R28,R29 — stateless; float32 arithmetic for downstream similarity
    static float[] decodeVector(byte[] bytes, int dimensions, VectorPrecision precision) {
        assert bytes != null : "bytes must not be null";
        assert precision != null : "precision must not be null";
        return switch (precision) {
            case FLOAT32 -> decodeFloats(bytes, dimensions);
            case FLOAT16 -> decodeFloat16s(bytes, dimensions);
        };
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
            acc = FloatVector.fromArray(SPECIES, a, i).fma(FloatVector.fromArray(SPECIES, b, i),
                    acc);
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (; i < a.length; i++)
            sum += a[i] * b[i];
        return sum;
    }

    /** Returns the cosine similarity in [−1, 1]; 0 if either vector has zero norm. */
    static float cosine(float[] a, float[] b) {
        float dot = dotProduct(a, b);
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
        for (; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return -(float) Math.sqrt(sum);
    }

    /** Dispatches to the appropriate similarity function; higher = more similar for all. */
    static float score(float[] a, float[] b, SimilarityFunction fn) {
        return switch (fn) {
            case COSINE -> cosine(a, b);
            case DOT_PRODUCT -> dotProduct(a, b);
            case EUCLIDEAN -> euclidean(a, b);
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
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }

    // -----------------------------------------------------------------------
    // Shared result type
    // -----------------------------------------------------------------------

    private record ScoredCandidate(byte[] docIdBytes, float score) {
        ScoredCandidate {
            if (!Float.isFinite(score)) {
                throw new IllegalArgumentException("score must be finite, got: " + score);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Abstract builder base
    // -----------------------------------------------------------------------

    // @spec F01.R32 — builder implements AutoCloseable; abandoned builder releases the tree
    // @spec F01.R3 — precision is an explicit builder choice; default FLOAT32; null rejected
    // @spec F01.R34 — dimensions validated against precision-aware overflow bound
    private abstract static class AbstractBuilder<D, B extends AbstractBuilder<D, B>>
            implements AutoCloseable {

        LsmTree lsmTree;
        MemorySerializer<D> docIdSerializer;
        int dimensions; // 0 = not set
        SimilarityFunction similarityFunction;
        VectorPrecision precision = VectorPrecision.FLOAT32;

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

        /**
         * Computes the maximum safe dimensions for the given precision, preventing integer overflow
         * when computing {@code dimensions * bytesPerComponent}.
         */
        private static int maxDimensionsFor(VectorPrecision p) {
            return Integer.MAX_VALUE / p.bytesPerComponent();
        }

        @SuppressWarnings("unchecked")
        public B dimensions(int dimensions) {
            if (dimensions <= 0)
                throw new IllegalArgumentException("dimensions must be > 0, got: " + dimensions);
            int maxDims = maxDimensionsFor(this.precision);
            if (dimensions > maxDims)
                throw new IllegalArgumentException("dimensions must be <= " + maxDims
                        + " to prevent integer overflow in byte allocation for " + this.precision
                        + " (" + this.precision.bytesPerComponent() + " bytes/component), got: "
                        + dimensions);
            this.dimensions = dimensions;
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B similarityFunction(SimilarityFunction fn) {
            this.similarityFunction = Objects.requireNonNull(fn,
                    "similarityFunction must not be null");
            return (B) this;
        }

        /**
         * Sets the vector storage precision. Default is {@link VectorPrecision#FLOAT32}.
         *
         * @param precision the precision; must not be null
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public B precision(VectorPrecision precision) {
            this.precision = Objects.requireNonNull(precision, "precision must not be null");
            return (B) this;
        }

        void validateBase() {
            Objects.requireNonNull(lsmTree, "lsmTree must not be null");
            Objects.requireNonNull(docIdSerializer, "docIdSerializer must not be null");
            if (dimensions <= 0) {
                throw new IllegalArgumentException("dimensions must be > 0");
            }
            Objects.requireNonNull(similarityFunction, "similarityFunction must not be null");
            Objects.requireNonNull(precision, "precision must not be null");
            // Precision-aware overflow check: dimensions may have been set before
            // precision was changed, so re-validate with the final precision.
            int maxDims = maxDimensionsFor(precision);
            if (dimensions > maxDims) {
                throw new IllegalArgumentException("dimensions " + dimensions + " exceeds maximum "
                        + maxDims + " for " + precision + " (" + precision.bytesPerComponent()
                        + " bytes/component) — would overflow in byte allocation");
            }
        }

        /**
         * Consumes the {@code lsmTree} reference, returning it and clearing the field so that
         * {@link #close()} will not close a tree whose ownership was transferred to the built
         * index.
         */
        LsmTree consumeTree() {
            LsmTree tree = this.lsmTree;
            this.lsmTree = null;
            return tree;
        }

        /**
         * Closes the {@link LsmTree} held by this builder, if ownership has not been transferred
         * via a successful {@link #consumeTree()} call. Idempotent — safe to call multiple times.
         * <p>
         * This allows callers to use try-with-resources on the builder to prevent resource leaks
         * when {@code build()} is never called (e.g., due to an exception in caller setup code).
         */
        @Override
        public void close() throws IOException {
            LsmTree tree = this.lsmTree;
            this.lsmTree = null;
            if (tree != null) {
                tree.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // IvfFlat implementation
    // -----------------------------------------------------------------------

    /**
     * Inverted File Flat (IVF-Flat) vector index.
     *
     * <h2>Key namespaces within the backing {@link LsmTree}</h2>
     *
     * <pre>
     *   [0x00][4-byte BE centroid_id]               → dim×4 bytes (centroid float32 coords)
     *   [0x01][4-byte BE centroid_id][doc_id_bytes] → dim×precision bytes (vector coords)
     *   [0x02][doc_id_bytes]                        → 4-byte BE centroid_id (reverse lookup)
     * </pre>
     *
     * <h2>Clustering strategy</h2> The first {@code numClusters} indexed vectors become centroids.
     * Subsequent vectors are assigned to the nearest centroid (using the configured
     * {@link SimilarityFunction}).
     */
    public static final class IvfFlat<D> implements VectorIndex.IvfFlat<D> {

        // Key namespace prefix bytes
        private static final byte CENTROID_PREFIX = 0x00;
        private static final byte POSTING_PREFIX = 0x01;
        private static final byte REVERSE_PREFIX = 0x02;

        // Scan bounds: [CENTROID_PREFIX, POSTING_PREFIX) covers all centroid keys
        private static final MemorySegment CENTROID_SCAN_START = MemorySegment
                .ofArray(new byte[]{ CENTROID_PREFIX });
        private static final MemorySegment CENTROID_SCAN_END = MemorySegment
                .ofArray(new byte[]{ POSTING_PREFIX });

        /** Number of striped locks for per-docId synchronization. */
        private static final int LOCK_STRIPE_COUNT = 64;

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;
        private final int dimensions;
        private final SimilarityFunction similarityFunction;
        private final int numClusters;
        private final int nprobe;
        private final VectorPrecision precision;
        private final Object[] docIdLocks;
        /** Lock guarding centroid creation in {@link #assignCentroid}. */
        private final Object centroidCreationLock = new Object();
        /** Idempotency guard — second and subsequent close() calls are no-ops per R33. */
        private volatile boolean closed;

        private IvfFlat(LsmTree lsmTree, MemorySerializer<D> docIdSerializer, int dimensions,
                SimilarityFunction similarityFunction, int numClusters, int nprobe,
                VectorPrecision precision) {
            this.lsmTree = Objects.requireNonNull(lsmTree, "lsmTree must not be null");
            this.docIdSerializer = Objects.requireNonNull(docIdSerializer,
                    "docIdSerializer must not be null");
            if (dimensions <= 0) {
                throw new IllegalArgumentException(
                        "dimensions must be positive, got: " + dimensions);
            }
            this.dimensions = dimensions;
            this.similarityFunction = Objects.requireNonNull(similarityFunction,
                    "similarityFunction must not be null");
            if (numClusters <= 0) {
                throw new IllegalArgumentException(
                        "numClusters must be positive, got: " + numClusters);
            }
            this.numClusters = numClusters;
            if (nprobe <= 0) {
                throw new IllegalArgumentException("nprobe must be positive, got: " + nprobe);
            }
            this.nprobe = nprobe;
            this.precision = Objects.requireNonNull(precision, "precision must not be null");
            this.docIdLocks = new Object[LOCK_STRIPE_COUNT];
            for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
                docIdLocks[i] = new Object();
            }
        }

        /** Returns the striped lock for the given docId byte representation. */
        private Object lockFor(byte[] docIdBytes) {
            int hash = Arrays.hashCode(docIdBytes);
            return docIdLocks[(hash & 0x7FFFFFFF) % LOCK_STRIPE_COUNT];
        }

        @Override
        public VectorPrecision precision() {
            return precision;
        }

        @Override
        public void index(D docId, float[] vector) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(vector, "vector must not be null");
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "vector.length=" + vector.length + " != dimensions=" + dimensions);
            }
            validateFiniteComponents(vector);
            if (precision == VectorPrecision.FLOAT16) {
                validateFloat16Components(vector);
            }

            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            byte[] vectorBytes = encodeVector(vector, precision);
            // @spec F01.R15,R10b — use quantized vector (encode-then-decode through
            // configured precision) for centroid assignment, so the posting is filed under
            // the centroid it is actually closest to after storage quantization.
            float[] quantizedVector = precision == VectorPrecision.FLOAT32 ? vector
                    : decodeVector(vectorBytes, dimensions, precision);
            int centroidId = assignCentroid(vector, quantizedVector);

            // Synchronize the read-modify-write of reverse lookup and posting entries
            // per docId to prevent concurrent re-index from leaving orphaned postings.
            synchronized (lockFor(docIdBytes)) {
                // Clean up old posting if this docId was previously indexed under a different
                // centroid
                byte[] revKey = reverseKey(docIdBytes);
                Optional<MemorySegment> oldRev = lsmTree.get(MemorySegment.ofArray(revKey));
                if (oldRev.isPresent()) {
                    int oldCentroidId = decodeCentroidId(
                            oldRev.get().toArray(ValueLayout.JAVA_BYTE), 0);
                    if (oldCentroidId != centroidId) {
                        lsmTree.delete(
                                MemorySegment.ofArray(postingKey(oldCentroidId, docIdBytes)));
                    }
                }

                // Store posting: [0x01][centroid_id][docId] → vector
                lsmTree.put(MemorySegment.ofArray(postingKey(centroidId, docIdBytes)),
                        MemorySegment.ofArray(vectorBytes));

                // Store reverse lookup: [0x02][docId] → centroid_id (4 bytes BE)
                lsmTree.put(MemorySegment.ofArray(reverseKey(docIdBytes)),
                        MemorySegment.ofArray(encodeCentroidId(centroidId)));
            }
        }

        @Override
        public void remove(D docId) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");

            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            byte[] revKey = reverseKey(docIdBytes);

            // Synchronize on the same per-docId striped lock used by index() to
            // prevent a concurrent index() from inserting a new posting between
            // this method's reverse-lookup read and its delete calls.
            synchronized (lockFor(docIdBytes)) {
                Optional<MemorySegment> revOpt = lsmTree.get(MemorySegment.ofArray(revKey));
                if (revOpt.isEmpty())
                    return; // not indexed

                int centroidId = decodeCentroidId(revOpt.get().toArray(ValueLayout.JAVA_BYTE), 0);

                lsmTree.delete(MemorySegment.ofArray(postingKey(centroidId, docIdBytes)));
                lsmTree.delete(MemorySegment.ofArray(revKey));
            }
        }

        // @spec F01.R16 — decode posting-list vectors at configured precision, score in float32
        // @spec F01.R17 — uses the original float32 query for all distance computations
        // @spec F01.R25a — filter non-finite (NaN + Infinity) scores before constructing results
        @Override
        public List<VectorIndex.SearchResult<D>> search(float[] query, int topK)
                throws IOException {
            Objects.requireNonNull(query, "query must not be null");
            if (query.length != dimensions) {
                throw new IllegalArgumentException(
                        "query.length=" + query.length + " != dimensions=" + dimensions);
            }
            if (topK <= 0)
                throw new IllegalArgumentException("topK must be > 0");

            // Load all centroids
            // @spec F01.R14,R10b — centroids are always stored at FLOAT32, regardless of
            // the configured index precision; decode with the FLOAT32 codec.
            List<float[]> centroidVecs = new ArrayList<>();
            List<Integer> centroidIds = new ArrayList<>();
            Iterator<Entry> centIt = lsmTree.scan(CENTROID_SCAN_START, CENTROID_SCAN_END);
            while (centIt.hasNext()) {
                Entry entry = centIt.next();
                if (!(entry instanceof Entry.Put put))
                    continue;
                byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                if (key.length != 5 || key[0] != CENTROID_PREFIX)
                    continue;
                int cid = decodeCentroidId(key, 1);
                float[] cVec = decodeFloats(put.value().toArray(ValueLayout.JAVA_BYTE), dimensions);
                centroidVecs.add(cVec);
                centroidIds.add(cid);
            }

            if (centroidVecs.isEmpty())
                return List.of();

            // Score centroids, pick top nprobe
            int actualNprobe = Math.min(nprobe, centroidVecs.size());
            int[] nearestCentroidIds = topNCentroids(query, centroidVecs, centroidIds,
                    actualNprobe);

            // Accumulate candidates from posting lists (min-heap to evict worst when over topK)
            PriorityQueue<ScoredCandidate> heap = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredCandidate::score)); // min at head for eviction

            for (int cid : nearestCentroidIds) {
                byte[] postStart = postingKey(cid, new byte[0]);
                byte[] postEnd = postingEndKey(cid);

                Iterator<Entry> postIt = lsmTree.scan(MemorySegment.ofArray(postStart),
                        MemorySegment.ofArray(postEnd));

                while (postIt.hasNext()) {
                    Entry entry = postIt.next();
                    if (!(entry instanceof Entry.Put put))
                        continue; // skip tombstones

                    byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                    if (key.length <= 5 || key[0] != POSTING_PREFIX)
                        continue;

                    byte[] docIdBytes = Arrays.copyOfRange(key, 5, key.length);
                    float[] vec = decodeVector(put.value().toArray(ValueLayout.JAVA_BYTE),
                            dimensions, precision);
                    float s = score(query, vec, similarityFunction);

                    // Skip non-finite scores — NaN and Infinity corrupt ordering
                    // and violate downstream contracts
                    if (!Float.isFinite(s))
                        continue;

                    heap.add(new ScoredCandidate(docIdBytes, s));
                    if (heap.size() > topK)
                        heap.poll(); // evict worst
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

        // @spec F01.R33 — idempotent close: second and subsequent calls must not propagate
        // to the underlying storage tree.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            lsmTree.close();
        }

        // --- Key construction helpers ---

        private static byte[] centroidKey(int centroidId) {
            return new byte[]{ CENTROID_PREFIX, (byte) (centroidId >>> 24),
                    (byte) (centroidId >>> 16), (byte) (centroidId >>> 8), (byte) centroidId };
        }

        private static byte[] postingKey(int centroidId, byte[] docIdBytes) {
            byte[] key = new byte[5 + docIdBytes.length];
            key[0] = POSTING_PREFIX;
            key[1] = (byte) (centroidId >>> 24);
            key[2] = (byte) (centroidId >>> 16);
            key[3] = (byte) (centroidId >>> 8);
            key[4] = (byte) centroidId;
            System.arraycopy(docIdBytes, 0, key, 5, docIdBytes.length);
            return key;
        }

        private static byte[] postingEndKey(int centroidId) {
            long next = (centroidId & 0xFFFFFFFFL) + 1L;
            if (next > 0xFFFFFFFFL) {
                return new byte[]{ REVERSE_PREFIX }; // overflow → next prefix byte
            }
            return new byte[]{ POSTING_PREFIX, (byte) (next >>> 24), (byte) (next >>> 16),
                    (byte) (next >>> 8), (byte) next };
        }

        private static byte[] reverseKey(byte[] docIdBytes) {
            byte[] key = new byte[1 + docIdBytes.length];
            key[0] = REVERSE_PREFIX;
            System.arraycopy(docIdBytes, 0, key, 1, docIdBytes.length);
            return key;
        }

        private static byte[] encodeCentroidId(int centroidId) {
            return new byte[]{ (byte) (centroidId >>> 24), (byte) (centroidId >>> 16),
                    (byte) (centroidId >>> 8), (byte) centroidId };
        }

        private static int decodeCentroidId(byte[] bytes, int offset) throws IOException {
            if (bytes.length < offset + 4) {
                throw new IOException("Corrupted centroid ID: expected at least " + (offset + 4)
                        + " bytes but got " + bytes.length);
            }
            return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                    | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
        }

        // --- Centroid assignment ---

        /**
         * Returns the centroid ID to assign to a new vector. Creates a new centroid if fewer than
         * {@code numClusters} centroids exist; otherwise assigns to the most similar existing one.
         *
         * @param originalVector the full-fidelity float32 vector; used only as the coordinates of a
         *            newly created centroid (centroids are stored at FLOAT32 regardless of index
         *            precision per R14/R10b)
         * @param quantizedVector the {@code originalVector} after {@code encode-then-decode}
         *            through the configured precision; used for similarity comparisons against
         *            existing centroids so the posting is filed under the centroid it is actually
         *            closest to after storage quantization (R15/R10b)
         */
        // @spec F01.R14,R15,R10b
        private int assignCentroid(float[] originalVector, float[] quantizedVector)
                throws IOException {
            // Synchronize on centroidCreationLock so that the scan + conditional creation
            // is atomic. Without this, two threads that both see centroids.size() < numClusters
            // compute the same newCid (max+1) and the second silently overwrites the first.
            synchronized (centroidCreationLock) {
                List<float[]> centroids = new ArrayList<>();
                List<Integer> centroidIdList = new ArrayList<>();

                Iterator<Entry> it = lsmTree.scan(CENTROID_SCAN_START, CENTROID_SCAN_END);
                while (it.hasNext()) {
                    Entry entry = it.next();
                    if (!(entry instanceof Entry.Put put))
                        continue;
                    byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                    if (key.length != 5 || key[0] != CENTROID_PREFIX)
                        continue;
                    int cid = decodeCentroidId(key, 1);
                    float[] cVec = decodeFloats(put.value().toArray(ValueLayout.JAVA_BYTE),
                            dimensions);
                    centroids.add(cVec);
                    centroidIdList.add(cid);
                }

                if (centroids.size() < numClusters) {
                    int newCid = centroidIdList.isEmpty() ? 0
                            : centroidIdList.stream().mapToInt(Integer::intValue).max().getAsInt()
                                    + 1;
                    lsmTree.put(MemorySegment.ofArray(centroidKey(newCid)),
                            MemorySegment.ofArray(encodeFloats(originalVector)));
                    return newCid;
                }

                return nearestCentroid(quantizedVector, centroids, centroidIdList);
            }
        }

        private int nearestCentroid(float[] vector, List<float[]> centroids, List<Integer> ids) {
            int best = ids.get(0);
            float bestScore = score(vector, centroids.get(0), similarityFunction);
            for (int i = 1; i < centroids.size(); i++) {
                float s = score(vector, centroids.get(i), similarityFunction);
                // Use !(bestScore >= s) instead of s > bestScore so that a finite score
                // always beats a NaN bestScore (IEEE 754: comparisons with NaN return false).
                if (Float.isNaN(bestScore) || s > bestScore) {
                    bestScore = s;
                    best = ids.get(i);
                }
            }
            return best;
        }

        private int[] topNCentroids(float[] query, List<float[]> centroids, List<Integer> ids,
                int n) {
            float[] scores = new float[centroids.size()];
            for (int i = 0; i < centroids.size(); i++) {
                scores[i] = score(query, centroids.get(i), similarityFunction);
            }
            Integer[] indices = new Integer[centroids.size()];
            for (int i = 0; i < indices.length; i++)
                indices[i] = i;
            // Sort descending by score, but push NaN to the end (worst). Float.compare
            // treats NaN as greater than all finite values; reversing args for descending
            // order would put NaN first, wasting the nprobe budget on invalid centroids.
            Arrays.sort(indices, (a, b) -> {
                boolean aNaN = Float.isNaN(scores[a]);
                boolean bNaN = Float.isNaN(scores[b]);
                if (aNaN && bNaN)
                    return 0;
                if (aNaN)
                    return 1; // a is NaN → sort after b
                if (bNaN)
                    return -1; // b is NaN → sort after a
                return Float.compare(scores[b], scores[a]); // descending for finite values
            });

            int[] result = new int[n];
            for (int i = 0; i < n; i++)
                result[i] = ids.get(indices[i]);
            return result;
        }

        // -----------------------------------------------------------------------
        // Builder
        // -----------------------------------------------------------------------

        /** Builder for {@link LsmVectorIndex.IvfFlat}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            private int numClusters = 256;
            private int nprobe = 8;
            private boolean nprobeExplicitlySet = false;

            public Builder<D> numClusters(int n) {
                if (n <= 0)
                    throw new IllegalArgumentException("numClusters must be > 0");
                this.numClusters = n;
                return this;
            }

            public Builder<D> nprobe(int n) {
                if (n <= 0)
                    throw new IllegalArgumentException("nprobe must be > 0");
                this.nprobe = n;
                this.nprobeExplicitlySet = true;
                return this;
            }

            public VectorIndex.IvfFlat<D> build() {
                validateBase();
                int effectiveNprobe = nprobe;
                if (nprobeExplicitlySet && nprobe > numClusters) {
                    throw new IllegalArgumentException("nprobe must be <= numClusters, got nprobe="
                            + nprobe + " and numClusters=" + numClusters);
                } else if (!nprobeExplicitlySet && nprobe > numClusters) {
                    effectiveNprobe = numClusters;
                }
                return new LsmVectorIndex.IvfFlat<>(consumeTree(), docIdSerializer, dimensions,
                        similarityFunction, numClusters, effectiveNprobe, precision);
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
     *
     * <pre>
     *   [doc_id_bytes]          → node value (see below) — node data
     *   [0xFE]                  → [4-byte BE max_layer][doc_id_bytes] — entry point
     *   [0xFF][doc_id_bytes]    → empty segment — soft-delete tombstone
     * </pre>
     *
     * <h2>Node value encoding</h2>
     *
     * <pre>
     *   [4-byte BE docIdBytesLen]
     *   [4-byte BE layerCount]
     *   for each layer l = 0..layerCount-1:
     *     [4-byte BE neighborCount_l]
     *     [neighborCount_l × docIdBytesLen bytes]
     *   [dim × precision.bytesPerComponent() bytes] — vector encoded per VectorPrecision
     * </pre>
     *
     * <h2>Soft-delete</h2> {@link #remove} writes a soft-delete key {@code [0xFF][docId]}. During
     * search, soft-deleted nodes are skipped in result collection.
     */
    public static final class Hnsw<D> implements VectorIndex.Hnsw<D> {

        private static final byte[] ENTRY_POINT_KEY = new byte[]{ (byte) 0xFE };
        private static final byte SOFT_DELETE_PREFIX = (byte) 0xFF;
        private volatile boolean closed;

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;
        private final int dimensions;
        private final SimilarityFunction similarityFunction;
        private final int maxConnections;
        private final int efConstruction;
        private final int efSearch;
        private final VectorPrecision precision;
        private final Object entryPointLock = new Object();
        private static final int NODE_LOCK_STRIPES = 64;
        private final Object[] nodeLocks;
        private final Random random = new Random();

        private Hnsw(LsmTree lsmTree, MemorySerializer<D> docIdSerializer, int dimensions,
                SimilarityFunction similarityFunction, int maxConnections, int efConstruction,
                int efSearch, VectorPrecision precision) {
            this.lsmTree = Objects.requireNonNull(lsmTree, "lsmTree must not be null");
            this.docIdSerializer = Objects.requireNonNull(docIdSerializer,
                    "docIdSerializer must not be null");
            if (dimensions <= 0) {
                throw new IllegalArgumentException(
                        "dimensions must be positive, got: " + dimensions);
            }
            this.dimensions = dimensions;
            this.similarityFunction = Objects.requireNonNull(similarityFunction,
                    "similarityFunction must not be null");
            if (maxConnections <= 0) {
                throw new IllegalArgumentException(
                        "maxConnections must be positive, got: " + maxConnections);
            }
            this.maxConnections = maxConnections;
            if (efConstruction <= 0) {
                throw new IllegalArgumentException(
                        "efConstruction must be positive, got: " + efConstruction);
            }
            this.efConstruction = efConstruction;
            if (efSearch <= 0) {
                throw new IllegalArgumentException("efSearch must be positive, got: " + efSearch);
            }
            this.efSearch = efSearch;
            this.precision = Objects.requireNonNull(precision, "precision must not be null");
            this.nodeLocks = new Object[NODE_LOCK_STRIPES];
            for (int i = 0; i < NODE_LOCK_STRIPES; i++) {
                nodeLocks[i] = new Object();
            }
        }

        @Override
        public VectorPrecision precision() {
            return precision;
        }

        @Override
        public void index(D docId, float[] vector) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(vector, "vector must not be null");
            if (vector.length != dimensions) {
                throw new IllegalArgumentException(
                        "vector.length=" + vector.length + " != dimensions=" + dimensions);
            }
            validateFiniteComponents(vector);
            if (precision == VectorPrecision.FLOAT16) {
                validateFloat16Components(vector);
            }

            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);

            // @spec F01.R20 — graph construction must use the quantized vector (decoded back
            // to float32) for all distance computations during neighbor selection, so the
            // graph edges are optimized for the same precision that search queries encounter.
            byte[] vectorBytes = encodeVector(vector, precision);
            float[] scoringVector = precision == VectorPrecision.FLOAT32 ? vector
                    : decodeVector(vectorBytes, dimensions, precision);

            int newLevel = randomLevel();

            EntryPoint ep;
            synchronized (entryPointLock) {
                ep = readEntryPoint();

                if (ep == null) {
                    // First node: create empty layer list and set as entry point
                    List<List<byte[]>> layers = new ArrayList<>();
                    for (int l = 0; l <= newLevel; l++)
                        layers.add(new ArrayList<>());
                    lsmTree.put(MemorySegment.ofArray(docIdBytes), MemorySegment
                            .ofArray(encodeNode(docIdBytes, layers, vector, precision)));
                    lsmTree.put(MemorySegment.ofArray(ENTRY_POINT_KEY),
                            MemorySegment.ofArray(encodeEntryPoint(docIdBytes, newLevel)));
                    // Clear soft-delete tombstone after node data is written so
                    // concurrent searches never see stale pre-removal data
                    lsmTree.delete(MemorySegment.ofArray(softDeleteKey(docIdBytes)));
                    return;
                }
            }

            int maxLayer = ep.maxLayer();
            byte[] currentEp = ep.docIdBytes();

            // Phase 1: traverse from maxLayer down to newLevel+1, greedy (ef=1)
            for (int lc = maxLayer; lc > newLevel; lc--) {
                currentEp = greedySearch1(currentEp, scoringVector, lc);
            }

            // Phase 2: from min(newLevel, maxLayer) down to 0, collect neighbors
            int startLevel = Math.min(newLevel, maxLayer);
            List<List<byte[]>> newNodeLayers = new ArrayList<>();
            for (int l = 0; l <= newLevel; l++)
                newNodeLayers.add(new ArrayList<>());

            // Write new node early so that trimToM can look it up during
            // bidirectional neighbor updates. trimToM scores each candidate
            // neighbor via lsmTree.get() — an unwritten node returns empty and
            // is silently excluded from trim selection, losing its backlink.
            lsmTree.put(MemorySegment.ofArray(docIdBytes), MemorySegment
                    .ofArray(encodeNode(docIdBytes, newNodeLayers, vector, precision)));

            for (int lc = startLevel; lc >= 0; lc--) {
                List<ScoredCandidate> candidates = searchLayer(currentEp, scoringVector,
                        efConstruction, lc);
                List<byte[]> selectedNeighbors = selectNeighbors(candidates, maxConnections);
                newNodeLayers.set(lc, selectedNeighbors);

                // Update currentEp for next (lower) layer: best candidate in this layer
                if (!candidates.isEmpty()) {
                    ScoredCandidate best = candidates.stream()
                            .max(Comparator.comparingDouble(ScoredCandidate::score)).orElseThrow();
                    currentEp = best.docIdBytes();
                }

                // Bidirectional neighbor updates — per-node lock prevents lost
                // updates when concurrent index() calls modify the same neighbor.
                for (byte[] nbId : selectedNeighbors) {
                    synchronized (nodeLockFor(nbId)) {
                        Optional<MemorySegment> nbOpt = lsmTree.get(MemorySegment.ofArray(nbId));
                        if (nbOpt.isEmpty())
                            continue;

                        DecodedNode nbNode = decodeNode(nbOpt.get().toArray(ValueLayout.JAVA_BYTE),
                                precision);
                        if (lc >= nbNode.layerNeighbors().size())
                            continue;

                        List<List<byte[]>> updatedLayers = new ArrayList<>(nbNode.layerNeighbors());
                        List<byte[]> updatedNbrs = new ArrayList<>(updatedLayers.get(lc));
                        updatedNbrs.add(docIdBytes);

                        if (updatedNbrs.size() > maxConnections) {
                            updatedNbrs = trimToM(nbNode.vector(), updatedNbrs, maxConnections);
                        }

                        updatedLayers.set(lc, updatedNbrs);
                        lsmTree.put(MemorySegment.ofArray(nbId), MemorySegment.ofArray(
                                encodeNode(nbId, updatedLayers, nbNode.vector(), precision)));
                    }
                }
            }

            // Rewrite new node with final neighbor lists populated by Phase 2
            lsmTree.put(MemorySegment.ofArray(docIdBytes), MemorySegment
                    .ofArray(encodeNode(docIdBytes, newNodeLayers, vector, precision)));

            // Clear soft-delete tombstone after new node data is written so
            // concurrent searches never see stale pre-removal vector data
            // through a visibility window (F-R5.shared_state.2.6)
            lsmTree.delete(MemorySegment.ofArray(softDeleteKey(docIdBytes)));

            // Promote entry point if this node has a higher level.
            // Re-read under lock to avoid lost-update race where a concurrent
            // thread with a higher level already promoted and we would overwrite
            // it with a lower maxLayer.
            if (newLevel > maxLayer) {
                synchronized (entryPointLock) {
                    EntryPoint currentEpState = readEntryPoint();
                    if (currentEpState == null || newLevel > currentEpState.maxLayer()) {
                        lsmTree.put(MemorySegment.ofArray(ENTRY_POINT_KEY),
                                MemorySegment.ofArray(encodeEntryPoint(docIdBytes, newLevel)));
                    }
                }
            }
        }

        @Override
        // @spec F01.R24 — soft-delete preserves graph connectivity; traversal still
        // visits soft-deleted nodes as waypoints, but results exclude them (see search()).
        public void remove(D docId) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            // Soft delete: write tombstone at [0xFF][docId]
            lsmTree.put(MemorySegment.ofArray(softDeleteKey(docIdBytes)), MemorySegment.NULL);
        }

        // @spec F01.R16 — stored node vectors decoded at configured precision, scored in float32
        // @spec F01.R17 — uses the original float32 query vector for all distance computations
        // @spec F01.R24 — soft-deleted nodes remain traversable but are filtered from results
        // @spec F01.R25a — filter non-finite (NaN + Infinity) scores before constructing results
        @Override
        public List<VectorIndex.SearchResult<D>> search(float[] query, int topK)
                throws IOException {
            Objects.requireNonNull(query, "query must not be null");
            if (query.length != dimensions) {
                throw new IllegalArgumentException(
                        "query.length=" + query.length + " != dimensions=" + dimensions);
            }
            if (topK <= 0)
                throw new IllegalArgumentException("topK must be > 0");

            EntryPoint ep = readEntryPoint();
            if (ep == null)
                return List.of();

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
                if (results.size() >= topK)
                    break;
                // Skip non-finite scores — Infinity violates SearchResult contract
                if (!Float.isFinite(c.score()))
                    continue;
                if (isSoftDeleted(c.docIdBytes()))
                    continue;
                D id = docIdSerializer.deserialize(MemorySegment.ofArray(c.docIdBytes()));
                results.add(new VectorIndex.SearchResult<>(id, c.score()));
            }
            return results;
        }

        // @spec F01.R33 — idempotent close: second and subsequent calls must be no-ops
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            lsmTree.close();
        }

        // --- HNSW helpers ---

        private int randomLevel() {
            // Floor(-ln(U[0,1]) / ln(maxConnections)) — standard HNSW level assignment
            double mL = 1.0 / Math.log(maxConnections);
            int level = (int) (-Math.log(Math.max(1e-10, random.nextDouble())) * mL);
            return Math.min(level, 16); // cap to prevent degenerate cases
        }

        private record EntryPoint(byte[] docIdBytes, int maxLayer) {
            EntryPoint {
                if (maxLayer < 0) {
                    throw new IllegalStateException(
                            "Corrupt entry point: maxLayer=" + maxLayer + " (must be >= 0)");
                }
            }
        }

        // @spec F01.R35 — validate entry-point bytes before reading structured fields so that
        // truncated data fails with a descriptive IOException rather than AIOOBE.
        private EntryPoint readEntryPoint() throws IOException {
            Optional<MemorySegment> opt = lsmTree.get(MemorySegment.ofArray(ENTRY_POINT_KEY));
            if (opt.isEmpty())
                return null;
            byte[] value = opt.get().toArray(ValueLayout.JAVA_BYTE);
            if (value.length < 4) {
                throw new IOException("Corrupted entry point: value length " + value.length
                        + " is less than the 4-byte maxLayer header");
            }
            if (value.length == 4) {
                throw new IOException(
                        "Corrupted entry point: value length 4 has no docId bytes after header");
            }
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
        private byte[] greedySearch1(byte[] entryDocId, float[] query, int layer)
                throws IOException {
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
        private List<ScoredCandidate> searchLayer(byte[] entryDocId, float[] query, int ef,
                int layer) throws IOException {
            Set<ByteBuffer> visited = new HashSet<>();
            visited.add(ByteBuffer.wrap(entryDocId));

            float entryScore = scoreNode(entryDocId, query);

            // Expansion frontier: max-heap by score (expand best first)
            PriorityQueue<ScoredCandidate> frontier = new PriorityQueue<>(
                    (a, b) -> Float.compare(b.score(), a.score()));
            // Result set: min-heap by score (evict worst when over ef)
            PriorityQueue<ScoredCandidate> results = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredCandidate::score));

            if (Float.isFinite(entryScore)) {
                ScoredCandidate entry = new ScoredCandidate(entryDocId, entryScore);
                frontier.add(entry);
                results.add(entry);
            } else {
                // Entry score is non-finite (NaN/Infinity) — cannot create a
                // ScoredCandidate, but we still need to explore from this node.
                // Seed the frontier with the entry's finite-scored neighbors.
                for (byte[] nbId : getNodeNeighbors(entryDocId, layer)) {
                    visited.add(ByteBuffer.wrap(nbId));
                    float nbScore = scoreNode(nbId, query);
                    if (!Float.isFinite(nbScore))
                        continue;
                    ScoredCandidate nb = new ScoredCandidate(nbId, nbScore);
                    frontier.add(nb);
                    results.add(nb);
                    if (results.size() > ef)
                        results.poll();
                }
            }

            while (!frontier.isEmpty()) {
                ScoredCandidate best = frontier.poll();
                float worstResult = results.isEmpty() ? Float.NEGATIVE_INFINITY
                        : results.peek().score();

                // No candidate can improve results
                if (best.score() < worstResult && results.size() >= ef)
                    break;

                for (byte[] nbId : getNodeNeighbors(best.docIdBytes(), layer)) {
                    ByteBuffer nbBuf = ByteBuffer.wrap(nbId);
                    if (!visited.add(nbBuf))
                        continue; // already visited

                    float nbScore = scoreNode(nbId, query);

                    // Skip non-finite scores — NaN and Infinity corrupt ordering
                    // and violate downstream SearchResult contracts
                    if (!Float.isFinite(nbScore))
                        continue;

                    float currentWorst = results.isEmpty() ? Float.NEGATIVE_INFINITY
                            : results.peek().score();

                    if (results.size() < ef || nbScore > currentWorst) {
                        ScoredCandidate nb = new ScoredCandidate(nbId, nbScore);
                        frontier.add(nb);
                        results.add(nb);
                        if (results.size() > ef)
                            results.poll();
                    }
                }
            }

            return new ArrayList<>(results);
        }

        private List<byte[]> selectNeighbors(List<ScoredCandidate> candidates, int maxM) {
            return candidates.stream().sorted((a, b) -> Float.compare(b.score(), a.score()))
                    .limit(maxM).map(ScoredCandidate::docIdBytes).collect(Collectors.toList());
        }

        /**
         * Trims {@code neighbors} to at most {@code maxM}, keeping the {@code maxM} most similar to
         * {@code centerVec} according to the configured {@link SimilarityFunction}.
         */
        private List<byte[]> trimToM(float[] centerVec, List<byte[]> neighbors, int maxM)
                throws IOException {
            List<ScoredCandidate> scored = new ArrayList<>();
            for (byte[] nbId : neighbors) {
                Optional<MemorySegment> nbOpt = lsmTree.get(MemorySegment.ofArray(nbId));
                if (nbOpt.isEmpty())
                    continue;
                DecodedNode nbNode = decodeNode(nbOpt.get().toArray(ValueLayout.JAVA_BYTE),
                        precision);
                float s = score(centerVec, nbNode.vector(), similarityFunction);
                scored.add(new ScoredCandidate(nbId, s));
            }
            return selectNeighbors(scored, maxM);
        }

        private float scoreNode(byte[] docIdBytes, float[] query) throws IOException {
            Optional<MemorySegment> opt = lsmTree.get(MemorySegment.ofArray(docIdBytes));
            if (opt.isEmpty())
                return Float.NEGATIVE_INFINITY;
            DecodedNode node = decodeNode(opt.get().toArray(ValueLayout.JAVA_BYTE), precision);
            return score(query, node.vector(), similarityFunction);
        }

        private List<byte[]> getNodeNeighbors(byte[] docIdBytes, int layer) throws IOException {
            Optional<MemorySegment> opt = lsmTree.get(MemorySegment.ofArray(docIdBytes));
            if (opt.isEmpty())
                return List.of();
            DecodedNode node = decodeNode(opt.get().toArray(ValueLayout.JAVA_BYTE), precision);
            if (layer >= node.layerNeighbors().size())
                return List.of();
            return node.layerNeighbors().get(layer);
        }

        private Object nodeLockFor(byte[] docIdBytes) {
            int hash = Arrays.hashCode(docIdBytes);
            return nodeLocks[(hash & 0x7FFF_FFFF) % NODE_LOCK_STRIPES];
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

        private record DecodedNode(List<List<byte[]>> layerNeighbors, float[] vector) {
        }

        // @spec F01.R19 — node serialization uses configured precision for the vector portion
        // @spec F01.R22 — float16 per-node size drops by exactly dim*2 vs float32
        // @spec F01.R36 — each neighbor identifier uses an explicit per-neighbor length prefix
        private static byte[] encodeNode(byte[] docIdBytes, List<List<byte[]>> layerNeighbors,
                float[] vector, VectorPrecision precision) {
            int docIdLen = docIdBytes.length;
            int layerCount = layerNeighbors.size();
            // Each neighbor: 4-byte length prefix + actual bytes
            int neighborBytes = layerNeighbors.stream()
                    .mapToInt(l -> 4 + l.stream().mapToInt(nb -> 4 + nb.length).sum()).sum();
            int totalSize = 4 + 4 + neighborBytes + vector.length * precision.bytesPerComponent();

            byte[] buf = new byte[totalSize];
            int off = 0;
            off = writeInt(buf, off, docIdLen);
            off = writeInt(buf, off, layerCount);
            for (List<byte[]> neighbors : layerNeighbors) {
                off = writeInt(buf, off, neighbors.size());
                for (byte[] nb : neighbors) {
                    off = writeInt(buf, off, nb.length);
                    System.arraycopy(nb, 0, buf, off, nb.length);
                    off += nb.length;
                }
            }
            byte[] vectorBytes = encodeVector(vector, precision);
            System.arraycopy(vectorBytes, 0, buf, off, vectorBytes.length);
            return buf;
        }

        // @spec F01.R21 — remaining vector bytes divisible-by-bpc check is a runtime error
        // @spec F01.R35 — validates input length with runtime checks before accessing bytes
        // @spec F01.R36 — reads each neighbor identifier via its per-neighbor length prefix
        private static DecodedNode decodeNode(byte[] bytes, VectorPrecision precision)
                throws IOException {
            int off = 0;
            int docIdLen = readInt(bytes, off);
            off += 4;
            int layerCount = readInt(bytes, off);
            off += 4;
            List<List<byte[]>> layers = new ArrayList<>(layerCount);
            for (int l = 0; l < layerCount; l++) {
                int neighborCount = readInt(bytes, off);
                off += 4;
                if (neighborCount < 0) {
                    throw new IOException("Corrupted node: negative neighborCount " + neighborCount
                            + " at layer " + l);
                }
                List<byte[]> neighbors = new ArrayList<>(neighborCount);
                for (int n = 0; n < neighborCount; n++) {
                    if (off + 4 > bytes.length) {
                        throw new IOException("Corrupted node: cannot read neighbor length "
                                + "at layer " + l + ", neighbor " + n + " (off=" + off + ", bufLen="
                                + bytes.length + ")");
                    }
                    int nbLen = readInt(bytes, off);
                    off += 4;
                    if (nbLen < 0) {
                        throw new IOException("Corrupted node: negative neighbor ID length " + nbLen
                                + " at layer " + l + ", neighbor " + n);
                    }
                    if (off + (long) nbLen > bytes.length) {
                        throw new IOException(
                                "Corrupted node: neighbor ID length " + nbLen + " at layer " + l
                                        + ", neighbor " + n + " would read past end of buffer (off="
                                        + off + ", bufLen=" + bytes.length + ")");
                    }
                    neighbors.add(Arrays.copyOfRange(bytes, off, off + nbLen));
                    off += nbLen;
                }
                layers.add(neighbors);
            }
            int vecBytes = bytes.length - off;
            int bpc = precision.bytesPerComponent();
            if (vecBytes % bpc != 0) {
                throw new IOException("Corrupted node: vector byte count " + vecBytes
                        + " is not divisible by bytesPerComponent " + bpc + " for precision "
                        + precision);
            }
            float[] vector = decodeVector(Arrays.copyOfRange(bytes, off, bytes.length),
                    vecBytes / bpc, precision);
            return new DecodedNode(layers, vector);
        }

        // -----------------------------------------------------------------------
        // Builder
        // -----------------------------------------------------------------------

        /** Builder for {@link LsmVectorIndex.Hnsw}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            private int maxConnections = 16;
            private int efConstruction = 200;
            private int efSearch = 50;

            public Builder<D> maxConnections(int maxConnections) {
                if (maxConnections <= 0)
                    throw new IllegalArgumentException("maxConnections must be > 0");
                this.maxConnections = maxConnections;
                return this;
            }

            public Builder<D> efConstruction(int ef) {
                if (ef <= 0)
                    throw new IllegalArgumentException("efConstruction must be > 0");
                this.efConstruction = ef;
                return this;
            }

            public Builder<D> efSearch(int ef) {
                if (ef <= 0)
                    throw new IllegalArgumentException("efSearch must be > 0");
                this.efSearch = ef;
                return this;
            }

            public VectorIndex.Hnsw<D> build() {
                validateBase();
                return new LsmVectorIndex.Hnsw<>(consumeTree(), docIdSerializer, dimensions,
                        similarityFunction, maxConnections, efConstruction, efSearch, precision);
            }
        }
    }
}
