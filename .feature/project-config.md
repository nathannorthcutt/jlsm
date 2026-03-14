---
created: "2026-03-12"
last_updated: "2026-03-12"
---

# Project Configuration

> Read by all TDD agents at the start of every session.
> Update with /feature-init when the project profile changes.

## Language & Runtime
**Language:** Java 25 (Amazon Corretto toolchain)
**Framework:** none — pure library

## Testing
**Test framework:** JUnit Jupiter (JUnit 5)
**Test directory:** `src/test/java` (per module, standard Gradle layout)
**Test file naming:** `*Test.java`
**Test conventions:**
- Strict TDD: write tests first, confirm they fail, then implement
- No test `module-info.java` — tests run in unnamed module to avoid split-package JPMS errors
- Internal packages exported to `ALL-UNNAMED` via `jvmArgs '--add-exports'` in each module's `build.gradle`
- Assertions enabled (`-ea`) in all test runs

## Source Layout
**Source directory:** `src/main/java` (per module, standard Gradle layout)
**Module/package structure:** 4 main modules (`jlsm-core`, `jlsm-indexing`, `jlsm-vector`, `jlsm-table`), 1 integration test module (`tests/jlsm-remote-integration`), 2 benchmark modules, 1 example module. All under `modules/`, `tests/`, `benchmarks/`, `examples/` directories. Each module has its own `module-info.java` (JPMS).

## Style & Quality
**Style guide:** Eclipse formatter (`config/eclipse/eclipse-formatter.xml`) + Checkstyle 10.21.4 (`config/checkstyle/checkstyle.xml`)
**Linter / formatter:** Spotless 6.25.0 (Eclipse formatter backend), Checkstyle 10.21.4
**Key conventions:**
- Defensive assertions (`assert`) throughout all code
- Eager input validation at public API boundaries (`Objects.requireNonNull`, `IllegalArgumentException`)
- No external runtime dependencies — pure library
- Iterative over recursive algorithms
- Prefer `record` for pure value holders
- Minimal scope: `private` by default, `final` where not reassigned
- Bounded iteration and timeouts on all blocking operations
- Graceful error handling: no JVM crashes, deferred close pattern

## Security Requirements
None specified.

## Run commands
**Run tests:** `./gradlew test`
**Run single test:** `./gradlew :modules:jlsm-core:test --tests "com.example.SomeTest.methodName"`
**Run integration tests:** `./gradlew :tests:jlsm-remote-integration:test`
**Lint:** `./gradlew spotlessCheck checkstyleMain checkstyleTest`
**Type check:** n/a — Java is compiled
