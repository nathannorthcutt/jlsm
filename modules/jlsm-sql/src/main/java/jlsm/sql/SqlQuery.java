package jlsm.sql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import jlsm.table.Predicate;

/**
 * The result of translating a SQL SELECT statement into jlsm-table query components.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Produced by {@link SqlTranslator} from a validated {@link SqlAst.SelectStatement}</li>
 * <li>Contains all information needed to execute a query via {@link jlsm.table.TableQuery}:
 * predicate tree, column projections, ordering, and pagination</li>
 * <li>Immutable record — safe to cache and reuse for prepared statement patterns</li>
 * </ul>
 *
 * @param predicate the WHERE clause translated to a Predicate tree, empty if no WHERE
 * @param projections column names to return (empty list means all columns / SELECT *)
 * @param aliases column aliases parallel to projections (empty string if no alias)
 * @param orderBy ORDER BY clauses with field name and direction
 * @param limit LIMIT value, empty if not specified
 * @param offset OFFSET value, empty if not specified
 * @param vectorDistance vector distance ordering info, empty if no VECTOR_DISTANCE in ORDER BY
 */
// @spec F07.R79,R80,R81,R82 — immutable record; null-reject all components; defensively copy lists;
// project/alias size check
public record SqlQuery(Optional<Predicate> predicate, List<String> projections,
        List<String> aliases, List<OrderBy> orderBy, OptionalInt limit, OptionalInt offset,
        Optional<VectorDistanceOrder> vectorDistance) {

    public SqlQuery {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(projections, "projections");
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(orderBy, "orderBy");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(vectorDistance, "vectorDistance");
        projections = List.copyOf(projections);
        aliases = List.copyOf(aliases);
        orderBy = List.copyOf(orderBy);
        if (!aliases.isEmpty() && projections.size() != aliases.size()) {
            throw new IllegalArgumentException("projections and aliases must have the same size: "
                    + projections.size() + " vs " + aliases.size());
        }
    }

    /**
     * A single ORDER BY clause.
     *
     * @param field the field name to sort by
     * @param ascending true for ASC, false for DESC
     */
    public record OrderBy(String field, boolean ascending) {
        public OrderBy {
            Objects.requireNonNull(field, "field");
        }
    }

    /**
     * A placeholder for a positional bind parameter ({@code ?}) in a predicate value position.
     * Implements {@link Comparable} so it can be used in range predicates (Gt, Gte, Lt, Lte,
     * Between) as well as equality predicates (Eq, Ne).
     *
     * @param index the zero-based parameter index
     */
    // @spec F07.R83 — BindMarker implements Comparable<BindMarker> comparing index values
    public record BindMarker(int index) implements Comparable<BindMarker> {
        public BindMarker {
            if (index < 0) {
                throw new IllegalArgumentException("bind marker index must be >= 0, got: " + index);
            }
        }

        @Override
        public int compareTo(BindMarker other) {
            return Integer.compare(index, other.index);
        }
    }

    /**
     * Describes a VECTOR_DISTANCE function call in ORDER BY position.
     *
     * @param field the vector field name
     * @param parameterIndex the bind parameter index for the query vector
     * @param metric the distance metric string (e.g. "cosine", "euclidean", "dot")
     * @param ascending true for ASC (default), false for DESC
     */
    // @spec F07.R84 — stores field, parameterIndex, metric, ascending (ORDER BY direction)
    public record VectorDistanceOrder(String field, int parameterIndex, String metric,
            boolean ascending) {
        public VectorDistanceOrder {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(metric, "metric");
            if (parameterIndex < 0) {
                throw new IllegalArgumentException(
                        "parameterIndex must be >= 0, got: " + parameterIndex);
            }
        }
    }
}
