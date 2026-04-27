package jlsm.encryption;

import java.time.Instant;
import java.util.Objects;

/**
 * Observer SPI for KMS / encryption-lifecycle events. Implementations receive structured event
 * payloads they may forward to logging, metrics, or external alerting systems. All callbacks have
 * default no-op implementations so partial-implementation observers are valid.
 *
 * <p>
 * Each event payload carries:
 * <ul>
 * <li>{@code eventSeq} — monotonic sequence number per category</li>
 * <li>{@code eventCategory} — see {@link EventCategory}</li>
 * <li>{@code seqDurability} — whether {@code eventSeq} survives restart</li>
 * <li>{@code timestamp} — wall-clock instant of the event</li>
 * <li>{@code tenantId} — owning tenant (may be null for cross-tenant events)</li>
 * <li>{@code correlationId} — opaque correlation identifier (R83h)</li>
 * </ul>
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83a, R83c, R83f, R83h, R76a-2).
 *
 * @spec encryption.primitives-lifecycle R83f
 * @spec encryption.primitives-lifecycle R83h
 */
public interface KmsObserver {

    /** A tenant's state-machine transitioned to a new state. */
    default void onTenantKekStateTransition(TenantStateTransitionEvent event) {
        Objects.requireNonNull(event, "event");
        // default: no-op
    }

    /** A rekey-lifecycle event fired (start, batch advance, complete, abort). */
    default void onRekeyEvent(RekeyEvent event) {
        Objects.requireNonNull(event, "event");
        // default: no-op
    }

    /** A KMS poller event fired (success, transient, permanent). */
    default void onPollingEvent(PollingEvent event) {
        Objects.requireNonNull(event, "event");
        // default: no-op
    }

    /** An unclassified-error escalation event fired. */
    default void onUnclassifiedError(UnclassifiedErrorEvent event) {
        Objects.requireNonNull(event, "event");
        // default: no-op
    }

    /** Common base fields carried on every event payload. */
    record EventEnvelope(long eventSeq, EventCategory eventCategory, boolean seqDurability,
            Instant timestamp, TenantId tenantId, String correlationId) {

        public EventEnvelope {
            Objects.requireNonNull(eventCategory, "eventCategory");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(correlationId, "correlationId");
            // tenantId may be null for cross-tenant events
        }
    }

    /** Tenant state-machine transition event. */
    record TenantStateTransitionEvent(EventEnvelope envelope, TenantState fromState,
            TenantState toState) {

        public TenantStateTransitionEvent {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(fromState, "fromState");
            Objects.requireNonNull(toState, "toState");
        }
    }

    /** Rekey-lifecycle event. */
    record RekeyEvent(EventEnvelope envelope, String phase, KekRef oldRef, KekRef newRef,
            long shardsCompleted) {

        public RekeyEvent {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(oldRef, "oldRef");
            Objects.requireNonNull(newRef, "newRef");
        }
    }

    /** KMS poller event. */
    record PollingEvent(EventEnvelope envelope, KekRef kekRef, String outcome) {

        public PollingEvent {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(kekRef, "kekRef");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Unclassified-error escalation event. */
    record UnclassifiedErrorEvent(EventEnvelope envelope, String errorClass, long countInWindow,
            boolean escalatedToPermanent) {

        public UnclassifiedErrorEvent {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(errorClass, "errorClass");
        }
    }
}
