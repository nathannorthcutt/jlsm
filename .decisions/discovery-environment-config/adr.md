---
problem: "discovery-environment-config"
date: "2026-04-13"
version: 1
status: "closed"
---

# Discovery Environment Configuration — Closed (Won't Pursue)

## Problem
Environment-specific discovery configuration — how should each DiscoveryProvider
implementation be configured for different environments?

## Decision
**Will not pursue.** This topic is explicitly ruled out and should not be raised again.

## Reason
Already resolved by the parent ADR `discovery-spi-design`. The SPI is deliberately
minimal — each `DiscoveryProvider` implementation accepts its own configuration via
constructor injection. The engine builder accepts the fully-configured provider.
No shared configuration abstraction is needed or desirable, as it would add coupling
between the SPI and specific environments, violating the minimal-contract design.

## Context
- Parent ADR: `.decisions/discovery-spi-design/adr.md` — explicitly defers environment
  config to each implementation
- KB: `.kb/distributed-systems/cluster-membership/service-discovery-patterns.md` — lists
  per-environment implementation strategies, each with unique config needs
- The engine builder supports both programmatic injection and ServiceLoader-based
  zero-config pluggability (META-INF/services/)

## Conditions for Reopening
If a unified configuration model proves necessary for operational reasons (e.g., a
configuration management system needs a single schema for all discovery providers),
reopen with `/architect "discovery-environment-config"`.
