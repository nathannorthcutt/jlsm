package jlsm.encryption;

/**
 * Closed set of categories carried on every {@link KmsObserver} event. Each category records
 * whether durable {@code eventSeq} ordering is provided across process restarts.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83f event-emission contract).
 *
 * @spec encryption.primitives-lifecycle R83f
 */
public enum EventCategory {

    /** Tenant state-machine transitions. Durable across restart. */
    STATE_TRANSITION(true),

    /** Rekey lifecycle events (start, batch, complete, abort). Durable across restart. */
    REKEY(true),

    /** KMS poller events (success, transient, permanent). Best-effort, not durable. */
    POLLING(false),

    /**
     * Unclassified-error escalation events (counter, threshold breach, escalation to permanent).
     * Best-effort, not durable.
     */
    UNCLASSIFIED_ERROR(false);

    private final boolean durable;

    EventCategory(boolean durable) {
        this.durable = durable;
    }

    /** True iff this category provides durable {@code eventSeq} ordering across restarts. */
    public boolean isDurable() {
        return durable;
    }
}
