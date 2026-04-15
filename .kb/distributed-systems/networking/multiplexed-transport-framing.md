---
title: "Multiplexed Transport Framing"
aliases: ["transport framing", "connection multiplexing", "wire protocol", "message framing"]
topic: "distributed-systems"
category: "networking"
tags: ["framing", "multiplexing", "correlation-id", "length-prefix", "NIO", "TCP", "transport", "connection-pooling"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per message dispatch"
  space: "O(concurrent_streams) for correlation map"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
related:
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
  - "patterns/concurrency/lock-held-side-effects.md"
decision_refs:
  - "connection-pooling"
  - "message-serialization-format"
  - "transport-abstraction-design"
sources:
  - url: "https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v5.html"
    title: "CQL Binary Protocol v5 Specification"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://ivanyu.me/blog/2024/09/08/kafka-protocol-practical-guide/"
    title: "Kafka Protocol Practical Guide"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://github.com/archiecobbs/muxable"
    title: "Muxable: Java NIO Channel Multiplexing"
    accessed: "2026-04-13"
    type: "repo"
  - url: "https://blog.stephencleary.com/2009/04/message-framing.html"
    title: "Message Framing (Stephen Cleary)"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://kafka.apache.org/24/protocol.html"
    title: "Kafka Protocol Guide"
    accessed: "2026-04-13"
    type: "docs"
---

# Multiplexed Transport Framing

## summary

Multiplexed transport framing enables multiple independent request-response
streams over a single TCP connection. The core components are: (1) a framing
protocol that delimits messages on the byte stream, (2) a stream/correlation
ID that matches responses to requests, and (3) write serialization to prevent
interleaved partial frames. Production systems (Cassandra CQL, Kafka) use
4-byte big-endian length prefixes with correlation IDs in fixed-position
headers. This is baseline infrastructure for any NIO-based cluster transport —
even "one connection per peer" designs need framing and correlation to
implement request-response semantics over TCP.

## how-it-works

TCP provides a reliable byte stream with no message boundaries. A framing
protocol imposes structure by prepending each message with metadata (length,
type, correlation ID) so the receiver can delimit, route, and match messages.

```
┌─────────────────────────────────────────────────┐
│  TCP byte stream                                │
│  ┌──────────┬──────────┬──────────┬──────────┐  │
│  │ Frame 1  │ Frame 2  │ Frame 3  │ Frame 4  │  │
│  │ REQ s=1  │ REQ s=2  │ RSP s=1  │ RSP s=2  │  │
│  └──────────┴──────────┴──────────┴──────────┘  │
│                                                 │
│  Each frame: [length][header][body]             │
│  Header:     [type][stream-id][flags]           │
│  Correlation: stream-id in request = stream-id  │
│               in response                       │
└─────────────────────────────────────────────────┘
```

The reader loop on each connection reads frames continuously, dispatches
fire-and-forget messages to handlers by type, and completes waiting
`CompletableFuture`s for request-response pairs by matching stream IDs.

### key-parameters

| Parameter | Description | Typical Range | Impact |
|-----------|-------------|---------------|--------|
| Length prefix size | Bytes for message length field | 4 bytes (int32) | Max message size: 2 GiB with signed int32 |
| Stream ID size | Bytes for correlation/stream ID | 2 bytes (int16) or 4 bytes (int32) | Max concurrent streams: 32K or 2B |
| Header size | Fixed bytes before body | 8-12 bytes | Per-message overhead |
| Max frame size | Upper bound on body length | 128 KiB–16 MiB | Backpressure granularity |
| Byte order | Endianness for multi-byte fields | Big-endian (network order) | Interop, debugging |

## algorithm-steps

### sender (write path)

1. **Allocate stream ID**: atomic counter `nextStreamId.getAndIncrement()`.
   For request-response, register `CompletableFuture` in pending map keyed
   by stream ID before writing. For fire-and-forget, use stream ID 0 or a
   reserved "no-reply" marker.
2. **Encode header**: write type tag (1 byte), stream ID (2-4 bytes),
   flags (1 byte) into a header buffer. Compute total frame length.
3. **Acquire write lock**: only one thread writes to the channel at a time.
   Use a `ReentrantLock` (not `synchronized` — virtual thread friendly).
4. **Write length prefix + header + body**: single write sequence while
   holding the lock. Length prefix is the total size of header + body.
5. **Release write lock**.

### receiver (read path)

1. **Read length prefix**: blocking read of 4 bytes. Decode as big-endian
   int32. This is the total frame size (header + body).
2. **Read frame**: blocking read of `length` bytes into a buffer.
3. **Parse header**: extract type tag, stream ID, flags from fixed positions.
4. **Dispatch**:
   - If stream ID matches a pending `CompletableFuture`: complete it with
     the response body. Remove from pending map.
   - If stream ID is 0 / no-reply: dispatch to registered handler by type
     (fire-and-forget message like a membership ping).
   - If stream ID is unknown: log warning, discard frame.
5. **Loop**: return to step 1.

## implementation-notes

### framing-formats

Two proven approaches from production systems:

**Kafka-style: 4-byte length prefix + flat header**

```
┌───────────┬──────────┬───────────┬──────────┬───────────┐
│ length:i32│ type:i16 │ stream:i32│ flags:i8 │ body      │
│ (4 bytes) │ (2 bytes)│ (4 bytes) │ (1 byte) │ (N bytes) │
└───────────┴──────────┴───────────┴──────────┴───────────┘
Total header: 4 + 2 + 4 + 1 = 11 bytes
Length field: size of everything after the length prefix = 7 + body_length
```

Kafka uses this pattern: `RequestOrResponse => Size (RequestMessage |
ResponseMessage)` where `Size => int32`. The correlation ID (Kafka's name
for stream ID) is an `INT32` in both request and response headers. All
multi-byte integers are big-endian. The protocol supports pipelining —
multiple outstanding requests per connection, matched by correlation ID.

**Cassandra CQL v5: envelope inside frame**

CQL v5 separates transport framing from message envelopes. Frames have a
6-byte header (compressed: 8-byte) with CRC24 integrity. Inside each frame,
envelopes carry a 9-byte header: version (1), flags (1), stream ID (2),
opcode (1), body length (4). Stream IDs are signed int16 (0-32767 for
client requests; negative values reserved for server-initiated events).

The two-layer design (frame → envelope) enables frame-level compression and
integrity checking independent of message content. This is more complex but
supports features like batching multiple small envelopes into one compressed
frame.

**Recommendation for jlsm**: the Kafka-style flat frame is simpler and
sufficient. CQL's two-layer design is warranted when frame-level compression
is needed — jlsm can add this later if message-level compression proves
insufficient.

### correlation-dispatch

**Stream ID allocation:**
- Use an `AtomicInteger` counter per connection. Monotonically increasing.
- Wraparound is safe: at 2^31 IDs with ~1M messages/sec, wraparound takes
  ~35 minutes. A pending map with max ~1000 entries will never collide with
  a wrapped ID from 35 minutes ago.
- Kafka uses int32 correlation IDs. Cassandra CQL uses int16 stream IDs
  (max 32767 concurrent streams per connection). For jlsm, int32 is
  preferred — int16 limits concurrent streams to 32K which may be
  constraining for full-cluster scatter-gather at 1000 nodes.
- Fire-and-forget messages (`send()`) should use stream ID 0 or -1 to
  signal "no response expected." The receiver does not allocate a pending
  future for these.

**Pending response map:**
- `ConcurrentHashMap<Integer, CompletableFuture<Message>>` keyed by stream ID.
- On request: `put(streamId, future)` before writing the frame.
- On response: `remove(streamId)` and `complete(future)` with the body.
- On timeout: scheduled task calls `remove(streamId)` and
  `completeExceptionally(future)` with `TimeoutException`.
- On connection close: iterate all pending entries, complete exceptionally
  with `IOException("connection closed")`.

**Orphaned future protection:**
- Every pending future must have a timeout. Use
  `future.orTimeout(timeout, TimeUnit.MILLISECONDS)` (Java 9+).
- The timeout handler must also remove the entry from the pending map to
  prevent memory leaks.

### write-serialization

Only one thread may write to a `SocketChannel` at a time. Without
serialization, interleaved partial writes from concurrent threads produce
corrupt frames on the wire.

**Recommended: ReentrantLock per connection**

```java
private final ReentrantLock writeLock = new ReentrantLock();

void writeFrame(ByteBuffer frame) throws IOException {
    writeLock.lock();
    try {
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    } finally {
        writeLock.unlock();
    }
}
```

Why `ReentrantLock` over `synchronized`: JDK 24 (JEP 491) removed virtual
thread pinning for `synchronized`, but `ReentrantLock` has been
virtual-thread-safe since JDK 21. Using `ReentrantLock` avoids any
platform-specific pinning behavior and makes the code portable across JDK
versions.

**Alternative: write queue with dedicated writer thread**

A `LinkedBlockingQueue<ByteBuffer>` of pre-encoded frames, drained by a
single writer virtual thread per connection. Pros: no lock contention,
natural backpressure (queue capacity). Cons: additional thread per connection,
latency from queue hop. Better for high-throughput, worse for latency-sensitive
paths (membership pings).

For jlsm: start with `ReentrantLock`. The write queue approach is an
optimization if profiling shows write lock contention under scatter-gather
burst load.

### connection-lifecycle

**Establishment:** lazy on first `send()` or `request()` to a peer.
`SocketChannel.open()` + `connect(address)` in blocking mode on a virtual
thread. Store in `ConcurrentHashMap<NodeAddress, PeerConnection>`.

**Reader loop:** one virtual thread per connection, blocking on
`channel.read()`. Runs continuously while connected. Parses frames and
dispatches. On read failure (IOException, -1 return): mark connection dead,
complete all pending futures exceptionally, schedule reconnect.

**Health checking:** two approaches:
1. **Passive** (recommended): rely on the membership protocol's heartbeat
   pings as implicit health checks. If pings stop, phi accrual detects the
   failure. No extra traffic needed.
2. **Active**: periodic TCP keepalive or application-level ping frame. Adds
   complexity with marginal benefit since the membership protocol already
   does this.

**Reconnection:** exponential backoff (100ms, 200ms, 400ms, ... up to 30s).
Use `AtomicReference<PeerConnection>` with CAS to prevent concurrent
reconnect races. The reconnecting thread creates a new `PeerConnection`,
CAS-swaps it in, and starts a new reader loop. Old connection's reader loop
terminates naturally on the broken channel.

**Graceful shutdown:** on `close()`:
1. Stop accepting new requests (set closed flag).
2. Complete all pending futures exceptionally with "transport closing."
3. Close all `SocketChannel`s.
4. Interrupt reader virtual threads (they're blocked on `channel.read()`
   which throws `ClosedChannelException`).

**Peer departure:** when the membership protocol notifies that a peer is dead,
remove its `PeerConnection` from the map, close the channel, and complete
all pending futures exceptionally.

### data-structure-requirements

- `ConcurrentHashMap<NodeAddress, PeerConnection>` — peer connection registry
- `ConcurrentHashMap<Integer, CompletableFuture<Message>>` — per-connection pending response map
- `AtomicInteger` — per-connection stream ID counter
- `ReentrantLock` — per-connection write lock
- `ByteBuffer` — reusable read/write buffers per connection (allocate once, reuse)

### edge-cases-and-gotchas

1. **Partial reads**: TCP may deliver partial frames. The reader must
   accumulate bytes until a complete frame is available. With blocking NIO
   on virtual threads, `channel.read(buffer)` blocks until bytes arrive,
   but may return fewer bytes than requested. Loop until buffer is full.
2. **Partial writes**: `channel.write(buffer)` may not write all bytes.
   Loop while `buffer.hasRemaining()`. Hold the write lock for the entire
   write sequence.
3. **Stream ID exhaustion**: at int32, effectively impossible in practice.
   At int16 (CQL-style), becomes a concern at 32K concurrent requests —
   use int32 for safety.
4. **Stale pending futures**: if a connection breaks and reconnects, old
   pending futures from the dead connection must be cleaned up. The
   reconnect path must drain and fail all pending futures before creating
   the new connection.
5. **Write lock and virtual threads**: `ReentrantLock` is safe. `synchronized`
   is safe on JDK 24+ (JEP 491). Avoid `synchronized` on JDK 21-23 to
   prevent carrier thread pinning.
6. **Head-of-line blocking**: a large frame delays all subsequent frames on
   the same connection. Mitigate by capping max frame size and chunking
   large payloads. For jlsm: cluster messages (pings, query requests) are
   small; SSTable bulk transfer should use a separate mechanism.

## complexity-analysis

### per-message-overhead

- Encode: O(1) — write fixed-size header + body copy
- Decode: O(1) — read fixed-size header, dispatch by stream ID (hash map lookup)
- Memory: O(concurrent_streams) for pending future map

### connection-overhead

- Per peer: 1 TCP connection, 1 reader virtual thread, 1 write lock,
  1 pending map, 1 stream ID counter
- At 1000 peers: 999 connections, 999 virtual threads (~1 MB total stack),
  999 pending maps (near-empty most of the time)

## tradeoffs

### strengths

- **Minimal FDs**: one connection per peer, optimal for 1000-node clusters
- **Concurrent streams**: membership pings and query requests coexist on the
  same connection without blocking each other
- **Foundation for future work**: the framing protocol is the base layer for
  backpressure, traffic priority, and compression — these become header
  flags and flow control logic, not architectural rewrites
- **Virtual-thread friendly**: reader virtual thread blocks on I/O naturally;
  writer uses `ReentrantLock` which parks virtual threads without pinning

### weaknesses

- **Head-of-line blocking**: one slow frame delays others on the same TCP
  connection. Not a concern for small cluster messages; problematic for bulk
  data transfer.
- **Write serialization**: concurrent senders serialize through the write lock.
  Under extreme burst (1000-peer scatter-gather response fan-in), lock
  contention could appear. Mitigated by write queue if profiling warrants.
- **More complex than bare connection-per-peer**: correlation dispatch, pending
  map lifecycle, frame parsing are real code. But this complexity is
  **necessary** — even connection-per-peer needs framing for request-response.

### compared-to-alternatives

- **Connection-per-Peer (exclusive channel)**: simpler but blocks the channel
  during request() RTT, preventing concurrent sends. Membership pings queue
  behind query responses. Converges toward multiplexing when fixes are applied.
- **Elastic Pool per Peer**: multiplies FDs (K connections × N peers). The
  pool lifecycle (borrow/return/evict) is more complex than multiplexing's
  correlation dispatch, with no advantage in a message-oriented transport.
- **HTTP/2 / QUIC**: production-grade multiplexing but require complex
  implementations (HPACK, flow control, TLS integration). Overkill for an
  internal cluster transport with small messages and known peers.

## practical-usage

### when-to-use

- Internal cluster transport with mixed traffic patterns (fire-and-forget +
  request-response) over persistent TCP connections
- Systems with many peers (100-1000) where FD count matters
- When future requirements include backpressure, traffic priority, or
  compression — these layer naturally on the framing protocol

### when-not-to-use

- Bulk data transfer (SSTable replication, snapshot transfer) — TCP
  head-of-line blocking becomes a real problem. Use dedicated connections
  or a streaming protocol.
- Environments where an existing framework (Netty, gRPC) is available —
  don't reimplement multiplexing if you can use a battle-tested library.

## reference-implementations

| System | Language | Framing | Stream ID | Write Model |
|--------|----------|---------|-----------|-------------|
| Cassandra CQL v5 | Java | 6-byte frame header + 9-byte envelope | int16 (32K streams) | Netty pipeline (channel handler) |
| Kafka | Java/Scala | 4-byte length prefix | int32 correlation ID | Selector thread + send queue |
| muxable-simple | Java | Length-prefixed frames over ByteChannel | Channel ID | NIO ByteChannel abstraction |

## code-skeleton

```java
// Frame layout: [length:int32][type:byte][streamId:int32][flags:byte][body:byte[]]
// Total header overhead: 10 bytes (4 length + 1 type + 4 streamId + 1 flags)

record Frame(byte type, int streamId, byte flags, byte[] body) {

    static final int HEADER_SIZE = 6; // type + streamId + flags (after length prefix)
    static final int NO_REPLY = 0;    // streamId for fire-and-forget

    ByteBuffer encode() {
        int frameSize = HEADER_SIZE + body.length;
        var buf = ByteBuffer.allocate(4 + frameSize);
        buf.putInt(frameSize);         // length prefix
        buf.put(type);
        buf.putInt(streamId);
        buf.put(flags);
        buf.put(body);
        return buf.flip();
    }

    static Frame decode(ByteBuffer buf) {
        byte type = buf.get();
        int streamId = buf.getInt();
        byte flags = buf.get();
        byte[] body = new byte[buf.remaining()];
        buf.get(body);
        return new Frame(type, streamId, flags, body);
    }
}

class MultiplexedConnection implements AutoCloseable {
    private final SocketChannel channel;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<byte[]>> pending
        = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    // Fire-and-forget
    void send(byte type, byte[] body) throws IOException {
        writeFrame(new Frame(type, Frame.NO_REPLY, (byte) 0, body).encode());
    }

    // Request-response
    CompletableFuture<byte[]> request(byte type, byte[] body, Duration timeout) {
        int streamId = nextStreamId.getAndIncrement();
        var future = new CompletableFuture<byte[]>()
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        pending.put(streamId, future);
        future.whenComplete((r, t) -> pending.remove(streamId));
        try {
            writeFrame(new Frame(type, streamId, (byte) 0, body).encode());
        } catch (IOException e) {
            pending.remove(streamId);
            future.completeExceptionally(e);
        }
        return future;
    }

    // Reader loop — run on a virtual thread
    void readerLoop(Map<Byte, MessageHandler> handlers) {
        var lengthBuf = ByteBuffer.allocate(4);
        while (!closed) {
            lengthBuf.clear();
            readFully(lengthBuf);            // blocks on virtual thread
            int frameSize = lengthBuf.flip().getInt();

            var frameBuf = ByteBuffer.allocate(frameSize);
            readFully(frameBuf);
            Frame frame = Frame.decode(frameBuf.flip());

            if (frame.streamId() != Frame.NO_REPLY) {
                var future = pending.remove(frame.streamId());
                if (future != null) future.complete(frame.body());
            } else {
                var handler = handlers.get(frame.type());
                if (handler != null) handler.handle(frame);
            }
        }
    }

    private void writeFrame(ByteBuffer buf) throws IOException {
        writeLock.lock();
        try {
            while (buf.hasRemaining()) channel.write(buf);
        } finally {
            writeLock.unlock();
        }
    }
}
```

## sources

1. [CQL Binary Protocol v5 Specification](https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v5.html) — authoritative spec for Cassandra's two-layer framing with CRC integrity and stream multiplexing. Key reference for stream ID sizing (int16, 32K limit) and frame-level compression.
2. [Kafka Protocol Practical Guide](https://ivanyu.me/blog/2024/09/08/kafka-protocol-practical-guide/) — detailed walkthrough of Kafka's 4-byte length-prefix framing with int32 correlation IDs. Demonstrates pipelining over a single connection.
3. [Kafka Protocol Guide](https://kafka.apache.org/24/protocol.html) — official specification for Kafka's binary protocol, request/response headers, and correlation ID semantics.
4. [Muxable: Java NIO Channel Multiplexing](https://github.com/archiecobbs/muxable) — Java NIO library multiplexing nested channels over a single ByteChannel. Reference for Java-specific patterns.
5. [Message Framing (Stephen Cleary)](https://blog.stephencleary.com/2009/04/message-framing.html) — foundational article on TCP message delimitation strategies: length-prefix vs delimiter vs combining.

---
*Researched: 2026-04-13 | Next review: 2026-10-13*
