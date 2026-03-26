package jlsm.engine.internal;

import jlsm.engine.HandleEvictedException;

import java.util.Objects;

/**
 * An opaque token representing a registered table handle. Must be released via
 * {@link HandleTracker#release(HandleRegistration)} when the handle is closed.
 */
final class HandleRegistration {

    private final String tableName;
    private final String sourceId;
    private final StackTraceElement[] allocationSite;
    private volatile boolean invalidated;
    private volatile HandleEvictedException.Reason invalidationReason;

    HandleRegistration(String tableName, String sourceId, StackTraceElement[] allocationSite) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        this.allocationSite = allocationSite == null ? null : allocationSite.clone();
    }

    String tableName() {
        return tableName;
    }

    String sourceId() {
        return sourceId;
    }

    StackTraceElement[] allocationSite() {
        return allocationSite == null ? null : allocationSite.clone();
    }

    boolean isInvalidated() {
        return invalidated;
    }

    /**
     * Returns the reason this registration was invalidated, or null if not yet invalidated.
     *
     * @return the invalidation reason, or null
     */
    HandleEvictedException.Reason invalidationReason() {
        return invalidationReason;
    }

    /**
     * Marks this registration as invalidated with the given reason.
     *
     * @param reason the invalidation reason; must not be null
     */
    void invalidate(HandleEvictedException.Reason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        this.invalidationReason = reason;
        this.invalidated = true;
    }

    /**
     * Marks this registration as invalidated with {@link HandleEvictedException.Reason#EVICTION}.
     */
    void invalidate() {
        invalidate(HandleEvictedException.Reason.EVICTION);
    }
}
