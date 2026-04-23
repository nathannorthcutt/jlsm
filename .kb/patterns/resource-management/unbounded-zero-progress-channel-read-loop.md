---
type: adversarial-finding
title: "Unbounded Zero-Progress Channel Read Loop"
topic: "patterns"
category: "resource-management"
tags: ["nio", "channel", "liveness", "bounded-iteration", "stall"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-22"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/internal/TrieSSTableReader.java"
related:
  - "fan-out-iterator-leak"
  - "timeout-wrapper-does-not-cancel-source-future"
decision_refs: []
source_audit: "implement-sstable-enhancements--wd-03"
sources:
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/SeekableByteChannel.html"
    title: "SeekableByteChannel — JavaDoc"
    accessed: "2026-04-22"
    type: "docs"
---

# Unbounded Zero-Progress Channel Read Loop

## What Happens

A raw-bytes channel read loop relies on NIO's EOF (`read < 0`) or
positive-progress (`read > 0`) returns to terminate, but NIO permits
`read == 0` (documented legitimate behavior on remote providers and
`SeekableByteChannel` wrappers). Under pathological conditions — stalled
remote endpoint, buggy wrapper, adversarial channel — the channel
returns `0` forever and the loop spins indefinitely.

Canonical anti-pattern:

```java
while (buf.hasRemaining()) {
    int n = ch.read(buf);
    if (n < 0) throw eof();
    // no bound on consecutive n == 0 returns
}
```

The "every iteration must terminate" coding guideline is violated. A
single pathological channel exhausts a CPU core with no diagnostic.

## Why It Happens

The NIO contract for `SeekableByteChannel.read(ByteBuffer)` is "the
number of bytes read, possibly zero, or `-1` if the channel has reached
end-of-stream." Most programmers conflate "channel is still open" with
"channel is making progress." A zero return that persists is legitimate
per the contract; the code must treat it as a terminable stall, not a
reason to spin.

## Fix Pattern

Count consecutive zero-progress returns; throw a descriptive `IOException`
after a configured bound; reset the counter on any positive-progress
return:

```java
static final int MAX_CONSECUTIVE_ZERO_READS = 1024;

int zeroStreak = 0;
while (buf.hasRemaining()) {
    int n = ch.read(buf);
    if (n < 0) throw new EOFException(...);
    if (n == 0) {
        if (++zeroStreak > MAX_CONSECUTIVE_ZERO_READS) {
            throw new IOException(
                "channel stalled at offset " + pos
                + " after " + zeroStreak + " consecutive zero reads");
        }
    } else {
        zeroStreak = 0;
    }
}
```

Tune the bound to the expected worst-case legitimate zero streak from
the channel provider; remote providers may need higher bounds, local
FileChannel never returns 0 so the bound can be aggressive.

## Detection

Data-transformation lens built an in-memory `SeekableByteChannel` whose
`read(ByteBuffer)` always returns 0, invoked the reader via reflection,
asserted `IOException`, and set a test timeout tighter than the Gradle
task timeout to catch the spin reliably.

## Seen In

- `TrieSSTableReader.readBytes` — audit finding
  F-R1.data_transformation.C2.03.

## Test Guidance

- Build a `SeekableByteChannel` that returns 0 unconditionally; call the
  read path under a JUnit `@Timeout(seconds=5)`; assert `IOException`
  with a message that contains the offset and stall count.
- Cover the "intermittent zero" case: channel returns 0 for N iterations,
  then positive progress — the counter must reset cleanly.
- Cover EOF semantics: returning `-1` must still terminate the loop via
  the pre-existing EOF path.

## Scope

Pattern appears in one finding in this audit but is a well-known defect
class in NIO-based storage code. Applies as a defensive standard to every
future reader in jlsm that performs raw-bytes channel reads (WAL, compaction
staging, remote-backed SSTable reads).
