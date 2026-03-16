---
glob: "**/src/jmh/**/*.java, **/perf-output/**, **/perf-scratch/**"
---

When working with JMH benchmarks, scratch benchmarks, or profiler output:

1. Always check `.claude/skills/perf-review/perf-context.md` for known
   hotpaths, Gradle invocation conventions, and output path structure.
2. Always check `perf-output/findings.md` before starting analysis to
   avoid re-investigating already-confirmed findings.
3. Before writing any benchmark, apply Decision gate 1 (scratch vs regression)
   and Decision gate 2 (snapshot vs sustained) from the perf-review skill.
4. Scratch benchmarks live in `perf-scratch/` and are always deleted after
   analysis. Never commit anything from `perf-scratch/`.
5. When writing sustained benchmarks, ensure `@State(Scope.Benchmark)` is
   used and state is NOT reset between measurement iterations — this is the
   critical difference from snapshot benchmarks.
6. Never modify `perf-output/findings.md` mid-session — only append during
   the session close protocol.
