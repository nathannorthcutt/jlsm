package jlsm.table;

import java.util.Objects;

/**
 * A query result entry with an associated relevance score.
 *
 * <p>
 * Contract: Immutable result from a ranked query (vector similarity, full-text, or hybrid). The
 * score semantics depend on the query type: higher is better for similarity and text relevance.
 *
 * @param key the document key
 * @param document the matched document
 * @param score relevance score (higher = more relevant)
 * @param <K> the key type
 */
// @spec partitioning.table-partitioning.R22 — public record in jlsm.table with key/document/score components
public record ScoredEntry<K>(K key, JlsmDocument document, double score) {

    // @spec partitioning.table-partitioning.R23,R24,R25 — null key→NPE (R23); null document→NPE (R24); NaN score→IAE (R25)
    public ScoredEntry {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(document, "document must not be null");
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException(
                    "score must not be NaN — ordering is undefined for NaN values");
        }
    }
}
