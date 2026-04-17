# CLAUDE.md

## Project Overview
`jlsm` is a pure Java 25 modular library implementing LSM-Tree components. Composable —
consumers use individual components to build key-value stores, vector indices, etc.

Modules: `jlsm-core` (interfaces + implementations), `jlsm-indexing`, `jlsm-vector`,
`jlsm-table` (document model + secondary indices), `jlsm-sql` (SQL parser/translator).

## Technology Stack
- **Java 25** — use modern language features; target version is 25
- **JPMS** — all modules declare `module-info.java`; keep inter-module dependencies minimal
- **Gradle** (Groovy DSL) — multi-project build

## Build Commands
```bash
./gradlew build                  # Compile + test + assemble all modules
./gradlew test                   # Run all tests
./gradlew :module:test           # Run tests for a specific submodule
./gradlew :module:test --tests "com.example.SomeTest.methodName"
```

## Git Workflow
- Never merge a PR you opened — only merge when explicitly instructed
- Never commit directly to `main` — all work on a feature branch
- Branch naming: kebab-case describing the work (e.g. `add-bloom-filter`)

## Feature Development
`.feature/<slug>/` — on-demand only. Profile: `.feature/project-config.md`
Quick: `/feature-quick "<description>"` — Full: `/feature "<description>"`
Resume: `/feature-resume "<slug>"` — Status: `/feature-resume "<slug>" --status`
Entry point: `/vallorcine-help`

## Knowledge Base & Decisions
`.kb/<topic>/<category>/<subject>.md` and `.decisions/<slug>/adr.md` — on-demand only.
Commands: `/research` `/architect` `/kb lookup` `/decisions revisit`

Setup: `/setup-vallorcine` (first time only — initializes everything)

## Codebase Quality
`/curate` — review quality signals, find stale decisions, knowledge gaps, and implicit dependencies.
`/curate --init` — first-time scan on existing codebase.
