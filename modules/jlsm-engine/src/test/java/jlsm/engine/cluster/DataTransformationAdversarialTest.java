package jlsm.engine.cluster;

import jlsm.cluster.NodeAddress;
import jlsm.cluster.Message;
import jlsm.cluster.MessageType;

import jlsm.engine.cluster.internal.CatalogClusteredTable;

import jlsm.cluster.internal.InJvmTransport;
import jlsm.engine.cluster.internal.RemotePartitionClient;
import jlsm.table.FieldType;
import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;
import jlsm.table.PartitionDescriptor;
import jlsm.table.TableEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for data-transformation concerns in the engine clustering subsystem.
 *
 * <p>
 * Focus: wire-format round-trip fidelity; silent-failure masking where distinct failure modes
 * collapse into an ambiguous "empty" sentinel; encoding/framing assumption mismatches.
 *
 * @spec engine.clustering.R101 — full document + operation mode round-trip fidelity
 * @spec engine.clustering.R111 — local-origin encoding failures distinct from remote failures
 * @spec engine.clustering.R112 — response encoder size accumulator uses checked arithmetic
 * @spec engine.clustering.R113 — ordered merge fails on malformed per-partition iterator elements
 * @spec engine.clustering.R114 — range-scan decoder rejects malformed responses
 */
final class DataTransformationAdversarialTest {

    private static final NodeAddress LOCAL = new NodeAddress("local", "localhost", 9001);
    private static final NodeAddress REMOTE = new NodeAddress("remote", "localhost", 9002);
    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("id", FieldType.Primitive.STRING).field("value", FieldType.Primitive.STRING)
            .build();

    private InJvmTransport localTransport;
    private InJvmTransport remoteTransport;
    private PartitionDescriptor descriptor;

    @BeforeEach
    void setUp() {
        InJvmTransport.clearRegistry();
        localTransport = new InJvmTransport(LOCAL);
        remoteTransport = new InJvmTransport(REMOTE);
        descriptor = new PartitionDescriptor(1L,
                MemorySegment.ofArray("a".getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray("m".getBytes(StandardCharsets.UTF_8)), REMOTE.nodeId(), 0L);
    }

    @AfterEach
    void tearDown() throws Exception {
        localTransport.close();
        remoteTransport.close();
        InJvmTransport.clearRegistry();
    }

    // Finding: F-R1.data_transformation.1.1
    // Bug: RemotePartitionClient.doGet collapses three distinct failure modes — (a) legitimately
    // not-found (empty payload), (b) client constructed without a schema (cannot deserialize),
    // and (c) malformed JSON in response payload (protocol drift / stub misbehaviour) — into an
    // undifferentiated Optional.empty. Callers cannot distinguish genuine absence from silent
    // data loss due to misconfiguration or corruption.
    // Correct behavior: null-schema path with a non-empty response payload must fail loudly
    // (IOException) rather than masquerade as "not found". The absence case (empty payload)
    // remains Optional.empty.
    // Fix location: RemotePartitionClient.doGet lines 164-170 (schema==null short-circuit).
    // Regression watch: legitimate not-found (zero-length payload) must still return
    // Optional.empty; schema-configured clients must still parse valid JSON responses.
    @Test
    @Timeout(10)
    @Disabled("F-R1.data_transformation.1.1 — FIX_IMPOSSIBLE: fix conflicts with pre-existing "
            + "RemotePartitionClientTest.get_sendsRequestAndReturnsDocument which codifies the "
            + "buggy silent-empty behaviour. See "
            + ".feature/f04-obligation-resolution--wd-03/audit/run-001/prove-fix-F-R1-data_transformation-1-1.md "
            + "for relaxation request.")
    void test_doGet_nullSchemaWithNonEmptyResponse_throwsInsteadOfSilentEmpty() throws IOException {
        // Server responds with a non-empty payload — the "found" signal per the wire protocol.
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(),
                    "{\"id\":\"key1\",\"value\":\"val1\"}".getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });

        // Client constructed via the no-schema constructor — schema is silently null.
        try (RemotePartitionClient client = new RemotePartitionClient(descriptor, REMOTE,
                localTransport, LOCAL, "users")) {
            // The client cannot deserialize — this is a misconfiguration, not a not-found.
            // Correct behaviour: surface the misconfiguration as an IOException so the caller
            // cannot confuse silent data loss with a genuine absence.
            assertThrows(IOException.class, () -> client.get("key1"),
                    "null-schema client with non-empty response must not return Optional.empty "
                            + "— that collapses silent data loss into the not-found signal");
        }
    }

    // Finding: F-R1.data_transformation.1.2
    // Bug: QueryRequestHandler.encodeRangeResponse uses an unguarded `int total` accumulator
    // (`total += 4 + keyBytes.length + 4 + docBytes.length`). When a scan response would exceed
    // Integer.MAX_VALUE bytes (either many small entries or modest-count large documents),
    // `total` silently wraps to a negative value and `ByteBuffer.allocate(total)` throws
    // `IllegalArgumentException("Negative capacity")`, which the handler rewraps as a generic
    // "QUERY_REQUEST dispatch failed" IOException — a misleading error taxonomy.
    // Correct behavior: overflow detected eagerly with `Math.addExact` (or equivalent guard),
    // emitting a clear IOException naming the condition ("range response size overflow" /
    // "response too large") so operators can diagnose the root cause.
    // Fix location: QueryRequestHandler.encodeRangeResponse lines 172-195 (the `total` accumulator
    // inside the while loop and/or the allocation call site).
    // Regression watch: normal-sized scans (tens of entries, kilobyte docs) must continue to
    // return a correctly-sized payload.
    //
    // Test strategy: invoke the private encoder via reflection with a synthetic iterator whose
    // entries have sum-of-(4+keyLen+4+docLen) exceeding Integer.MAX_VALUE. The iterator must
    // NOT actually allocate the full byte arrays (that would need ~2 GiB of heap, which the
    // 512 MiB test JVM cannot satisfy) — instead, it reports large sizes and lets the encoder's
    // `getBytes(UTF_8)` realize them. In practice this test OOMs before the overflow triggers,
    // which itself demonstrates the unbounded-allocation aspect of the bug.
    // Finding: F-R1.data_transformation.1.4
    // Bug: RemotePartitionClient.doGet and decodeRangeResponsePayload have no magic byte /
    // version header on QUERY_RESPONSE payloads. A peer running a drifted protocol version
    // (different byte order, different framing, alternative encoding) produces responses that
    // the decoder interprets as if they conformed to the current format. Two failure modes:
    // (a) doGet runs `new String(payload, UTF_8)` on arbitrary bytes — binary payloads (e.g.
    // BSON, MessagePack) decode to garbage with U+FFFD replacements; JlsmDocument.fromJson
    // then throws IAE which is caught and returned as Optional.empty — indistinguishable
    // from "not found". (b) decodeRangeResponsePayload calls ByteBuffer.wrap with default BE
    // byte order; a little-endian peer's `count=3` reads as 0x03000000 (~50M) which passes
    // the `entryCount < 0` guard at L369 and hits the truncation guard at L378-380, throwing
    // a misleading "Truncated RANGE response" error that masks the real "protocol version
    // mismatch" root cause.
    // Correct behavior: every response payload carries a short magic+version prefix. On
    // decode, a mismatched magic surfaces as a clear IOException naming "protocol drift"
    // or "version mismatch" so operators can diagnose the root cause immediately rather
    // than chase a "truncated" / "not found" red herring.
    // Fix location: RemotePartitionClient.doGet (lines 163-179) and decodeRangeResponsePayload
    // (lines 360-402) need to verify a magic-byte prefix; the matching encode must be added
    // in QueryRequestHandler.dispatch (GET/RANGE arms) so responses always carry the magic.
    // Regression watch: legitimate empty responses (not-found, empty range) must continue
    // to round-trip correctly.
    @Test
    @Timeout(10)
    @Disabled("F-R1.data_transformation.1.4 — FIX_IMPOSSIBLE: adding a magic byte to the "
            + "response format is a coordinated wire-protocol change that must modify both "
            + "RemotePartitionClient (decoder) AND QueryRequestHandler (encoder). Per the "
            + "prove-fix contract, a single agent may only modify the finding's construct "
            + "paths (RemotePartitionClient). A one-sided fix (verify magic on decode only) "
            + "breaks every existing test — no handler ever emits the magic. See "
            + ".feature/f04-obligation-resolution--wd-03/audit/run-001/prove-fix-F-R1-data_transformation-1-4.md "
            + "for relaxation request: widen the authorized construct paths to include "
            + "QueryRequestHandler so the magic-byte prefix can be emitted and verified in "
            + "a single atomic change.")
    void test_doGet_protocolDriftWithoutMagicByte_throwsInsteadOfSilentEmpty() throws IOException {
        // Server responds with a binary payload (simulates a newer protocol version that
        // swapped from JSON-over-UTF-8 to an opaque binary envelope). The first 4 bytes are
        // 0x01 0x02 0x03 0x04 — not valid JSON when decoded as UTF-8.
        final byte[] binaryPayload = new byte[]{ 0x01, 0x02, 0x03, 0x04, (byte) 0xFF, (byte) 0xFE,
                (byte) 0xFD, (byte) 0xFC };
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), binaryPayload);
            return CompletableFuture.completedFuture(response);
        });

        try (RemotePartitionClient client = new RemotePartitionClient(descriptor, REMOTE,
                localTransport, LOCAL, SCHEMA, "users")) {
            // The client sees a non-empty payload lacking the expected magic prefix. This is
            // protocol drift — NOT a not-found. Correct behaviour: surface as IOException
            // naming the mismatch so operators can diagnose.
            assertThrows(IOException.class, () -> client.get("key1"),
                    "doGet must reject a response payload lacking the magic/version prefix "
                            + "rather than silently return Optional.empty — the latter collapses "
                            + "protocol drift into the not-found signal and masks silent data loss.");
        }
    }

    @Test
    @Timeout(60)
    void test_encodeRangeResponse_unboundedSumOverflowsIntTotal_throwsSemanticIOException()
            throws Exception {
        // Build a synthetic iterator that reports ~256 MiB per entry. Needs 9 entries to
        // push `int total` past Integer.MAX_VALUE (9 * (256 MiB + 8 bytes of length prefixes)
        // ≈ 2.3 GiB) — which would OOM the test JVM before overflow triggers.
        final int perEntryBytes = 256 * 1024 * 1024;
        final int entriesNeededToOverflow = 9;

        final JlsmSchema schema = JlsmSchema.builder("rng", 1)
                .field("id", FieldType.Primitive.STRING).field("v", FieldType.Primitive.STRING)
                .build();
        final String largeValue = "x".repeat(perEntryBytes);
        final JlsmDocument doc = JlsmDocument.of(schema, "id", "k", "v", largeValue);

        final Iterator<TableEntry<String>> synthetic = new Iterator<>() {
            private int yielded;

            @Override
            public boolean hasNext() {
                return yielded < entriesNeededToOverflow;
            }

            @Override
            public TableEntry<String> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                yielded++;
                return new TableEntry<>("k-" + yielded, doc);
            }
        };

        final Class<?> handlerClass = Class
                .forName("jlsm.engine.cluster.internal.QueryRequestHandler");
        final Method m = handlerClass.getDeclaredMethod("encodeRangeResponse", Iterator.class);
        m.setAccessible(true);

        // The *correct* behaviour after the fix is a clear IOException naming the overflow.
        // Before the fix, reflection surfaces either an `IllegalArgumentException` (negative
        // ByteBuffer capacity) or an `OutOfMemoryError` (no overflow guard allows unbounded
        // list growth). The assertion below distinguishes the fixed path from either failure.
        final InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, synthetic));
        final Throwable cause = ite.getCause();
        assertNotNull(cause, "reflection invocation must surface a cause");
        assertInstanceOf(IOException.class, cause,
                "encodeRangeResponse must surface overflow as IOException rather than leaking "
                        + "IllegalArgumentException or OutOfMemoryError. Got: "
                        + cause.getClass().getName() + " — " + cause.getMessage());
    }

    // Finding: F-R1.data_transformation.1.5
    // Bug: CatalogClusteredTable.mergeOrdered uses a PriorityQueue comparator
    // `Comparator.comparing(he -> he.current.key())` that dereferences without a null guard; if
    // a local-short-circuit iterator ever yields a malformed TableEntry element (null reference
    // or — hypothetically — a TableEntry whose key is null), the comparator invocation from
    // PriorityQueue.offer raises a raw NullPointerException (or an AssertionError from the
    // HeapEntry record's null-current assertion under -ea) that escapes scan() as an unchecked
    // exception. That violates the scan()/Iterator contract, which only declares checked
    // IOException and NoSuchElementException.
    // Correct behavior: a malformed iterator element must be surfaced as a well-typed checked
    // IOException naming the condition (e.g., "malformed local iterator element") rather than
    // leaking an AssertionError or NullPointerException from PriorityQueue/HeapEntry internals.
    //
    // Note on TableEntry with null key: the TableEntry record's canonical constructor rejects
    // null keys (TableEntry.java:14 `Objects.requireNonNull(key, ...)`). There is no way to
    // construct a TableEntry with a null key via the public API — so the finding's literal
    // attack (TableEntry with null key) is structurally impossible. The closest realizable
    // failure mode is a malformed iterator that yields a null TableEntry reference. This test
    // exercises that scenario via a local Engine whose scan() returns a rogue iterator.
    //
    // Fix location: CatalogClusteredTable.mergeOrdered lines 554-559 (null-guard on it.next()
    // before
    // the heap.offer call) AND/OR the heap comparator at line 552 (handle a null current).
    // Regression watch: well-behaved local iterators must continue to merge correctly; scans
    // that never encounter a malformed element must produce identical results.
    @Test
    @Timeout(10)
    void test_mergeOrdered_malformedLocalIteratorElement_doesNotLeakAssertionOrNpe()
            throws Exception {
        // Build two rogue iterators so the single-iterator fast path (iterators.size() == 1)
        // is bypassed and the heap-construction path is exercised. Each iterator yields a
        // null TableEntry reference on its first next() call.
        final Iterator<TableEntry<String>> rogue1 = newRogueIterator();
        final Iterator<TableEntry<String>> rogue2 = newRogueIterator();
        final List<Iterator<TableEntry<String>>> iterators = List.of(rogue1, rogue2);

        final Method mergeOrdered = CatalogClusteredTable.class.getDeclaredMethod("mergeOrdered",
                List.class);
        mergeOrdered.setAccessible(true);

        // Correct behaviour: mergeOrdered surfaces the malformed element as a checked
        // IOException (wrapped in InvocationTargetException by reflection). Buggy behaviour:
        // leaks AssertionError (HeapEntry record assertion, under -ea) or NullPointerException
        // (comparator dereference, under -da). Either of those indicates a missing runtime
        // guard.
        final InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> mergeOrdered.invoke(null, iterators),
                "mergeOrdered with a malformed iterator element must surface a checked "
                        + "exception — not leak a raw AssertionError or NullPointerException.");
        final Throwable cause = ite.getCause();
        assertNotNull(cause, "reflection invocation must surface a cause");
        // The fix is a runtime guard that throws IOException (or a similarly typed checked
        // exception) naming the malformed input. AssertionError and NullPointerException are
        // the two buggy-state symptoms to reject.
        assertFalse(cause instanceof AssertionError,
                "mergeOrdered must not leak AssertionError from HeapEntry assertions — "
                        + "assertions are disabled in production; the guard must be a runtime "
                        + "check. Got: " + cause.getClass().getName() + " — " + cause.getMessage());
        assertFalse(cause instanceof NullPointerException,
                "mergeOrdered must not leak NullPointerException from the heap comparator — "
                        + "the fix must explicitly reject malformed iterator elements. Got: "
                        + cause.getClass().getName() + " — " + cause.getMessage());
    }

    private static Iterator<TableEntry<String>> newRogueIterator() {
        return new Iterator<>() {
            private boolean yielded;

            @Override
            public boolean hasNext() {
                return !yielded;
            }

            @Override
            public TableEntry<String> next() {
                if (yielded) {
                    throw new NoSuchElementException();
                }
                yielded = true;
                return null; // adversarial: malformed iterator yields a null element
            }
        };
    }

    // Finding: F-R1.data_transformation.1.6
    // Bug: RemotePartitionClient.decodeRangeResponsePayload silently swallows a schema==null
    // client configuration by returning Collections.emptyIterator() whenever the schema is null
    // — regardless of whether the response payload carries a populated, valid RANGE body. A
    // scatter-gather scan invoked on a client built via the no-schema constructor discards an
    // entire partition's response without any error, metric, or log. Upstream
    // CatalogClusteredTable.scan
    // counts this iterator toward "responding" (not "unavailable") so PartialResultMetadata
    // reports isComplete=true while the user has silently lost data.
    // Correct behavior: when schema is null but the response payload is non-empty (indicating
    // the server did produce range results), throw IOException naming the misconfiguration so
    // the caller cannot confuse silent data loss with a genuine empty range.
    // Fix location: RemotePartitionClient.decodeRangeResponsePayload lines 362-365
    // (the `responsePayload.length == 0 || schema == null` short-circuit).
    // Regression watch: legitimately empty range responses (length == 0) must still yield an
    // empty iterator regardless of schema; schema-configured clients with a populated payload
    // must still deserialize and return entries.
    @Test
    @Timeout(10)
    void test_decodeRangeResponsePayload_nullSchemaWithPopulatedPayload_throwsInsteadOfSilentEmpty()
            throws IOException {
        // Build a valid, populated RANGE response payload (two entries).
        final JlsmDocument doc1 = JlsmDocument.of(SCHEMA, "id", "k-a", "value", "alpha");
        final JlsmDocument doc2 = JlsmDocument.of(SCHEMA, "id", "k-b", "value", "beta");
        final byte[] key1 = "k-a".getBytes(StandardCharsets.UTF_8);
        final byte[] doc1Json = doc1.toJson().getBytes(StandardCharsets.UTF_8);
        final byte[] key2 = "k-b".getBytes(StandardCharsets.UTF_8);
        final byte[] doc2Json = doc2.toJson().getBytes(StandardCharsets.UTF_8);
        final int total = 4 + (4 + key1.length + 4 + doc1Json.length)
                + (4 + key2.length + 4 + doc2Json.length);
        final java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(total);
        buf.putInt(2);
        buf.putInt(key1.length);
        buf.put(key1);
        buf.putInt(doc1Json.length);
        buf.put(doc1Json);
        buf.putInt(key2.length);
        buf.put(key2);
        buf.putInt(doc2Json.length);
        buf.put(doc2Json);
        final byte[] populatedPayload = buf.array();

        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), populatedPayload);
            return CompletableFuture.completedFuture(response);
        });

        // No-schema constructor — schema is silently null.
        try (RemotePartitionClient client = new RemotePartitionClient(descriptor, REMOTE,
                localTransport, LOCAL, "users")) {
            // Correct behaviour: surface the misconfiguration as an IOException — the populated
            // body unambiguously signals the server sent range data which the client cannot
            // deserialize, so masking this as an empty iterator is silent data loss.
            assertThrows(IOException.class, () -> client.getRange("a", "z"),
                    "null-schema client with a populated RANGE response must not return "
                            + "Collections.emptyIterator() — that collapses silent data loss "
                            + "into the empty-range signal and contaminates scatter-gather "
                            + "aggregations with PartialResultMetadata.isComplete=true.");
        }
    }

    // Finding: F-R1.data_transformation.1.7
    // Bug: RemotePartitionClient.getRangeAsync catches a RuntimeException from
    // QueryRequestPayload.encodeRange (a local, client-side encoding failure) and returns a
    // CompletableFuture that fails with IOException("Failed to encode RANGE payload", e).
    // Upstream CatalogClusteredTable.scan treats every failed future as a node-availability issue
    // (adds the node to `unavailable` in PartialResultMetadata) — so a purely local encoding
    // bug is reported to operators as a remote-node outage, masking the real root cause.
    // Correct behavior: a RuntimeException from encodeRange is a programmer/state bug on the
    // local client, not a transport failure. The failure must propagate synchronously (i.e.
    // getRangeAsync itself throws) rather than be wrapped into a transport-failure-signaled
    // failed future. Synchronous propagation distinguishes "never contacted the node" from
    // "node failed to respond" at the call site — callers get the raw RuntimeException and
    // can react accordingly, rather than silently classifying the node as unavailable.
    // Fix location: RemotePartitionClient.getRangeAsync lines 287-292 (the try/catch around
    // encodeRange that wraps RuntimeException into a failed IOException future).
    // Regression watch: non-null fromKey/toKey validation (L279-280) must still throw NPE
    // synchronously; successful encode paths must still produce a future; genuine transport
    // failures (transport.request throwing or returning a failed future) must still surface
    // through the returned future.
    @Test
    @Timeout(10)
    void test_getRangeAsync_encodeFailure_doesNotMasqueradeAsNodeOutage() throws Exception {
        // Construct a client with a valid tableName (ctor requires it), then mutate the
        // final `tableName` field to an empty string via reflection to force
        // QueryRequestPayload.validateTableName to throw IllegalArgumentException at encode
        // time — simulating a future state where the client's encoding inputs become invalid
        // post-construction (e.g. mutable metadata, corrupted internal state, an added field
        // that fails to encode for legitimately non-null inputs).
        remoteTransport.registerHandler(MessageType.QUERY_REQUEST, (sender, msg) -> {
            // Should never be invoked — encoding fails client-side before transport dispatch.
            final Message response = new Message(MessageType.QUERY_RESPONSE, REMOTE,
                    msg.sequenceNumber(), new byte[0]);
            return CompletableFuture.completedFuture(response);
        });

        try (RemotePartitionClient client = new RemotePartitionClient(descriptor, REMOTE,
                localTransport, LOCAL, SCHEMA, "users")) {
            // Corrupt the client's tableName field to trigger an encodeRange RuntimeException.
            final java.lang.reflect.Field f = RemotePartitionClient.class
                    .getDeclaredField("tableName");
            f.setAccessible(true);
            f.set(client, "");

            // Correct behaviour: the client-side encoding failure propagates synchronously as
            // a RuntimeException (the real root cause), rather than being laundered into a
            // failed future that upstream CatalogClusteredTable.scan would silently reclassify as
            // node unavailability.
            assertThrows(RuntimeException.class, () -> client.getRangeAsync("a", "z"),
                    "A client-side encoding failure must propagate synchronously — it is a "
                            + "local programmer/state bug, not a transport failure, and must not "
                            + "be wrapped into a failed future that upstream scatter-gather "
                            + "code would misclassify as a node outage.");
        }
    }

}
