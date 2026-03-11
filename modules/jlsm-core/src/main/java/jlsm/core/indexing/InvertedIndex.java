package jlsm.core.indexing;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.Iterator;

/**
 * A probabilistic-membership-free inverted index that maps terms to sets of document IDs (postings
 * lists), enabling full-text search and column-property lookups.
 *
 * <p>
 * The term type is fixed by each sub-interface; the document ID type {@code D} is a parameter of
 * the concrete variant.
 *
 * @param <D> the document ID type
 */
public sealed interface InvertedIndex<D> extends Closeable
        permits InvertedIndex.StringTermed, InvertedIndex.LongTermed, InvertedIndex.SegmentTermed {

    @Override
    void close() throws IOException;

    /** Inverted index whose terms are UTF-8 {@link String} values. */
    non-sealed interface StringTermed<D> extends InvertedIndex<D> {
        /**
         * Associates {@code docId} with each term in {@code terms}.
         *
         * @param docId the document ID to index; must not be null
         * @param terms the terms to associate with the document; must not be null or empty
         * @throws IOException if the underlying store cannot be written to
         */
        void index(D docId, Collection<String> terms) throws IOException;

        /**
         * Removes the association between {@code docId} and each term in {@code terms}.
         *
         * @param docId the document ID to remove; must not be null
         * @param terms the terms to disassociate from the document; must not be null or empty
         * @throws IOException if the underlying store cannot be written to
         */
        void remove(D docId, Collection<String> terms) throws IOException;

        /**
         * Returns an iterator over all document IDs associated with {@code term}.
         *
         * @param term the term to look up; must not be null
         * @return an iterator over matching document IDs; empty if the term has no postings
         * @throws IOException if the underlying store cannot be read
         */
        Iterator<D> lookup(String term) throws IOException;
    }

    /**
     * Inverted index whose terms are {@code long} primitives. Term bytes are sign-bit-flipped
     * big-endian 8 bytes so byte order matches numeric order (same as TypedLsmTree.LongKeyed).
     */
    non-sealed interface LongTermed<D> extends InvertedIndex<D> {
        /**
         * Associates {@code docId} with each term in {@code terms}.
         *
         * @param docId the document ID to index; must not be null
         * @param terms the long terms to associate with the document; must not be null or empty
         * @throws IOException if the underlying store cannot be written to
         */
        void index(D docId, Collection<Long> terms) throws IOException;

        /**
         * Removes the association between {@code docId} and each term in {@code terms}.
         *
         * @param docId the document ID to remove; must not be null
         * @param terms the long terms to disassociate from the document; must not be null or empty
         * @throws IOException if the underlying store cannot be written to
         */
        void remove(D docId, Collection<Long> terms) throws IOException;

        /**
         * Returns an iterator over all document IDs associated with {@code term}.
         *
         * @param term the long term to look up
         * @return an iterator over matching document IDs; empty if the term has no postings
         * @throws IOException if the underlying store cannot be read
         */
        Iterator<D> lookup(long term) throws IOException;
    }

    /** Inverted index whose terms are raw {@link MemorySegment} values. */
    non-sealed interface SegmentTermed<D> extends InvertedIndex<D> {
        /**
         * Associates {@code docId} with each term in {@code terms}.
         *
         * @param docId the document ID to index; must not be null
         * @param terms the segment terms to associate with the document; must not be null or empty
         * @throws IOException if the underlying store cannot be written to
         */
        void index(D docId, Collection<MemorySegment> terms) throws IOException;

        /**
         * Removes the association between {@code docId} and each term in {@code terms}.
         *
         * @param docId the document ID to remove; must not be null
         * @param terms the segment terms to disassociate from the document; must not be null or
         *            empty
         * @throws IOException if the underlying store cannot be written to
         */
        void remove(D docId, Collection<MemorySegment> terms) throws IOException;

        /**
         * Returns an iterator over all document IDs associated with {@code term}.
         *
         * @param term the segment term to look up; must not be null
         * @return an iterator over matching document IDs; empty if the term has no postings
         * @throws IOException if the underlying store cannot be read
         */
        Iterator<D> lookup(MemorySegment term) throws IOException;
    }
}
