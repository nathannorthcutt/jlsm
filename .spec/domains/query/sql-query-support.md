---
{
  "id": "query.sql-query-support",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [
    "algorithms/sql-extensions/vector-similarity-sql-syntax"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F07"
  ]
}
---
# query.sql-query-support — SQL Query Support

## Requirements

### Scope and entry point

R1. The `jlsm-sql` module must export only the `jlsm.sql` package. No internal packages may be exported to other modules.

R2. `JlsmSql.parse` must accept a non-null SQL string and a non-null `JlsmSchema`, and return a `SqlQuery` representing the translated query.

R3. `JlsmSql.parse` must reject a null SQL string with a `NullPointerException` before any lexing begins.

R4. `JlsmSql.parse` must reject a null schema with a `NullPointerException` before any lexing begins.

R5. `JlsmSql.parse` must reject a blank SQL string (empty or whitespace-only) with a `SqlParseException` having position 0.

R6. `JlsmSql` must be a non-instantiable utility class with a private constructor.

R7. The pipeline must compose lexer, parser, and translator in sequence: SQL string to token list to AST to `SqlQuery`. No stage may be skipped.

### Lexer — tokenization

R8. `SqlLexer.tokenize` must accept a non-null SQL string and return an immutable list of `Token` values ending with a single `EOF` token.

R9. `SqlLexer.tokenize` must reject a null input with a `NullPointerException`.

R10. The lexer must recognise SQL keywords case-insensitively: `SELECT`, `FROM`, `WHERE`, `AND`, `OR`, `NOT`, `ORDER`, `BY`, `ASC`, `DESC`, `LIMIT`, `OFFSET`, `BETWEEN`, `IS`, `NULL`, `TRUE`, `FALSE`, `AS`, `LIKE`, `IN`, `MATCH`, `VECTOR_DISTANCE`.

R11. The lexer must classify an identifier-like word as a keyword only when its uppercase form exactly matches a keyword entry. Words that differ (e.g. `SELECTED`, `FROMAGE`) must be classified as `IDENTIFIER`.

R12. The lexer must tokenize single-quoted string literals. The content between quotes (excluding the outer quotes) must be stored as the token text.

R13. The lexer must handle the SQL escaped-quote convention: two adjacent single-quotes (`''`) inside a string literal must produce a single literal quote character in the token text.

R14. The lexer must throw `SqlParseException` with the position of the opening quote when a string literal reaches the end of input without a closing quote.

R15. The lexer must tokenize numeric literals consisting of digits with at most one decimal point. A leading decimal point followed by a digit must be accepted as a valid numeric literal.

R16. The lexer must tokenize identifiers starting with a letter or underscore, continuing with letters, digits, or underscores.

R17. The lexer must tokenize all comparison operators: `=` (EQ), `!=` (NE), `<>` (NE), `<` (LT), `<=` (LTE), `>` (GT), `>=` (GTE).

R18. The lexer must tokenize punctuation characters: `(` (LPAREN), `)` (RPAREN), `,` (COMMA), `.` (DOT), `*` (STAR).

R19. The lexer must tokenize `?` as a `PARAMETER` token.

R20. The lexer must skip whitespace characters between tokens without producing tokens for them.

R21. The lexer must throw `SqlParseException` with the position of the offending character when it encounters a character that is not whitespace, a quote, a digit, a letter, an underscore, or a recognised operator/punctuation character.

R22. The lexer must throw `SqlParseException` when a `!` character is not immediately followed by `=`. The error message must suggest `!=`.

R23. Every `Token` must carry the zero-based character offset in the original SQL string where the token begins.

R24. The `Token` record must reject a null `TokenType` with a `NullPointerException`.

R25. The `Token` record must reject a null text with a `NullPointerException`.

R26. The `Token` record must reject a negative position with an `IllegalArgumentException`.

R27. The `EOF` token must have an empty string as its text and a position equal to the length of the input SQL string.

### Parser — AST construction

R28. `SqlParser.parse` must accept a non-null, non-empty list of `Token` values and return a `SqlAst.SelectStatement`.

R29. `SqlParser.parse` must reject a null token list with a `NullPointerException`.

R30. `SqlParser.parse` must reject an empty token list with a `SqlParseException`.

R31. The parser must reject non-SELECT statements (`INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`) with a `SqlParseException` identifying the unsupported keyword.

R32. The parser must enforce a maximum expression nesting depth of 128. Exceeding this depth must produce a `SqlParseException` mentioning the depth limit.

R33. The parser must implement operator precedence: OR has the lowest precedence, then AND, then NOT, then comparison operators and BETWEEN/IS NULL.

R34. The parser must parse `BETWEEN expr AND expr` as a range expression, consuming the `AND` token as part of the BETWEEN syntax rather than as a logical connective.

R35. The parser must parse `IS NULL` and `IS NOT NULL` as null-test expressions.

R36. The parser must parse parenthesised expressions `(expr)` as grouping, consuming the opening and closing parentheses.

R37. The parser must assign sequential zero-based indices to positional bind parameters (`?`) in the order they appear in the SQL string.

R38. The parser must parse `SELECT *` as a single `Wildcard` column projection.

R39. The parser must parse named columns with optional `AS alias` syntax.

R40. The parser must parse `ORDER BY` clauses with optional `ASC` or `DESC` direction, defaulting to ascending.

R41. The parser must parse `LIMIT` and `OFFSET` values as integer literals. Non-integer values (decimals, non-numeric tokens) must produce a `SqlParseException`.

R42. The parser must parse `MATCH(args)` and `VECTOR_DISTANCE(args)` as `FunctionCall` expressions with the function name uppercased.

R43. The parser must allow `MATCH` and `VECTOR_DISTANCE` function calls in both WHERE clause expressions and ORDER BY clause expressions.

R44. The parser must produce a `SqlParseException` with the position of the unexpected token when a required token type is not found.

R45. The parser must not advance past the `EOF` token. Repeated reads at `EOF` must continue to return the `EOF` token without error.

### AST — sealed type hierarchy

R46. `SqlAst` must be a sealed interface. All AST node types must be records implementing `SqlAst` or a sub-interface of `SqlAst`.

R47. The `SelectStatement` record must store: columns (non-empty list), table name (non-blank string), optional WHERE expression, ORDER BY clauses (possibly empty list), optional LIMIT, optional OFFSET.

R48. The `SelectStatement` record must produce defensively-copied immutable lists for columns and orderBy.

R49. The `Column` sealed interface must permit exactly two implementations: `Wildcard` (no fields) and `Named` (name plus optional alias).

R50. The `Expression` sealed interface must permit exactly these implementations: `Comparison`, `Logical`, `Not`, `Between`, `IsNull`, `ColumnRef`, `StringLiteral`, `NumberLiteral`, `BooleanLiteral`, `Parameter`, `FunctionCall`.

R51. Every AST record that holds a reference-type field must reject null values for that field with a `NullPointerException` in its compact constructor.

R52. The `FunctionCall` record must produce a defensively-copied immutable list of its arguments.

R53. The `NumberLiteral` record must store the raw text representation of the number, not a parsed numeric value.

### Translator — AST to SqlQuery

R54. `SqlTranslator.translate` must accept a non-null `SelectStatement` and a non-null `JlsmSchema`, and return a `SqlQuery`.

R55. `SqlTranslator.translate` must reject a null statement with a `NullPointerException`.

R56. `SqlTranslator.translate` must reject a null schema with a `NullPointerException`.

R57. The translator must validate every column name referenced in SELECT projections against the schema. A column name not present in the schema must produce a `SqlParseException` identifying the unknown field and the schema name.

R58. The translator must validate every column name referenced in WHERE expressions against the schema with the same error contract as R57.

R59. The translator must validate every column name referenced in ORDER BY clauses against the schema with the same error contract as R57.

R60. The translator must type-check literal values in comparison expressions against the field type declared in the schema. A type mismatch (e.g. string literal against an INT32 field) must produce a `SqlParseException`.

R61. The translator must skip type-checking for bind parameter markers (`?`), deferring type validation to execution time.

R62. The translator must translate `Comparison` AST nodes to the corresponding `Predicate` leaf: `EQ` to `Predicate.Eq`, `NE` to `Predicate.Ne`, `GT` to `Predicate.Gt`, `GTE` to `Predicate.Gte`, `LT` to `Predicate.Lt`, `LTE` to `Predicate.Lte`.

R63. The translator must translate `BETWEEN` AST nodes to `Predicate.Between`.

R64. The translator must translate `Logical AND` nodes to `Predicate.And` with a two-element children list, and `Logical OR` nodes to `Predicate.Or` with a two-element children list.

R65. The translator must translate `MATCH(field, query)` to `Predicate.FullTextMatch`.

R66. The translator must reject `MATCH` calls with a number of arguments other than 2 with a `SqlParseException`.

R67. The translator must reject `MATCH` when the target field is not of type `STRING` or `BoundedString` with a `SqlParseException`.

R68. The translator must reject `MATCH` when the query argument is not a string literal with a `SqlParseException`. Bind parameters are not accepted as the MATCH query argument because `Predicate.FullTextMatch` (F10.R19) stores the query as a resolved `String` and cannot defer resolution to execution time.

R69. The translator must translate `VECTOR_DISTANCE(field, vector, metric)` in ORDER BY position to a `SqlQuery.VectorDistanceOrder` record, not to a `Predicate`.

R70. The translator must reject `VECTOR_DISTANCE` calls with a number of arguments other than 3 with a `SqlParseException`.

R71. The translator must reject `VECTOR_DISTANCE` when the target field is neither a `VectorType` nor an `ArrayType` whose element type is `FLOAT16` or `FLOAT32`. An `ArrayType` with a non-float element type (e.g. `STRING`) must be rejected with a `SqlParseException`.

R72. The translator must reject `VECTOR_DISTANCE` when the vector argument is not a bind parameter (`?`) with a `SqlParseException`.

R73. The translator must reject `VECTOR_DISTANCE` when the metric argument is not a string literal with a `SqlParseException`.

R74. The translator must handle reversed comparisons (value on left, column on right) by swapping operands and flipping the comparison operator. `GT` must flip to `LT`, `GTE` to `LTE`, `LT` to `GT`, `LTE` to `GTE`. `EQ` and `NE` must remain unchanged after flip.

R75. The translator must translate `SELECT *` to an empty projections list in `SqlQuery`, signifying all columns.

R76. The translator must preserve column aliases from `AS` clauses in the `SqlQuery.aliases` list, parallel to the projections list. Columns without aliases must have an empty string at the corresponding index.

R77. The translator must parse numeric literal text into `Integer` when it fits in int range, `Long` when it fits in long range but not int, and `Double` when the text contains a decimal point.

R78. The translator must throw `SqlParseException` when a numeric literal exceeds the range of `Long` (for integers) or `Double` (for decimals).

### SqlQuery — result record

R79. `SqlQuery` must be an immutable record storing: optional predicate, projections list, aliases list, orderBy list, optional limit, optional offset, optional vectorDistance.

R80. `SqlQuery` must reject null values for any of its component fields with a `NullPointerException`.

R81. `SqlQuery` must produce defensively-copied immutable lists for projections, aliases, and orderBy.

R82. `SqlQuery` must reject mismatched sizes between projections and aliases (when aliases is non-empty) with an `IllegalArgumentException`.

R83. The `BindMarker` record must implement `Comparable<BindMarker>` by comparing index values.

R84. The `VectorDistanceOrder` record must store the field name, the bind parameter index for the query vector, the metric string, and an `ascending` flag that mirrors the ORDER BY direction (true for ASC, false for DESC).

### SqlParseException — error reporting

R85. `SqlParseException` must be a checked exception (extending `Exception`).

R86. `SqlParseException` must carry a `position` field representing the zero-based character offset in the SQL string where the error was detected, or -1 when the position is unknown.

R87. `SqlParseException` must support construction with a message and position, and with a message, position, and cause.

### Type compatibility rules

R88. String literals must be compatible with `STRING`, `BoundedString`, and `TIMESTAMP` field types.

R89. Numeric literals must be compatible with `INT8`, `INT16`, `INT32`, `INT64`, `FLOAT16`, `FLOAT32`, `FLOAT64`, and `TIMESTAMP` field types.

R90. Boolean literals must be compatible only with `BOOLEAN` field types.

R91. Literals must not be compatible with `ArrayType`, `ObjectType`, or `VectorType` field types. Comparisons against these types must produce a `SqlParseException`.

### Unsupported constructs — explicit rejection

R92. The translator must throw `SqlParseException` when encountering a `NOT` expression in WHERE position (not yet implemented).

R93. The translator must throw `SqlParseException` when encountering an `IS NULL` or `IS NOT NULL` expression in WHERE position (not yet implemented).

R94. The translator must throw `SqlParseException` when encountering an unsupported function name (neither `MATCH` nor `VECTOR_DISTANCE`) in WHERE position.

R95. The translator must throw `SqlParseException` when an ORDER BY expression is neither a `ColumnRef` nor a `VECTOR_DISTANCE` function call.

### Statelessness and thread safety

R96. `SqlLexer` instances must be safe to use concurrently from multiple threads. Each call to `tokenize` must operate on only the provided input with no shared mutable state between calls.

R97. `SqlParser` instances must reset all internal state (position, parameter index, expression depth) at the start of each `parse` call. Reusing a parser instance across sequential calls must produce correct results.

R98. `SqlTranslator` instances must hold no mutable state. Each `translate` call must be independent.

R99. `JlsmSql.parse` must construct fresh lexer, parser, and translator instances per invocation to guarantee no cross-call state leakage.

### Input boundary conditions

R100. The lexer must handle an input consisting solely of whitespace by returning a list containing only the `EOF` token.

R101. The lexer must reject a numeric literal that ends with a decimal point and has no fractional digits (e.g. `42.`) with a `SqlParseException` positioned at the start of the literal. A trailing dot without digits is not a valid numeric token and must not be silently accepted.

R102. The lexer must handle identifiers containing digits after the initial letter or underscore (e.g. `field_1`, `col2`).

R103. The parser must consume all tokens up to `EOF`. Trailing tokens after a complete valid SELECT statement that are not `EOF` must produce a `SqlParseException` (enforced by the parser expecting `EOF` or a valid continuation at each grammar production).

---

## Design Narrative

### Intent

Enable users already familiar with SQL to query `JlsmTable` using standard SQL SELECT syntax. The `jlsm-sql` module provides a pure-Java parser (no external dependencies) that translates a SQL string into a `SqlQuery` object containing a `Predicate` tree and query parameters ready for execution against `JlsmTable`. This bridges the gap between the programmatic `TableQuery` fluent API and declarative SQL.

### Why this approach

A hand-written recursive descent parser was chosen over a parser generator (ANTLR, JavaCC) to avoid external dependencies and keep the module self-contained within JPMS. The three-stage pipeline (lexer, parser, translator) separates concerns cleanly: the lexer handles character-level concerns and keyword recognition, the parser enforces grammar and builds a type-safe AST, and the translator validates against the schema and produces the execution-ready `SqlQuery`. Schema validation happens at translation time (not parse time) so the parser can be reused for schema-independent tasks if needed. The AST uses a sealed hierarchy of records, enabling exhaustive pattern matching in the translator.

### What was ruled out

- **Parser generators (ANTLR, JavaCC):** Introduce external dependencies and code-generation steps that conflict with the project's no-external-runtime-dependencies policy.
- **Direct string-to-predicate translation (no AST):** Couples parsing and semantic validation, making the code harder to test and extend. The AST intermediate representation is essential for correctness of operator precedence, reversed comparisons, and BETWEEN...AND disambiguation.
- **Full SQL support:** Joins, subqueries, aggregates, and DML are explicitly out of scope. The parser rejects these at the grammar level rather than silently ignoring them.
- **Runtime type coercion:** Literal values are type-checked against the schema at translation time. Implicit coercion (e.g. string to number) was rejected to prevent silent data errors.

### Out of scope

- Joins, subqueries, CTEs, window functions
- Aggregates (COUNT, SUM, AVG, MIN, MAX), GROUP BY, HAVING
- INSERT, UPDATE, DELETE (DML)
- UNION, INTERSECT, EXCEPT (set operations)
- DDL (CREATE TABLE, DROP, ALTER)
- NOT and IS NULL predicate translation (parsed but not yet translated)
- LIKE operator translation (tokenized but not yet parsed in expressions)
- IN operator translation (tokenized but not yet parsed in expressions)
- Double-quoted identifiers or backtick-quoted identifiers
- Multi-table FROM clauses
- Nested subqueries in WHERE or FROM

---

## Verification Notes

### Verified: v2 — 2026-04-18

**Overall: PASS**

| Scope | Count |
|-------|-------|
| SATISFIED | 103 |
| PARTIAL | 0 |
| VIOLATED | 0 |
| UNTESTABLE | 0 |

Evidence is anchored by `@spec F07.RN` annotations across
`modules/jlsm-sql/src/main/java/jlsm/sql/*.java` and the test suite in
`modules/jlsm-sql/src/test/java/jlsm/sql/*.java`. The full pipeline —
`JlsmSql.parse` → `SqlLexer.tokenize` → `SqlParser.parse` → `SqlTranslator.translate` —
is annotated end to end, including SqlAst/SqlQuery record structure and the
SqlParseException contract.

#### Amendments (v1 → v2)

- **R68**: widened-bind-parameter clause removed. Now requires MATCH query
  argument to be a string literal. Rationale: F10.R19 locks
  `Predicate.FullTextMatch.query` to a `String`; deferred bind resolution
  would require a cross-module change to jlsm-table that is out of scope for
  this verification.
- **R71**: clarified the permitted target field type. `VectorType` is always
  accepted; `ArrayType` is accepted only when the element type is `FLOAT16`
  or `FLOAT32`. An `ArrayType` with a non-float element (e.g. `STRING`) must
  be rejected.
- **R84**: `VectorDistanceOrder` record also stores an `ascending` flag that
  mirrors the ORDER BY direction (ASC → true, DESC → false).
- **R101**: inverted. A numeric literal ending with a decimal point and no
  fractional digits (e.g. `42.`) must be rejected with a `SqlParseException`.
  The prior "include trailing dot in token text" wording did not match the
  implementation (which rejects) or the long-standing
  `trailingDotNumericLiteral` test.

#### Code fixes applied

- **R78** — `SqlTranslator.parseNumber` now detects decimal overflow to
  `Double.POSITIVE_INFINITY` / `NaN` and throws `SqlParseException`. Previously
  `Double.parseDouble("1e400")` returned Infinity silently, producing a
  predicate value that bypassed the translator's overflow contract.
- **R103** — `SqlParser.parseSelectStatement` now asserts the next token is
  `EOF` before returning. Trailing junk after a complete SELECT (e.g.
  `SELECT * FROM t EXTRA`) is now rejected with an `SqlParseException`
  carrying the trailing token's position. Previously the parser silently
  discarded all trailing tokens.

#### Regression tests added

- `SqlTranslatorAdversarialTest.decimalOverflowThrowsSqlParseException`
- `SqlTranslatorAdversarialTest.decimalNegativeOverflowThrowsSqlParseException`
- `SqlParserAdversarialTest.trailingTokensAfterSelectRejected`
- `SqlParserAdversarialTest.trailingTokensAfterLimitRejected`
- `SqlParserAdversarialTest.noTrailingTokensParsesCleanly` (positive
  regression guard)

#### Structural test gaps filled

New class `F07StructuralTest` covers reflective requirements that the
existing suite did not exercise directly: R1 (module exports), R6 (private
utility constructor), R46/R49/R50 (sealed hierarchy permits), R79 (SqlQuery
record), R48/R52/R81 (defensive list copies), R83 (`BindMarker.compareTo`
ordering), R100 (whitespace-only input → single EOF), and R77 (numeric
widening Integer→Long→Double).

Every existing test method that maps to an F07 requirement was also
annotated with a `// @spec F07.RN` tag.

#### Deferred obligations

None.

#### Undocumented behavior noted

- `SqlLexer` tokenizes `-` as a `MINUS` token, which the parser consumes to
  produce negative `NumberLiteral`s. The spec does not enumerate this token
  under R17/R18/R21, but the behavior is validated by
  `ContractBoundariesAdversarialTest.test_SqlLexer_negativeNumericLiteral_parseable`
  and the broader numeric-range tests. Left as implementation detail rather
  than a new requirement — negative numeric literals are a property of
  numeric literal syntax (R15), not a standalone operator.
