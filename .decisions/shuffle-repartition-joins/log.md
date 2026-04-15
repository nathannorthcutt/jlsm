## 2026-04-14 — deferred

**Agent:** Architect Agent
**Event:** deferred
**Parent ADR:** distributed-join-execution
**Summary:** Shuffle/repartition join strategy deferred. Two-tier (co-partitioned + broadcast) covers >90% of workloads. Resume when large non-co-partitioned joins become a common use case and the reject path is unacceptable.

---
