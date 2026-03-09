package jlsm.sstable;

import jlsm.core.bloom.BloomFilter;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.core.sstable.SSTableMetadata;
import jlsm.core.sstable.SSTableReader;
import jlsm.sstable.internal.EntryCodec;
import jlsm.sstable.internal.KeyIndex;
import jlsm.sstable.internal.SSTableFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads an SSTable file written by {@link TrieSSTableWriter}.
 *
 * <p>Two factory methods:
 * <ul>
 *   <li>{@link #open} — eager: loads the entire data region into memory on open</li>
 *   <li>{@link #openLazy} — lazy: loads only footer, key index, bloom filter; reads data on demand</li>
 * </ul>
 */
public final class TrieSSTableReader implements SSTableReader {

    private final SSTableMetadata metadata;
    private final KeyIndex keyIndex;
    private final BloomFilter bloomFilter;
    private final long dataEnd; // file offset just past the last data block (= index start)

    // Eager mode: all data bytes pre-loaded; lazy mode: null
    private final byte[] eagerData;

    // Lazy mode: open file channel; eager mode: null
    private final FileChannel lazyChannel;

    private volatile boolean closed = false;

    private TrieSSTableReader(SSTableMetadata metadata, KeyIndex keyIndex, BloomFilter bloomFilter,
                               long dataEnd, byte[] eagerData, FileChannel lazyChannel) {
        this.metadata = metadata;
        this.keyIndex = keyIndex;
        this.bloomFilter = bloomFilter;
        this.dataEnd = dataEnd;
        this.eagerData = eagerData;
        this.lazyChannel = lazyChannel;
    }

    // ---- Factory methods ----

    public static TrieSSTableReader open(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");

        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooter(ch, fileSize);
            KeyIndex keyIndex = readKeyIndex(ch, footer);
            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            // Read entire data region eagerly
            int dataLen = (int) footer.idxOffset;
            byte[] data = readBytes(ch, 0L, dataLen);
            ch.close();

            return new TrieSSTableReader(meta, keyIndex, bloom, footer.idxOffset, data, null);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
    }

    public static TrieSSTableReader openLazy(Path path, BloomFilter.Deserializer bloomDeserializer)
            throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(bloomDeserializer, "bloomDeserializer must not be null");

        FileChannel ch = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = ch.size();
            Footer footer = readFooter(ch, fileSize);
            KeyIndex keyIndex = readKeyIndex(ch, footer);
            BloomFilter bloom = readBloomFilter(ch, footer, bloomDeserializer);
            SSTableMetadata meta = buildMetadata(path, fileSize, footer, keyIndex);

            return new TrieSSTableReader(meta, keyIndex, bloom, footer.idxOffset, null, ch);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
    }

    // ---- SSTableReader interface ----

    @Override
    public SSTableMetadata metadata() {
        return metadata;
    }

    @Override
    public Optional<Entry> get(MemorySegment key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        checkNotClosed();

        if (!bloomFilter.mightContain(key)) {
            return Optional.empty();
        }

        Optional<Long> fileOffsetOpt = keyIndex.lookup(key);
        if (fileOffsetOpt.isEmpty()) {
            return Optional.empty();
        }

        long fileOffset = fileOffsetOpt.get();
        byte[] entryBytes = readDataAt(fileOffset, SSTableFormat.DEFAULT_BLOCK_SIZE);
        // entryBytes starts at fileOffset, so the entry is at index 0
        return Optional.of(EntryCodec.decode(entryBytes, 0));
    }

    @Override
    public Iterator<Entry> scan() throws IOException {
        checkNotClosed();
        byte[] data = getAllData();
        return new DataRegionIterator(data, dataEnd);
    }

    @Override
    public Iterator<Entry> scan(MemorySegment fromKey, MemorySegment toKey) throws IOException {
        Objects.requireNonNull(fromKey, "fromKey must not be null");
        Objects.requireNonNull(toKey, "toKey must not be null");
        checkNotClosed();
        return new IndexRangeIterator(keyIndex.rangeIterator(fromKey, toKey));
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (lazyChannel != null) {
            lazyChannel.close();
        }
    }

    // ---- Internal helpers ----

    private void checkNotClosed() throws IOException {
        if (closed) throw new IllegalStateException("reader is closed");
    }

    /** Returns the data at {@code fileOffset}, reading at most {@code maxBytes} bytes. */
    private byte[] readDataAt(long fileOffset, int maxBytes) throws IOException {
        int len = (int) Math.min(maxBytes, dataEnd - fileOffset);
        if (len <= 0) throw new IOException("file offset out of data range: " + fileOffset);
        if (eagerData != null) {
            byte[] buf = new byte[len];
            System.arraycopy(eagerData, (int) fileOffset, buf, 0, len);
            return buf;
        }
        return readBytes(lazyChannel, fileOffset, len);
    }

    /** Returns the full data region (for full scan). */
    private byte[] getAllData() throws IOException {
        if (eagerData != null) return eagerData;
        return readBytes(lazyChannel, 0L, (int) dataEnd);
    }

    // ---- Footer / index / filter loading ----

    private record Footer(long idxOffset, long idxLength, long fltOffset, long fltLength,
                           long entryCount) {}

    private static Footer readFooter(FileChannel ch, long fileSize) throws IOException {
        if (fileSize < SSTableFormat.FOOTER_SIZE) {
            throw new IOException("not a valid SSTable file: too small");
        }
        byte[] buf = readBytes(ch, fileSize - SSTableFormat.FOOTER_SIZE, SSTableFormat.FOOTER_SIZE);
        long idxOffset  = readLong(buf, 0);
        long idxLength  = readLong(buf, 8);
        long fltOffset  = readLong(buf, 16);
        long fltLength  = readLong(buf, 24);
        long entryCount = readLong(buf, 32);
        long magic      = readLong(buf, 40);
        if (magic != SSTableFormat.MAGIC) {
            throw new IOException("not a valid SSTable file: bad magic " + Long.toHexString(magic));
        }
        return new Footer(idxOffset, idxLength, fltOffset, fltLength, entryCount);
    }

    private static KeyIndex readKeyIndex(FileChannel ch, Footer footer) throws IOException {
        byte[] buf = readBytes(ch, footer.idxOffset, (int) footer.idxLength);
        int numKeys = readInt(buf, 0);
        List<MemorySegment> keys = new ArrayList<>(numKeys);
        List<Long> offsets = new ArrayList<>(numKeys);
        int off = 4;
        for (int i = 0; i < numKeys; i++) {
            int keyLen = readInt(buf, off);
            off += 4;
            byte[] keyBytes = new byte[keyLen];
            System.arraycopy(buf, off, keyBytes, 0, keyLen);
            off += keyLen;
            long fileOffset = readLong(buf, off);
            off += 8;
            keys.add(MemorySegment.ofArray(keyBytes));
            offsets.add(fileOffset);
        }
        return new KeyIndex(keys, offsets);
    }

    private static BloomFilter readBloomFilter(FileChannel ch, Footer footer,
                                                BloomFilter.Deserializer deserializer)
            throws IOException {
        byte[] buf = readBytes(ch, footer.fltOffset, (int) footer.fltLength);
        // Use an Arena-allocated segment to satisfy the alignment constraints of the deserializer
        // (BlockedBloomFilter uses JAVA_INT/JAVA_LONG without withByteAlignment(1)).
        MemorySegment seg = Arena.ofAuto().allocate(buf.length, 8);
        MemorySegment.copy(MemorySegment.ofArray(buf), 0, seg, 0, buf.length);
        return deserializer.deserialize(seg);
    }

    private static SSTableMetadata buildMetadata(Path path, long fileSize, Footer footer,
                                                  KeyIndex keyIndex) {
        MemorySegment smallestKey = null;
        MemorySegment largestKey = null;
        Iterator<KeyIndex.Entry> it = keyIndex.iterator();
        while (it.hasNext()) {
            KeyIndex.Entry e = it.next();
            if (smallestKey == null) smallestKey = e.key();
            largestKey = e.key();
        }
        assert smallestKey != null : "key index must not be empty when building metadata";
        return new SSTableMetadata(0L, path, Level.L0, smallestKey, largestKey,
                SequenceNumber.ZERO, SequenceNumber.ZERO, fileSize, footer.entryCount);
    }

    // ---- Low-level I/O ----

    private static byte[] readBytes(FileChannel ch, long offset, int length) throws IOException {
        if (length == 0) return new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            int read = ch.read(buf, offset + buf.position());
            if (read < 0) throw new IOException("unexpected EOF at offset " + (offset + buf.position()));
        }
        return buf.array();
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off]     & 0xFF) << 24)
             | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8)
             |  (buf[off + 3] & 0xFF);
    }

    private static long readLong(byte[] buf, int off) {
        return ((long)(buf[off]     & 0xFF) << 56)
             | ((long)(buf[off + 1] & 0xFF) << 48)
             | ((long)(buf[off + 2] & 0xFF) << 40)
             | ((long)(buf[off + 3] & 0xFF) << 32)
             | ((long)(buf[off + 4] & 0xFF) << 24)
             | ((long)(buf[off + 5] & 0xFF) << 16)
             | ((long)(buf[off + 6] & 0xFF) << 8)
             |  (long)(buf[off + 7] & 0xFF);
    }

    // ---- Iterators ----

    /**
     * Iterates entries by parsing the raw data region linearly (block by block).
     * Parses: [int count][entry...][int count][entry...]... until offset reaches dataEnd.
     */
    private static final class DataRegionIterator implements Iterator<Entry> {
        private final byte[] data;
        private final long dataEnd;
        private int offset = 0;
        private List<Entry> blockEntries = new ArrayList<>();
        private int entryIdx = 0;
        private Entry next;

        DataRegionIterator(byte[] data, long dataEnd) {
            this.data = data;
            this.dataEnd = dataEnd;
            advance();
        }

        private void advance() {
            next = null;
            while (true) {
                if (entryIdx < blockEntries.size()) {
                    next = blockEntries.get(entryIdx++);
                    return;
                }
                if (offset >= dataEnd) return;
                // Parse next block
                int count = readInt(data, offset);
                offset += 4;
                blockEntries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    Entry e = EntryCodec.decode(data, offset);
                    blockEntries.add(e);
                    offset += EntryCodec.encodedSize(e);
                }
                entryIdx = 0;
            }
        }

        @Override public boolean hasNext() { return next != null; }
        @Override public Entry next() {
            if (next == null) throw new NoSuchElementException();
            Entry result = next;
            advance();
            return result;
        }

        private static int readInt(byte[] buf, int off) {
            return ((buf[off]     & 0xFF) << 24)
                 | ((buf[off + 1] & 0xFF) << 16)
                 | ((buf[off + 2] & 0xFF) << 8)
                 |  (buf[off + 3] & 0xFF);
        }
    }

    /**
     * Iterates entries via key index range results, reading each entry at its absolute file offset.
     */
    private final class IndexRangeIterator implements Iterator<Entry> {
        private final Iterator<KeyIndex.Entry> indexIter;
        private Entry next;

        IndexRangeIterator(Iterator<KeyIndex.Entry> indexIter) {
            this.indexIter = indexIter;
            advance();
        }

        private void advance() {
            next = null;
            if (!indexIter.hasNext()) return;
            KeyIndex.Entry ie = indexIter.next();
            try {
                byte[] buf = readDataAt(ie.fileOffset(), SSTableFormat.DEFAULT_BLOCK_SIZE);
                next = EntryCodec.decode(buf, 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override public boolean hasNext() { return next != null; }
        @Override public Entry next() {
            if (next == null) throw new NoSuchElementException();
            Entry result = next;
            advance();
            return result;
        }
    }
}
