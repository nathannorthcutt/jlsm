package jlsm.sql;

import java.util.Objects;

import jlsm.table.JlsmSchema;

/**
 * Public entry point for parsing and translating SQL queries.
 *
 * <p>
 * Contract:
 * <ul>
 * <li>Receives: a SQL string and the target table's {@link JlsmSchema}</li>
 * <li>Returns: a {@link SqlQuery} ready for execution</li>
 * <li>Side effects: none (composes lexer → parser → translator)</li>
 * <li>Error conditions: throws {@link SqlParseException} on any lexing, parsing, or validation
 * error, with position information</li>
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * JlsmSchema schema = ...;
 * SqlQuery query = JlsmSql.parse("SELECT name, age FROM users WHERE age > 30 LIMIT 10", schema);
 * }</pre>
 */
// @spec query.sql-query-support.R6 — non-instantiable utility class with private constructor
public final class JlsmSql {

    private JlsmSql() {
        // utility class
    }

    /**
     * Parses and validates a SQL SELECT string against the given schema.
     *
     * @param sql the SQL SELECT string, must not be null or blank
     * @param schema the target table schema for column validation, must not be null
     * @return the parsed and validated query
     * @throws SqlParseException if the SQL is malformed or references invalid columns/types
     */
    // @spec query.sql-query-support.R2,R3,R4,R5,R7,R99 — non-null args, blank rejection, lexer→parser→translator
    // pipeline
    public static SqlQuery parse(String sql, JlsmSchema schema) throws SqlParseException {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(schema, "schema");
        if (sql.isBlank()) {
            throw new SqlParseException("SQL string must not be blank", 0);
        }
        final var lexer = new SqlLexer();
        final var parser = new SqlParser();
        final var translator = new SqlTranslator();
        return translator.translate(parser.parse(lexer.tokenize(sql)), schema);
    }
}
