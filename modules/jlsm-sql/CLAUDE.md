# jlsm-sql

SQL SELECT parser and translator. Parses a SQL string into a `SqlQuery` containing
a `Predicate` tree, projections, ordering, and limit/offset — ready for execution
against a `JlsmTable`.

## Dependencies

- `jlsm.table` (transitive) — `JlsmSchema` for column/type validation, `Predicate` for WHERE clauses

## Exported Package

- `jlsm.sql` — public API: `JlsmSql` (entry point), `SqlQuery`, `SqlParseException`

## Key Design

- Hand-written recursive descent parser — no external parsing library
- Pipeline: SQL string → `SqlLexer` (tokens) → `SqlParser` (AST) → `SqlTranslator` (SqlQuery)
- Schema validation happens at translation time, not parse time
- Supports: `SELECT`, `WHERE`, `ORDER BY`, `LIMIT`, `OFFSET`, `MATCH()`, `VECTOR_DISTANCE()`, bind parameters (`?`)
