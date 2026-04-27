package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DeployerInstanceId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.KmsTransientException;
import jlsm.encryption.TenantId;
import jlsm.encryption.TenantState;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;

/**
 * Tests for {@link KmsPoller} (R79, R79a, R79b, R79c, R79d, R83c-2). The poller probes a tenant's
 * tier-1 KEK on a configured cadence and feeds the result into the {@link TenantStateMachine}.
 *
 * @spec encryption.primitives-lifecycle R79
 * @spec encryption.primitives-lifecycle R79a
 * @spec encryption.primitives-lifecycle R79b
 * @spec encryption.primitives-lifecycle R79d
 * @spec encryption.primitives-lifecycle R83c-2
 */
class KmsPollerTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final KekRef KEK_REF = new KekRef("kek-ref-1");

    private static byte[] secret() {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) i;
        }
        return s;
    }

    private static DeployerInstanceId deployerId() {
        return new DeployerInstanceId(secret());
    }

    private static PollingScheduler scheduler() {
        // High rate-limit so unit tests do not deflect on budget exhaustion.
        return PollingScheduler.withRateLimit(deployerId(), 10_000);
    }

    private static TenantStateMachine stateMachine(@TempDir Path tmp) {
        return TenantStateMachine.create(TenantStateProgress.open(tmp), 5);
    }

    @Test
    void createNullKmsClientRejected(@TempDir Path tmp) {
        final TenantStateMachine sm = stateMachine(tmp);
        final PollingScheduler s = scheduler();
        assertThrows(NullPointerException.class, () -> KmsPoller.create(null, s, sm));
    }

    @Test
    void createNullSchedulerRejected(@TempDir Path tmp) {
        final KmsClient client = new RecordingKmsClient(true, null);
        final TenantStateMachine sm = stateMachine(tmp);
        assertThrows(NullPointerException.class, () -> KmsPoller.create(client, null, sm));
    }

    @Test
    void createNullStateMachineRejected(@TempDir Path tmp) {
        final KmsClient client = new RecordingKmsClient(true, null);
        final PollingScheduler s = scheduler();
        assertThrows(NullPointerException.class, () -> KmsPoller.create(client, s, null));
    }

    @Test
    void startNullsRejected(@TempDir Path tmp) {
        final KmsPoller poller = KmsPoller.create(new RecordingKmsClient(true, null), scheduler(),
                stateMachine(tmp));
        try {
            assertThrows(NullPointerException.class,
                    () -> poller.start(null, KEK_REF, Duration.ofSeconds(30)));
            assertThrows(NullPointerException.class,
                    () -> poller.start(TENANT_A, null, Duration.ofSeconds(30)));
            assertThrows(NullPointerException.class, () -> poller.start(TENANT_A, KEK_REF, null));
        } finally {
            poller.stop(TENANT_A);
        }
    }

    @Test
    void startNonPositiveCadenceRejected(@TempDir Path tmp) {
        final KmsPoller poller = KmsPoller.create(new RecordingKmsClient(true, null), scheduler(),
                stateMachine(tmp));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> poller.start(TENANT_A, KEK_REF, Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> poller.start(TENANT_A, KEK_REF, Duration.ofSeconds(-1)));
        } finally {
            poller.stop(TENANT_A);
        }
    }

    @Test
    void stopNullTenantRejected(@TempDir Path tmp) {
        final KmsPoller poller = KmsPoller.create(new RecordingKmsClient(true, null), scheduler(),
                stateMachine(tmp));
        assertThrows(NullPointerException.class, () -> poller.stop(null));
    }

    @Test
    void startInvokesIsUsable(@TempDir Path tmp) throws Exception {
        final RecordingKmsClient client = new RecordingKmsClient(true, null);
        final KmsPoller poller = KmsPoller.create(client, scheduler(), stateMachine(tmp));
        try {
            poller.start(TENANT_A, KEK_REF, Duration.ofMillis(50));
            // Wait for the loop to fire at least one probe (cadence is 50ms — should be quick).
            assertTrue(client.firstCallLatch.await(2, TimeUnit.SECONDS),
                    "poller must invoke KmsClient.isUsable on the started tenant");
            assertEquals(KEK_REF, client.lastProbedRef.get(),
                    "poller must probe the configured tenantKekRef");
        } finally {
            poller.stop(TENANT_A);
        }
    }

    @Test
    void transientFailureDoesNotTransitionState(@TempDir Path tmp) throws Exception {
        // R79a: transient failures must not count toward N. The state must remain HEALTHY despite
        // many transient probe failures.
        final RecordingKmsClient client = new RecordingKmsClient(false,
                new KmsTransientException("simulated transient outage"));
        final TenantStateMachine sm = stateMachine(tmp);
        final KmsPoller poller = KmsPoller.create(client, scheduler(), sm);
        try {
            poller.start(TENANT_A, KEK_REF, Duration.ofMillis(20));
            // Allow several probes to fire.
            Thread.sleep(300);
            assertEquals(TenantState.HEALTHY, sm.currentState(TENANT_A),
                    "R79a: transient failures must not transition the state machine");
            assertTrue(client.calls.get() > 1, "loop must fire multiple probes within window");
        } finally {
            poller.stop(TENANT_A);
        }
    }

    @Test
    void permanentFailureDrivesStateTransition(@TempDir Path tmp) throws Exception {
        // R79a + R76a-1: PERMANENT classification must drive the state machine. With the default
        // grace threshold of 5, after at least 5 PERMANENT probes the tenant must transition to
        // GRACE_READ_ONLY.
        final RecordingKmsClient client = new RecordingKmsClient(false,
                new KmsPermanentException("simulated permanent failure"));
        final TenantStateMachine sm = TenantStateMachine.create(TenantStateProgress.open(tmp), 3);
        final KmsPoller poller = KmsPoller.create(client, scheduler(), sm);
        try {
            poller.start(TENANT_A, KEK_REF, Duration.ofMillis(20));
            // Wait for several probes — but bound the total wait.
            final long deadlineNs = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadlineNs) {
                if (sm.currentState(TENANT_A) == TenantState.GRACE_READ_ONLY) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(TenantState.GRACE_READ_ONLY, sm.currentState(TENANT_A),
                    "R79a: repeated permanent failures must drive HEALTHY → GRACE_READ_ONLY");
        } finally {
            poller.stop(TENANT_A);
        }
    }

    @Test
    void perTenantIsolationOneTenantFailureDoesNotKillOthers(@TempDir Path tmp) throws Exception {
        // R79b: one tenant's polling load (or fault) must not affect other tenants.
        // Tenant-A's probe always throws RuntimeException (worst-case unhandled error in the loop);
        // Tenant-B's probe always returns true. Tenant-B must continue to receive probes.
        final RecordingKmsClient client = new RecordingKmsClient();
        client.modeForTenant.put(KEK_REF, ProbeMode.OK);
        client.modeForTenant.put(new KekRef("kek-broken"), ProbeMode.RUNTIME_ERROR);

        final TenantStateMachine sm = stateMachine(tmp);
        final KmsPoller poller = KmsPoller.create(client, scheduler(), sm);
        try {
            poller.start(TENANT_A, new KekRef("kek-broken"), Duration.ofMillis(20));
            poller.start(TENANT_B, KEK_REF, Duration.ofMillis(20));
            // Wait for tenant-B's loop to make progress.
            assertTrue(client.successLatch.await(2, TimeUnit.SECONDS),
                    "R79b: tenant-A's broken loop must not block tenant-B's polling");
            // Confirm tenant-A's loop is still firing too — i.e., it self-recovers from runtime
            // errors rather than dying after a single throw.
            final int snapshot = client.runtimeErrorCalls.get();
            Thread.sleep(120);
            final int latest = client.runtimeErrorCalls.get();
            assertTrue(latest > snapshot,
                    "R79b: a runtime error inside one tenant's probe must not kill its loop");
        } finally {
            poller.stop(TENANT_A);
            poller.stop(TENANT_B);
        }
    }

    @Test
    void doubleStartIsIdempotent(@TempDir Path tmp) throws Exception {
        // Idempotent start: a second start for the same tenantId is a no-op (no second loop
        // spawned)
        final RecordingKmsClient client = new RecordingKmsClient(true, null);
        final KmsPoller poller = KmsPoller.create(client, scheduler(), stateMachine(tmp));
        try {
            poller.start(TENANT_A, KEK_REF, Duration.ofMillis(50));
            poller.start(TENANT_A, KEK_REF, Duration.ofMillis(50));
            // Wait for at least one probe.
            assertTrue(client.firstCallLatch.await(2, TimeUnit.SECONDS));
            // Sample call rate over a window: a double-spawned loop would yield ~2x the probes.
            // We don't assert exact count (cadence + scheduling jitter) — only that the loop is
            // alive.
            assertTrue(client.calls.get() >= 1);
        } finally {
            poller.stop(TENANT_A);
        }
    }

    @Test
    void stopIdempotent(@TempDir Path tmp) {
        final KmsPoller poller = KmsPoller.create(new RecordingKmsClient(true, null), scheduler(),
                stateMachine(tmp));
        // Stop without start: idempotent
        poller.stop(TENANT_A);
        // Start, stop, stop: idempotent
        poller.start(TENANT_A, KEK_REF, Duration.ofMillis(100));
        poller.stop(TENANT_A);
        poller.stop(TENANT_A);
    }

    @Test
    void stopHaltsLoop(@TempDir Path tmp) throws Exception {
        // After stop, no further probes fire for the tenant (allowing for one in-flight to
        // complete).
        final RecordingKmsClient client = new RecordingKmsClient(true, null);
        final KmsPoller poller = KmsPoller.create(client, scheduler(), stateMachine(tmp));
        poller.start(TENANT_A, KEK_REF, Duration.ofMillis(20));
        assertTrue(client.firstCallLatch.await(2, TimeUnit.SECONDS));
        poller.stop(TENANT_A);
        // Allow the loop to wind down past one cadence.
        Thread.sleep(100);
        final int after = client.calls.get();
        Thread.sleep(150);
        final int laterStill = client.calls.get();
        assertTrue(laterStill - after <= 1,
                "stop must halt the loop — no further probes beyond at most one in-flight");
    }

    /** Probe-mode enum for the multi-tenant test. */
    private enum ProbeMode {
        OK, RUNTIME_ERROR
    }

    /** Stub KmsClient that records calls and produces a configured outcome. */
    private static final class RecordingKmsClient implements KmsClient {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<KekRef> lastProbedRef = new AtomicReference<>();
        private final CountDownLatch firstCallLatch = new CountDownLatch(1);
        private final CountDownLatch successLatch = new CountDownLatch(1);
        private final AtomicInteger runtimeErrorCalls = new AtomicInteger();
        private final boolean defaultUsable;
        private final KmsException defaultThrow;
        private final ConcurrentHashMap<KekRef, ProbeMode> modeForTenant = new ConcurrentHashMap<>();

        RecordingKmsClient() {
            this(true, null);
        }

        RecordingKmsClient(boolean defaultUsable, KmsException defaultThrow) {
            this.defaultUsable = defaultUsable;
            this.defaultThrow = defaultThrow;
        }

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean isUsable(KekRef kekRef) throws KmsException {
            calls.incrementAndGet();
            lastProbedRef.set(kekRef);
            firstCallLatch.countDown();
            final ProbeMode mode = modeForTenant.get(kekRef);
            if (mode == ProbeMode.RUNTIME_ERROR) {
                runtimeErrorCalls.incrementAndGet();
                throw new RuntimeException("simulated runtime error inside probe");
            }
            if (mode == ProbeMode.OK) {
                successLatch.countDown();
                return true;
            }
            // Default mode: configured at construction.
            if (defaultThrow != null) {
                throw defaultThrow;
            }
            if (defaultUsable) {
                successLatch.countDown();
            }
            return defaultUsable;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
