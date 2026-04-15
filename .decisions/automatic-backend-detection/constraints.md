---
problem: "Automatic backend detection for SSTable block size defaults"
slug: "automatic-backend-detection"
captured: "2026-04-14"
status: "draft"
---

# Constraint Profile — automatic-backend-detection

## Problem Statement
Should TrieSSTableWriter automatically select a default block size based on the
target Path's FileSystem/FileStore metadata, rather than always defaulting to 4096?

## Constraints

### Scale
Library used across local SSD, NFS, S3, GCS backends. Block size range
4KB–32MB already validated by SSTableFormat. The auto-detection must work
correctly across all NIO FileSystem providers.

### Resources
Pure Java 25, JPMS, no external dependencies. Detection mechanism must use
only java.nio.file APIs available in the JDK.

### Complexity Budget
Convenience feature — must be zero-surprise. Wrong auto-detection is worse
than no auto-detection. The explicit blockSize() builder method is always
available as an override.

### Accuracy / Correctness
Detected block size must be a reasonable default, not necessarily optimal.
A wrong default that silently degrades performance is unacceptable. Falling
back to DEFAULT_BLOCK_SIZE (4096) is acceptable if detection fails.

### Operational Requirements
Detection must be zero-cost at writer construction time — no I/O, no network
calls, no blocking operations. Must not throw on any FileSystem provider.

### Fit
Java NIO FileSystem SPI is the integration point. Path is already required
by the writer. FileStore and FileSystem are the available metadata sources.

## Key Constraints (most narrowing)
1. **Zero-cost** — detection must not perform I/O or block; metadata queries only
2. **Zero-surprise** — wrong detection is worse than no detection; fallback to 4096 is safe
3. **NIO SPI only** — must work with any compliant FileSystem provider without provider-specific code

## Unknown / Not Specified
None — full profile captured.
