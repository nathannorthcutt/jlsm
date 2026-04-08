---
title: "Untrusted Storage Byte Length"
aliases: ["trusted byte array length", "missing decode length check", "storage corruption silent decode"]
topic: "patterns"
category: "validation"
tags: ["deserialization", "storage", "corruption", "bounds-check", "decode"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-08"
applies_to:
  - "modules/jlsm-vector/src/main/java/jlsm/vector/LsmVectorIndex.java"
related:
  - "incomplete-serialization-round-trip"
decision_refs: []
sources: []
---

# Untrusted Storage Byte Length

## Summary

Internal decode routines trust that byte arrays read from storage have the
expected length without runtime validation. When storage is corrupted, compacted
incorrectly, or a serializer produces variable-width output, the decode path
either throws an uninformative `ArrayIndexOutOfBoundsException`, silently
decodes garbage (trailing zeros from under-read), or reads past the intended
boundary. The core issue is that persistence boundaries are treated as trusted
internal calls rather than untrusted data sources requiring length guards.

## Problem

A decode method assumes the byte array it receives has a specific length
dictated by the data type (e.g., 4 bytes for an int, `dimensions * 2` bytes
for a float16 vector). It proceeds to read bytes at fixed offsets without
checking that the array actually has enough bytes. If the array is truncated,
oversized, or variable-width, the method either crashes with an unhelpful
exception, silently produces wrong values, or reads into adjacent data.

```java
// WRONG: trusts that bytes.length == 4
int decodeCentroidId(byte[] bytes) {
    return (bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3];
}

// WRONG: trusts that bytes.length == dimensions * 2
float[] decodeFloat16s(byte[] bytes, int dimensions) {
    float[] result = new float[dimensions];
    for (int i = 0; i < dimensions; i++) {
        result[i] = fromFloat16(bytes[i * 2], bytes[i * 2 + 1]);
    }
    return result;
}

// WRONG: assumes neighbor count from fixed-width encoding
int[] decodeNeighborIds(byte[] bytes) {
    int count = bytes.length / 4;  // silently drops remainder bytes
    int[] ids = new int[count];
    for (int i = 0; i < count; i++) {
        ids[i] = decodeInt(bytes, i * 4);
    }
    return ids;
}
```

## Symptoms

- `ArrayIndexOutOfBoundsException` with no context about what was being decoded
  or what length was expected vs actual
- Silent data corruption: truncated vectors filled with zero-valued trailing
  floats, producing incorrect distance calculations
- Neighbor arrays with phantom zero-ID entries from trailing partial bytes
- Corrupt storage that propagates through compaction because the decode never
  rejected the bad input

## Root Cause

Decode routines are written as if they are internal methods consuming data from
a trusted in-process source. But storage is an external boundary: files can be
truncated by crashes, corrupted by disk errors, or written by a different
serializer version. The persistence layer must be treated with the same
suspicion as network input.

## Fix Pattern

1. **Guard every decode entry point.** Before accessing any byte, check that
   the array length matches the expected length exactly. Throw a descriptive
   `IOException` or `IllegalArgumentException` that includes both expected and
   actual lengths.

2. **Reject partial data explicitly.** For fixed-width encodings, require an
   exact length match — not just "at least N bytes." An oversized array is also
   suspicious and should be rejected or logged.

3. **Include context in error messages.** The exception message should identify
   what was being decoded (e.g., "centroid ID", "float16 vector"), the expected
   byte count, and the actual byte count.

4. **Validate alignment for multi-element arrays.** When decoding arrays of
   fixed-width elements (e.g., 4-byte neighbor IDs), check that the byte array
   length is an exact multiple of the element width. Reject arrays with
   trailing partial elements.

```java
int decodeCentroidId(byte[] bytes) {
    if (bytes.length != 4) {
        throw new IllegalArgumentException(
            "centroid ID requires exactly 4 bytes, got " + bytes.length);
    }
    return (bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3];
}

float[] decodeFloat16s(byte[] bytes, int dimensions) {
    int expected = dimensions * 2;
    if (bytes.length != expected) {
        throw new IllegalArgumentException(
            "float16 vector decode: expected " + expected
            + " bytes for " + dimensions + " dimensions, got " + bytes.length);
    }
    float[] result = new float[dimensions];
    for (int i = 0; i < dimensions; i++) {
        result[i] = fromFloat16(bytes[i * 2], bytes[i * 2 + 1]);
    }
    return result;
}
```

## Detection

- Data transformation lens: test decode routines with truncated, oversized, and
  zero-length byte arrays; check whether exceptions are descriptive or whether
  garbage values are silently returned
- Grep for decode/deserialize methods that index into `byte[]` or
  `MemorySegment` without a preceding length check
- Look for `bytes.length / N` patterns that silently discard remainder bytes

## Audit Findings

Identified in float16-vector-support audit run-001:
- `decodeCentroidId()` — `ArrayIndexOutOfBoundsException` on truncated input (F-R6.dt.1.1)
- `decodeFloats()`/`decodeFloat16s()` — silent garbage decode on short vectors (F-R6.dt.1.2)
- `encodeNode()`/`decodeNode()` — fixed-width neighbor assumption corrupts data (F-R6.dt.1.4)
