# sql-extensions — Category Index
*Topic: algorithms*

SQL syntax extensions for non-traditional query operations — vector similarity
search, full-text matching, and other domain-specific query patterns that extend
standard SQL SELECT semantics.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [vector-similarity-sql-syntax.md](vector-similarity-sql-syntax.md) | Vector Similarity Search SQL Syntax | active | N/A (survey) | Choosing SQL syntax for vector nearest-neighbor queries |
| [asymmetric-operand-assumption.md](asymmetric-operand-assumption.md) | Asymmetric operand assumption (adversarial) | active | data-integrity tendency | SQL translator operand handling |
| [unchecked-parse-propagation.md](unchecked-parse-propagation.md) | Unchecked parse exception propagation (adversarial) | active | data-integrity tendency | Numeric parsing in translators |
| [bind-parameter-comparable-gap.md](bind-parameter-comparable-gap.md) | Bind parameter Comparable gap (adversarial) | active | data-integrity confirmed | Bind params with range operators |
| [missing-field-type-validation.md](missing-field-type-validation.md) | Missing field-type validation (adversarial) | active | data-integrity confirmed | Type checking in SQL translators |
| [unbounded-recursion-depth.md](unbounded-recursion-depth.md) | Unbounded recursion depth (adversarial) | active | security tendency | Recursive descent parsers |
| [sql-query-support.md](sql-query-support.md) | sql-query-support (feature footprint) | stable | feature audit record | SQL parser/translator overview |
| [locale-dependent-string-operations.md](locale-dependent-string-operations.md) | Locale-dependent string operations (adversarial) | active | data-integrity confirmed | String case conversion without Locale.ROOT |
| [resource-exhaustion-list-bounds.md](resource-exhaustion-list-bounds.md) | Resource exhaustion via unbounded list accumulation (adversarial) | active | security confirmed | Parser accumulator lists without size bounds |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [vector-similarity-sql-syntax.md](vector-similarity-sql-syntax.md) — survey of all major approaches

## Research Gaps
- Full-text search SQL syntax extensions (MATCH, CONTAINS, FREETEXT patterns)
- Hybrid search syntax (combining vector + full-text + scalar in one query)

## Shared References Used
@../../_refs/complexity-notation.md
