---
title: "Iterator use-after-close on non-static inner class iterators"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/*"
research_status: active
last_researched: "2026-03-25"
---

# Iterator use-after-close on non-static inner class iterators

## What happens

Non-static inner class iterators that access enclosing class I/O resources (channels,
data arrays) do not check the enclosing object's `closed` flag in their `advance()`
method. After the enclosing reader is closed:

- **Eager mode** (data pre-loaded in memory): iteration silently continues, returning
  valid entries from a conceptually-closed reader. Violates close semantics.
- **Lazy mode** (data read on demand via channel): iteration throws
  `UncheckedIOException(ClosedChannelException)` instead of the contract-expected
  `IllegalStateException("reader is closed")`. The wrong exception type breaks
  error handling in callers that pattern-match on exception type.

## Why implementations default to this

The `checkNotClosed()` guard is placed at the public API entry points (`scan()`,
`get()`) but not in the iterator's internal `advance()` method. The iterator is
constructed from within the checked entry point, so the developer assumes the closed
check is "already done." However, the iterator outlives the entry point call — it is
returned to the caller, who may close the reader and then continue iterating.

## Test guidance

- For any reader/writer that returns an iterator from a method guarded by a
  closed check: test that iterating AFTER closing the reader throws the correct
  exception (`IllegalStateException` for close-guarded types).
- Test both eager and lazy modes — they exhibit different failure modes.
- Test with multi-block data to ensure the iterator must actually advance past
  the prefetched entry to trigger the read.

## Found in

- streaming-block-decompression (audit round 1, 2026-03-25): `CompressedBlockIterator.advance()`
  and `IndexRangeIterator.advance()` in `TrieSSTableReader`. Fixed by adding
  `if (closed) throw new IllegalStateException("reader is closed")` at the top of both methods.
