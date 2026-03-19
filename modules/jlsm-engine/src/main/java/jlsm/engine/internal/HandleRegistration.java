package jlsm.engine.internal;

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

    HandleRegistration(String tableName, String sourceId, StackTraceElement[] allocationSite) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        this.allocationSite = allocationSite;
    }

    String tableName() {
        return tableName;
    }

    String sourceId() {
        return sourceId;
    }

    StackTraceElement[] allocationSite() {
        return allocationSite;
    }

    boolean isInvalidated() {
        return invalidated;
    }

    void invalidate() {
        this.invalidated = true;
    }
}
