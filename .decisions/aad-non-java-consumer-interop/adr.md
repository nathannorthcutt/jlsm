---
problem: "aad-non-java-consumer-interop"
date: "2026-04-23"
version: 1
status: "deferred"
---

# AAD Non-Java Consumer Interoperability — Deferred

## Problem

The AAD canonical encoding is language-agnostic (the BNF in `aad-canonical-encoding` ADR is sufficient to implement in any language), but no dedicated implementation guide for non-Java consumers currently exists. This becomes blocking if a concrete non-Java client of jlsm wrapped-DEK format appears (e.g., a Python backup/restore tool, a Go migration utility, a cross-language compliance scanner).

## Why Deferred

Scoped out during `aad-canonical-encoding` decision. No non-Java consumer currently exists. Writing an implementation guide speculatively would risk specifying details that the first real consumer would need differently.

## Resume When

When a concrete non-Java consumer requirement appears, such as:
- A Python/Go backup-restore tool needs to verify DEK wraps against KMS
- A compliance scanner needs to decrypt DEKs offline in a non-JVM language
- A migration utility moves jlsm-wrapped DEKs between jlsm and a non-Java system

## What Is Known So Far

Identified during architecture evaluation of `aad-canonical-encoding`. See `.decisions/aad-canonical-encoding/adr.md` for the canonical encoding that any non-Java implementation must match. The BNF in the Decision section is sufficient for a correct implementation; what this ADR would add is:

- Reference test vectors (input `Map<String,String>` + expected AAD hex)
- Language-specific implementation notes (e.g., for Python, handling `str` → UTF-8 bytes explicitly; for Go, handling `map[string]string` ordering via `sort.Strings`)
- A conformance test suite that non-Java implementations can run against jlsm-wrapped DEKs

## Next Step

Run `/architect "AAD non-Java consumer interop"` when ready to evaluate.
