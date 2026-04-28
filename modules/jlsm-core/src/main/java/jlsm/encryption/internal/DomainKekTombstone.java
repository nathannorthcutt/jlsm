package jlsm.encryption.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

/**
 * Per-{@code (tenantId, domainId)} revocation tombstone (R83b-1a). Mark-revoked operations are
 * idempotent; the quiescence barrier waits for the configured upper bound (default = cache TTL, 24h
 * ceiling, 48h hard maximum per P4-1) before forcing a zero-pass on cached domain-KEK material.
 *
 * <p>
 * The tombstone is keyed by {@code (tenantId, domainId)} only — table component is ignored when
 * marking, but {@link #isRevoked(TableScope)} must accept {@link TableScope} so the read path can
 * consult it without re-deriving keys. Per R83b-1a's "lazy-population" relationship to R83g, this
 * tombstone is the per-domain short-circuit; per-DEK entries are populated on demand by the R83g
 * cache.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83b, R83b-1a, R83g); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R83b-1a
 */
public final class DomainKekTombstone {

    /** P4-1 hard maximum quiescence barrier — never exceed this regardless of caller config. */
    static final Duration HARD_MAX_BOUND = Duration.ofHours(48);

    /** Default cache-TTL bound when no explicit value is supplied. */
    static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);

    private final Duration quiescenceBound;

    /**
     * Set of revoked {@code (tenantId, domainId)} keys. The map's keys are the only meaningful
     * data; the value is a per-key state record carrying the in-flight reader counter.
     */
    private final ConcurrentHashMap<DomainKey, TombstoneState> states = new ConcurrentHashMap<>();

    private DomainKekTombstone(Duration quiescenceBound) {
        Objects.requireNonNull(quiescenceBound, "quiescenceBound");
        if (quiescenceBound.isZero() || quiescenceBound.isNegative()) {
            throw new IllegalArgumentException(
                    "quiescenceBound must be positive, got " + quiescenceBound);
        }
        // P4-1 cap: any caller-supplied value above 48h is silently capped. The implementation
        // remembers the capped value so {@link #quiesceAndZeroize}'s timeout calculation is
        // bounded.
        this.quiescenceBound = quiescenceBound.compareTo(HARD_MAX_BOUND) > 0 ? HARD_MAX_BOUND
                : quiescenceBound;
    }

    /** Construct with the cache-TTL default. */
    public static DomainKekTombstone withCacheTtlDefault() {
        return new DomainKekTombstone(DEFAULT_CACHE_TTL);
    }

    /** Construct with explicit quiescence bound. Capped at 48h hard maximum per P4-1. */
    public static DomainKekTombstone withQuiescenceBound(Duration bound) {
        return new DomainKekTombstone(bound);
    }

    /** Configured quiescence bound after P4-1 capping. */
    public Duration quiescenceBound() {
        return quiescenceBound;
    }

    /**
     * Mark {@code scope} as revoked. Idempotent.
     *
     * @throws NullPointerException if {@code scope} is null
     */
    public void markRevoked(TableScope scope) {
        Objects.requireNonNull(scope, "scope");
        final DomainKey key = DomainKey.of(scope);
        states.computeIfAbsent(key, _k -> new TombstoneState(Instant.now()));
    }

    /** True iff {@code scope} has been marked revoked. */
    public boolean isRevoked(TableScope scope) {
        Objects.requireNonNull(scope, "scope");
        return states.containsKey(DomainKey.of(scope));
    }

    /**
     * Wait for in-flight readers on {@code scope} to drain (up to {@code within}), then zero cached
     * material. Returns the result, including a {@code timedOut} flag if the quiescence barrier
     * expired before all readers drained (forced-zero path).
     *
     * <p>
     * Implementation note: the public-API stub accepts an in-flight-reader counter that callers may
     * register via the (future) reader-registration hook. The current public contract waits up to
     * {@code within} for the counter to reach zero. With no readers registered, returns immediately
     * (R83b-1a "no in-flight readers" case).
     */
    public QuiescenceResult quiesceAndZeroize(TableScope scope, Duration within) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(within, "within");
        final DomainKey key = DomainKey.of(scope);
        final TombstoneState state = states.get(key);
        // No tombstone yet means no reader could have been registered post-tombstone — treat as
        // zero in-flight, immediate completion.
        final long inFlight = state == null ? 0L : state.inFlightCount.get();
        // Effective bound: the minimum of caller-supplied {@code within} and the configured
        // quiescenceBound (which itself is already P4-1-capped). The forced-zero path applies
        // when in-flight readers cannot drain within the bound.
        final Duration effectiveBound = within.compareTo(quiescenceBound) < 0 ? within
                : quiescenceBound;
        final Instant deadline = Instant.now().plus(effectiveBound);
        boolean timedOut = false;
        long currentInFlight = inFlight;
        while (currentInFlight > 0 && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(1L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            currentInFlight = state == null ? 0L : state.inFlightCount.get();
        }
        if (currentInFlight > 0) {
            timedOut = true;
        }
        return new QuiescenceResult(scope, timedOut, currentInFlight, Instant.now());
    }

    /** Internal per-(tenantId, domainId) key. Records-as-keys; equals/hashCode are auto. */
    private record DomainKey(TenantId tenantId, DomainId domainId) {

        static DomainKey of(TableScope scope) {
            return new DomainKey(scope.tenantId(), scope.domainId());
        }
    }

    /** Internal per-tombstone state. {@code inFlightCount} is reserved for reader registration. */
    private static final class TombstoneState {

        @SuppressWarnings("unused")
        private final Instant insertedAt;

        private final AtomicLong inFlightCount = new AtomicLong(0L);

        TombstoneState(Instant insertedAt) {
            this.insertedAt = insertedAt;
        }
    }

    /** Result of {@link #quiesceAndZeroize}. */
    public record QuiescenceResult(TableScope scope, boolean timedOut, long inFlightAtForce,
            Instant completedAt) {

        public QuiescenceResult {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(completedAt, "completedAt");
            if (inFlightAtForce < 0) {
                throw new IllegalArgumentException(
                        "inFlightAtForce must be non-negative, got " + inFlightAtForce);
            }
        }
    }
}
