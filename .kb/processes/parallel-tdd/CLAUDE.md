# processes / parallel-tdd
*Topic: processes*
*Tags: parallel-tdd, coordinator, feature-coordinate, balanced-mode, speed-mode, check-vs-test, checkstyle, spotless, sealed-classes, integration-frontier, OOM, dispatch-discipline, subagent, verification-gate*

> Patterns and disciplines specific to running parallel TDD via `/feature-coordinate` (balanced or speed mode), where 2+ subagents run autonomous test/implement/refactor pipelines on a shared module tree. Failures here are usually about the gap between the subagent's inner-loop verification (`:test`) and the coordinator's outer-loop merge gate (`:check`).

## Contents

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| parallel-coordinator-check-discipline | [parallel-coordinator-check-discipline.md](parallel-coordinator-check-discipline.md) | Subagents must run `:check` (not `:test`) before COMPLETE; coordinator must single-threaded backstop post-batch; cap concurrent units to ≤ 2 in heavy projects to avoid OOM-flake on adversarial stress tests | 2026-04-27 |
