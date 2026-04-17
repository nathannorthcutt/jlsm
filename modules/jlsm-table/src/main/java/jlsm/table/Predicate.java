package jlsm.table;

import java.util.List;
import java.util.Objects;

/**
 * A query predicate AST node. Predicates form a tree that the query executor inspects to select
 * indices and build execution plans.
 *
 * <p>
 * Designed to be inspectable and serializable for future SQL translation.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>All leaf predicates carry a field name and typed value(s)</li>
 * <li>Composite predicates (And, Or) hold a list of child predicates</li>
 * <li>Field name and type compatibility validated eagerly at construction</li>
 * </ul>
 */
// @spec F10.R14 — sealed with exactly 11 implementations: Eq, Ne, Gt, Gte, Lt, Lte, Between,
// FullTextMatch, VectorNearest, And, Or
public sealed interface Predicate {

    /** Equality: field == value. Usable with any primitive field type. */
    // @spec F10.R15,R16 — reject null field and value with NPE
    record Eq(String field, Object value) implements Predicate {
        public Eq {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
        }
    }

    /** Inequality: field != value. Usable with any primitive field type. */
    // @spec F10.R15,R16 — reject null field and value with NPE
    record Ne(String field, Object value) implements Predicate {
        public Ne {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
        }
    }

    /** Greater than: field > value. Requires an ordered field type. */
    // @spec F10.R15,R17 — Comparable<?> value; reject null field and value with NPE
    record Gt(String field, Comparable<?> value) implements Predicate {
        public Gt {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
        }
    }

    /** Greater than or equal: field >= value. Requires an ordered field type. */
    record Gte(String field, Comparable<?> value) implements Predicate {
        public Gte {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
        }
    }

    /** Less than: field < value. Requires an ordered field type. */
    record Lt(String field, Comparable<?> value) implements Predicate {
        public Lt {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
        }
    }

    /** Less than or equal: field <= value. Requires an ordered field type. */
    record Lte(String field, Comparable<?> value) implements Predicate {
        public Lte {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
        }
    }

    /** Range: low <= field <= high. Requires an ordered field type. */
    // @spec F10.R15,R18 — reject null field/low/high with NPE; enforce low/high same class
    record Between(String field, Comparable<?> low, Comparable<?> high) implements Predicate {
        public Between {
            Objects.requireNonNull(field);
            Objects.requireNonNull(low);
            Objects.requireNonNull(high);
            if (low.getClass() != high.getClass()) {
                throw new IllegalArgumentException("low and high must be the same type, got "
                        + low.getClass().getName() + " and " + high.getClass().getName());
            }
        }
    }

    /**
     * Full-text match: field contains the query terms. Requires a STRING field with FULL_TEXT
     * index.
     */
    // @spec F10.R15,R19 — reject null field and query with NPE
    record FullTextMatch(String field, String query) implements Predicate {
        public FullTextMatch {
            Objects.requireNonNull(field);
            Objects.requireNonNull(query);
            if (query.isBlank()) {
                throw new IllegalArgumentException("query must not be empty or blank");
            }
        }
    }

    /**
     * Vector nearest neighbour: find the topK closest vectors to the query. Requires a VECTOR
     * index.
     */
    // @spec F10.R20,R21,R22,R23 — reject null field/queryVector (NPE), reject non-positive topK
    // (IAE),
    // defensive copy queryVector on construction and on accessor
    record VectorNearest(String field, float[] queryVector, int topK) implements Predicate {
        public VectorNearest {
            Objects.requireNonNull(field);
            Objects.requireNonNull(queryVector);
            if (queryVector.length == 0) {
                throw new IllegalArgumentException("queryVector must not be empty");
            }
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive");
            }
            queryVector = queryVector.clone();
        }

        /**
         * Returns a defensive copy of the query vector.
         */
        @Override
        public float[] queryVector() {
            return queryVector.clone();
        }
    }

    /** Logical AND: all children must match. */
    // @spec F10.R24,R25,R26 — reject null children (NPE), require >=2 children (IAE),
    // store defensively copied immutable list via List.copyOf
    record And(List<Predicate> children) implements Predicate {
        public And {
            Objects.requireNonNull(children);
            if (children.size() < 2) {
                throw new IllegalArgumentException("And requires at least 2 children");
            }
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i) == null) {
                    throw new NullPointerException("And children must not contain null elements");
                }
            }
            children = List.copyOf(children);
        }
    }

    /** Logical OR: at least one child must match. */
    // @spec F10.R27,R28,R29 — reject null children (NPE), require >=2 children (IAE),
    // store defensively copied immutable list via List.copyOf
    record Or(List<Predicate> children) implements Predicate {
        public Or {
            Objects.requireNonNull(children);
            if (children.size() < 2) {
                throw new IllegalArgumentException("Or requires at least 2 children");
            }
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i) == null) {
                    throw new NullPointerException("Or children must not contain null elements");
                }
            }
            children = List.copyOf(children);
        }
    }
}
