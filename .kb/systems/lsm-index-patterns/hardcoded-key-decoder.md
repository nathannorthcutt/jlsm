---
title: "Hardcoded key decoder in query executor"
type: adversarial-finding
domain: "data-integrity"
severity: "confirmed"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/QueryExecutor.java"
research_status: active
last_researched: "2026-03-25"
---

# Hardcoded key decoder in query executor

## What happens

When a query executor decodes primary keys from raw bytes back to the typed key
(String, Long, etc.), a hardcoded decoder (e.g., always UTF-8 String) produces
garbage values for non-String key types. Long-keyed tables get 8 bytes of
big-endian encoded data interpreted as UTF-8 text, which either produces an
unreadable string or throws ClassCastException when the caller accesses the key.

## Why implementations default to this

String keys are the most common case and the first implemented. The decoder is a
small utility method written early, and generic type erasure means the bug isn't
caught at compile time — `(K) decodeKey(...)` compiles for any K. Tests typically
use String keys, so the bug is invisible until Long-keyed tables are exercised.

## Test guidance

- Always test query execution with BOTH String-keyed and Long-keyed primary keys
- After calling `execute()`, verify the returned key's type and value, not just
  result count
- Use `assertInstanceOf(Long.class, entry.key())` to catch type-erased mismatches

## Found in

- table-indices-and-queries (audit round 2, 2026-03-25): QueryExecutor.decodeKey always returned `new String(bytes, UTF_8)` for all key types. Fixed by adding configurable `Function<MemorySegment, K>` key decoder.
