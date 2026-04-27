package jlsm.encryption.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsErrorClassifier;
import jlsm.encryption.KmsErrorClassifier.ErrorClass;
import jlsm.encryption.KmsException;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

/**
 * Per-tenant virtual-thread KMS poll loop. Probes the tenant's tier-1 KEK on the configured cadence
 * and feeds the result into the tenant state machine. Default-on for flavor-3 deployments per R79d.
 * Coalesces with eager probes via the R83c-2 rate limit.
 *
 * <p>
 * Lifecycle: {@link #start} spawns a virtual thread that loops on {@link KmsClient#isUsable};
 * {@link #stop} interrupts the loop and waits up to a configurable shutdown bound for it to exit.
 *
 * <p>
 * <b>Per-tenant isolation (R79b):</b> each tenant's loop runs on its own virtual thread; an
 * unhandled runtime exception inside one tenant's probe must not terminate other tenants' loops or
 * the poller's overall capacity. The loop catches all {@link Throwable}s, treats unhandled errors
 * as transient noise, and keeps running until {@link #stop} is invoked.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R79, R79a, R79b, R79c, R79d, R83c-2).
 *
 * @spec encryption.primitives-lifecycle R79
 * @spec encryption.primitives-lifecycle R79a
 * @spec encryption.primitives-lifecycle R79b
 * @spec encryption.primitives-lifecycle R79d
 * @spec encryption.primitives-lifecycle R83c-2
 */
public final class KmsPoller {

    /** Default shutdown bound for the per-tenant loop to exit after stop(). */
    private static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(2);

    /**
     * Synthetic {@link DomainId} attached to polling-detected scope identifiers. The polling path
     * is tenant-scoped, not domain-scoped — but {@link TenantStateMachine#recordPermanentFailure}
     * requires a {@link TableScope} for the dedup hook. We pin a stable placeholder so polling-
     * detected failures are routed through the same code path as DEK-read-detected failures.
     */
    private static final DomainId POLLING_DOMAIN = new DomainId("polling");

    /** Synthetic {@link TableId} for the polling-detected scope; see {@link #POLLING_DOMAIN}. */
    private static final TableId POLLING_TABLE = new TableId("kek-probe");

    private final KmsClient kmsClient;
    private final PollingScheduler scheduler;
    private final TenantStateMachine stateMachine;

    /** Per-tenant loop registry keyed by tenant id. Idempotent start is guarded by putIfAbsent. */
    private final ConcurrentHashMap<TenantId, TenantLoop> loops = new ConcurrentHashMap<>();

    private KmsPoller(KmsClient kmsClient, PollingScheduler scheduler,
            TenantStateMachine stateMachine) {
        this.kmsClient = kmsClient;
        this.scheduler = scheduler;
        this.stateMachine = stateMachine;
    }

    /** Construct a poller wired to its collaborators. */
    public static KmsPoller create(KmsClient kmsClient, PollingScheduler scheduler,
            TenantStateMachine stateMachine) {
        Objects.requireNonNull(kmsClient, "kmsClient");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(stateMachine, "stateMachine");
        return new KmsPoller(kmsClient, scheduler, stateMachine);
    }

    /**
     * Start the poll loop for {@code tenantId} probing {@code tenantKekRef} on the given
     * {@code cadence}. Idempotent: a second start for the same tenantId is a no-op.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code cadence} is non-positive
     */
    public void start(TenantId tenantId, KekRef tenantKekRef, Duration cadence) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tenantKekRef, "tenantKekRef");
        Objects.requireNonNull(cadence, "cadence");
        if (cadence.isZero() || cadence.isNegative()) {
            throw new IllegalArgumentException("cadence must be positive, got " + cadence);
        }

        // Idempotent: putIfAbsent races so only one start wins per tenantId.
        final TenantLoop fresh = new TenantLoop(tenantId, tenantKekRef, cadence);
        final TenantLoop existing = loops.putIfAbsent(tenantId, fresh);
        if (existing != null) {
            return; // already running
        }
        fresh.start();
    }

    /** Stop the poll loop for {@code tenantId}. Idempotent. */
    public void stop(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        final TenantLoop loop = loops.remove(tenantId);
        if (loop != null) {
            loop.stop();
        }
    }

    /** Per-tenant loop state. */
    private final class TenantLoop {

        private final TenantId tenantId;
        private final KekRef tenantKekRef;
        private final Duration cadence;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile Thread thread;

        TenantLoop(TenantId tenantId, KekRef tenantKekRef, Duration cadence) {
            this.tenantId = tenantId;
            this.tenantKekRef = tenantKekRef;
            this.cadence = cadence;
        }

        void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            // R79b: spawn one virtual thread per tenant — one tenant's load (or fault) cannot
            // affect other tenants' KMS quota or jlsm's thread pool capacity.
            final Thread t = Thread.ofVirtual().name("jlsm-kms-poller-" + redactedTenant(tenantId))
                    .uncaughtExceptionHandler((th, ex) -> {
                        // Per R79b: a runtime error in one tenant's loop must not bubble out to
                        // poison thread-pool capacity. The loop body already catches Throwable;
                        // this
                        // handler is a defence-in-depth backstop.
                    }).start(this::run);
            this.thread = t;
        }

        void stop() {
            running.set(false);
            final Thread t = thread;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(SHUTDOWN_BOUND.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * The poll-loop body. Iterative — never recursive. Each iteration:
         * <ol>
         * <li>Try to acquire one unit of the per-instance aggregate rate-limit budget (R79c, shared
         * with eager probes per R83c-2). If exhausted, sleep for the cadence and retry.</li>
         * <li>Invoke {@link KmsClient#isUsable}.</li>
         * <li>Classify the outcome via {@link KmsErrorClassifier}.</li>
         * <li>Feed PERMANENT classifications into the {@link TenantStateMachine} (R79a). TRANSIENT
         * and UNCLASSIFIED outcomes do not advance the counter.</li>
         * <li>Sleep for the configured cadence; honour interrupt.</li>
         * </ol>
         */
        private void run() {
            // Each loop iteration is bounded — there is no recursive descent. Loop terminates when
            // running == false OR when the carrier thread is interrupted (also via stop()).
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    pollOnce();
                } catch (Throwable t) {
                    // R79b: catch ALL Throwables — including RuntimeException and Error-derived
                    // classes that may be thrown by a misbehaving SPI implementation. The loop
                    // continues on the next cadence tick. We do not log here because we do not
                    // have a logging contract — KmsObserver event emission is the future
                    // observability path.
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!sleepCadence()) {
                    return;
                }
            }
        }

        private void pollOnce() {
            // R79c per-instance aggregate rate-limit. Shared with eager probes per R83c-2.
            // When exhausted, defer this poll — the next iteration's sleep gives budget time to
            // refill and the next iteration probes.
            if (!scheduler.tryAcquireBudget()) {
                return;
            }
            try {
                final boolean usable = kmsClient.isUsable(tenantKekRef);
                if (usable) {
                    // Future work: feed back into the state machine for grace-read-only → healthy
                    // recovery path (R77 per-tenant). This unit ships the failure path; the
                    // recovery half is documented as an extension point.
                    return;
                }
                // isUsable returned false but did not throw — there is no exception to classify.
                // Treat as a permanent classification (the KEK is unreachable from this caller).
                recordPermanentSafely();
            } catch (KmsException kmsEx) {
                final ErrorClass cls = KmsErrorClassifier.classify(kmsEx);
                if (cls == ErrorClass.PERMANENT) {
                    recordPermanentSafely();
                }
                // TRANSIENT and UNCLASSIFIED do not advance the counter (R79a + R76a).
            }
        }

        private void recordPermanentSafely() {
            try {
                final TableScope scope = new TableScope(tenantId, POLLING_DOMAIN, POLLING_TABLE);
                stateMachine.recordPermanentFailure(tenantId, scope, ErrorClass.PERMANENT);
            } catch (IOException ioe) {
                // R76b-1: durable-commit failure on the state-progress file. Swallow so the loop
                // continues — restart will re-discover the failure on the next probe and the
                // durable record will eventually catch up. Observability path lands in WU-6's
                // observer wiring.
            } catch (RuntimeException re) {
                // Defensive: an unexpected runtime fault inside the state machine must not
                // terminate this tenant's loop (R79b). Same rationale as IOException above.
            }
        }

        /**
         * Sleep for the configured cadence. Returns false if interrupted (loop should exit).
         */
        private boolean sleepCadence() {
            try {
                // Bounded sleep — never indefinite per coding-guidelines.md "Bounded Iteration".
                Thread.sleep(cadence.toMillis(), (cadence.toNanosPart() % 1_000_000));
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static String redactedTenant(TenantId t) {
            // TenantId.toString() already redacts; use a stable thread-name suffix that does not
            // leak the raw tenant value.
            return Integer.toHexString(System.identityHashCode(t));
        }
    }
}
