package jlsm.tree;

import jlsm.core.compaction.Compactor;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.io.MemorySerializer;
import jlsm.core.memtable.MemTable;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.sstable.SSTableReader;
import jlsm.core.tree.TypedLsmTree;
import jlsm.core.wal.WriteAheadLog;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Non-instantiable namespace class containing three concrete {@link TypedLsmTree} implementations,
 * one per key type.
 *
 * <ul>
 * <li>{@link StringKeyed} — UTF-8 String keys
 * <li>{@link LongKeyed} — {@code long} keys (sign-bit-flipped big-endian for correct ordering)
 * <li>{@link SegmentKeyed} — raw {@link MemorySegment} keys
 * </ul>
 *
 * <p>
 * Obtain instances via the factory methods {@link #stringKeyedBuilder()},
 * {@link #longKeyedBuilder()}, and {@link #segmentKeyedBuilder()}.
 */
public final class TypedStandardLsmTree {

    private TypedStandardLsmTree() {
        throw new UnsupportedOperationException("utility class");
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Returns a builder for a {@link TypedLsmTree.StringKeyed} backed by a {@link StandardLsmTree}.
     */
    public static <V> StringKeyed.Builder<V> stringKeyedBuilder() {
        return new StringKeyed.Builder<>();
    }

    /**
     * Returns a builder for a {@link TypedLsmTree.LongKeyed} backed by a {@link StandardLsmTree}.
     */
    public static <V> LongKeyed.Builder<V> longKeyedBuilder() {
        return new LongKeyed.Builder<>();
    }

    /**
     * Returns a builder for a {@link TypedLsmTree.SegmentKeyed} backed by a
     * {@link StandardLsmTree}.
     */
    public static <V> SegmentKeyed.Builder<V> segmentKeyedBuilder() {
        return new SegmentKeyed.Builder<>();
    }

    // -----------------------------------------------------------------------
    // Shared key-encoding helpers
    // -----------------------------------------------------------------------

    private static MemorySegment encodeString(String key) {
        assert key != null : "key must not be null";
        return MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encodes a {@code long} key as 8 big-endian bytes with the sign bit flipped so that
     * byte-lexicographic order matches numeric order.
     *
     * <p>
     * {@link Long#MIN_VALUE} → {@code 0x00…00} (smallest), {@link Long#MAX_VALUE} → {@code 0xFF…FF}
     * (largest).
     */
    private static MemorySegment encodeLong(long key) {
        long unsigned = key ^ Long.MIN_VALUE;
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (unsigned & 0xFF);
            unsigned >>>= 8;
        }
        return MemorySegment.ofArray(bytes);
    }

    // -----------------------------------------------------------------------
    // Shared builder base (avoids copy-pasting the 8 infrastructure setters)
    // -----------------------------------------------------------------------

    /**
     * Common infrastructure fields for all three builder types. Each concrete builder subclass
     * extends this and adds a {@code valueSerializer} field plus a typed {@code build()} method.
     */
    private abstract static class AbstractBuilder<V, B extends AbstractBuilder<V, B>> {

        final StandardLsmTree.Builder delegateBuilder = StandardLsmTree.builder();

        @SuppressWarnings("unchecked")
        public B wal(WriteAheadLog wal) {
            delegateBuilder.wal(wal);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B memTableFactory(Supplier<MemTable> memTableFactory) {
            delegateBuilder.memTableFactory(memTableFactory);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B sstableWriterFactory(SSTableWriterFactory writerFactory) {
            delegateBuilder.sstableWriterFactory(writerFactory);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B sstableReaderFactory(SSTableReaderFactory readerFactory) {
            delegateBuilder.sstableReaderFactory(readerFactory);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B idSupplier(LongSupplier idSupplier) {
            delegateBuilder.idSupplier(idSupplier);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B pathFn(BiFunction<Long, Level, Path> pathFn) {
            delegateBuilder.pathFn(pathFn);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B memTableFlushThresholdBytes(long bytes) {
            delegateBuilder.memTableFlushThresholdBytes(bytes);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B recoverFromWal(boolean recover) {
            delegateBuilder.recoverFromWal(recover);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B existingSSTables(List<SSTableReader> readers) {
            delegateBuilder.existingSSTables(readers);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B compactor(Compactor compactor) {
            delegateBuilder.compactor(compactor);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B compression(CompressionCodec codec) {
            delegateBuilder.compression(codec);
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B compressionPolicy(Function<Level, CompressionCodec> policy) {
            delegateBuilder.compressionPolicy(policy);
            return (B) this;
        }

        // @spec sstable.v3-format-upgrade.R23 — typed builder delegates blockSize to
        // StandardLsmTree.Builder
        @SuppressWarnings("unchecked")
        public B blockSize(int blockSize) {
            delegateBuilder.blockSize(blockSize);
            return (B) this;
        }
    }

    // -----------------------------------------------------------------------
    // StringKeyed
    // -----------------------------------------------------------------------

    /**
     * A {@link TypedLsmTree.StringKeyed} that delegates to a {@link StandardLsmTree} and encodes
     * keys as UTF-8 bytes.
     *
     * @param <V> the value type
     */
    public static final class StringKeyed<V> implements TypedLsmTree.StringKeyed<V> {

        private final StandardLsmTree delegate;
        private final MemorySerializer<V> valueSerializer;

        private StringKeyed(StandardLsmTree delegate, MemorySerializer<V> valueSerializer) {
            assert delegate != null : "delegate must not be null";
            assert valueSerializer != null : "valueSerializer must not be null";
            this.delegate = delegate;
            this.valueSerializer = valueSerializer;
        }

        @Override
        public void put(String key, V value) throws IOException {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            delegate.put(encodeString(key), valueSerializer.serialize(value));
        }

        @Override
        public void delete(String key) throws IOException {
            Objects.requireNonNull(key, "key must not be null");
            delegate.delete(encodeString(key));
        }

        @Override
        public Optional<V> get(String key) throws IOException {
            Objects.requireNonNull(key, "key must not be null");
            return delegate.get(encodeString(key)).map(valueSerializer::deserialize);
        }

        @Override
        public Iterator<Entry> scan(String from, String to) throws IOException {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            return delegate.scan(encodeString(from), encodeString(to));
        }

        @Override
        public Iterator<Entry> scan() throws IOException {
            return delegate.scan();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /** Builder for {@link StringKeyed}. */
        public static final class Builder<V> extends AbstractBuilder<V, Builder<V>> {

            private MemorySerializer<V> valueSerializer;

            public Builder<V> valueSerializer(MemorySerializer<V> valueSerializer) {
                this.valueSerializer = Objects.requireNonNull(valueSerializer,
                        "valueSerializer must not be null");
                return this;
            }

            public StringKeyed<V> build() throws IOException {
                Objects.requireNonNull(valueSerializer, "valueSerializer must not be null");
                StandardLsmTree delegate = delegateBuilder.build();
                return new TypedStandardLsmTree.StringKeyed<>(delegate, valueSerializer);
            }
        }
    }

    // -----------------------------------------------------------------------
    // LongKeyed
    // -----------------------------------------------------------------------

    /**
     * A {@link TypedLsmTree.LongKeyed} that delegates to a {@link StandardLsmTree} and encodes keys
     * as sign-bit-flipped big-endian 8 bytes for correct numeric ordering.
     *
     * @param <V> the value type
     */
    public static final class LongKeyed<V> implements TypedLsmTree.LongKeyed<V> {

        private final StandardLsmTree delegate;
        private final MemorySerializer<V> valueSerializer;

        private LongKeyed(StandardLsmTree delegate, MemorySerializer<V> valueSerializer) {
            assert delegate != null : "delegate must not be null";
            assert valueSerializer != null : "valueSerializer must not be null";
            this.delegate = delegate;
            this.valueSerializer = valueSerializer;
        }

        @Override
        public void put(long key, V value) throws IOException {
            Objects.requireNonNull(value, "value must not be null");
            delegate.put(encodeLong(key), valueSerializer.serialize(value));
        }

        @Override
        public void delete(long key) throws IOException {
            delegate.delete(encodeLong(key));
        }

        @Override
        public Optional<V> get(long key) throws IOException {
            return delegate.get(encodeLong(key)).map(valueSerializer::deserialize);
        }

        @Override
        public Iterator<Entry> scan(long from, long to) throws IOException {
            return delegate.scan(encodeLong(from), encodeLong(to));
        }

        @Override
        public Iterator<Entry> scan() throws IOException {
            return delegate.scan();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /** Builder for {@link LongKeyed}. */
        public static final class Builder<V> extends AbstractBuilder<V, Builder<V>> {

            private MemorySerializer<V> valueSerializer;

            public Builder<V> valueSerializer(MemorySerializer<V> valueSerializer) {
                this.valueSerializer = Objects.requireNonNull(valueSerializer,
                        "valueSerializer must not be null");
                return this;
            }

            public LongKeyed<V> build() throws IOException {
                Objects.requireNonNull(valueSerializer, "valueSerializer must not be null");
                StandardLsmTree delegate = delegateBuilder.build();
                return new TypedStandardLsmTree.LongKeyed<>(delegate, valueSerializer);
            }
        }
    }

    // -----------------------------------------------------------------------
    // SegmentKeyed
    // -----------------------------------------------------------------------

    /**
     * A {@link TypedLsmTree.SegmentKeyed} that delegates to a {@link StandardLsmTree} using raw
     * {@link MemorySegment} keys compared byte-lexicographically.
     *
     * @param <V> the value type
     */
    public static final class SegmentKeyed<V> implements TypedLsmTree.SegmentKeyed<V> {

        private final StandardLsmTree delegate;
        private final MemorySerializer<V> valueSerializer;

        private SegmentKeyed(StandardLsmTree delegate, MemorySerializer<V> valueSerializer) {
            assert delegate != null : "delegate must not be null";
            assert valueSerializer != null : "valueSerializer must not be null";
            this.delegate = delegate;
            this.valueSerializer = valueSerializer;
        }

        @Override
        public void put(MemorySegment key, V value) throws IOException {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            delegate.put(key, valueSerializer.serialize(value));
        }

        @Override
        public void delete(MemorySegment key) throws IOException {
            Objects.requireNonNull(key, "key must not be null");
            delegate.delete(key);
        }

        @Override
        public Optional<V> get(MemorySegment key) throws IOException {
            Objects.requireNonNull(key, "key must not be null");
            return delegate.get(key).map(valueSerializer::deserialize);
        }

        @Override
        public Iterator<Entry> scan(MemorySegment from, MemorySegment to) throws IOException {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            return delegate.scan(from, to);
        }

        @Override
        public Iterator<Entry> scan() throws IOException {
            return delegate.scan();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /** Builder for {@link SegmentKeyed}. */
        public static final class Builder<V> extends AbstractBuilder<V, Builder<V>> {

            private MemorySerializer<V> valueSerializer;

            public Builder<V> valueSerializer(MemorySerializer<V> valueSerializer) {
                this.valueSerializer = Objects.requireNonNull(valueSerializer,
                        "valueSerializer must not be null");
                return this;
            }

            public SegmentKeyed<V> build() throws IOException {
                Objects.requireNonNull(valueSerializer, "valueSerializer must not be null");
                StandardLsmTree delegate = delegateBuilder.build();
                return new TypedStandardLsmTree.SegmentKeyed<>(delegate, valueSerializer);
            }
        }
    }
}
