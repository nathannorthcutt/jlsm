# LSM Index Patterns — Category Index
*Topic: systems*

How different index types (inverted, vector, secondary) map their logical
operations to LSM tree read primitives, and the resulting I/O access patterns
at the SSTable block level. Informs block cache strategy and scan-path
optimization decisions.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [index-scan-patterns.md](index-scan-patterns.md) | Index Scan Patterns over LSM Storage | active | per-index access pattern taxonomy | Scan optimization, cache strategy |
| [composite-key-reindex-orphan.md](composite-key-reindex-orphan.md) | Composite-key re-index orphan (adversarial) | active | data-integrity bug class | Any index using composite keys with mutable assignment |
| [runtime-type-inference-ambiguity.md](runtime-type-inference-ambiguity.md) | Runtime type inference ambiguity (adversarial) | active | data-integrity bug class | Any index that infers FieldType from Java runtime type |
| [multi-index-atomicity.md](multi-index-atomicity.md) | Multi-index atomicity (adversarial) | active | data-integrity bug class | Any registry managing multiple unique indices |
| [between-mixed-type-mismatch.md](between-mixed-type-mismatch.md) | Between predicate mixed-type mismatch (adversarial) | active | data-integrity bug class | Any Between/range predicate accepting Comparable<?> |
| [between-inverted-range.md](between-inverted-range.md) | Between predicate inverted range (adversarial) | active | data-integrity bug class | Any range lookup using TreeMap.subMap or similar |
| [table-indices-and-queries.md](table-indices-and-queries.md) | table-indices-and-queries (feature footprint) | stable | feature audit record | Secondary index + query API overview |
| [builder-resource-leak-on-failure.md](builder-resource-leak-on-failure.md) | Builder resource leak on partial failure (adversarial) | active | memory-safety bug class | Any builder creating multiple closeable resources |
| [nan-score-ordering-corruption.md](nan-score-ordering-corruption.md) | NaN score ordering corruption (adversarial) | active | data-integrity bug class | Any scored result merge using Double.compare |
| [range-query-inverted-bounds.md](range-query-inverted-bounds.md) | Range query inverted bounds (adversarial) | active | data-integrity tendency | Any range query accepting (from, to) key pairs |
| [soft-delete-reindex-tombstone.md](soft-delete-reindex-tombstone.md) | Soft-delete reindex tombstone persistence (adversarial) | active | data-integrity bug class | Any index using soft-delete tombstones for lazy removal |
| [hardcoded-key-decoder.md](hardcoded-key-decoder.md) | Hardcoded key decoder in query executor (adversarial) | active | data-integrity bug class | Any generic executor decoding typed keys from bytes |
| [scan-filter-unchecked-compareto.md](scan-filter-unchecked-compareto.md) | Scan-and-filter unchecked compareTo (adversarial) | active | data-integrity bug class | Any scan-filter using Comparable.compareTo on untyped values |
| [segment-identity-equality.md](segment-identity-equality.md) | MemorySegment identity equality in records (adversarial) | active | data-integrity bug class | Any record with MemorySegment component fields |
| [deferred-close-catch-scope.md](deferred-close-catch-scope.md) | Deferred close loops catch only IOException (adversarial) | active | memory-safety bug class | Any deferred-close loop over Closeable resources |
| [nan-at-construction.md](nan-at-construction.md) | NaN score accepted at construction (adversarial) | active | data-integrity bug class | Any value type carrying numeric score for ordering |
| [record-result-missing-validation.md](record-result-missing-validation.md) | Public record result missing compact constructor validation (adversarial) | active | data-integrity tendency | Any public record carrying score or required reference |
| [vector-mutable-array-input.md](vector-mutable-array-input.md) | Mutable vector array stored by reference at construction (adversarial) | active | data-integrity bug class | Any construct accepting float[]/short[] across trust boundary |
| [vector-field-type.md](vector-field-type.md) | vector-field-type (feature footprint) | stable | feature audit record | VectorType field + index + serialization overview |
| [iterator-use-after-close.md](iterator-use-after-close.md) | Iterator use-after-close on non-static inner class iterators (adversarial) | active | data-integrity bug class | Any reader returning iterators from close-guarded methods |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [index-scan-patterns.md](index-scan-patterns.md) — access pattern taxonomy and cache strategy analysis

## Research Gaps
- Compaction impact on index scan locality (how does compaction reorder blocks relative to index access patterns?)
- Benchmark data for centroid-pinning cache tier vs standard LRU for IVF-Flat workloads
- Cross-index cache contention analysis (HNSW point-gets vs inverted-index scans sharing one BlockCache)

## Shared References Used
@../../_refs/complexity-notation.md
