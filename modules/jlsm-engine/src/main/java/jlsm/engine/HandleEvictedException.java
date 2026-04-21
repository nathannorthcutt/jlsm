package jlsm.engine;

import java.util.Objects;

/**
 * Thrown when an operation is attempted on a table handle that has been evicted, closed, or
 * invalidated due to engine shutdown or table drop.
 *
 * <p>
 * Carries diagnostic information about the eviction context: the table name, the source identifier,
 * the handle count at the time of eviction, the allocation site (if tracking was enabled), and the
 * reason for eviction.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md}
 */
// @spec engine.in-process-database-engine.R29,R47,R48 — subclass of IllegalStateException carrying
// the eviction reason
public final class HandleEvictedException extends IllegalStateException {

    /**
     * The reason a handle was evicted.
     */
    public enum Reason {
        /** Handle was evicted due to handle limit pressure. */
        EVICTION,
        /** Handle was invalidated because the engine was shut down. */
        ENGINE_SHUTDOWN,
        /** Handle was invalidated because the table was dropped. */
        TABLE_DROPPED
    }

    private final String tableName;
    private final String sourceId;
    private final int handleCountAtEviction;
    private final StackTraceElement[] allocationSite;
    private final Reason reason;

    /**
     * Constructs a new HandleEvictedException.
     *
     * @param tableName the name of the table; must not be null
     * @param sourceId the source identifier of the handle; must not be null
     * @param handleCountAtEviction the number of handles open at eviction time
     * @param allocationSite the allocation stack trace, or null if tracking was off
     * @param reason the reason for eviction; must not be null
     */
    public HandleEvictedException(String tableName, String sourceId, int handleCountAtEviction,
            StackTraceElement[] allocationSite, Reason reason) {
        super(buildMessage(tableName, sourceId, handleCountAtEviction, reason));
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (handleCountAtEviction < 0) {
            throw new IllegalArgumentException(
                    "handleCountAtEviction must be non-negative, got " + handleCountAtEviction);
        }
        this.handleCountAtEviction = handleCountAtEviction;
        this.allocationSite = allocationSite == null ? null : allocationSite.clone();
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    private static String buildMessage(String tableName, String sourceId, int handleCountAtEviction,
            Reason reason) {
        return "Handle evicted: table=" + tableName + ", source=" + sourceId + ", openHandles="
                + handleCountAtEviction + ", reason=" + reason;
    }

    /** Returns the name of the table the handle referenced. */
    public String tableName() {
        return tableName;
    }

    /** Returns the source identifier of the evicted handle. */
    public String sourceId() {
        return sourceId;
    }

    /** Returns the number of open handles at the time of eviction. */
    public int handleCountAtEviction() {
        return handleCountAtEviction;
    }

    /** Returns a defensive copy of the allocation site stack trace, or null if tracking was off. */
    public StackTraceElement[] allocationSite() {
        return allocationSite == null ? null : allocationSite.clone();
    }

    /** Returns the reason the handle was evicted. */
    public Reason reason() {
        return reason;
    }
}
