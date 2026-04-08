---
title: "Wall-Clock Dependency in Duration Logic"
aliases: ["Instant.now() in tests", "non-deterministic time", "clock injection"]
topic: "patterns"
category: "testing"
tags: ["testing", "time", "determinism", "clock-injection"]
research_status: "stable"
confidence: "high"
last_researched: "2026-04-06"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/GracePeriodManager.java"
related:
  - "static-mutable-state-test-pollution"
decision_refs: []
sources:
  - url: "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html"
    title: "java.time.Clock — injectable time source"
    accessed: "2026-04-06"
    type: "docs"
---

# Wall-Clock Dependency in Duration Logic

## Summary

Time-based logic (grace period expiration, heartbeat interval measurement) that
calls `Instant.now()` or `System.currentTimeMillis()` directly instead of using
an injectable time source. This causes non-deterministic tests, vulnerability
to clock adjustments (NTP, DST), and inconsistent timestamps when multiple
clock reads occur within a single logical operation.

## Problem

```java
public boolean isExpired(Instant departureTime) {
    return Duration.between(departureTime, Instant.now())
                   .compareTo(gracePeriod) > 0;
}

public List<MemberId> expiredDepartures() {
    return departures.entrySet().stream()
        .filter(e -> isExpired(e.getValue()))  // calls Instant.now() per entry
        .map(Map.Entry::getKey)
        .toList();
}
```

Three problems:
1. **Non-deterministic tests** — test outcomes depend on wall-clock timing,
   causing flaky tests at boundary conditions
2. **Clock adjustment vulnerability** — NTP step or DST change can cause
   premature or delayed expiration
3. **Multi-read inconsistency** — `expiredDepartures()` calls `Instant.now()`
   once per entry; entries near the boundary may see different "now" values

## Symptoms

- Tests that pass locally but fail in CI (or vice versa) due to timing
- Flaky tests at exact expiration boundaries
- Grace periods that expire too early or too late after clock adjustments
- Inconsistent behavior where some entries in a batch are treated as expired
  and others are not, despite identical timestamps

## Root Cause

Direct coupling to the system clock (`Instant.now()`, `System.currentTimeMillis()`)
instead of using an injectable time source. The code cannot be tested
deterministically because the input (current time) cannot be controlled.

## Fix Pattern

1. **Inject a `Clock`** — accept `java.time.Clock` as a constructor parameter
   with a default of `Clock.systemUTC()`:

   ```java
   private final Clock clock;

   public GracePeriodManager(Duration gracePeriod, Clock clock) {
       this.clock = clock;
       this.gracePeriod = gracePeriod;
   }
   ```

2. **Single-capture per operation** — read the clock once at the start of an
   operation and use that value throughout:

   ```java
   public List<MemberId> expiredDepartures() {
       Instant now = clock.instant();  // single capture
       return departures.entrySet().stream()
           .filter(e -> isExpired(e.getValue(), now))
           .map(Map.Entry::getKey)
           .toList();
   }
   ```

3. **Use `Clock.fixed()` in tests** — deterministic, controllable, no flakiness:

   ```java
   var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
   var manager = new GracePeriodManager(Duration.ofMinutes(5), clock);
   ```

## Detection

- Grep for `Instant.now()`, `System.currentTimeMillis()`, `System.nanoTime()`
  in non-test production code that performs duration comparisons
- Look for multiple clock reads within a single method (multi-read inconsistency)
- Flaky test history on time-sensitive assertions

## Audit Findings

Identified in engine-clustering audit run-001:
- `GracePeriodManager.isInGracePeriod` — direct `Instant.now()` call
- `GracePeriodManager.expiredDepartures` — per-entry `Instant.now()` via filter
- `GracePeriodManager.recordDeparture` — `Instant.now()` for timestamp capture
