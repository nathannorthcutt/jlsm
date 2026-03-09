# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`jlsm` is a pure Java 25 modular library implementing LSM-Tree (Log-Structured Merge-Tree) components. It is designed to be composable — consumers can use individual components to build higher-level products such as key-value stores and vector database indices.

## Technology Stack

- **Java 25** — uses modern language features; target Java version is 25
- **JPMS** — all modules declare `module-info.java`; keep inter-module dependencies explicit and minimal
- **Gradle** (Groovy DSL, `build.gradle`) — multi-project build

## Build Commands

```bash
./gradlew build          # Compile + test + assemble all modules
./gradlew test           # Run all tests
./gradlew check          # Run all verification (tests, linting, etc.)
./gradlew :module:test   # Run tests for a specific submodule

# Run a single test class
./gradlew :module:test --tests "com.example.SomeTest"

# Run a single test method
./gradlew :module:test --tests "com.example.SomeTest.methodName"
```

@standards/architecture.md
@standards/testing.md

## Git Workflow

- **Never commit directly to `main`** — all work must be done on a feature branch
- **Branch naming** — use kebab-case names that describe the work (e.g., `add-bloom-filter`, `fix-wal-recovery`)
- **Starting work** — if currently on `main`, ask whether there is an existing branch to switch to or gather enough context to create an appropriate branch before proceeding
- **Finishing work** — when work is complete, open a PR with a summary of changes and request a review before merging to `main`
