---
title: "sql-query-support"
type: feature-footprint
domains: ["sql-parsing", "query-translation"]
constructs: ["JlsmSql", "SqlLexer", "SqlParser", "SqlTranslator", "SqlQuery", "SqlAst"]
applies_to:
  - "modules/jlsm-sql/src/main/**"
research_status: stable
last_researched: "2026-03-25"
---

# sql-query-support

## What it built
SQL SELECT parser and translator for `jlsm-table`. Pipeline: SQL string → lexer
(tokens) → recursive descent parser (AST) → translator (Predicate tree + query
metadata). Supports WHERE with comparisons, AND/OR/NOT, BETWEEN, IS NULL, MATCH(),
VECTOR_DISTANCE() in ORDER BY, LIMIT/OFFSET, and positional bind parameters.

## Key constructs
- `JlsmSql` — public entry point: `parse(sql, schema) → SqlQuery`
- `SqlLexer` — tokenizer with keyword recognition, string/numeric literals, bind params
- `SqlParser` — recursive descent parser producing `SqlAst.SelectStatement`
- `SqlTranslator` — AST → `SqlQuery` with schema validation
- `SqlQuery` — immutable result record with predicate, projections, ordering, limits
- `SqlQuery.BindMarker` — `Comparable` placeholder for bind parameters in predicates

## Adversarial findings
- Asymmetric operand assumption: translator assumed field-left/value-right → [KB entry](asymmetric-operand-assumption.md)
- Unchecked parse propagation: `Long.parseLong` overflow bypassed checked exception contract → [KB entry](unchecked-parse-propagation.md)

## Cross-references
- KB: .kb/algorithms/sql-extensions/vector-similarity-sql-syntax.md
- Depends on: jlsm-table (Predicate, JlsmSchema, FieldType)
