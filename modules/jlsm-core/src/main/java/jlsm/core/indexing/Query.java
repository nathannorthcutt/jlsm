package jlsm.core.indexing;

import java.util.Objects;

/**
 * Composable query DSL for full-text search.
 *
 * <p>
 * Sealed interface with four permitted record types:
 * <ul>
 * <li>{@link TermQuery} — matches documents containing a specific token in a specific field
 * <li>{@link AndQuery} — intersection of two queries
 * <li>{@link OrQuery} — union of two queries
 * <li>{@link NotQuery} — documents matching {@code include} but not {@code exclude}
 * </ul>
 */
public sealed interface Query
        permits Query.TermQuery, Query.AndQuery, Query.OrQuery, Query.NotQuery {

    /**
     * Matches documents whose {@code field} contains {@code term} after tokenization.
     *
     * @param field the field name to search; must not be null or blank, and must not contain '\0'
     * @param term the raw term token to match; must not be null
     */
    record TermQuery(String field, String term) implements Query {

        public TermQuery {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(term, "term must not be null");
            if (field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
            if (field.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "field must not contain the null character '\\0'");
            }
        }
    }

    /**
     * Returns the intersection of the documents matching {@code left} and {@code right}.
     *
     * @param left the left operand; must not be null
     * @param right the right operand; must not be null
     */
    record AndQuery(Query left, Query right) implements Query {

        public AndQuery {
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
        }
    }

    /**
     * Returns the union of the documents matching {@code left} and {@code right}.
     *
     * @param left the left operand; must not be null
     * @param right the right operand; must not be null
     */
    record OrQuery(Query left, Query right) implements Query {

        public OrQuery {
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
        }
    }

    /**
     * Returns documents matching {@code include} minus those matching {@code exclude}.
     *
     * @param include the base set of documents; must not be null
     * @param exclude the documents to remove from the base set; must not be null
     */
    record NotQuery(Query include, Query exclude) implements Query {

        public NotQuery {
            Objects.requireNonNull(include, "include must not be null");
            Objects.requireNonNull(exclude, "exclude must not be null");
        }
    }
}
