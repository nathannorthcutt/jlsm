package jlsm.sql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Abstract syntax tree for the supported SQL SELECT subset.
 *
 * <p>
 * This is a sealed hierarchy of records representing the parsed SQL structure. The AST preserves
 * SQL semantics and is translated to {@code Predicate} / {@code TableQuery} by
 * {@link SqlTranslator}.
 *
 * <p>
 * Governed by: brief.md — Architecture section (SQL AST intermediate representation).
 */
public sealed interface SqlAst {

    // ── Top-level statement ──────────────────────────────────────

    /**
     * A complete SELECT statement.
     *
     * @param columns projected columns (at least one, or a single Wildcard)
     * @param table the table name in the FROM clause
     * @param where optional WHERE expression
     * @param orderBy ORDER BY clauses (may be empty)
     * @param limit optional LIMIT value
     * @param offset optional OFFSET value
     */
    record SelectStatement(List<Column> columns, String table, Optional<Expression> where,
            List<OrderByClause> orderBy, Optional<Integer> limit,
            Optional<Integer> offset) implements SqlAst {

        public SelectStatement {
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(where, "where");
            Objects.requireNonNull(orderBy, "orderBy");
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(offset, "offset");
            columns = List.copyOf(columns);
            orderBy = List.copyOf(orderBy);
            assert !columns.isEmpty() : "columns must not be empty";
            assert !table.isBlank() : "table must not be blank";
        }
    }

    // ── Column projections ───────────────────────────────────────

    sealed interface Column extends SqlAst {

        /** {@code SELECT *} */
        record Wildcard() implements Column {
        }

        /**
         * A named column, optionally aliased.
         *
         * @param name the field name
         * @param alias optional alias from AS clause
         */
        record Named(String name, Optional<String> alias) implements Column {
            public Named {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(alias, "alias");
            }
        }
    }

    // ── Expressions (WHERE clause) ───────────────────────────────

    sealed interface Expression extends SqlAst {

        /** Binary comparison: {@code field op value} */
        record Comparison(Expression left, ComparisonOp op,
                Expression right) implements Expression {
            public Comparison {
                Objects.requireNonNull(left, "left");
                Objects.requireNonNull(op, "op");
                Objects.requireNonNull(right, "right");
            }
        }

        /** Boolean connective: AND / OR */
        record Logical(Expression left, LogicalOp op, Expression right) implements Expression {
            public Logical {
                Objects.requireNonNull(left, "left");
                Objects.requireNonNull(op, "op");
                Objects.requireNonNull(right, "right");
            }
        }

        /** {@code NOT expr} */
        record Not(Expression operand) implements Expression {
            public Not {
                Objects.requireNonNull(operand, "operand");
            }
        }

        /** {@code expr BETWEEN low AND high} */
        record Between(Expression field, Expression low, Expression high) implements Expression {
            public Between {
                Objects.requireNonNull(field, "field");
                Objects.requireNonNull(low, "low");
                Objects.requireNonNull(high, "high");
            }
        }

        /** {@code expr IS [NOT] NULL} */
        record IsNull(Expression operand, boolean negated) implements Expression {
            public IsNull {
                Objects.requireNonNull(operand, "operand");
            }
        }

        /** Column reference: an identifier in expression position */
        record ColumnRef(String name) implements Expression {
            public ColumnRef {
                Objects.requireNonNull(name, "name");
            }
        }

        /** String literal: {@code 'value'} */
        record StringLiteral(String value) implements Expression {
            public StringLiteral {
                Objects.requireNonNull(value, "value");
            }
        }

        /** Numeric literal: integer or decimal */
        record NumberLiteral(String text) implements Expression {
            public NumberLiteral {
                Objects.requireNonNull(text, "text");
            }
        }

        /** Boolean literal: TRUE or FALSE */
        record BooleanLiteral(boolean value) implements Expression {
        }

        /** Positional bind parameter: {@code ?} */
        record Parameter(int index) implements Expression {
            public Parameter {
                assert index >= 0 : "parameter index must be >= 0";
            }
        }

        /**
         * Function call: {@code MATCH(field, query)} or
         * {@code VECTOR_DISTANCE(field, vector, metric)}.
         *
         * @param name the function name (e.g. "MATCH", "VECTOR_DISTANCE")
         * @param arguments the function arguments
         */
        record FunctionCall(String name, List<Expression> arguments) implements Expression {
            public FunctionCall {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(arguments, "arguments");
                arguments = List.copyOf(arguments);
            }
        }
    }

    // ── Enums ────────────────────────────────────────────────────

    enum ComparisonOp {
        EQ, NE, LT, LTE, GT, GTE
    }

    enum LogicalOp {
        AND, OR
    }

    // ── ORDER BY ─────────────────────────────────────────────────

    /**
     * A single ORDER BY clause.
     *
     * @param expression the expression to sort by (column ref or function call)
     * @param ascending true for ASC (default), false for DESC
     */
    record OrderByClause(Expression expression, boolean ascending) implements SqlAst {
        public OrderByClause {
            Objects.requireNonNull(expression, "expression");
        }
    }
}
