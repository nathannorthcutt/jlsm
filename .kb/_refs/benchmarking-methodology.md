---
type: reference-fragment
title: Benchmarking Methodology
---
# How Benchmarks Are Cited

Unless otherwise noted, performance figures reference the ANN-Benchmarks
suite (https://ann-benchmarks.com) under these conditions:
- Datasets: GloVe-100 (1.2M 100-dim vectors) or SIFT-1M (1M 128-dim vectors)
- Hardware: single CPU core unless GPU explicitly noted
- Metric: queries-per-second (QPS) at recall@10 = 0.90
- All figures are approximate — hardware variance is ±15%

When citing non-ANN-Benchmarks figures, state dataset, hardware, and metric explicitly.
