package jlsm.table.internal;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.indexing.VectorIndex;
import jlsm.core.indexing.VectorPrecision;

/**
 * Test-only helpers producing in-memory {@link VectorIndex.Factory} instances, used to satisfy the
 * WD-02 factory requirement in registry and adversarial tests without spinning up a real
 * {@code LsmVectorIndex}. Production code must not depend on this class.
 */
public final class InMemoryVectorFactories {

    private InMemoryVectorFactories() {
    }

    /**
     * Returns a factory that produces a minimal in-memory {@link VectorIndex.IvfFlat} fake. The
     * fake uses squared Euclidean distance (higher score = closer) regardless of the requested
     * {@link SimilarityFunction} — tests that care about scoring should use a real factory.
     */
    public static VectorIndex.Factory ivfFlatFake() {
        return (table, field, dims, precision, sim) -> new InMemoryIvfFlat(dims, precision);
    }

    /**
     * Returns a factory that always throws on create — used to assert that missing-factory paths or
     * error propagation work as expected.
     */
    public static VectorIndex.Factory alwaysThrowing() {
        return (table, field, dims, precision, sim) -> {
            throw new IOException("synthetic factory failure for " + table + "/" + field);
        };
    }

    private static final class InMemoryIvfFlat implements VectorIndex.IvfFlat<MemorySegment> {

        private final int dims;
        private final VectorPrecision precision;
        private final Map<Key, float[]> store = new LinkedHashMap<>();
        private volatile boolean closed;

        InMemoryIvfFlat(int dims, VectorPrecision precision) {
            this.dims = dims;
            this.precision = precision;
        }

        @Override
        public void index(MemorySegment docId, float[] vector) {
            ensureOpen();
            store.put(new Key(docId.toArray(ValueLayout.JAVA_BYTE)), vector.clone());
        }

        @Override
        public void remove(MemorySegment docId) {
            ensureOpen();
            store.remove(new Key(docId.toArray(ValueLayout.JAVA_BYTE)));
        }

        @Override
        public List<VectorIndex.SearchResult<MemorySegment>> search(float[] query, int topK) {
            ensureOpen();
            List<VectorIndex.SearchResult<MemorySegment>> out = new ArrayList<>();
            for (Map.Entry<Key, float[]> e : store.entrySet()) {
                float[] v = e.getValue();
                float dist = 0.0f;
                for (int i = 0; i < Math.min(query.length, v.length); i++) {
                    float d = query[i] - v[i];
                    dist += d * d;
                }
                out.add(new VectorIndex.SearchResult<>(MemorySegment.ofArray(e.getKey().bytes),
                        -dist));
            }
            out.sort((a, b) -> Float.compare(b.score(), a.score()));
            return out.size() > topK ? out.subList(0, topK) : out;
        }

        @Override
        public VectorPrecision precision() {
            return precision;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("in-memory vector index is closed");
            }
        }

        private record Key(byte[] bytes) {

            @Override
            public boolean equals(Object o) {
                return o instanceof Key k && Arrays.equals(bytes, k.bytes);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(bytes);
            }
        }
    }
}
