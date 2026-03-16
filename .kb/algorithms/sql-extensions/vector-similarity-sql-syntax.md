---
title: "Vector Similarity Search SQL Syntax"
aliases: ["vector SQL", "nearest neighbor SQL", "ANN SQL syntax"]
topic: "algorithms"
category: "sql-extensions"
tags: ["vector-search", "sql", "nearest-neighbor", "similarity"]
complexity:
  time_build: "N/A (syntax survey)"
  time_query: "N/A (syntax survey)"
  space: "N/A (syntax survey)"
research_status: "active"
last_researched: "2026-03-16"
sources:
  - url: "https://github.com/pgvector/pgvector"
    title: "pgvector — Open-source vector similarity search for Postgres"
    accessed: "2026-03-16"
    type: "repo"
  - url: "https://duckdb.org/docs/stable/core_extensions/vss"
    title: "DuckDB Vector Similarity Search Extension"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/vector_distance.html"
    title: "Oracle VECTOR_DISTANCE function reference"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://learn.microsoft.com/en-us/sql/t-sql/functions/vector-search-transact-sql"
    title: "SQL Server VECTOR_SEARCH (Transact-SQL)"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://clickhouse.com/docs/engines/table-engines/mergetree-family/annindexes"
    title: "ClickHouse Vector Search"
    accessed: "2026-03-16"
    type: "docs"
  - url: "https://github.com/asg017/sqlite-vec"
    title: "sqlite-vec — Vector search SQLite extension"
    accessed: "2026-03-16"
    type: "repo"
---

# Vector Similarity Search SQL Syntax

## summary

There is no ISO SQL standard for vector similarity search. Every database that
supports it has invented its own syntax. The approaches fall into three distinct
patterns: custom operators (pgvector), distance functions composed with
ORDER BY + LIMIT (Oracle, DuckDB, ClickHouse), and dedicated table-valued
search functions (SQL Server). For a library designing its own SQL dialect,
the function-based approach is the most portable and easiest to parse.

## how-it-works

### the three syntax patterns

**Pattern 1 — Custom operators** (pgvector)
```sql
SELECT * FROM items ORDER BY embedding <-> '[1,2,3]' LIMIT 5;
```
Operators: `<->` (L2), `<=>` (cosine), `<#>` (inner product), `<+>` (L1),
`<~>` (hamming), `<%>` (jaccard). Compact but requires lexer support for
multi-character symbolic operators. PostgreSQL-specific.

**Pattern 2 — Distance function + ORDER BY + LIMIT** (Oracle, DuckDB, ClickHouse)
```sql
-- Oracle 23ai
SELECT * FROM docs
ORDER BY VECTOR_DISTANCE(embedding, :query_vec, COSINE)
FETCH FIRST 10 ROWS ONLY;

-- DuckDB
SELECT * FROM docs
ORDER BY array_distance(embedding, [1,2,3])
LIMIT 10;

-- ClickHouse
SELECT * FROM docs
ORDER BY L2Distance(embedding, [1,2,3])
LIMIT 5;
```
Uses standard SQL ORDER BY + LIMIT with a scalar distance function. The
function returns a numeric distance score. Most SQL-like pattern — no new
syntax beyond the function name itself.

**Pattern 3 — Dedicated search function** (SQL Server 2025)
```sql
SELECT t.id, s.distance
FROM VECTOR_SEARCH(
    TABLE = articles AS t,
    COLUMN = embedding,
    SIMILAR_TO = @qv,
    METRIC = 'cosine',
    TOP_N = 10
) AS s
ORDER BY s.distance;
```
Table-valued function with named parameters. Most explicit but heaviest
syntax — requires parsing a non-standard function-in-FROM clause with
keyword arguments.

### key-parameters

| System | Function / Operator | Metric Param | K Param | Notes |
|--------|-------------------|--------------|---------|-------|
| pgvector | `<->`, `<=>`, `<#>` | implicit in operator | LIMIT | operator per metric |
| Oracle 23ai | `VECTOR_DISTANCE(col, vec, metric)` | 3rd arg: COSINE, EUCLIDEAN, DOT, etc. | FETCH FIRST N | also has shorthand: `COSINE_DISTANCE()`, `L2_DISTANCE()` |
| DuckDB | `array_distance(col, vec)` | separate functions per metric | LIMIT | `array_cosine_similarity()`, `array_cosine_distance()`, `array_inner_product()` |
| ClickHouse | `L2Distance(col, vec)` | separate functions per metric | LIMIT | `cosineDistance()`, `dotProduct()` |
| SQL Server | `VECTOR_SEARCH(...)` | METRIC = 'cosine' | TOP_N = k | table-valued, named params |
| sqlite-vec | `col MATCH vec` | implicit (L2 default) | LIMIT | virtual table only |

## algorithm-steps

When designing a SQL vector search syntax for a custom parser:

1. **Choose the pattern** — function-based (Pattern 2) is recommended for
   custom parsers because it reuses standard SQL ORDER BY + LIMIT and only
   requires adding a function call to the expression grammar
2. **Decide function naming** — single function with metric parameter
   (Oracle style: `VECTOR_DISTANCE(col, vec, 'cosine')`) vs. one function per
   metric (DuckDB/ClickHouse style: `cosine_distance(col, vec)`)
3. **Define the vector literal format** — array syntax `[1.0, 2.0, 3.0]` or
   bind parameter `?`
4. **Define k** — use standard LIMIT clause (no new syntax needed)
5. **Decide on distance vs. similarity** — distance (lower = closer) is more
   common; cosine similarity (higher = closer) requires negation for ORDER BY

## implementation-notes

### data-structure-requirements

The parser needs to recognise the chosen function name(s) as built-in functions
in expression position. No new grammar productions are needed beyond standard
function call syntax: `IDENTIFIER '(' expr ',' expr [',' expr] ')'`.

### edge-cases-and-gotchas

- **Bind parameters for vectors**: `VECTOR_DISTANCE(embedding, ?, 'cosine')`
  — the `?` must resolve to a float array at execution time
- **Metric as string literal vs. keyword**: Oracle uses an unquoted keyword
  (`COSINE`), SQL Server uses a quoted string (`'cosine'`). String literals
  are simpler to parse
- **Distance column naming**: SQL Server auto-generates a `distance` column.
  For ORDER BY + LIMIT patterns, the distance is just the function result —
  users can alias it: `VECTOR_DISTANCE(...) AS dist`
- **Post-filter vs. pre-filter**: most systems apply WHERE after vector search
  (post-filter). Document this limitation clearly

## tradeoffs

### strengths

**Pattern 2 (function-based) strengths:**
- Minimal parser changes — just a new built-in function name
- Composes naturally with existing SQL: WHERE, ORDER BY, LIMIT, aliases
- Users familiar with any SQL database will recognise the pattern
- Maps cleanly to a `Predicate` or `OrderBy` in a query translation layer

### weaknesses

**Pattern 2 weaknesses:**
- Less explicit about "this is a vector search" vs. a regular ORDER BY
- No built-in way to express "top-K ANN search" distinctly from "sort by
  distance" — the optimiser must infer ANN from ORDER BY + LIMIT

### compared-to-alternatives

- Pattern 1 (operators) is the most compact but requires custom lexer tokens
  and is PostgreSQL-specific
- Pattern 3 (table-valued function) is the most explicit but requires the
  heaviest parser changes and is SQL Server-specific
- Pattern 2 is the middle ground: standard SQL composition, minimal parser
  impact, widely adopted (Oracle, DuckDB, ClickHouse)

## practical-usage

### when-to-use

Use the function-based pattern (Pattern 2) when:
- Building a SQL parser for a library or embedded database
- You want to minimise custom syntax and reuse standard SQL constructs
- Your users are likely familiar with multiple database systems

### when-not-to-use

- If your system is PostgreSQL-only, pgvector operators are idiomatic
- If you need explicit ANN vs. exact search control, consider Pattern 3's
  named-parameter approach

## recommendation-for-jlsm-sql

For `jlsm-sql`, the recommended syntax follows Pattern 2 (Oracle-style single
function with metric parameter):

```sql
SELECT * FROM documents
WHERE category = 'science'
ORDER BY VECTOR_DISTANCE(embedding, ?, 'cosine')
LIMIT 10;
```

Rationale:
- **Single function**: `VECTOR_DISTANCE(column, vector, metric)` — one name
  to parse, metric as a string literal parameter
- **Standard LIMIT**: reuses existing LIMIT clause for top-K
- **Bind parameter for vector**: `?` placeholder maps to a float array at
  execution time, consistent with other bind parameters in the brief
- **Translates to existing API**: maps to `OrderBy` + `Limit` in `TableQuery`,
  with the distance function becoming a vector search predicate internally
- **Alternative**: `NEAREST(column, ?, k)` as a WHERE-clause function that
  returns a boolean (row is in top-k). Simpler mental model but harder to
  compose with other ORDER BY clauses

## sources

1. [pgvector](https://github.com/pgvector/pgvector) — PostgreSQL extension;
   defines the operator-based pattern that popularised SQL vector search
2. [DuckDB VSS](https://duckdb.org/docs/stable/core_extensions/vss) —
   function-based pattern with `array_distance()` family
3. [Oracle VECTOR_DISTANCE](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/vector_distance.html) —
   single function with metric parameter; closest to proposed jlsm-sql syntax
4. [SQL Server VECTOR_SEARCH](https://learn.microsoft.com/en-us/sql/t-sql/functions/vector-search-transact-sql) —
   table-valued function pattern with named parameters
5. [ClickHouse ANN indexes](https://clickhouse.com/docs/engines/table-engines/mergetree-family/annindexes) —
   per-metric function names (`L2Distance`, `cosineDistance`)
6. [sqlite-vec](https://github.com/asg017/sqlite-vec) — MATCH operator on
   virtual tables; SQLite-specific approach

---
*Researched: 2026-03-16 | Next review: 2026-09-16*
