package jlsm.core.indexing;

import java.util.Set;

/**
 * Utility class providing a curated set of English stop words suitable for use with
 * {@link FilteringTokenizer}.
 *
 * <p>
 * Stop words are common words (articles, prepositions, conjunctions, etc.) that carry little
 * lexical meaning and are typically excluded from full-text index terms to reduce index size and
 * improve search precision.
 */
public final class EnglishStopWords {

    private EnglishStopWords() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Common English stop words suitable for full-text search indexing.
     *
     * <p>
     * Based on the Snowball English stop word list, which is the canonical list used by Lucene,
     * Elasticsearch, and other search engines. BSD licensed; original author: Martin Porter.
     */
    public static final Set<String> WORDS = Set.of(
            // Personal pronouns
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your", "yours",
            "yourself", "yourselves", "he", "him", "his", "himself", "she", "her", "hers",
            "herself", "it", "its", "itself", "they", "them", "their", "theirs", "themselves",
            // Interrogatives/demonstratives
            "what", "which", "who", "whom", "this", "that", "these", "those",
            // Auxiliary verbs
            "am", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "having",
            "do", "does", "did", "doing", "will", "would", "shall", "should", "may", "might",
            "must", "can", "could",
            // Articles/prepositions
            "a", "an", "the", "of", "at", "by", "for", "with", "about", "against", "between",
            "into", "through", "during", "before", "after", "above", "below", "to", "from", "up",
            "down", "in", "out", "on", "off", "over", "under", "as", "until", "while",
            // Conjunctions/adverbs
            "and", "but", "if", "or", "so", "nor", "not", "no", "than", "too", "very", "just",
            "own", "same", "again", "further", "then", "once", "here", "there", "when", "where",
            "why", "how", "all", "both", "each", "few", "more", "most", "other", "some", "such",
            "only", "because");
}
