package jlsm.engine.internal;

import jlsm.engine.AllocationTracking;
import jlsm.engine.EngineMetrics;
import jlsm.engine.HandleEvictedException;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks open table handles per source, enforces configurable limits, and performs
 * greedy-source-first eviction under pressure.
 *
 * <p>
 * Thread-safe: all mutable state is guarded by either ConcurrentHashMap or synchronized blocks.
 *
 * <p>
 * Governed by: {@code .decisions/engine-api-surface-design/adr.md}
 */
final class HandleTracker implements Closeable {

    private final int maxHandlesPerSourcePerTable;
    private final int maxHandlesPerTable;
    private final int maxTotalHandles;
    private final AllocationTracking allocationTracking;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Map from tableName -> (sourceId -> list of registrations in insertion order). The outer
     * ConcurrentHashMap provides thread-safe table-level access. Inner maps and lists are guarded
     * by synchronizing on the per-table map.
     */
    private final ConcurrentHashMap<String, Map<String, List<HandleRegistration>>> tableHandles = new ConcurrentHashMap<>();

    /**
     * Set of table names that have been invalidated via {@link #invalidateTable}. Registrations for
     * invalidated tables are rejected to prevent a race where a concurrent register() creates a new
     * map entry after invalidateTable removes the old one.
     */
    private final Set<String> invalidatedTableNames = Collections
            .newSetFromMap(new ConcurrentHashMap<>());

    private HandleTracker(Builder builder) {
        if (builder.maxHandlesPerSourcePerTable <= 0) {
            throw new IllegalArgumentException("maxHandlesPerSourcePerTable must be positive: "
                    + builder.maxHandlesPerSourcePerTable);
        }
        if (builder.maxHandlesPerTable <= 0) {
            throw new IllegalArgumentException(
                    "maxHandlesPerTable must be positive: " + builder.maxHandlesPerTable);
        }
        if (builder.maxTotalHandles <= 0) {
            throw new IllegalArgumentException(
                    "maxTotalHandles must be positive: " + builder.maxTotalHandles);
        }
        assert builder.maxHandlesPerSourcePerTable > 0
                : "maxHandlesPerSourcePerTable must be positive";
        assert builder.maxHandlesPerTable > 0 : "maxHandlesPerTable must be positive";
        assert builder.maxTotalHandles > 0 : "maxTotalHandles must be positive";
        this.maxHandlesPerSourcePerTable = builder.maxHandlesPerSourcePerTable;
        this.maxHandlesPerTable = builder.maxHandlesPerTable;
        this.maxTotalHandles = builder.maxTotalHandles;
        this.allocationTracking = Objects.requireNonNull(builder.allocationTracking,
                "allocationTracking must not be null");
    }

    /**
     * Returns a new builder for constructing a HandleTracker.
     *
     * @return a new builder; never null
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Registers a new handle for the given table and source.
     *
     * @param tableName the table name; must not be null
     * @param sourceId the source identifier; must not be null
     * @return a registration token that must be released when the handle is closed
     */
    HandleRegistration register(String tableName, String sourceId) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (closed.get()) {
            throw new IllegalStateException("HandleTracker is closed");
        }

        final StackTraceElement[] allocationSite = switch (allocationTracking) {
            case OFF, CALLER_TAG -> null;
            case FULL_STACK -> Thread.currentThread().getStackTrace();
        };

        final HandleRegistration registration = new HandleRegistration(tableName, sourceId,
                allocationSite);

        // Retry loop guards against phantom registration: if invalidateTable removed
        // this sourceMap from tableHandles between computeIfAbsent and synchronized,
        // the registration would be orphaned in a detached map. Detect and retry.
        while (true) {
            // Reject registration for tables that have been invalidated (e.g., dropped).
            // This prevents the race where register() creates a new map entry after
            // invalidateTable has removed the old one.
            if (invalidatedTableNames.contains(tableName)) {
                registration.invalidate(HandleEvictedException.Reason.TABLE_DROPPED);
                return registration;
            }
            final Map<String, List<HandleRegistration>> sourceMap = tableHandles
                    .computeIfAbsent(tableName, k -> new LinkedHashMap<>());
            synchronized (sourceMap) {
                // Verify the sourceMap is still the live one in tableHandles
                if (tableHandles.get(tableName) != sourceMap) {
                    // sourceMap was detached by a concurrent invalidateTable — retry
                    continue;
                }
                sourceMap.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(registration);
            }
            break;
        }

        // Double-check: if the table was invalidated between insertion and this check,
        // invalidate the registration we just added. This closes the final race window
        // where invalidateTable runs after our computeIfAbsent but before evictIfNeeded.
        if (invalidatedTableNames.contains(tableName)) {
            release(registration);
            registration.invalidate(HandleEvictedException.Reason.TABLE_DROPPED);
            return registration;
        }

        evictIfNeeded(tableName);

        return registration;
    }

    /**
     * Releases a previously registered handle. Idempotent — releasing an already-released or
     * invalidated registration is a no-op.
     *
     * @param registration the registration to release; must not be null
     */
    void release(HandleRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");

        // Defensively invalidate the registration so that any code still holding a
        // reference cannot pass isInvalidated() checks after release. Idempotent —
        // HandleRegistration.invalidate() is a no-op if already invalidated.
        registration.invalidate(HandleEvictedException.Reason.EVICTION);

        final String tableName = registration.tableName();
        final String sourceId = registration.sourceId();

        final Map<String, List<HandleRegistration>> sourceMap = tableHandles.get(tableName);
        if (sourceMap == null) {
            return;
        }
        synchronized (sourceMap) {
            final List<HandleRegistration> regs = sourceMap.get(sourceId);
            if (regs == null) {
                return;
            }
            regs.remove(registration);
            if (regs.isEmpty()) {
                sourceMap.remove(sourceId);
            }
            if (sourceMap.isEmpty()) {
                tableHandles.remove(tableName);
            }
        }
    }

    /**
     * Evicts handles if the table exceeds its handle limit, using greedy-source-first strategy.
     * Also checks total handle limit across all tables.
     *
     * @param tableName the table to check; must not be null
     */
    void evictIfNeeded(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");

        final Map<String, List<HandleRegistration>> sourceMap = tableHandles.get(tableName);
        if (sourceMap == null) {
            return;
        }

        synchronized (sourceMap) {
            // Check per-source-per-table limits
            for (final Map.Entry<String, List<HandleRegistration>> entry : sourceMap.entrySet()) {
                final List<HandleRegistration> regs = entry.getValue();
                while (regs.size() > maxHandlesPerSourcePerTable) {
                    evictOldest(regs, HandleEvictedException.Reason.EVICTION);
                }
            }

            // Check per-table limit
            int tableTotal = countTableHandles(sourceMap);
            while (tableTotal > maxHandlesPerTable) {
                evictFromGreediestSource(sourceMap, HandleEvictedException.Reason.EVICTION);
                tableTotal = countTableHandles(sourceMap);
            }
        }

        // Check total limit across all tables — evict from the greediest table globally,
        // not just the triggering table. Each iteration finds the table with the most
        // handles and evicts one handle from its greediest source.
        while (countTotalHandles() > maxTotalHandles) {
            final Map<String, List<HandleRegistration>> greediest = findGreediestTable();
            if (greediest == null) {
                break;
            }
            synchronized (greediest) {
                if (countTableHandles(greediest) == 0) {
                    break;
                }
                // Re-check total under the lock to avoid over-eviction
                if (countTotalHandles() <= maxTotalHandles) {
                    break;
                }
                evictFromGreediestSource(greediest, HandleEvictedException.Reason.EVICTION);
            }
        }
    }

    /**
     * Invalidates all tracked handles with the given reason.
     *
     * @param reason the invalidation reason; must not be null
     */
    void invalidateAll(HandleEvictedException.Reason reason) {
        Objects.requireNonNull(reason, "reason must not be null");

        for (final Map.Entry<String, Map<String, List<HandleRegistration>>> tableEntry : tableHandles
                .entrySet()) {
            final Map<String, List<HandleRegistration>> sourceMap = tableEntry.getValue();
            synchronized (sourceMap) {
                for (final List<HandleRegistration> regs : sourceMap.values()) {
                    for (final HandleRegistration reg : regs) {
                        reg.invalidate(reason);
                    }
                }
                sourceMap.clear();
            }
        }
        tableHandles.clear();
    }

    /**
     * Invalidates all tracked handles for a specific table.
     *
     * @param tableName the table name; must not be null
     * @param reason the invalidation reason; must not be null
     */
    void invalidateTable(String tableName, HandleEvictedException.Reason reason) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        // Mark the table as invalidated BEFORE removing from tableHandles. This prevents
        // a concurrent register() from inserting a new map entry after we remove the old
        // one — register() checks invalidatedTableNames and rejects the registration.
        invalidatedTableNames.add(tableName);

        final Map<String, List<HandleRegistration>> sourceMap = tableHandles.remove(tableName);
        if (sourceMap == null) {
            return;
        }
        synchronized (sourceMap) {
            for (final List<HandleRegistration> regs : sourceMap.values()) {
                for (final HandleRegistration reg : regs) {
                    reg.invalidate(reason);
                }
            }
            sourceMap.clear();
        }
    }

    /**
     * Returns a snapshot of current engine metrics.
     *
     * @return the current metrics; never null
     */
    EngineMetrics snapshot() {
        final Map<String, Integer> handlesPerTable = new HashMap<>();
        final Map<String, Map<String, Integer>> handlesPerSourcePerTable = new HashMap<>();
        int totalOpenHandles = 0;

        for (final Map.Entry<String, Map<String, List<HandleRegistration>>> tableEntry : tableHandles
                .entrySet()) {
            final String tableName = tableEntry.getKey();
            final Map<String, List<HandleRegistration>> sourceMap = tableEntry.getValue();
            int tableTotal = 0;
            final Map<String, Integer> sourceCountMap = new HashMap<>();

            synchronized (sourceMap) {
                for (final Map.Entry<String, List<HandleRegistration>> sourceEntry : sourceMap
                        .entrySet()) {
                    final int count = sourceEntry.getValue().size();
                    if (count > 0) {
                        sourceCountMap.put(sourceEntry.getKey(), count);
                        tableTotal += count;
                    }
                }
            }

            if (tableTotal > 0) {
                handlesPerTable.put(tableName, tableTotal);
                handlesPerSourcePerTable.put(tableName, Map.copyOf(sourceCountMap));
                totalOpenHandles += tableTotal;
            }
        }

        final int tableCount = handlesPerTable.size();
        return new EngineMetrics(tableCount, totalOpenHandles, handlesPerTable,
                handlesPerSourcePerTable);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            invalidateAll(HandleEvictedException.Reason.ENGINE_SHUTDOWN);
        }
    }

    /**
     * Returns the number of open handles for the given table.
     *
     * @param tableName the table name; must not be null
     * @return the number of open handles, or 0 if no handles are registered for the table
     */
    int handleCountForTable(String tableName) {
        final Map<String, List<HandleRegistration>> sourceMap = tableHandles.get(tableName);
        if (sourceMap == null) {
            return 0;
        }
        synchronized (sourceMap) {
            return countTableHandles(sourceMap);
        }
    }

    // ---- Private helpers ----

    private static int countTableHandles(Map<String, List<HandleRegistration>> sourceMap) {
        int count = 0;
        for (final List<HandleRegistration> regs : sourceMap.values()) {
            count += regs.size();
        }
        return count;
    }

    /**
     * Returns the sourceMap for the table with the most open handles, or {@code null} if no tables
     * have handles. Used by the total-limit eviction loop to target the globally greediest table
     * rather than whichever table triggered the check.
     */
    private Map<String, List<HandleRegistration>> findGreediestTable() {
        Map<String, List<HandleRegistration>> greediest = null;
        int maxCount = 0;
        for (final Map<String, List<HandleRegistration>> candidate : tableHandles.values()) {
            final int count;
            synchronized (candidate) {
                count = countTableHandles(candidate);
            }
            if (count > maxCount) {
                maxCount = count;
                greediest = candidate;
            }
        }
        return greediest;
    }

    private int countTotalHandles() {
        int total = 0;
        for (final Map<String, List<HandleRegistration>> sourceMap : tableHandles.values()) {
            synchronized (sourceMap) {
                total += countTableHandles(sourceMap);
            }
        }
        return total;
    }

    /**
     * Evicts the oldest handle from the source with the most handles (greedy-source-first).
     */
    private static void evictFromGreediestSource(Map<String, List<HandleRegistration>> sourceMap,
            HandleEvictedException.Reason reason) {
        String greediest = null;
        int maxCount = 0;
        for (final Map.Entry<String, List<HandleRegistration>> entry : sourceMap.entrySet()) {
            final int count = entry.getValue().size();
            if (count > maxCount) {
                maxCount = count;
                greediest = entry.getKey();
            }
        }
        if (greediest == null) {
            return;
        }
        final List<HandleRegistration> regs = sourceMap.get(greediest);
        assert regs != null && !regs.isEmpty() : "greediest source must have registrations";
        evictOldest(regs, reason);
        if (regs.isEmpty()) {
            sourceMap.remove(greediest);
        }
    }

    /**
     * Evicts the oldest (first) registration from the list and marks it invalidated.
     */
    private static void evictOldest(List<HandleRegistration> regs,
            HandleEvictedException.Reason reason) {
        assert !regs.isEmpty() : "cannot evict from empty list";
        final HandleRegistration victim = regs.removeFirst();
        victim.invalidate(reason);
    }

    /**
     * Builder for {@link HandleTracker}.
     */
    static final class Builder {

        private int maxHandlesPerSourcePerTable = 16;
        private int maxHandlesPerTable = 64;
        private int maxTotalHandles = 1024;
        private AllocationTracking allocationTracking = AllocationTracking.OFF;

        private Builder() {
        }

        Builder maxHandlesPerSourcePerTable(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException(
                        "maxHandlesPerSourcePerTable must be positive: " + max);
            }
            assert max > 0 : "maxHandlesPerSourcePerTable must be positive";
            this.maxHandlesPerSourcePerTable = max;
            return this;
        }

        Builder maxHandlesPerTable(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxHandlesPerTable must be positive: " + max);
            }
            assert max > 0 : "maxHandlesPerTable must be positive";
            this.maxHandlesPerTable = max;
            return this;
        }

        Builder maxTotalHandles(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxTotalHandles must be positive: " + max);
            }
            assert max > 0 : "maxTotalHandles must be positive";
            this.maxTotalHandles = max;
            return this;
        }

        Builder allocationTracking(AllocationTracking tracking) {
            Objects.requireNonNull(tracking, "tracking must not be null");
            this.allocationTracking = tracking;
            return this;
        }

        HandleTracker build() {
            if (maxHandlesPerSourcePerTable > maxHandlesPerTable) {
                throw new IllegalArgumentException("maxHandlesPerSourcePerTable ("
                        + maxHandlesPerSourcePerTable + ") must not exceed maxHandlesPerTable ("
                        + maxHandlesPerTable + ")");
            }
            if (maxHandlesPerTable > maxTotalHandles) {
                throw new IllegalArgumentException("maxHandlesPerTable (" + maxHandlesPerTable
                        + ") must not exceed maxTotalHandles (" + maxTotalHandles + ")");
            }
            return new HandleTracker(this);
        }
    }
}
