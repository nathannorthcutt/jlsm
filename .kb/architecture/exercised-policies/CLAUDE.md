# architecture / exercised-policies

> Empirical records of the first time a project policy ADR is exercised.
> Captures what the policy successfully bound, what it cost, what it left
> unexercised, and any open questions raised by the exercise. Per the
> project principle that theoretical policies are worse than exercised
> ones (`feedback_exercise_processes_pre_ga`), each policy gets a record
> when it first runs in anger.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| pre-ga-deprecation-policy-first-exercise | [pre-ga-deprecation-policy-first-exercise.md](pre-ga-deprecation-policy-first-exercise.md) | First exercise of `pre-ga-format-deprecation-policy` ADR — SSTable v1–v4 collapse during WD-02 planning; policy bound the v5-only baseline and erased a downstream design question; left compaction-driven rewrite, bounded sweep, watermark cross-check, and operator-triggered upgrade unexercised | 2026-04-25 |
