# patterns / adversarial-review

> Patterns and gaps discovered in the adversarial-review pipeline itself —
> probes that should exist during `/spec-author` or `/feature-harden` but
> currently don't, usually surfaced by `/audit` finding what the earlier
> passes missed.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| hash-function-algebraic-probes | [hash-function-algebraic-probes.md](hash-function-algebraic-probes.md) | spec-authoring falsification misses algebraic pre-image collisions in pinned hash combine rules; audit catches what spec-author missed | 2026-04-21 |
