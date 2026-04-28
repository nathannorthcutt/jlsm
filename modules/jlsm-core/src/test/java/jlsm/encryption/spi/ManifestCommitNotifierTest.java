package jlsm.encryption.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.spi.ManifestCommitNotifier.ManifestSnapshot;
import jlsm.encryption.spi.ManifestCommitNotifier.Subscription;

/**
 * Tests for {@link ManifestCommitNotifier} SPI (R37c). The contract is:
 * <ul>
 * <li>{@code subscribe(listener)} returns a non-null {@link Subscription}.</li>
 * <li>The {@link Subscription} is {@link AutoCloseable} and idempotent close.</li>
 * <li>{@code listener.onCommit(before, after)} fires synchronously for every commit while
 * subscribed; never after close.</li>
 * </ul>
 *
 * @spec encryption.primitives-lifecycle R37c
 */
class ManifestCommitNotifierTest {

    private static final TableScope SCOPE_A = new TableScope(new TenantId("tenantA"),
            new DomainId("domain-1"), new TableId("t1"));

    private static ManifestSnapshot snapshot(int dekVersion) {
        final Map<TableScope, Integer> map = new HashMap<>();
        map.put(SCOPE_A, dekVersion);
        return new InMemoryManifestCommitNotifier.TestSnapshot(map);
    }

    @Test
    void subscribe_returnsNonNullSubscription() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final Subscription sub = notifier.subscribe((b, a) -> {
        });
        assertNotNull(sub);
        sub.close();
    }

    @Test
    void subscribe_nullListener_throwsNpe() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        assertThrows(NullPointerException.class, () -> notifier.subscribe(null));
    }

    @Test
    void publish_invokesActiveListener() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final AtomicInteger calls = new AtomicInteger();
        final Subscription sub = notifier.subscribe((before, after) -> calls.incrementAndGet());

        notifier.publish(snapshot(1), snapshot(2));
        notifier.publish(snapshot(2), snapshot(3));

        assertEquals(2, calls.get());
        sub.close();
    }

    @Test
    void publish_afterClose_doesNotInvokeListener() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final AtomicInteger calls = new AtomicInteger();
        final Subscription sub = notifier.subscribe((before, after) -> calls.incrementAndGet());
        sub.close();

        notifier.publish(snapshot(1), snapshot(2));

        assertEquals(0, calls.get());
    }

    @Test
    void subscription_close_isIdempotent() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final Subscription sub = notifier.subscribe((b, a) -> {
        });
        sub.close();
        sub.close(); // must not throw
        sub.close();
        assertEquals(0, notifier.activeSubscriberCount());
    }

    @Test
    void multipleSubscribers_eachReceiveCommits() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final AtomicInteger countA = new AtomicInteger();
        final AtomicInteger countB = new AtomicInteger();

        final Subscription a = notifier.subscribe((b1, a1) -> countA.incrementAndGet());
        final Subscription b = notifier.subscribe((b1, a1) -> countB.incrementAndGet());

        notifier.publish(snapshot(1), snapshot(2));

        assertEquals(1, countA.get());
        assertEquals(1, countB.get());

        a.close();
        notifier.publish(snapshot(2), snapshot(3));
        assertEquals(1, countA.get(), "after close, listener A must not fire");
        assertEquals(2, countB.get());

        b.close();
    }

    @Test
    void manifestSnapshot_dekVersionFor_nullScope_throwsNpe() {
        final ManifestSnapshot snap = snapshot(1);
        assertThrows(NullPointerException.class, () -> snap.dekVersionFor(null));
    }

    @Test
    void manifestSnapshot_scopes_containsKnownScope() {
        final ManifestSnapshot snap = snapshot(7);
        assertTrue(snap.scopes().contains(SCOPE_A));
        assertEquals(7, snap.dekVersionFor(SCOPE_A));
    }

    @Test
    void manifestSnapshot_referencedVersions_defaultBehavior() {
        // Default impl returns singleton of dekVersionFor when non-zero. A snapshot with no
        // override (anonymous impl with only dekVersionFor) must surface that singleton so
        // callers without per-version tracking still drive convergence detection at all.
        final ManifestSnapshot snap = new ManifestSnapshot() {
            @Override
            public java.util.Set<TableScope> scopes() {
                return java.util.Set.of(SCOPE_A);
            }

            @Override
            public int dekVersionFor(TableScope scope) {
                ManifestSnapshot.requireScope(scope);
                return scope.equals(SCOPE_A) ? 9 : 0;
            }
        };
        assertEquals(java.util.Set.of(9), snap.referencedVersions(SCOPE_A));
    }

    @Test
    void manifestSnapshot_referencedVersions_emptyWhenScopeUnknown() {
        final ManifestSnapshot snap = new ManifestSnapshot() {
            @Override
            public java.util.Set<TableScope> scopes() {
                return java.util.Set.of();
            }

            @Override
            public int dekVersionFor(TableScope scope) {
                ManifestSnapshot.requireScope(scope);
                return 0;
            }
        };
        assertTrue(snap.referencedVersions(SCOPE_A).isEmpty());
    }

    @Test
    void manifestSnapshot_referencedVersions_nullScope_throwsNpe() {
        final ManifestSnapshot snap = snapshot(1);
        assertThrows(NullPointerException.class, () -> snap.referencedVersions(null));
    }

    @Test
    void distinctSubscriptions_areNotSame() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final Subscription a = notifier.subscribe((b1, a1) -> {
        });
        final Subscription b = notifier.subscribe((b1, a1) -> {
        });
        assertNotSame(a, b);
        a.close();
        b.close();
    }

    @Test
    void publishedSnapshots_arePassedThrough() {
        final InMemoryManifestCommitNotifier notifier = new InMemoryManifestCommitNotifier();
        final ManifestSnapshot before = snapshot(1);
        final ManifestSnapshot after = snapshot(2);
        final ManifestSnapshot[] received = new ManifestSnapshot[2];
        final Subscription sub = notifier.subscribe((b, a) -> {
            received[0] = b;
            received[1] = a;
        });

        notifier.publish(before, after);

        assertSame(before, received[0]);
        assertSame(after, received[1]);
        sub.close();
    }
}
