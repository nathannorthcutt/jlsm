---
problem: "authenticated-discovery"
date: "2026-04-13"
version: 1
status: "deferred"
---

# Authenticated Discovery — Deferred

## Problem
Should cluster nodes authenticate during discovery/bootstrap, and if so, what
mechanism (mTLS, shared token, TOFU)?

## Why Deferred
Transport TLS is not yet implemented. The connection-pooling ADR explicitly states
"assume plain TCP initially." Authentication is orthogonal to the discovery SPI —
it's a transport-layer concern layered on top of TCP connections. Evaluating auth
strategies before the transport has TLS support would produce a decision without
an implementation target.

## Resume When
When transport TLS support is implemented and the cluster needs to operate on
untrusted networks.

## What Is Known So Far
KB research at `.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`
covers mTLS, shared-token (HMAC), and TOFU strategies with a comparison matrix.
Production systems: CockroachDB requires mTLS by default, Cassandra makes it optional,
Consul uses gossip encryption + mTLS. The recommendation for jlsm will likely be
mTLS for production with shared-token as a simpler alternative for small clusters.

## Next Step
Run `/architect "authenticated-discovery"` when transport TLS is available.
