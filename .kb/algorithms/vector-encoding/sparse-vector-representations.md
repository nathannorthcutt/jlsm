---
title: "Sparse Vector Representations and Indexing"
aliases: ["sparse vectors", "SPLADE", "learned sparse", "hybrid search"]
topic: "algorithms"
category: "vector-encoding"
tags: ["sparse", "splade", "inverted-index", "hybrid-search", "csr", "learned-sparse"]
complexity:
  time_build: "O(nnz) per vector"
  time_query: "O(nnz_q * posting_list_avg)"
  space: "O(total nnz) across corpus"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "algorithms/vector-quantization/scalar-quantization.md"
  - "algorithms/vector-quantization/binary-quantization.md"
  - "algorithms/vector-encoding/sparse-vector-encoding.md"
decision_refs: ["sparse-vector-support", "vector-storage-cost-optimization"]
sources:
  - url: "https://qdrant.tech/articles/modern-sparse-neural-retrieval/"
    title: "Modern Sparse Neural Retrieval: From Theory to Practice - Qdrant"
    accessed: "2026-04-13"
    type: "article"
  - url: "https://dl.acm.org/doi/10.1145/3626772.3657769"
    title: "Seismic: Efficient Inverted Indexes for Approximate Retrieval over Learned Sparse Representations (SIGIR 2024 Best Paper)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://arxiv.org/abs/2109.10086"
    title: "SPLADE v2: Sparse Lexical and Expansion Model for Information Retrieval"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://en.wikipedia.org/wiki/Learned_sparse_retrieval"
    title: "Learned sparse retrieval - Wikipedia"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://dl.acm.org/doi/10.1145/3626772.3657968"
    title: "SPLATE: Sparse Late Interaction Retrieval (SIGIR 2024)"
    accessed: "2026-04-13"
    type: "paper"
---

# Sparse Vector Representations and Indexing

## summary

Learned sparse representations (SPLADE, DeepImpact, uniCOIL) produce high-dimensional
vectors where most dimensions are zero and non-zero weights carry semantic importance
learned from neural models. Unlike traditional bag-of-words, these models perform term
expansion -- adding semantically related tokens not in the original text. Sparse vectors
map naturally to inverted indexes, making them a strong fit for LSM-tree storage where
term-to-posting-list is the native data model. Hybrid dense+sparse search combines
complementary retrieval signals for state-of-the-art recall.

See [sparse-vector-encoding.md](sparse-vector-encoding.md) for low-level storage formats
(COO, bitmap, CSR). This article covers the representation models, indexing strategies,
and hybrid search patterns built on top of those formats.

## learned-sparse-models

### splade

SPLADE (Sparse Lexical and Expansion Model) uses a BERT backbone with a log-saturated
attention pooling layer over the MLM head logits. For each input token position, the
model produces a weight for every vocabulary token. A max-pool across positions yields
a single sparse vector over the full vocabulary (~30K dimensions for BERT WordPiece).

```
weights = log(1 + ReLU(BERT_MLM_logits[seq_len, vocab_size]))
sparse_vector = max_pool(weights, dim=0)      # vocab_size-dim vector
```

Key properties: separate query/document encoders (SPLADE++) for asymmetric sparsity;
FLOPS regularization controls non-zero mass; typical nnz 20-60 per query, 100-300 per
document (vs 30K vocabulary); knowledge distillation from cross-encoders.

### deepimpact-unicoil-epic

| Model | Mechanism | Expansion | Typical nnz |
|-------|-----------|-----------|-------------|
| DeepImpact | 2-layer MLP over BERT per token, scalar score | Document-side only | 50-150 |
| uniCOIL | Single scalar weight per token from BERT | With docT5query expansion | 80-200 |
| EPIC | Expansion via passage interaction, contextualized | Both sides | 100-400 |

DeepImpact discards non-first WordPiece subwords; uniCOIL compensates via external
expansion (docT5query). SPLADE performs expansion implicitly through the MLM head.

## indexing-for-lsm-trees

### inverted-index-mapping

Sparse vectors map directly to an inverted index: each non-zero dimension becomes a
term, and the document is appended to that term's posting list with its weight.

```
document d = {dim_42: 1.7, dim_891: 0.3, dim_7204: 2.1}

posting_lists:
  term_42:   [..., (doc_id=d, weight=1.7)]
  term_891:  [..., (doc_id=d, weight=0.3)]
  term_7204: [..., (doc_id=d, weight=2.1)]
```

This is the same structure as `jlsm-indexing`'s `LsmInvertedIndex`, where the composite
key is `[term_bytes][doc_id_bytes]` and the value is the weight. An LSM-tree naturally
supports this: writes append to the memtable, compaction merges posting lists, and
point lookups retrieve a single posting.

### posting-list-organization

The Seismic system (SIGIR 2024 Best Paper) demonstrated that impact-ordered posting
lists -- sorted by weight descending -- combined with geometric block partitioning
dramatically improve query speed for learned sparse representations.

Key observations from Seismic:
- **Concentration of importance**: top 10 entries hold ~75% of L1 mass for queries;
  ~50 entries (~30% of nnz) hold the same for documents
- **Static pruning**: retaining only top-lambda entries per list (lambda ~6000) removes
  tail entries with minimal recall loss
- **Block partitioning**: K-means clustering on document vectors within each list creates
  geometrically-cohesive blocks; summary vectors enable block-level skipping
- **Performance**: sub-millisecond latency (303us on MS MARCO) at 95% accuracy

### compression-for-posting-lists

Posting list compression for sparse vectors differs from traditional IR:
- **Delta encoding**: sorted doc IDs compressed via delta + variable-byte coding
- **Weight quantization**: 8-bit scalar quantization of weights (4x reduction, negligible
  accuracy loss per Seismic results)
- **StreamVByte**: best trade-off between memory footprint and retrieval accuracy for
  forward index compression of learned sparse representations
- **Summary pruning**: block summary vectors retain only alpha-mass coordinates
  (alpha ~0.4) to reduce skip-structure overhead

## hybrid-dense-sparse-search

### score-fusion-strategies

```
score_rrf(d) = sum over L: 1/(k + rank_L(d))                          # RRF, k=60
score_lin(d) = alpha * norm(dense(d)) + (1-alpha) * norm(sparse(d))    # linear combo
```

| Strategy | Pros | Cons |
|----------|------|------|
| RRF | Rank-based, no calibration needed | Ignores score magnitude |
| Linear combo | Uses score magnitude | Requires normalization, alpha tuning |
| Cascade | Sparse first, dense rerank | Lower latency, sequential dependency |

### late-interaction-colbert

ColBERT stores per-token dense embeddings (128-dim) and computes MaxSim relevance
across all query-document token pairs. It is a multi-vector representation -- richer
than single-vector but more expensive than inverted-index lookup. ColBERTv2 compresses
token embeddings via residual quantization to ~2 bytes per dimension.

SPLATE (SIGIR 2024) bridges ColBERT and SPLADE: it trains a lightweight MLM adapter on
frozen ColBERTv2 token embeddings, mapping them to sparse vocabulary-space vectors. This
yields SPLADE-quality sparse representations without retraining from scratch, and enables
a single model to serve both late-interaction and inverted-index retrieval paths.

### architecture-for-hybrid-in-lsm

A practical hybrid pipeline in an LSM-backed system:
1. **Sparse retrieval** via inverted index (natural LSM-tree fit) -- top-k1 candidates
2. **Dense retrieval** via HNSW or IVF index -- top-k2 candidates
3. **Fusion** via RRF or linear combination on the union
4. **Optional rerank** via cross-encoder or ColBERT on top-k3

The sparse path reuses `LsmInvertedIndex` directly. The dense path uses `LsmVectorIndex`.
Fusion is a lightweight in-memory merge requiring no additional storage structure.

## comparison-with-dense-quantized

| Property | Sparse (SPLADE) | Dense SQ8 | Dense BQ |
|----------|----------------|-----------|----------|
| Storage per doc | ~600 bytes (150 nnz x 4B) | 768 bytes (768-dim) | 96 bytes (768-dim) |
| Index structure | Inverted index | Graph (HNSW) | Graph (HNSW) |
| Lexical precision | High | Low | Low |
| Semantic recall | Moderate | High | Moderate |
| Query latency | Sub-ms (pruned) | 1-5ms (HNSW) | <1ms (Hamming) |
| Training required | Yes (SPLADE model) | Embedding model | Embedding model |
| Streaming insert | Yes (append posting) | Partial (graph update) | Partial (graph update) |

Sparse representations excel when exact term matching matters (entity names, product
codes, rare terms). Dense quantized approaches excel for semantic similarity where
vocabulary overlap is low. Hybrid search combining both consistently outperforms either
in retrieval benchmarks (BEIR, MS MARCO).

## when-to-use

- Retrieval where lexical precision matters (entity search, product catalogs)
- Systems already using inverted indexes (natural migration path from BM25)
- LSM-tree storage where term-to-posting-list is the native model
- Hybrid pipelines where sparse handles the lexical signal

**Prefer dense-only** for pure semantic similarity, very short queries, or when
maintaining two indexes is too costly.

## sources

1. [Modern Sparse Neural Retrieval - Qdrant](https://qdrant.tech/articles/modern-sparse-neural-retrieval/) --
   comprehensive overview of SPLADE, DeepImpact, uniCOIL, and expansion models
2. [Seismic: Efficient Inverted Indexes for Learned Sparse Representations](https://dl.acm.org/doi/10.1145/3626772.3657769) --
   SIGIR 2024 Best Paper; block-partitioned impact-ordered posting lists
3. [SPLADE v2](https://arxiv.org/abs/2109.10086) -- FLOPS regularization, knowledge
   distillation, separate query/document encoders
4. [Learned sparse retrieval - Wikipedia](https://en.wikipedia.org/wiki/Learned_sparse_retrieval) --
   overview of model taxonomy: DeepCT, TILDE, Sparta, SPLADE variants
5. [SPLATE: Sparse Late Interaction Retrieval (SIGIR 2024)](https://dl.acm.org/doi/10.1145/3626772.3657968) --
   MLM adapter on frozen ColBERTv2 embeddings for dual sparse+late-interaction serving

---
*Researched: 2026-04-13 | Next review: 2026-10-13*
