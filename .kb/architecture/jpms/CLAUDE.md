# architecture / jpms

> JPMS (Java Platform Module System) patterns and constraints that shape jlsm's architecture and spec authoring.

| Subject | File | Description | Added |
|---------|------|-------------|-------|
| module-dag-spec-anticipation | [module-dag-spec-anticipation.md](module-dag-spec-anticipation.md) | Sealed-type specs must anticipate that legitimate construction callers may live in sibling public packages within the same module — JPMS package-visibility is per-package, not per-module. Pattern statement, three options, canonical spec phrasing. | 2026-04-25 |
