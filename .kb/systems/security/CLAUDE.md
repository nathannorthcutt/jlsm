# Security — Category Index
*Topic: systems*

Security patterns for JVM-based storage systems — key management, encryption
integration, and in-memory data protection.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) | JVM Key Handling Patterns | stable | Off-heap Arena key storage | Caller-provided key lifecycle management |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) — key lifecycle, KMS integration, Arena-based storage

## Research Gaps
- Threat modeling for encrypted LSM-tree components (WAL, SSTable, MemTable)
- Java Security Manager alternatives post-deprecation
