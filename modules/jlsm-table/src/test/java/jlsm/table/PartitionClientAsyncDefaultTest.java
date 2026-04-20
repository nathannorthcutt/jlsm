package jlsm.table;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the default {@link PartitionClient#getRangeAsync(String, String)} method.
 *
 * <p>
 * Delivers: F04.R77 — default async wrapper must not block callers and must surface runtime errors
 * as failed futures (H-CB-9 hardening finding).
 */
final class PartitionClientAsyncDefaultTest {

    // Finding: H-CB-9
    // Bug: The default getRangeAsync catches only IOException from doGetRange. A RuntimeException
    // would escape synchronously, breaking the "never blocks, always returns future" contract
    // and crashing scatter-gather coordinators that don't expect a sync throw here.
    // Correct behavior: RuntimeException from doGetRange is wrapped in a failed future (never
    // synchronously thrown). Only NullPointerException on null args is a permitted sync throw.
    // Fix location: PartitionClient.getRangeAsync default method
    // Regression watch: IOException is still surfaced as a failed future; success still returns
    // a completed future.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void default_getRangeAsync_runtimeExceptionFromDoGetRange_wrappedInFailedFuture()
            throws Exception {
        final PartitionClient client = new RuntimeThrowingClient();
        final CompletableFuture<Iterator<TableEntry<String>>> future;
        try {
            future = client.getRangeAsync("a", "z");
        } catch (RuntimeException re) {
            fail("getRangeAsync must not throw synchronously — got: " + re);
            return;
        }
        assertNotNull(future, "getRangeAsync must return a non-null future");
        final ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
        final Throwable cause = ex.getCause();
        assertNotNull(cause);
        assertTrue(cause instanceof RuntimeException || cause instanceof IOException,
                "Cause must be RuntimeException or IOException, got: " + cause.getClass());
    }

    // Finding: H-CB-9 (complement)
    // Bug: The default getRangeAsync may wrap null-check NPEs in a failed future rather than
    // throwing synchronously — failing caller-visible preconditions.
    // Correct behavior: Null fromKey/toKey still raises NullPointerException synchronously
    // (explicit precondition — callers expect to catch NPE, not to unwrap a future).
    // Fix location: PartitionClient.getRangeAsync default method (precondition first)
    // Regression watch: Non-null inputs continue to return a future.
    @Test
    void default_getRangeAsync_nullFromKey_throwsNpeSync() {
        final PartitionClient client = new NoopClient();
        assertThrows(NullPointerException.class, () -> client.getRangeAsync(null, "z"));
        assertThrows(NullPointerException.class, () -> client.getRangeAsync("a", null));
    }

    /** A PartitionClient whose doGetRange always throws a RuntimeException. */
    private static final class RuntimeThrowingClient implements PartitionClient {
        @Override
        public PartitionDescriptor descriptor() {
            return null;
        }

        @Override
        public void doCreate(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> doGet(String key) {
            return Optional.empty();
        }

        @Override
        public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void doDelete(String key) {
        }

        @Override
        public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
            throw new RuntimeException("boom");
        }

        @Override
        public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    /** A trivial no-op PartitionClient. */
    private static final class NoopClient implements PartitionClient {
        @Override
        public PartitionDescriptor descriptor() {
            return null;
        }

        @Override
        public void doCreate(String key, JlsmDocument doc) {
        }

        @Override
        public Optional<JlsmDocument> doGet(String key) {
            return Optional.empty();
        }

        @Override
        public void doUpdate(String key, JlsmDocument doc, UpdateMode mode) {
        }

        @Override
        public void doDelete(String key) {
        }

        @Override
        public Iterator<TableEntry<String>> doGetRange(String fromKey, String toKey) {
            return List.<TableEntry<String>>of().iterator();
        }

        @Override
        public List<ScoredEntry<String>> doQuery(Predicate predicate, int limit) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
