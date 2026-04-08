---
title: "Mutation Outside Rollback Scope"
aliases: ["partial rollback", "unscoped mutation"]
topic: "patterns"
category: "resource-management"
tags: ["resource-management", "rollback", "consistency", "mutation"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-table/src/main/java/jlsm/table/IndexRegistry.java"
related:
  - "partial-init-no-rollback"
decision_refs: []
sources: []
---

# Mutation Outside Rollback Scope

## Summary

In multi-step mutation operations with try/catch rollback, one or more mutation
steps are placed outside the rollback scope. If a later step fails, the
out-of-scope mutation is not rolled back, leaving the system in an inconsistent
state (e.g., index updated but document store not, or vice versa). The fix is
to move all related mutations inside the same try/catch rollback scope.

## Problem

A multi-step mutation where one step is outside the rollback:

```java
public void onUpdate(String id, Document doc) {
    documentStore.put(id, doc);  // outside try — not rolled back
    try {
        index1.update(id, doc);
        index2.update(id, doc);
    } catch (Exception e) {
        index1.rollback(id);
        index2.rollback(id);
        throw e;
    }
}
```

If `index1.update` fails, `documentStore.put` has already committed and is not
rolled back. The document store and index are now inconsistent.

## Symptoms

- Inconsistent state after a failed operation (data in one store, missing in another)
- Queries return stale or phantom results
- Recovery or retry does not fix the inconsistency because the partial mutation
  is already committed

## Root Cause

The developer places the "safe" mutation outside the try/catch, assuming it
cannot fail or that its success is independent. But rollback must cover all
mutations that are part of the same logical operation, regardless of failure
likelihood.

## Fix Pattern

Move all related mutations inside the same rollback scope:

```java
public void onUpdate(String id, Document doc) {
    try {
        documentStore.put(id, doc);
        index1.update(id, doc);
        index2.update(id, doc);
    } catch (Exception e) {
        documentStore.rollback(id);
        index1.rollback(id);
        index2.rollback(id);
        throw e;
    }
}
```

If true atomicity is needed, use a write-ahead log or compensating transaction
pattern.

## Detection

- Look for try/catch blocks that perform rollback — check whether all mutations
  for the logical operation are inside the try block
- shared_state lens: trace mutation ordering and rollback coverage
- Check whether mutations before or after the try/catch affect the same
  logical entity

## Audit Findings

Identified in vector-field-type audit run-001:
- `IndexRegistry.onUpdate` — `documentStore.put` outside rollback scope
- `IndexRegistry.onDelete` — `documentStore.remove` outside rollback scope
