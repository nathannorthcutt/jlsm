# patterns / distributed-systems

> Anti-patterns and fix patterns specific to distributed system components.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| stub-client-data-loss | [stub-client-data-loss.md](stub-client-data-loss.md) | Stub implementations that satisfy type system but drop payloads; fix with round-trip tests | 2026-04-06 |
| local-failure-masquerading-as-remote-outage | [local-failure-masquerading-as-remote-outage.md](local-failure-masquerading-as-remote-outage.md) | Uniform catch on remote-dispatch path collapses local-origin errors into node-unavailability; fix by splitting try scope so only transport failures produce partition-unavailable | 2026-04-20 |
