---
type: adversarial-finding
domain: validation
severity: confirmed
tags: [simd, boundary, escape-state, chunked-processing, panama, vector-api]
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/"
sources:
  - json-only-simd-jsonl audit run-001, 2026-04-12
---

# SIMD Block-Boundary Carry State

## Pattern

SIMD processing that partitions input into fixed-size blocks (64-byte for Panama
clmul, LANE_COUNT for Vector API) must carry escape/quoting state across block
boundaries. Without carry, a backslash at the last position of block N does not
affect classification of the first byte of block N+1, causing escaped quotes to
be misclassified as structural characters. The same class of bug affects the
transition from the last SIMD block to the scalar tail loop.

Both Panama clmul-based and Vector API shift-XOR-based implementations are
susceptible.

## Why It Happens

SIMD implementations naturally operate on independent blocks. Escape
classification (backslash-quote pairs) is inherently sequential state — the
meaning of a byte depends on the byte before it. When the block boundary falls
between a backslash and the character it escapes, the second block has no
knowledge that the preceding byte was an escape character. The same gap exists
at the SIMD-to-scalar handoff: the scalar tail loop starts with a clean state
instead of inheriting the final escape flag from the last SIMD block.

## Fix

Maintain a single-bit carry flag (`prevBlockEscaped`) across block iterations:

```java
boolean carry = false;
for (int blockStart = 0; blockStart < len; blockStart += BLOCK_SIZE) {
    // If carry is set, the first byte of this block is escaped
    // and must not be treated as structural
    boolean firstByteEscaped = carry;

    // ... SIMD classification of block ...

    if (firstByteEscaped) {
        structuralMask &= ~1L; // clear bit 0
    }

    // Carry out: was the last byte of this block a backslash?
    carry = (data[blockStart + BLOCK_SIZE - 1] == '\\')
            && !isEscaped(blockStart + BLOCK_SIZE - 1);
}
// Pass carry into scalar tail loop
scalarTail(carry, ...);
```

## Detection

- Contract-boundaries lens: boundary condition analysis at block-size-aligned
  positions
- Adversarial test: place a backslash at byte 63 of a 64-byte block followed by
  a quote at byte 0 of the next block; verify the quote is classified as escaped
- Adversarial test: verify scalar tail inherits carry from the last SIMD block

## Scope

Applies to any SIMD text-processing pipeline that classifies characters based on
preceding escape sequences: JSON parsing, CSV parsing, string literal scanning.
The pattern is not specific to any single SIMD instruction set — it affects both
Panama Foreign Function clmul approaches and the Vector API shift-XOR approach.

Affected constructs in json-only-simd-jsonl: PanamaStage1 (F-R1.cb.3.1),
VectorStage1 (F-R1.cb.3.3).
