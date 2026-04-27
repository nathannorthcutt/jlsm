package jlsm.encryption.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jlsm.encryption.TableScope;

/**
 * In-memory test fake for {@link ManifestCommitNotifier} (R37c). Drives synchronous listener
 * callbacks from a
 * {@link #publish(ManifestCommitNotifier.ManifestSnapshot, ManifestCommitNotifier.ManifestSnapshot)}
 * call so tests can simulate manifest commits without a real manifest module.
 *
 * <p>
 * Test-scope only; thread-safe.
 */
public final class InMemoryManifestCommitNotifier implements ManifestCommitNotifier {

    /**
     * Map from subscription id to listener. Listeners removed on close.
     */
    private final Map<Long, ManifestCommitListener> listeners = new ConcurrentHashMap<>();

    private final AtomicLong nextId = new AtomicLong();

    @Override
    public Subscription subscribe(ManifestCommitListener listener) {
        Objects.requireNonNull(listener, "listener");
        final long id = nextId.incrementAndGet();
        listeners.put(id, listener);
        return new InMemorySubscription(id);
    }

    /**
     * Synchronously dispatch {@code (before, after)} to every active subscriber. Listeners are
     * invoked sequentially in subscription order.
     */
    public void publish(ManifestSnapshot before, ManifestSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        for (ManifestCommitListener l : listeners.values()) {
            l.onCommit(before, after);
        }
    }

    /** Number of currently-active subscribers. Test-only diagnostic. */
    public int activeSubscriberCount() {
        return listeners.size();
    }

    /** Simple test snapshot; immutable view of (scope → maxDekVersion). */
    public static final class TestSnapshot implements ManifestSnapshot {

        private final Map<TableScope, Integer> versions;

        public TestSnapshot(Map<TableScope, Integer> versions) {
            Objects.requireNonNull(versions, "versions");
            this.versions = Map.copyOf(versions);
        }

        @Override
        public Set<TableScope> scopes() {
            return versions.keySet();
        }

        @Override
        public int dekVersionFor(TableScope scope) {
            ManifestSnapshot.requireScope(scope);
            return versions.getOrDefault(scope, 0);
        }
    }

    private final class InMemorySubscription implements Subscription {

        private final long id;
        private volatile boolean closed;

        InMemorySubscription(long id) {
            this.id = id;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            listeners.remove(id);
        }
    }
}
