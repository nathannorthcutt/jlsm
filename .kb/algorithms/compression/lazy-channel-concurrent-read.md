---
title: "Lazy channel position-then-read race condition"
type: adversarial-finding
domain: "concurrency"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/TrieSSTableReader.java"
research_status: active
last_researched: "2026-03-26"
---

# Lazy channel position-then-read race condition

## What happens

Lazy SSTable readers share a single `SeekableByteChannel` for on-demand block
reads. The `readBytes(channel, offset, length)` helper calls `ch.position(offset)`
followed by `ch.read(buf)` — a two-step sequence that is not atomic. When two
threads concurrently call `get()` or `scan()` on the same lazy reader, thread A's
`position()` can be overwritten by thread B's `position()` before thread A's
`read()` executes. Thread A then reads data from the wrong file offset, producing
silently corrupt decompressed blocks or entry data.

## Why implementations default to this

`SeekableByteChannel` has a mutable position, and the position+read pattern looks
correct in single-threaded contexts. Developers often assume callers will use
separate reader instances per thread, but the `SSTableReader` API doesn't document
thread-safety requirements.

## Test guidance

- For any lazy reader sharing a `SeekableByteChannel`: spawn N threads doing
  concurrent `get()` calls for keys in different blocks. Verify all returned
  values are correct across many iterations.
- The safe pattern: `synchronized (channel) { channel.position(offset); read; }`
  or use `FileChannel.read(buf, position)` which is inherently atomic.

## Found in

- block-compression (audit round 2, 2026-03-26): `TrieSSTableReader.readBytes`
  position-then-read was unsynchronized. Fixed by synchronizing on the channel object.
