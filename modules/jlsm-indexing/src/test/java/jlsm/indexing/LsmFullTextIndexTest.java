package jlsm.indexing;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.indexing.EnglishStopWords;
import jlsm.core.indexing.FullTextIndex;
import jlsm.core.indexing.PorterStemmer;
import jlsm.core.indexing.Query;
import jlsm.core.indexing.Query.AndQuery;
import jlsm.core.indexing.Query.NotQuery;
import jlsm.core.indexing.Query.OrQuery;
import jlsm.core.indexing.Query.TermQuery;
import jlsm.core.indexing.Tokenizer;
import jlsm.core.io.MemorySerializer;
import jlsm.core.tree.LsmTree;
import jlsm.memtable.ConcurrentSkipListMemTable;
import jlsm.sstable.TrieSSTableReader;
import jlsm.sstable.TrieSSTableWriter;
import jlsm.tree.SSTableReaderFactory;
import jlsm.tree.SSTableWriterFactory;
import jlsm.tree.StandardLsmTree;
import jlsm.wal.local.LocalWriteAheadLog;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LsmFullTextIndexTest {

    @TempDir
    Path tempDir;

    private final AtomicLong idCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
            for (byte b : bytes) {
                v = (v << 8) | (b & 0xFFL);
            }
            return v;
        }
    };

    private LsmTree buildTree(long flushThreshold) throws IOException {
        return StandardLsmTree.builder()
                .wal(LocalWriteAheadLog.builder().directory(tempDir).build())
                .memTableFactory(ConcurrentSkipListMemTable::new)
                .sstableWriterFactory((SSTableWriterFactory) (id, level,
                        path) -> new TrieSSTableWriter(id, level, path))
                .sstableReaderFactory((SSTableReaderFactory) path -> TrieSSTableReader.open(path,
                        BlockedBloomFilter.deserializer()))
                .idSupplier(idCounter::getAndIncrement)
                .pathFn((id, level) -> tempDir.resolve("sst-" + id + "-L" + level.index() + ".sst"))
                .memTableFlushThresholdBytes(flushThreshold).build();
    }

    private FullTextIndex<Long> buildIndex(long flushThreshold) throws IOException {
        return LsmFullTextIndex.<Long>builder()
                .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                        .lsmTree(buildTree(flushThreshold)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .build())
                .build();
    }

    private static <D> List<D> drain(Iterator<D> it) {
        List<D> result = new ArrayList<>();
        it.forEachRemaining(result::add);
        return result;
    }

    // -----------------------------------------------------------------------
    // Tokenizer tests (WhitespaceTokenizer behaviour via index+search)
    // -----------------------------------------------------------------------

    @Test
    void tokenizer_emptyStringProducesNoTokens() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", ""));
            List<Long> results = drain(index.search(new TermQuery("body", "")));
            assertTrue(results.isEmpty(), "empty token should not match");
        }
    }

    @Test
    void tokenizer_whitespacePaddedStringIsTokenized() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "  hello world  "));
            assertFalse(drain(index.search(new TermQuery("body", "hello"))).isEmpty());
            assertFalse(drain(index.search(new TermQuery("body", "world"))).isEmpty());
        }
    }

    @Test
    void tokenizer_uppercaseNormalized() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("title", "JAVA"));
            assertFalse(drain(index.search(new TermQuery("title", "java"))).isEmpty());
        }
    }

    @Test
    void tokenizer_punctuationStripped() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "Hello, World!"));
            assertFalse(drain(index.search(new TermQuery("body", "hello"))).isEmpty());
            assertFalse(drain(index.search(new TermQuery("body", "world"))).isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // TermQuery tests
    // -----------------------------------------------------------------------

    @Test
    void termQuery_indexAndSearchReturnsDoc() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "hello world"));
            List<Long> results = drain(index.search(new TermQuery("body", "hello")));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void termQuery_missingTermReturnsEmpty() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "hello"));
            List<Long> results = drain(index.search(new TermQuery("body", "missing")));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void termQuery_wrongFieldReturnsEmpty() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("title", "hello"));
            List<Long> results = drain(index.search(new TermQuery("body", "hello")));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void termQuery_multipleDocsForSameTerm() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "cat"));
            index.index(2L, Map.of("body", "cat"));
            index.index(3L, Map.of("body", "cat"));
            List<Long> results = drain(index.search(new TermQuery("body", "cat")));
            assertEquals(3, results.size());
            assertTrue(results.containsAll(List.of(1L, 2L, 3L)));
        }
    }

    @Test
    void termQuery_multipleFields() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("title", "java", "body", "programming"));
            assertFalse(drain(index.search(new TermQuery("title", "java"))).isEmpty());
            assertFalse(drain(index.search(new TermQuery("body", "programming"))).isEmpty());
            assertTrue(drain(index.search(new TermQuery("title", "programming"))).isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Remove tests
    // -----------------------------------------------------------------------

    @Test
    void remove_deindexedDocNotReturned() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "hello"));
            index.remove(1L, Map.of("body", "hello"));
            List<Long> results = drain(index.search(new TermQuery("body", "hello")));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void remove_retokenizesOriginalText() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "Hello World!"));
            index.remove(1L, Map.of("body", "Hello World!"));
            assertTrue(drain(index.search(new TermQuery("body", "hello"))).isEmpty());
            assertTrue(drain(index.search(new TermQuery("body", "world"))).isEmpty());
        }
    }

    @Test
    void remove_partialFieldRemove() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("title", "java", "body", "programming"));
            index.remove(1L, Map.of("title", "java"));
            assertTrue(drain(index.search(new TermQuery("title", "java"))).isEmpty());
            assertFalse(drain(index.search(new TermQuery("body", "programming"))).isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // AndQuery tests
    // -----------------------------------------------------------------------

    @Test
    void andQuery_bothPresent() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java programming"));
            List<Long> results = drain(index.search(new AndQuery(new TermQuery("body", "java"),
                    new TermQuery("body", "programming"))));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void andQuery_missingLeft() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "programming"));
            List<Long> results = drain(index.search(new AndQuery(new TermQuery("body", "java"),
                    new TermQuery("body", "programming"))));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void andQuery_missingRight() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java"));
            List<Long> results = drain(index.search(new AndQuery(new TermQuery("body", "java"),
                    new TermQuery("body", "programming"))));
            assertTrue(results.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // OrQuery tests
    // -----------------------------------------------------------------------

    @Test
    void orQuery_eitherPresent() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java"));
            List<Long> results = drain(index.search(
                    new OrQuery(new TermQuery("body", "java"), new TermQuery("body", "python"))));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void orQuery_bothPresent_deduped() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java python"));
            List<Long> results = drain(index.search(
                    new OrQuery(new TermQuery("body", "java"), new TermQuery("body", "python"))));
            // doc 1 appears in both sets — must appear exactly once
            assertEquals(1, results.size());
            assertTrue(results.contains(1L));
        }
    }

    @Test
    void orQuery_neitherPresent_empty() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "scala"));
            List<Long> results = drain(index.search(
                    new OrQuery(new TermQuery("body", "java"), new TermQuery("body", "python"))));
            assertTrue(results.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // NotQuery tests
    // -----------------------------------------------------------------------

    @Test
    void notQuery_includeMinusExclude() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java"));
            index.index(2L, Map.of("body", "java python"));
            List<Long> results = drain(index.search(
                    new NotQuery(new TermQuery("body", "java"), new TermQuery("body", "python"))));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void notQuery_emptyExclude() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java"));
            List<Long> results = drain(index.search(
                    new NotQuery(new TermQuery("body", "java"), new TermQuery("body", "absent"))));
            assertEquals(List.of(1L), results);
        }
    }

    @Test
    void notQuery_emptyInclude() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            List<Long> results = drain(index.search(
                    new NotQuery(new TermQuery("body", "absent"), new TermQuery("body", "java"))));
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void notQuery_fullOverlapEmpty() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java"));
            List<Long> results = drain(index.search(
                    new NotQuery(new TermQuery("body", "java"), new TermQuery("body", "java"))));
            assertTrue(results.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Nested query tests
    // -----------------------------------------------------------------------

    @Test
    void nested_andOfOr() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java python"));
            index.index(2L, Map.of("body", "java scala"));
            // (java OR python) AND (java OR scala)
            Query q = new AndQuery(
                    new OrQuery(new TermQuery("body", "java"), new TermQuery("body", "python")),
                    new OrQuery(new TermQuery("body", "java"), new TermQuery("body", "scala")));
            List<Long> results = drain(index.search(q));
            assertEquals(2, results.size());
            assertTrue(results.containsAll(List.of(1L, 2L)));
        }
    }

    @Test
    void nested_orOfAnd() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java programming"));
            index.index(2L, Map.of("body", "python scripting"));
            // (java AND programming) OR (python AND scripting)
            Query q = new OrQuery(
                    new AndQuery(new TermQuery("body", "java"),
                            new TermQuery("body", "programming")),
                    new AndQuery(new TermQuery("body", "python"),
                            new TermQuery("body", "scripting")));
            List<Long> results = drain(index.search(q));
            assertEquals(2, results.size());
            assertTrue(results.containsAll(List.of(1L, 2L)));
        }
    }

    @Test
    void nested_notWithAndInclude() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            index.index(1L, Map.of("body", "java programming"));
            index.index(2L, Map.of("body", "java scripting"));
            // (java AND programming) NOT scripting
            Query q = new NotQuery(
                    new AndQuery(new TermQuery("body", "java"),
                            new TermQuery("body", "programming")),
                    new TermQuery("body", "scripting"));
            List<Long> results = drain(index.search(q));
            assertEquals(List.of(1L), results);
        }
    }

    // -----------------------------------------------------------------------
    // Depth limit
    // -----------------------------------------------------------------------

    @Test
    void depthLimit_chainExceedingMaxDepthThrows() throws IOException {
        // Build a chain of AndQuery deeper than maxQueryDepth (default 64)
        Query q = new TermQuery("body", "leaf");
        for (int i = 0; i < 65; i++) {
            q = new AndQuery(new TermQuery("body", "leaf"), q);
        }
        final Query deepQuery = q;
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            assertThrows(IllegalStateException.class, () -> index.search(deepQuery));
        }
    }

    @Test
    void depthLimit_customMaxDepth() throws IOException {
        // A chain of depth 3 should work with maxQueryDepth=64 but fail with maxQueryDepth=2
        Query q = new AndQuery(new TermQuery("body", "a"),
                new AndQuery(new TermQuery("body", "b"), new TermQuery("body", "c")));
        // maxQueryDepth=1 should fail (depth reaches 2)
        try (FullTextIndex<Long> index = LsmFullTextIndex.<Long>builder()
                .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                        .lsmTree(buildTree(Long.MAX_VALUE)).docIdSerializer(LONG_DOC_ID_SERIALIZER)
                        .build())
                .maxQueryDepth(1).build()) {
            assertThrows(IllegalStateException.class, () -> index.search(q));
        }
    }

    // -----------------------------------------------------------------------
    // Builder validation
    // -----------------------------------------------------------------------

    @Test
    void builder_nullInvertedIndexAtSetTimeThrows() {
        assertThrows(NullPointerException.class,
                () -> LsmFullTextIndex.<Long>builder().invertedIndex(null));
    }

    @Test
    void builder_nullInvertedIndexAtBuildTimeThrows() {
        assertThrows(NullPointerException.class, () -> LsmFullTextIndex.<Long>builder().build());
    }

    @Test
    void builder_nullTokenizerThrows() {
        assertThrows(NullPointerException.class,
                () -> LsmFullTextIndex.<Long>builder().tokenizer(null));
    }

    @Test
    void builder_invalidMaxQueryDepthThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> LsmFullTextIndex.<Long>builder().maxQueryDepth(0));
        assertThrows(IllegalArgumentException.class,
                () -> LsmFullTextIndex.<Long>builder().maxQueryDepth(-1));
    }

    // -----------------------------------------------------------------------
    // Field validation
    // -----------------------------------------------------------------------

    @Test
    void fieldValidation_blankFieldInTermQueryThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TermQuery("  ", "term"));
    }

    @Test
    void fieldValidation_emptyFieldInTermQueryThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TermQuery("", "term"));
    }

    @Test
    void fieldValidation_nullByteInFieldThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TermQuery("fie\0ld", "term"));
    }

    @Test
    void fieldValidation_blankFieldInIndexThrows() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, Map.of("  ", "text")));
        }
    }

    @Test
    void fieldValidation_nullByteInFieldInIndexThrows() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> index.index(1L, Map.of("fie\0ld", "text")));
        }
    }

    // -----------------------------------------------------------------------
    // Flush
    // -----------------------------------------------------------------------

    @Test
    void flush_searchAfterForcedFlushReturnsCorrectResults() throws IOException {
        try (FullTextIndex<Long> index = buildIndex(1L)) {
            index.index(1L, Map.of("body", "java programming"));
            index.index(2L, Map.of("body", "java scripting"));
            List<Long> results = drain(index.search(new TermQuery("body", "java")));
            assertEquals(2, results.size());
            assertTrue(results.containsAll(List.of(1L, 2L)));
        }
    }

    // -----------------------------------------------------------------------
    // Stemming tests
    // -----------------------------------------------------------------------

    @Nested
    class Stemming {

        private FullTextIndex<Long> buildStemmedIndex(long flushThreshold) throws IOException {
            return LsmFullTextIndex.<Long>builder()
                    .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                            .lsmTree(buildTree(flushThreshold))
                            .docIdSerializer(LONG_DOC_ID_SERIALIZER).build())
                    .stemmer(PorterStemmer.INSTANCE).build();
        }

        @Test
        void stemming_runningIndexedSearchRunFindsDoc() throws IOException {
            try (FullTextIndex<Long> index = buildStemmedIndex(Long.MAX_VALUE)) {
                index.index(1L, Map.of("body", "running"));
                List<Long> results = drain(index.search(new TermQuery("body", "run")));
                assertFalse(results.isEmpty(),
                        "searching 'run' should find doc indexed under 'running'");
                assertTrue(results.contains(1L));
            }
        }

        @Test
        void stemming_catsIndexedSearchCatFindsDoc() throws IOException {
            try (FullTextIndex<Long> index = buildStemmedIndex(Long.MAX_VALUE)) {
                index.index(1L, Map.of("body", "cats"));
                List<Long> results = drain(index.search(new TermQuery("body", "cat")));
                assertFalse(results.isEmpty(),
                        "searching 'cat' should find doc indexed under 'cats'");
                assertTrue(results.contains(1L));
            }
        }

        @Test
        void stemming_generalizationsIndexedSearchGeneralFindsDoc() throws IOException {
            try (FullTextIndex<Long> index = buildStemmedIndex(Long.MAX_VALUE)) {
                index.index(1L, Map.of("body", "generalizations"));
                List<Long> results = drain(index.search(new TermQuery("body", "general")));
                assertFalse(results.isEmpty(),
                        "searching 'general' should find doc indexed under 'generalizations'");
                assertTrue(results.contains(1L));
            }
        }

        @Test
        void noStemmer_runningIndexedSearchRunNotFound() throws IOException {
            // Regression guard: without a stemmer, 'run' must NOT match 'running'.
            // Also verifies the positive case: WITH a stemmer the same doc IS found.
            // The second assertion requires the PorterStemmer + StemmingTokenizer to be wired,
            // so this test will fail until both are implemented.
            try (FullTextIndex<Long> noStemIndex = buildIndex(Long.MAX_VALUE)) {
                noStemIndex.index(1L, Map.of("body", "running"));
                List<Long> noStemResults = drain(noStemIndex.search(new TermQuery("body", "run")));
                assertTrue(noStemResults.isEmpty(),
                        "without stemmer, 'run' must not match 'running'");
            }
            try (FullTextIndex<Long> stemIndex = buildStemmedIndex(Long.MAX_VALUE)) {
                stemIndex.index(2L, Map.of("body", "running"));
                List<Long> stemResults = drain(stemIndex.search(new TermQuery("body", "run")));
                assertFalse(stemResults.isEmpty(), "with stemmer, 'run' must match 'running'");
            }
        }

        @Test
        void builder_nullStemmerThrowsNullPointerException() throws IOException {
            // Null stemmer must be rejected at setter time
            assertThrows(NullPointerException.class,
                    () -> LsmFullTextIndex.<Long>builder().stemmer(null));
            // Valid stemmer must actually wire through: index "running", search "run"
            try (FullTextIndex<Long> index = buildStemmedIndex(Long.MAX_VALUE)) {
                index.index(99L, Map.of("body", "running"));
                List<Long> results = drain(index.search(new TermQuery("body", "run")));
                assertFalse(results.isEmpty(),
                        "stemmer set via builder must be applied during indexing");
            }
        }

        @Test
        void stemming_explicitTokenizerAndStemmerCombined() throws IOException {
            // A tokenizer that splits on commas (different from the default whitespace split)
            Tokenizer commaSplit = text -> {
                String[] parts = text.split(",");
                List<String> result = new ArrayList<>();
                for (String part : parts) {
                    String trimmed = part.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
                return result;
            };

            try (FullTextIndex<Long> index = LsmFullTextIndex.<Long>builder()
                    .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                            .lsmTree(buildTree(Long.MAX_VALUE))
                            .docIdSerializer(LONG_DOC_ID_SERIALIZER).build())
                    .tokenizer(commaSplit).stemmer(PorterStemmer.INSTANCE).build()) {
                // Text split by comma → ["running", "cats"], each then stemmed
                index.index(1L, Map.of("body", "running,cats"));
                // Standard whitespace tokenizer would treat "running,cats" as one token;
                // the comma tokenizer separates them, then Porter stems each
                assertFalse(drain(index.search(new TermQuery("body", "run"))).isEmpty(),
                        "comma tokenizer + stemmer: 'run' should match 'running'");
                assertFalse(drain(index.search(new TermQuery("body", "cat"))).isEmpty(),
                        "comma tokenizer + stemmer: 'cat' should match 'cats'");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Stop word tests
    // -----------------------------------------------------------------------

    @Nested
    class StopWords {

        private FullTextIndex<Long> buildIndexWithEnglishStopWords(long flushThreshold)
                throws IOException {
            return LsmFullTextIndex.<Long>builder()
                    .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                            .lsmTree(buildTree(flushThreshold))
                            .docIdSerializer(LONG_DOC_ID_SERIALIZER).build())
                    .stopWords(EnglishStopWords.WORDS).build();
        }

        @Test
        void stopWords_contentWordIsIndexed_searchFindsDoc() throws IOException {
            // "the cat" → only "cat" indexed; searching "cat" finds doc
            try (FullTextIndex<Long> index = buildIndexWithEnglishStopWords(Long.MAX_VALUE)) {
                index.index(1L, Map.of("body", "the cat"));
                List<Long> results = drain(index.search(new TermQuery("body", "cat")));
                assertFalse(results.isEmpty(), "'cat' should be found");
                assertTrue(results.contains(1L));
            }
        }

        /**
         * Since {@link EnglishStopWords#WORDS} is currently empty, "the" IS indexed and this search
         * returns doc 1 — so this assertion FAILS before implementation. That is correct.
         */
        @Test
        void stopWords_stopWordIsNotIndexed_searchReturnsEmpty() throws IOException {
            // "the cat" → "the" not indexed; searching "the" returns empty
            try (FullTextIndex<Long> index = buildIndexWithEnglishStopWords(Long.MAX_VALUE)) {
                index.index(1L, Map.of("body", "the cat"));
                List<Long> results = drain(index.search(new TermQuery("body", "the")));
                assertTrue(results.isEmpty(), "'the' is a stop word and should not be findable");
            }
        }

        @Test
        void stopWords_noFilterConfigured_stopWordIsIndexed_searchFindsDoc() throws IOException {
            // Regression guard: without stop word filtering, "the" IS indexed and searchable
            try (FullTextIndex<Long> index = buildIndex(Long.MAX_VALUE)) {
                index.index(1L, Map.of("body", "the cat"));
                List<Long> results = drain(index.search(new TermQuery("body", "the")));
                assertFalse(results.isEmpty(), "without stop words, 'the' should be indexed");
            }
        }

        @Test
        void stopWords_nullStopWordsThrowsNPE() {
            assertThrows(NullPointerException.class,
                    () -> LsmFullTextIndex.<Long>builder().stopWords(null));
        }

        /**
         * Since {@link EnglishStopWords#WORDS} is currently empty, "the" will NOT be filtered
         * during indexing, so the final {@code assertTrue(... "the" ...isEmpty())} will FAIL. That
         * is correct — the test confirms failure before implementation.
         */
        @Test
        void stopWords_withStemmer_runningCatsIndexedCorrectly() throws IOException {
            // Tokens from "the running cats" with English stop words + Porter stemmer:
            // "the" → filtered; "running" → stemmed to "run"; "cats" → stemmed to "cat"
            try (FullTextIndex<Long> index = LsmFullTextIndex.<Long>builder()
                    .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                            .lsmTree(buildTree(Long.MAX_VALUE))
                            .docIdSerializer(LONG_DOC_ID_SERIALIZER).build())
                    .stopWords(EnglishStopWords.WORDS).stemmer(PorterStemmer.INSTANCE).build()) {
                index.index(1L, Map.of("body", "the running cats"));
                assertFalse(drain(index.search(new TermQuery("body", "run"))).isEmpty(),
                        "'run' should match 'running' after stemming");
                assertFalse(drain(index.search(new TermQuery("body", "cat"))).isEmpty(),
                        "'cat' should match 'cats' after stemming");
                assertTrue(drain(index.search(new TermQuery("body", "the"))).isEmpty(),
                        "'the' is a stop word and must not be in the index");
            }
        }

        @Test
        void stopWords_customStopWordSet_customWordFiltered() throws IOException {
            // Use a custom set {"foo"} — "foo" should not be indexed, "bar" should be
            try (FullTextIndex<Long> index = LsmFullTextIndex.<Long>builder()
                    .invertedIndex(LsmInvertedIndex.<Long>stringTermedBuilder()
                            .lsmTree(buildTree(Long.MAX_VALUE))
                            .docIdSerializer(LONG_DOC_ID_SERIALIZER).build())
                    .stopWords(Set.of("foo")).build()) {
                index.index(1L, Map.of("body", "foo bar"));
                assertTrue(drain(index.search(new TermQuery("body", "foo"))).isEmpty(),
                        "'foo' is in the custom stop word set and must not be indexed");
                assertFalse(drain(index.search(new TermQuery("body", "bar"))).isEmpty(),
                        "'bar' is not a stop word and must be indexed");
            }
        }
    }
}
