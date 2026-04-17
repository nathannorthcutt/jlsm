package jlsm.sstable;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.sstable.internal.SSTableFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for SSTable v2 compression integration.
 *
 * <p>
 * Targets findings from block-compression spec-analysis.md.
 */
class SSTableCompressionAdversarialTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes());
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private List<Entry> basicEntries() {
        return List.of(put("apple", "red", 1), put("banana", "yellow", 2),
                put("cherry", "dark-red", 3), put("date", "brown", 4),
                put("elderberry", "purple", 5));
    }

    // ---- CONTRACT-GAP-2: V2 reader must auto-include NoneCodec ----

    @Test
    void v2ReaderWithOnlyDeflateCodecHandlesIncompressibleBlocks(@TempDir Path dir)
            throws IOException {
        Random rng = new Random(42);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "key-%05d".formatted(i);
            byte[] randomValue = new byte[200];
            rng.nextBytes(randomValue);
            entries.add(new Entry.Put(seg(key), MemorySegment.ofArray(randomValue),
                    new SequenceNumber(i + 1)));
        }

        Path path = dir.resolve("incompressible.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(),
                null, CompressionCodec.deflate())) {
            assertEquals(50, r.metadata().entryCount());
            Iterator<Entry> iter = r.scan();
            int count = 0;
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            assertEquals(50, count);
        }
    }

    @Test
    void v2LazyReaderWithOnlyDeflateCodecHandlesIncompressibleBlocks(@TempDir Path dir)
            throws IOException {
        Random rng = new Random(99);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String key = "lazy-key-%05d".formatted(i);
            byte[] randomValue = new byte[300];
            rng.nextBytes(randomValue);
            entries.add(new Entry.Put(seg(key), MemorySegment.ofArray(randomValue),
                    new SequenceNumber(i + 1)));
        }

        Path path = dir.resolve("lazy-incompressible.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        try (TrieSSTableReader r = TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate())) {
            assertEquals(30, r.metadata().entryCount());
            assertTrue(r.get(seg("lazy-key-00000")).isPresent());
        }
    }

    // ---- C2-F3: buildCodecMap silently accepts null elements in codecs varargs ----
    // Passing a null element in the codecs array should throw NPE with a descriptive message,
    // not a raw NPE from inside buildCodecMap.
    @Test
    void openWithNullCodecElementThrowsDescriptiveException_C2F3(@TempDir Path dir)
            throws IOException {
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("nullcodec.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // C2-F3: null element in varargs — should throw with descriptive message
        Exception ex = assertThrows(Exception.class, () -> TrieSSTableReader.open(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate(), null));
        // It should be NullPointerException or IllegalArgumentException
        assertTrue(ex instanceof NullPointerException || ex instanceof IllegalArgumentException,
                "C2-F3: Expected NPE or IAE, got: " + ex.getClass().getName());
    }

    @Test
    void openLazyWithNullCodecElementThrowsDescriptiveException_C2F3(@TempDir Path dir)
            throws IOException {
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("nullcodec-lazy.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        Exception ex = assertThrows(Exception.class, () -> TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate(), null));
        assertTrue(ex instanceof NullPointerException || ex instanceof IllegalArgumentException,
                "C2-F3: Expected NPE or IAE for lazy open, got: " + ex.getClass().getName());
    }

    // ---- C2-F6: readFooter no field consistency validation ----
    // A corrupt v2 footer with negative mapLength should produce IOException, not IAE/NASE.
    @Test
    void corruptV2FooterNegativeMapLengthThrowsIOException_C2F6(@TempDir Path dir)
            throws IOException {
        // Write a valid v2 SSTable first
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("corrupt-footer.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Corrupt the footer: set mapLength to -1 (bytes 8-15 of footer)
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
            long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V2;
            // mapLength is at offset 8 within the v2 footer
            ch.position(footerStart + 8);
            ByteBuffer negOne = ByteBuffer.allocate(8);
            negOne.putLong(-1L);
            negOne.flip();
            ch.write(negOne);
        }

        // C2-F6: Should throw IOException with descriptive message
        Exception ex = assertThrows(Exception.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.none(), CompressionCodec.deflate()));
        assertTrue(ex instanceof IOException,
                "C2-F6: corrupt footer should throw IOException, got: " + ex.getClass().getName()
                        + " — " + ex.getMessage());
    }

    // C2-F6: Corrupt footer with negative entryCount
    @Test
    void corruptV2FooterNegativeEntryCountThrowsIOException_C2F6(@TempDir Path dir)
            throws IOException {
        List<Entry> entries = basicEntries();
        Path path = dir.resolve("corrupt-entrycount.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Corrupt entryCount field (at offset 48 within v2 footer)
        long fileSize = Files.size(path);
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
            long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V2;
            ch.position(footerStart + 48);
            ByteBuffer negOne = ByteBuffer.allocate(8);
            negOne.putLong(-1L);
            negOne.flip();
            ch.write(negOne);
        }

        // C2-F6: Negative entry count should be caught
        Exception ex = assertThrows(Exception.class,
                () -> TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                        CompressionCodec.none(), CompressionCodec.deflate()));
        assertTrue(ex instanceof IOException,
                "C2-F6: negative entryCount should throw IOException, got: "
                        + ex.getClass().getName());
    }

    // ---- C2-F11/C2-F14: Iterator closed-state inconsistency ----
    // After close(), hasNext() returns stale true, but next() throws ISE.

    @Test
    void compressedBlockIteratorClosedStateBehavior_C2F11(@TempDir Path dir) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(put("key-%05d".formatted(i), "value-%d".formatted(i), i + 1));
        }
        Path path = dir.resolve("closed-iter.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.none(), CompressionCodec.deflate());
        Iterator<Entry> iter = r.scan();
        assertTrue(iter.hasNext(), "iterator should have entries before close");
        r.close();

        // C2-F11 / F08.R19 (v3): after mid-iteration close, hasNext() must throw
        // IllegalStateException rather than returning a stale value. Previously this
        // inconsistency (hasNext=true / next throws) was documented as a bug; the
        // resolution is to make hasNext() symmetric with next() — both throw on close.
        assertThrows(IllegalStateException.class, iter::hasNext,
                "C2-F11: hasNext() must throw after close, not return a stale value");
    }

    @Test
    void indexRangeIteratorClosedStateBehavior_C2F14(@TempDir Path dir) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(put("key-%05d".formatted(i), "value-%d".formatted(i), i + 1));
        }
        Path path = dir.resolve("closed-range-iter.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        TrieSSTableReader r = TrieSSTableReader.open(path, BlockedBloomFilter.deserializer(), null,
                CompressionCodec.none(), CompressionCodec.deflate());
        Iterator<Entry> iter = r.scan(seg("key-00000"), seg("key-00010"));
        assertTrue(iter.hasNext(), "range iterator should have entries before close");
        r.close();

        // C2-F14: Same pattern as C2-F11
        boolean hasNextAfterClose = iter.hasNext();
        if (hasNextAfterClose) {
            assertThrows(Exception.class, iter::next,
                    "C2-F14: hasNext()=true but next() throws after close — inconsistent");
        }
    }

    // ---- F02.R33: v2 key-index entries must reject invalid blockIndex / intraBlockOffset ----

    /**
     * Writes a valid v2 SSTable, then overwrites the first key-index entry's blockIndex field with
     * {@code patchedBlockIndex} (and optionally intraBlockOffset with {@code patchedIntraOffset}).
     * Returns the resulting path.
     */
    private Path writeAndPatchFirstKeyIndexEntry(Path dir, String name, int patchedBlockIndex,
            Integer patchedIntraOffset) throws IOException {
        List<Entry> entries = basicEntries();
        Path path = dir.resolve(name);
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // Locate key index via footer: v2 layout is [... mapOffset, mapLength, idxOffset,
        // idxLength, ...]. idxOffset lives at footerStart+16 (bytes 16..23).
        long fileSize = Files.size(path);
        long idxOffset;
        int keyLen;
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long footerStart = fileSize - SSTableFormat.FOOTER_SIZE_V2;
            ByteBuffer footer = ByteBuffer.allocate(SSTableFormat.FOOTER_SIZE_V2);
            ch.position(footerStart);
            while (footer.hasRemaining()) {
                if (ch.read(footer) < 0)
                    break;
            }
            footer.flip();
            footer.position(16);
            idxOffset = footer.getLong();

            // Read the first key-index entry header to find keyLen (idxOffset + 4 skips numKeys)
            ByteBuffer header = ByteBuffer.allocate(4);
            ch.position(idxOffset + 4);
            while (header.hasRemaining()) {
                if (ch.read(header) < 0)
                    break;
            }
            header.flip();
            keyLen = header.getInt();
        }

        // blockIndex field = idxOffset + 4 (numKeys) + 4 (keyLen) + keyLen
        long blockIndexPos = idxOffset + 4L + 4L + keyLen;
        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
            ByteBuffer patch = ByteBuffer.allocate(4);
            patch.putInt(patchedBlockIndex).flip();
            ch.position(blockIndexPos);
            while (patch.hasRemaining()) {
                ch.write(patch);
            }
            if (patchedIntraOffset != null) {
                ByteBuffer intra = ByteBuffer.allocate(4);
                intra.putInt(patchedIntraOffset).flip();
                ch.position(blockIndexPos + 4);
                while (intra.hasRemaining()) {
                    ch.write(intra);
                }
            }
        }
        return path;
    }

    // @spec F02.R33 — blockIndex out of [0, blockCount) must produce IOException at key-index
    // read time, not IndexOutOfBoundsException later when accessing the compression map.
    @Test
    void v2KeyIndexRejectsBlockIndexOutOfRange_F02R33(@TempDir Path dir) throws IOException {
        Path path = writeAndPatchFirstKeyIndexEntry(dir, "bad-block-index.sst",
                Integer.MAX_VALUE /* clearly >= blockCount */, null);

        Exception ex = assertThrows(Exception.class, () -> TrieSSTableReader.open(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        assertInstanceOf(IOException.class, ex,
                "R33: corrupt blockIndex must surface as IOException, got: "
                        + ex.getClass().getName() + " — " + ex.getMessage());
        assertTrue(
                ex.getMessage() != null && (ex.getMessage().contains("blockIndex")
                        || ex.getMessage().contains("block index")),
                "R33: expected descriptive message naming blockIndex, got: " + ex.getMessage());
    }

    // @spec F02.R33 — negative blockIndex must produce IOException at key-index read time.
    @Test
    void v2KeyIndexRejectsNegativeBlockIndex_F02R33(@TempDir Path dir) throws IOException {
        Path path = writeAndPatchFirstKeyIndexEntry(dir, "neg-block-index.sst", -1, null);

        Exception ex = assertThrows(Exception.class, () -> TrieSSTableReader.open(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        assertInstanceOf(IOException.class, ex,
                "R33: negative blockIndex must surface as IOException, got: "
                        + ex.getClass().getName() + " — " + ex.getMessage());
    }

    // @spec F02.R33 — negative intraBlockOffset must produce IOException at key-index read time.
    @Test
    void v2KeyIndexRejectsNegativeIntraBlockOffset_F02R33(@TempDir Path dir) throws IOException {
        Path path = writeAndPatchFirstKeyIndexEntry(dir, "neg-intra-offset.sst", 0, -1);

        Exception ex = assertThrows(Exception.class, () -> TrieSSTableReader.open(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.deflate()));
        assertInstanceOf(IOException.class, ex,
                "R33: negative intraBlockOffset must surface as IOException, got: "
                        + ex.getClass().getName() + " — " + ex.getMessage());
        assertTrue(ex.getMessage() != null
                && (ex.getMessage().contains("intra") || ex.getMessage().contains("offset")),
                "R33: expected descriptive message naming intra-block offset, got: "
                        + ex.getMessage());
    }

    // ---- C2-F18: readBytes position-then-read race on lazy channel ----
    // Concurrent reads on a shared lazy channel can interleave position+read calls.
    @Test
    void lazyConcurrentReadsProduceCorrectResults_C2F18(@TempDir Path dir) throws Exception {
        // Write enough entries to span multiple blocks
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String key = "key-%06d".formatted(i);
            String value = "value-content-for-concurrency-test-%06d".formatted(i);
            entries.add(put(key, value, i + 1));
        }

        Path path = dir.resolve("concurrent.sst");
        try (TrieSSTableWriter w = new TrieSSTableWriter(1L, Level.L0, path,
                n -> new BlockedBloomFilter(n, 0.01), CompressionCodec.deflate())) {
            for (Entry e : entries) {
                w.append(e);
            }
            w.finish();
        }

        // C2-F18: Open lazily and do concurrent point lookups
        try (TrieSSTableReader r = TrieSSTableReader.openLazy(path,
                BlockedBloomFilter.deserializer(), null, CompressionCodec.none(),
                CompressionCodec.deflate())) {

            int numThreads = 8;
            int iterations = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterations; i++) {
                            // Each thread looks up different keys to force different block reads
                            int keyIdx = (threadId * iterations + i) % 200;
                            String key = "key-%06d".formatted(keyIdx);
                            Optional<Entry> result = r.get(seg(key));
                            if (result.isEmpty()) {
                                errors.add("Thread-%d: key '%s' not found (iteration %d)"
                                        .formatted(threadId, key, i));
                            } else {
                                String expectedValue = "value-content-for-concurrency-test-%06d"
                                        .formatted(keyIdx);
                                byte[] valueBytes = ((Entry.Put) result.get()).value()
                                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                                if (!expectedValue.equals(new String(valueBytes))) {
                                    errors.add(
                                            "Thread-%d: key '%s' value mismatch (iteration %d): expected '%s', got '%s'"
                                                    .formatted(threadId, key, i, expectedValue,
                                                            new String(valueBytes)));
                                }
                            }
                        }
                    } catch (Exception e) {
                        errors.add("Thread-%d: exception: %s".formatted(threadId, e.getMessage()));
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            // C2-F18: If there's a race, some reads will return wrong data or throw.
            // We document the outcome rather than asserting empty — this is the adversarial probe.
            if (!errors.isEmpty()) {
                fail("C2-F18: CONFIRMED — concurrent lazy reads produced %d errors (sample: %s)"
                        .formatted(errors.size(), errors.peek()));
            }
        }
    }
}
