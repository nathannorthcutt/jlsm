package jlsm.indexing;

import jlsm.core.indexing.FilteringTokenizer;
import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.InvertedIndex;
import jlsm.core.indexing.Query;
import jlsm.core.indexing.Stemmer;
import jlsm.core.indexing.StemmingTokenizer;
import jlsm.core.indexing.Tokenizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Non-instantiable namespace class containing the {@link LsmFullTextIndex.Impl} implementation of
 * {@link FullTextIndex}, which layers tokenization, multi-field support, and a composable query DSL
 * on top of an {@link InvertedIndex.StringTermed}.
 *
 * <p>
 * Obtain instances via {@link #builder()}.
 *
 * <h2>Field+term encoding</h2>
 *
 * <pre>
 *   qualified term = field + '\0' + token
 * </pre>
 *
 * The null-byte separator is safe because:
 * <ul>
 * <li>Field names are validated to not contain {@code '\0'}
 * <li>The default tokenizer ({@link WhitespaceTokenizer}) produces only lowercase alphanumeric
 * tokens
 * </ul>
 */
public final class LsmFullTextIndex {

    /** Separator between field name and token in the qualified term. */
    static final char FIELD_TERM_SEPARATOR = '\0';

    private LsmFullTextIndex() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Returns a builder for a {@link Impl}.
     *
     * @param <D> the document ID type
     * @return a new builder
     */
    public static <D> Impl.Builder<D> builder() {
        return new Impl.Builder<>();
    }

    // -----------------------------------------------------------------------
    // WhitespaceTokenizer
    // -----------------------------------------------------------------------

    /**
     * Default tokenizer: splits on whitespace, strips non-letter/digit characters, lowercases, and
     * drops empty tokens.
     */
    static final class WhitespaceTokenizer implements Tokenizer {

        static final WhitespaceTokenizer INSTANCE = new WhitespaceTokenizer();

        private WhitespaceTokenizer() {
        }

        @Override
        public Collection<String> tokenize(String text) {
            assert text != null : "text must not be null";
            String[] parts = text.trim().split("\\s+");
            List<String> tokens = new ArrayList<>(parts.length);
            for (String part : parts) {
                String token = part.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            return tokens;
        }
    }

    // -----------------------------------------------------------------------
    // Impl
    // -----------------------------------------------------------------------

    /**
     * {@link FullTextIndex} implementation backed by an {@link InvertedIndex.StringTermed}.
     *
     * @param <D> the document ID type
     */
    public static final class Impl<D> implements FullTextIndex<D> {

        private static final int DEFAULT_MAX_QUERY_DEPTH = 64;

        private final InvertedIndex.StringTermed<D> invertedIndex;
        private final Tokenizer tokenizer;
        private final Stemmer stemmer;
        private final Set<String> stopWords;
        private final int maxQueryDepth;

        private Impl(InvertedIndex.StringTermed<D> invertedIndex, Tokenizer tokenizer,
                Stemmer stemmer, Set<String> stopWords, int maxQueryDepth) {
            assert invertedIndex != null : "invertedIndex must not be null";
            assert tokenizer != null : "tokenizer must not be null";
            assert maxQueryDepth > 0 : "maxQueryDepth must be positive";
            this.invertedIndex = invertedIndex;
            this.tokenizer = tokenizer;
            this.stemmer = stemmer;
            this.stopWords = stopWords;
            this.maxQueryDepth = maxQueryDepth;
        }

        @Override
        public void index(D docId, Map<String, String> fields) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(fields, "fields must not be null");
            List<String> qualifiedTerms = buildQualifiedTerms(fields);
            invertedIndex.index(docId, qualifiedTerms);
        }

        @Override
        public void remove(D docId, Map<String, String> fields) throws IOException {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(fields, "fields must not be null");
            List<String> qualifiedTerms = buildQualifiedTerms(fields);
            invertedIndex.remove(docId, qualifiedTerms);
        }

        @Override
        public Iterator<D> search(Query query) throws IOException {
            Objects.requireNonNull(query, "query must not be null");
            return evaluate(query, 0).iterator();
        }

        @Override
        public void close() throws IOException {
            invertedIndex.close();
        }

        private List<String> buildQualifiedTerms(Map<String, String> fields) {
            List<String> qualifiedTerms = new ArrayList<>();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String field = entry.getKey();
                validateFieldName(field);
                String text = entry.getValue();
                Collection<String> tokens = tokenizer.tokenize(text);
                for (String token : tokens) {
                    qualifiedTerms.add(field + FIELD_TERM_SEPARATOR + token);
                }
            }
            return qualifiedTerms;
        }

        private static void validateFieldName(String field) {
            Objects.requireNonNull(field, "field must not be null");
            if (field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
            if (field.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "field must not contain the null character '\\0'");
            }
        }

        // Recursion is bounded by maxQueryDepth (configurable, default 64) per coding standards:
        // an explicit depth limit is enforced and a clear exception is thrown on exhaustion.
        private Set<D> evaluate(Query query, int depth) throws IOException {
            if (depth > maxQueryDepth) {
                throw new IllegalStateException("max query depth " + maxQueryDepth + " exceeded");
            }
            return switch (query) {
                case Query.TermQuery tq -> {
                    validateFieldName(tq.field());
                    String term = stemmer != null ? stemmer.stem(tq.term()) : tq.term();
                    // Stop word in query → term was never indexed; skip the lookup
                    if (stopWords != null && stopWords.contains(term)) {
                        yield Set.of();
                    }
                    String qualified = tq.field() + FIELD_TERM_SEPARATOR + term;
                    Iterator<D> it = invertedIndex.lookup(qualified);
                    Set<D> result = new HashSet<>();
                    it.forEachRemaining(result::add);
                    yield result;
                }
                case Query.AndQuery aq -> {
                    Set<D> left = evaluate(aq.left(), depth + 1);
                    Set<D> right = evaluate(aq.right(), depth + 1);
                    left.retainAll(right);
                    yield left;
                }
                case Query.OrQuery oq -> {
                    Set<D> left = evaluate(oq.left(), depth + 1);
                    Set<D> right = evaluate(oq.right(), depth + 1);
                    left.addAll(right);
                    yield left;
                }
                case Query.NotQuery nq -> {
                    Set<D> include = evaluate(nq.include(), depth + 1);
                    Set<D> exclude = evaluate(nq.exclude(), depth + 1);
                    include.removeAll(exclude);
                    yield include;
                }
            };
        }

        // -----------------------------------------------------------------------
        // Builder
        // -----------------------------------------------------------------------

        /** Builder for {@link LsmFullTextIndex.Impl}. */
        public static final class Builder<D> {

            private InvertedIndex.StringTermed<D> invertedIndex;
            private Tokenizer tokenizer = WhitespaceTokenizer.INSTANCE;
            private int maxQueryDepth = DEFAULT_MAX_QUERY_DEPTH;
            private Stemmer stemmer = null;
            private Set<String> stopWords = null;

            public Builder<D> invertedIndex(InvertedIndex.StringTermed<D> invertedIndex) {
                this.invertedIndex = Objects.requireNonNull(invertedIndex,
                        "invertedIndex must not be null");
                return this;
            }

            public Builder<D> tokenizer(Tokenizer tokenizer) {
                this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer must not be null");
                return this;
            }

            public Builder<D> stemmer(Stemmer stemmer) {
                this.stemmer = Objects.requireNonNull(stemmer, "stemmer must not be null");
                return this;
            }

            public Builder<D> stopWords(Set<String> stopWords) {
                this.stopWords = Objects.requireNonNull(stopWords, "stopWords must not be null");
                return this;
            }

            public Builder<D> maxQueryDepth(int maxQueryDepth) {
                if (maxQueryDepth <= 0) {
                    throw new IllegalArgumentException(
                            "maxQueryDepth must be positive, got: " + maxQueryDepth);
                }
                this.maxQueryDepth = maxQueryDepth;
                return this;
            }

            public FullTextIndex<D> build() {
                Objects.requireNonNull(invertedIndex, "invertedIndex must not be null");
                Tokenizer effectiveTokenizer = tokenizer;
                if (stopWords != null) {
                    effectiveTokenizer = new FilteringTokenizer(effectiveTokenizer, stopWords);
                }
                if (stemmer != null) {
                    effectiveTokenizer = new StemmingTokenizer(effectiveTokenizer, stemmer);
                }
                return new Impl<>(invertedIndex, effectiveTokenizer, stemmer,
                        stopWords != null ? Set.copyOf(stopWords) : null, maxQueryDepth);
            }
        }
    }
}
