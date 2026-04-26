# jlsm-cluster

Cluster transport SPI and NIO-based multiplexed-framing implementation. Hosts the foundational
networking layer used by membership protocols, scatter-gather query dispatch, and (future)
encryption-on-wire.

## Dependencies

- `jlsm.core` — module dependencies only (no transitive types yet)

`jlsm.engine` depends on this module via `requires transitive jlsm.cluster` so cluster API types
flow through the engine's own public surface to consumers.

## Exported Packages

- `jlsm.cluster` — public API: `ClusterTransport` SPI, `Message`, `MessageType`, `MessageHandler`,
  `NodeAddress`

## Internal Packages

Not exported in `module-info.java`:

- `jlsm.cluster.internal` — `MultiplexedTransport` (top-level NIO impl), `PeerConnection`,
  `Frame` + `FrameCodec`, `Handshake`, `PendingMap`, `Reassembler`, `Chunker`, `AbuseTracker`,
  `DispatchPool`, `TransportMetrics`, plus the legacy in-JVM test stub `InJvmTransport` and
  shared codec helper `NodeAddressCodec`

The qualified export `exports jlsm.cluster.internal to jlsm.engine` is in place because
`NodeAddressCodec` is consumed by membership-protocol code in jlsm-engine. External modules
cannot reach the internal package.

## Key Design Decisions

- **Module placement** — `jlsm-cluster` is a single new module sitting below jlsm-engine in the
  DAG. ([ADR](.decisions/transport-module-placement/adr.md))
- **Transport abstraction** — Message-oriented SPI with `send` (fire-and-forget) and `request`
  (request-response with `CompletableFuture`). ([ADR](.decisions/transport-abstraction-design/adr.md))
- **Connection management** — Single TCP connection per peer with Kafka-style binary framing,
  per-stream multiplexing, virtual-thread reader, ReentrantLock write serialization.
  ([ADR](.decisions/connection-pooling/adr.md))
- **Wire protocol** — 4-byte length prefix + 14-byte header (type tag + stream id + flags +
  sequence number) + body. Big-endian. Bidirectional handshake (R40 + R40-bidi) with version,
  nodeId, host, port. ([Spec](.spec/domains/transport/multiplexed-framing.md) v3 APPROVED)

## Test Configuration

Tests run with `--add-exports jlsm.cluster/jlsm.cluster.internal=ALL-UNNAMED` per the project's
standard internal-package access pattern. Integration tests (e.g.
`MultiplexedTransportRoundTripIntegrationTest`, `MultiplexedTransportLargeMessageTest`) bind
real localhost TCP sockets on ephemeral ports.

## Spec Coverage

Implementation backs `transport.multiplexed-framing` v3 APPROVED. Direct `@spec` annotations
cover R1-R29, R30, R30a, R34b, R34c, R34d, R35-R38, R37a, R37c, R39, R40, R40-bidi, R43, R43a,
R44, R45 (counters a-m). Two complete spec amendments deferred for future work units:

- **R23b** N≥3 simultaneous handshake queue resolution — basic two-peer tie-break works (lower
  nodeId wins outbound); N≥3 case has no deterministic test harness yet.
- **R26b scheduler-failure path** — `orTimeout` arming is in place; the explicit
  RejectedExecutionException recovery branch lacks a dedicated regression test.

These are the only known spec gaps and are tracked in CHANGELOG Known Gaps.

## Known Gaps

None blocking baseline use. The two deferred items above are detection-vs-correctness — the
behavior is correct in the common path; the missing pieces are exotic edge cases.
