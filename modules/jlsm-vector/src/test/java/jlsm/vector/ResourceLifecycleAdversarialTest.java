package jlsm.vector;

import jlsm.bloom.PassthroughBloomFilter;
import jlsm.core.indexing.SimilarityFunction;
import jlsm.core.tree.LsmTree;
import jlsm.core.io.MemorySerializer;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.core.model.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource-lifecycle adversarial tests for vector index constructs. Test methods are added by
 * prove-fix subagents for confirmed findings.
 */
class ResourceLifecycleAdversarialTest {

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

    // Finding: F-R2.resource_lifecycle.2.1
    // Bug: Hnsw.close() lacks idempotency guard — double-close delegates directly to LsmTree
    // Correct behavior: Calling close() multiple times should be safe (idempotent); second call is
    // a no-op
    // and does NOT re-invoke lsmTree.close()
    // Fix location: Hnsw.close() (LsmVectorIndex.java) — add a closed guard
    // Regression watch: Ensure the first close() still properly closes the LsmTree
    @Test
    void test_hnsw_close_idempotent_doubleCloseDoesNotReclose() throws Exception {
        var closeCount = new AtomicLong(0);
        LsmTree realTree = buildTree(1024 * 1024);

        // Wrap the real tree to count close() invocations
        LsmTree countingTree = new LsmTree() {
            @Override
            public void put(MemorySegment key, MemorySegment value) throws IOException {
                realTree.put(key, value);
            }

            @Override
            public void delete(MemorySegment key) throws IOException {
                realTree.delete(key);
            }

            @Override
            public Optional<MemorySegment> get(MemorySegment key) throws IOException {
                return realTree.get(key);
            }

            @Override
            public Iterator<Entry> scan() throws IOException {
                return realTree.scan();
            }

            @Override
            public Iterator<Entry> scan(MemorySegment from, MemorySegment to) throws IOException {
                return realTree.scan(from, to);
            }

            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                realTree.close();
            }
        };

        var hnsw = LsmVectorIndex.<Long>hnswBuilder().lsmTree(countingTree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT).build();

        // First close
        hnsw.close();
        assertEquals(1, closeCount.get(),
                "First close should delegate to lsmTree.close() exactly once");

        // Second close — must be a no-op, not re-invoke lsmTree.close()
        hnsw.close();
        assertEquals(1, closeCount.get(),
                "Second close must not re-invoke lsmTree.close() — idempotency guard missing");
    }

    // Finding: F-R2.resource_lifecycle.1.4
    // Bug: AbstractBuilder has no resource cleanup — stranded LsmTree reference on abandoned
    // builder
    // Correct behavior: Builder should implement AutoCloseable so that an abandoned builder
    // can clean up the LsmTree via try-with-resources. close() on builder should close the
    // tree only if build() was never called (ownership not transferred).
    // Fix location: AbstractBuilder (LsmVectorIndex.java:321-385) — implement AutoCloseable
    // Regression watch: build() must clear the lsmTree reference so close() doesn't double-close
    @Test
    void test_abstractBuilder_abandonedBuilder_closesLsmTree() throws Exception {
        LsmTree tree = buildTree(1024 * 1024);

        // Create a builder, pass the tree, then abandon without calling build()
        // The builder should be AutoCloseable so try-with-resources cleans up
        var builder = LsmVectorIndex.<Long>ivfFlatBuilder().lsmTree(tree)
                .docIdSerializer(LONG_DOC_ID_SERIALIZER).dimensions(4)
                .similarityFunction(SimilarityFunction.DOT_PRODUCT);

        // The builder must implement AutoCloseable — this is the core assertion.
        // Without AutoCloseable, an abandoned builder strands the LsmTree with no
        // cleanup mechanism.
        assertInstanceOf(AutoCloseable.class, builder,
                "Builder must implement AutoCloseable to allow resource cleanup on abandonment");

        // If the builder is AutoCloseable, verify that closing it closes the stranded tree.
        // Use reflection to call close() since it won't compile as a direct cast until fixed.
        builder.getClass().getMethod("close").invoke(builder);

        // After builder.close(), the tree should be closed — verify by trying to use it
        assertThrows(IllegalStateException.class, () -> {
            tree.put(MemorySegment.ofArray(new byte[]{ 1 }),
                    MemorySegment.ofArray(new byte[]{ 2 }));
        }, "LsmTree should be closed after abandoned builder is closed");
    }
}
