---
problem: "aad-attribute-set-evolution"
date: "2026-04-23"
version: 1
status: "deferred"
---

# AAD Attribute-Set Evolution — Deferred

## Problem

How should jlsm handle adding a new required `EncryptionContext` attribute between versions? If jlsm v1.N+1 requires an attribute that v1.N did not supply, unwrap of v1.N-wrapped DEKs will fail because the AAD bytes differ. This is an ecosystem-migration problem distinct from the canonical encoding itself.

## Why Deferred

Scoped out during `aad-canonical-encoding` decision. The canonical encoding stabilizes the *format*; attribute-set evolution is a separate concern that only becomes blocking when a concrete requirement to add a new attribute appears.

## Resume When

When `aad-canonical-encoding` implementation is stable and a specific proposal to add a new required context attribute appears (e.g., a `region` or `env` attribute for multi-region deployments).

## What Is Known So Far

Identified during architecture evaluation of `aad-canonical-encoding`. See `.decisions/aad-canonical-encoding/adr.md` for the architectural context — specifically the "What This Decision Does NOT Solve" section.

Precedent: R80a-1 already handles one attribute-set variation (DEK purpose adds `tableId` + `dekVersion`, other purposes exclude them). The mechanism is static per-purpose, not version-driven. A version-driven attribute set would need either (a) a version stamp in the AAD itself or (b) a policy that new attributes are only added on major version bumps with a coordinated re-wrap.

## Next Step

Run `/architect "AAD attribute-set evolution"` when ready to evaluate.
