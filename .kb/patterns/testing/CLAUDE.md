# patterns / testing

> Anti-patterns and fix patterns for test reliability and determinism.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| wall-clock-dependency-in-duration-logic | [wall-clock-dependency-in-duration-logic.md](wall-clock-dependency-in-duration-logic.md) | Direct Instant.now() in duration logic; fix with injectable Clock and single-capture | 2026-04-06 |
| static-mutable-state-test-pollution | [static-mutable-state-test-pollution.md](static-mutable-state-test-pollution.md) | Static ConcurrentHashMap registries leaking across tests; fix with AutoCloseable or instance-scoped | 2026-04-06 |
