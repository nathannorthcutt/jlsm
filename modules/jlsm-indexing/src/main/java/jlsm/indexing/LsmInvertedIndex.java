package jlsm.indexing;

import jlsm.core.indexing.InvertedIndex;
import jlsm.core.io.MemorySerializer;
import jlsm.core.model.Entry;
import jlsm.core.tree.LsmTree;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Non-instantiable namespace class containing three concrete {@link InvertedIndex} implementations,
 * one per term type.
 *
 * <ul>
 *   <li>{@link StringTermed} — UTF-8 String terms
 *   <li>{@link LongTermed} — {@code long} terms (sign-bit-flipped big-endian for correct ordering)
 *   <li>{@link SegmentTermed} — raw {@link MemorySegment} terms
 * </ul>
 *
 * <p>Obtain instances via the factory methods {@link #stringTermedBuilder()},
 * {@link #longTermedBuilder()}, and {@link #segmentTermedBuilder()}.
 *
 * <h2>Composite key encoding</h2>
 * <pre>
 *   [4-byte big-endian term length][term bytes][doc-id bytes]
 * </pre>
 * All postings for the same term sort contiguously. A range scan for a term uses:
 * <ul>
 *   <li>{@code scanStart = [termLen_be4][termBytes]} — sorts before all composite keys for this term
 *       because a prefix key sorts before any longer key with the same prefix under unsigned lex order
 *   <li>{@code scanEnd = incrementPrefix(scanStart)} — the lexicographic successor of the prefix;
 *       {@code null} on overflow → fall back to unbounded scan + prefix check in {@link DocIdIterator}
 * </ul>
 */
public final class LsmInvertedIndex {

    private LsmInvertedIndex() {
        throw new UnsupportedOperationException("utility class");
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /** Returns a builder for a {@link InvertedIndex.StringTermed} backed by an {@link LsmTree}. */
    public static <D> StringTermed.Builder<D> stringTermedBuilder() {
        return new StringTermed.Builder<>();
    }

    /** Returns a builder for a {@link InvertedIndex.LongTermed} backed by an {@link LsmTree}. */
    public static <D> LongTermed.Builder<D> longTermedBuilder() {
        return new LongTermed.Builder<>();
    }

    /** Returns a builder for a {@link InvertedIndex.SegmentTermed} backed by an {@link LsmTree}. */
    public static <D> SegmentTermed.Builder<D> segmentTermedBuilder() {
        return new SegmentTermed.Builder<>();
    }

    // -----------------------------------------------------------------------
    // Shared composite-key encoding helpers
    // -----------------------------------------------------------------------

    /**
     * Builds the 4-byte big-endian prefix {@code [termLen | termBytes]}.
     * This sorts before all composite keys for this term.
     */
    static byte[] buildPrefix(byte[] termBytes) {
        assert termBytes != null : "termBytes must not be null";
        byte[] prefix = new byte[4 + termBytes.length];
        int len = termBytes.length;
        prefix[0] = (byte) ((len >>> 24) & 0xFF);
        prefix[1] = (byte) ((len >>> 16) & 0xFF);
        prefix[2] = (byte) ((len >>> 8) & 0xFF);
        prefix[3] = (byte) (len & 0xFF);
        System.arraycopy(termBytes, 0, prefix, 4, termBytes.length);
        return prefix;
    }

    /**
     * Increments {@code prefix} as a big-endian unsigned integer. Returns {@code null} on overflow
     * (all bytes are {@code 0xFF}), indicating an unbounded scan is needed.
     */
    static MemorySegment incrementPrefix(byte[] prefix) {
        assert prefix != null : "prefix must not be null";
        byte[] incremented = prefix.clone();
        for (int i = incremented.length - 1; i >= 0; i--) {
            if ((incremented[i] & 0xFF) < 0xFF) {
                incremented[i]++;
                return MemorySegment.ofArray(incremented);
            }
            incremented[i] = 0;
        }
        return null; // overflow
    }

    /**
     * Builds a composite key {@code [termLen_be4 | termBytes | docIdBytes]}.
     */
    static MemorySegment compositeKey(byte[] termBytes, byte[] docIdBytes) {
        assert termBytes != null : "termBytes must not be null";
        assert docIdBytes != null : "docIdBytes must not be null";
        byte[] prefix = buildPrefix(termBytes);
        byte[] key = new byte[prefix.length + docIdBytes.length];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        System.arraycopy(docIdBytes, 0, key, prefix.length, docIdBytes.length);
        return MemorySegment.ofArray(key);
    }

    /**
     * Encodes a {@code long} term as 8 big-endian bytes with the sign bit flipped so that
     * byte-lexicographic order matches numeric order.
     */
    static byte[] encodeLongTerm(long term) {
        long unsigned = term ^ Long.MIN_VALUE;
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (unsigned & 0xFF);
            unsigned >>>= 8;
        }
        return bytes;
    }

    // -----------------------------------------------------------------------
    // Shared builder base
    // -----------------------------------------------------------------------

    private abstract static class AbstractBuilder<D, B extends AbstractBuilder<D, B>> {

        LsmTree lsmTree;
        MemorySerializer<D> docIdSerializer;

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
    }

    // -----------------------------------------------------------------------
    // DocIdIterator — shared across all three variants
    // -----------------------------------------------------------------------

    /**
     * Iterates over document IDs from an LSM scan iterator, prefix-checking each composite key
     * and skipping tombstones.
     *
     * @param <D> the document ID type
     */
    static final class DocIdIterator<D> implements Iterator<D> {

        private final Iterator<Entry> delegate;
        private final byte[] termPrefix;
        private final MemorySerializer<D> docIdSerializer;
        private D next;
        private boolean done;

        DocIdIterator(Iterator<Entry> delegate, byte[] termPrefix, MemorySerializer<D> docIdSerializer) {
            assert delegate != null : "delegate must not be null";
            assert termPrefix != null : "termPrefix must not be null";
            assert docIdSerializer != null : "docIdSerializer must not be null";
            this.delegate = delegate;
            this.termPrefix = termPrefix;
            this.docIdSerializer = docIdSerializer;
            advance();
        }

        private void advance() {
            next = null;
            while (delegate.hasNext()) {
                Entry entry = delegate.next();
                // Check prefix match
                MemorySegment key = entry.key();
                if (!hasPrefix(key, termPrefix)) {
                    done = true;
                    return;
                }
                // Skip tombstones
                if (entry instanceof Entry.Delete) {
                    continue;
                }
                // Extract docId bytes (everything after the prefix)
                long prefixLen = termPrefix.length;
                long keyLen = key.byteSize();
                assert keyLen > prefixLen : "composite key must be longer than prefix";
                MemorySegment docIdSegment = key.asSlice(prefixLen, keyLen - prefixLen);
                next = docIdSerializer.deserialize(docIdSegment);
                return;
            }
            done = true;
        }

        private static boolean hasPrefix(MemorySegment key, byte[] prefix) {
            if (key.byteSize() < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (key.get(ValueLayout.JAVA_BYTE, i) != prefix[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public D next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            D result = next;
            advance();
            return result;
        }
    }

    // -----------------------------------------------------------------------
    // StringTermed
    // -----------------------------------------------------------------------

    /**
     * An {@link InvertedIndex.StringTermed} that delegates to an {@link LsmTree} and encodes
     * terms as UTF-8 bytes within a composite key.
     *
     * @param <D> the document ID type
     */
    public static final class StringTermed<D> implements InvertedIndex.StringTermed<D> {

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;

        private StringTermed(LsmTree lsmTree, MemorySerializer<D> docIdSerializer) {
            assert lsmTree != null : "lsmTree must not be null";
            assert docIdSerializer != null : "docIdSerializer must not be null";
            this.lsmTree = lsmTree;
            this.docIdSerializer = docIdSerializer;
        }

        @Override
        public void index(D docId, Collection<String> terms) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            for (String term : terms) {
                Objects.requireNonNull(term, "term must not be null");
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                MemorySegment key = compositeKey(termBytes, docIdBytes);
                lsmTree.put(key, MemorySegment.NULL);
            }
        }

        @Override
        public void remove(D docId, Collection<String> terms) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            for (String term : terms) {
                Objects.requireNonNull(term, "term must not be null");
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                MemorySegment key = compositeKey(termBytes, docIdBytes);
                lsmTree.delete(key);
            }
        }

        @Override
        public Iterator<D> lookup(String term) throws IOException {
            Objects.requireNonNull(term, "term must not be null");
            byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
            return doLookup(termBytes);
        }

        private Iterator<D> doLookup(byte[] termBytes) throws IOException {
            byte[] prefix = buildPrefix(termBytes);
            MemorySegment scanEnd = incrementPrefix(prefix);
            Iterator<Entry> entries;
            if (scanEnd != null) {
                entries = lsmTree.scan(MemorySegment.ofArray(prefix), scanEnd);
            } else {
                // Overflow (all-0xFF prefix) — use unbounded scan; DocIdIterator prefix-checks.
                // Unreachable in practice: requires a ~4GB term.
                entries = lsmTree.scan();
            }
            return new DocIdIterator<>(entries, prefix, docIdSerializer);
        }

        @Override
        public void close() throws IOException {
            lsmTree.close();
        }

        /** Builder for {@link LsmInvertedIndex.StringTermed}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            public InvertedIndex.StringTermed<D> build() {
                Objects.requireNonNull(lsmTree, "lsmTree must not be null");
                Objects.requireNonNull(docIdSerializer, "docIdSerializer must not be null");
                return new LsmInvertedIndex.StringTermed<>(lsmTree, docIdSerializer);
            }
        }
    }

    // -----------------------------------------------------------------------
    // LongTermed
    // -----------------------------------------------------------------------

    /**
     * An {@link InvertedIndex.LongTermed} that delegates to an {@link LsmTree} and encodes
     * terms as sign-bit-flipped big-endian 8 bytes within a composite key.
     *
     * @param <D> the document ID type
     */
    public static final class LongTermed<D> implements InvertedIndex.LongTermed<D> {

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;

        private LongTermed(LsmTree lsmTree, MemorySerializer<D> docIdSerializer) {
            assert lsmTree != null : "lsmTree must not be null";
            assert docIdSerializer != null : "docIdSerializer must not be null";
            this.lsmTree = lsmTree;
            this.docIdSerializer = docIdSerializer;
        }

        @Override
        public void index(D docId, Collection<Long> terms) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            for (Long term : terms) {
                Objects.requireNonNull(term, "term must not be null");
                byte[] termBytes = encodeLongTerm(term);
                MemorySegment key = compositeKey(termBytes, docIdBytes);
                lsmTree.put(key, MemorySegment.NULL);
            }
        }

        @Override
        public void remove(D docId, Collection<Long> terms) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            for (Long term : terms) {
                Objects.requireNonNull(term, "term must not be null");
                byte[] termBytes = encodeLongTerm(term);
                MemorySegment key = compositeKey(termBytes, docIdBytes);
                lsmTree.delete(key);
            }
        }

        @Override
        public Iterator<D> lookup(long term) throws IOException {
            byte[] termBytes = encodeLongTerm(term);
            byte[] prefix = buildPrefix(termBytes);
            MemorySegment scanEnd = incrementPrefix(prefix);
            Iterator<Entry> entries;
            if (scanEnd != null) {
                entries = lsmTree.scan(MemorySegment.ofArray(prefix), scanEnd);
            } else {
                // Overflow (all-0xFF prefix) — unreachable in practice (8-byte long term + 4-byte
                // length header = 12 bytes; overflow requires all 12 bytes to be 0xFF).
                entries = lsmTree.scan();
            }
            return new DocIdIterator<>(entries, prefix, docIdSerializer);
        }

        @Override
        public void close() throws IOException {
            lsmTree.close();
        }

        /** Builder for {@link LsmInvertedIndex.LongTermed}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            public InvertedIndex.LongTermed<D> build() {
                Objects.requireNonNull(lsmTree, "lsmTree must not be null");
                Objects.requireNonNull(docIdSerializer, "docIdSerializer must not be null");
                return new LsmInvertedIndex.LongTermed<>(lsmTree, docIdSerializer);
            }
        }
    }

    // -----------------------------------------------------------------------
    // SegmentTermed
    // -----------------------------------------------------------------------

    /**
     * An {@link InvertedIndex.SegmentTermed} that delegates to an {@link LsmTree} and uses
     * raw {@link MemorySegment} bytes as terms within a composite key.
     *
     * @param <D> the document ID type
     */
    public static final class SegmentTermed<D> implements InvertedIndex.SegmentTermed<D> {

        private final LsmTree lsmTree;
        private final MemorySerializer<D> docIdSerializer;

        private SegmentTermed(LsmTree lsmTree, MemorySerializer<D> docIdSerializer) {
            assert lsmTree != null : "lsmTree must not be null";
            assert docIdSerializer != null : "docIdSerializer must not be null";
            this.lsmTree = lsmTree;
            this.docIdSerializer = docIdSerializer;
        }

        @Override
        public void index(D docId, Collection<MemorySegment> terms) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            for (MemorySegment term : terms) {
                Objects.requireNonNull(term, "term must not be null");
                byte[] termBytes = term.toArray(ValueLayout.JAVA_BYTE);
                MemorySegment key = compositeKey(termBytes, docIdBytes);
                lsmTree.put(key, MemorySegment.NULL);
            }
        }

        @Override
        public void remove(D docId, Collection<MemorySegment> terms) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            byte[] docIdBytes = docIdSerializer.serialize(docId).toArray(ValueLayout.JAVA_BYTE);
            for (MemorySegment term : terms) {
                Objects.requireNonNull(term, "term must not be null");
                byte[] termBytes = term.toArray(ValueLayout.JAVA_BYTE);
                MemorySegment key = compositeKey(termBytes, docIdBytes);
                lsmTree.delete(key);
            }
        }

        @Override
        public Iterator<D> lookup(MemorySegment term) throws IOException {
            Objects.requireNonNull(term, "term must not be null");
            byte[] termBytes = term.toArray(ValueLayout.JAVA_BYTE);
            byte[] prefix = buildPrefix(termBytes);
            MemorySegment scanEnd = incrementPrefix(prefix);
            Iterator<Entry> entries;
            if (scanEnd != null) {
                entries = lsmTree.scan(MemorySegment.ofArray(prefix), scanEnd);
            } else {
                // Overflow (all-0xFF prefix) — use unbounded scan; DocIdIterator prefix-checks.
                // Unreachable in practice: requires a ~4GB term.
                entries = lsmTree.scan();
            }
            return new DocIdIterator<>(entries, prefix, docIdSerializer);
        }

        @Override
        public void close() throws IOException {
            lsmTree.close();
        }

        /** Builder for {@link LsmInvertedIndex.SegmentTermed}. */
        public static final class Builder<D> extends AbstractBuilder<D, Builder<D>> {

            public InvertedIndex.SegmentTermed<D> build() {
                Objects.requireNonNull(lsmTree, "lsmTree must not be null");
                Objects.requireNonNull(docIdSerializer, "docIdSerializer must not be null");
                return new LsmInvertedIndex.SegmentTermed<>(lsmTree, docIdSerializer);
            }
        }
    }
}
