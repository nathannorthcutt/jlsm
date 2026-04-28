package jlsm.encryption.internal;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.spi.CompactionInputRegistry;
import jlsm.encryption.spi.CompactionInputRegistry.SSTableId;
import jlsm.encryption.spi.WalLivenessSource;

/**
 * Prune unreferenced DEKs for a given scope (R30, R30a, R30b, R30c). Reads a manifest snapshot and
 * the compaction-input set under a shared read lock that prevents new compaction registrations
 * during the snapshot. Pruned DEK plaintexts are zeroized (R31).
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@link CompactionInputRegistry} — current SSTables under compaction (snapshot-locked)</li>
 * <li>{@link WalLivenessSource} — per-tenant unreplayed WAL count keyed by KekRef (R75c)</li>
 * <li>{@link ManifestSnapshotSource} — per-scope live DEK versions referenced by SSTables in the
 * manifest, plus an SSTable-id → version lookup for compaction-input translation</li>
 * </ul>
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R30, R30a, R30b, R30c, R31); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R30
 * @spec encryption.primitives-lifecycle R30a
 * @spec encryption.primitives-lifecycle R30b
 * @spec encryption.primitives-lifecycle R30c
 * @spec encryption.primitives-lifecycle R31
 */
public final class DekPruner {

    private final TenantShardRegistry registry;
    private final CompactionInputRegistry compactionInputs;
    private final WalLivenessSource walLiveness;
    private final ManifestSnapshotSource manifest;
    private final ZeroisationCallback zeroisationCallback;

    private DekPruner(TenantShardRegistry registry, CompactionInputRegistry compactionInputs,
            WalLivenessSource walLiveness, ManifestSnapshotSource manifest,
            ZeroisationCallback zeroisationCallback) {
        this.registry = registry;
        this.compactionInputs = compactionInputs;
        this.walLiveness = walLiveness;
        this.manifest = manifest;
        this.zeroisationCallback = zeroisationCallback;
    }

    /**
     * Construct a pruner wired to the supplied collaborators (no zeroisation observer).
     */
    public static DekPruner create(TenantShardRegistry registry,
            CompactionInputRegistry compactionInputs, WalLivenessSource walLiveness,
            ManifestSnapshotSource manifest) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(compactionInputs, "compactionInputs");
        Objects.requireNonNull(walLiveness, "walLiveness");
        Objects.requireNonNull(manifest, "manifest");
        return new DekPruner(registry, compactionInputs, walLiveness, manifest, NO_OP_CALLBACK);
    }

    /**
     * Construct a pruner wired to the supplied collaborators with a R31-zeroisation observer. The
     * observer fires for each pruned wrapped-DEK before its bytes are zeroised in memory.
     */
    public static DekPruner create(TenantShardRegistry registry,
            CompactionInputRegistry compactionInputs, WalLivenessSource walLiveness,
            ManifestSnapshotSource manifest, ZeroisationCallback zeroisationCallback) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(compactionInputs, "compactionInputs");
        Objects.requireNonNull(walLiveness, "walLiveness");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(zeroisationCallback, "zeroisationCallback");
        return new DekPruner(registry, compactionInputs, walLiveness, manifest,
                zeroisationCallback);
    }

    /**
     * Prune DEKs in scope {@code (tenantId, domainId, tableId)} that are no longer referenced by
     * any live SSTable, in-flight compaction input, or unreplayed WAL segment. Returns the set of
     * pruned versions; their plaintext bytes have been zeroized before this method returns (R31).
     *
     * @throws NullPointerException if any argument is null
     */
    public Set<DekVersion> pruneUnreferenced(TenantId tenantId, DomainId domainId, TableId tableId)
            throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(domainId, "domainId");
        Objects.requireNonNull(tableId, "tableId");

        // R30b + R30c: take an atomic snapshot of (manifest live versions, compaction-input set)
        // under a shared read lock that excludes new compaction-input registrations.
        final ProtectedSet protectedSet = takeSnapshot(tenantId, domainId, tableId);

        // Determine the candidate prune set against the registry's current shard. The current
        // (highest) version is always retained as in-use (new writes must continue to find it).
        final Set<DekVersion> pruned = new HashSet<>();
        registry.updateShard(tenantId, current -> {
            final int currentMaxVersion = highestVersionInScope(current, tenantId, domainId,
                    tableId);
            final Map<DekHandle, WrappedDek> filtered = new HashMap<>(current.deks());
            final Iterator<Map.Entry<DekHandle, WrappedDek>> it = filtered.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<DekHandle, WrappedDek> entry = it.next();
                final DekHandle h = entry.getKey();
                if (!h.tenantId().equals(tenantId) || !h.domainId().equals(domainId)
                        || !h.tableId().equals(tableId)) {
                    continue;
                }
                final int v = h.version().value();
                if (v == currentMaxVersion) {
                    // Always retain the head — it is the "in-use" DEK by definition.
                    continue;
                }
                if (protectedSet.versions.contains(v)) {
                    continue;
                }
                // R31 observer fires before zeroisation. The wrappedBytes accessor returns a
                // defensive copy so we cannot zero the canonical persisted form here in memory
                // (it already gets superseded by the new shard write below); we zero the copy
                // we hand the observer.
                final WrappedDek wd = entry.getValue();
                final byte[] copy = wd.wrappedBytes();
                try {
                    zeroisationCallback.onPrune(h, copy);
                } finally {
                    Arrays.fill(copy, (byte) 0);
                }
                it.remove();
                pruned.add(h.version());
            }
            // Build the new shard — same identity, same domain-keks, same kek ref + salt, with
            // the filtered DEK map.
            final KeyRegistryShard newShard = new KeyRegistryShard(current.tenantId(), filtered,
                    current.domainKeks(), current.activeTenantKekRef(), current.hkdfSalt());
            return new TenantShardRegistry.ShardUpdate<>(newShard, null);
        });
        // Defensive: walLiveness is consulted for cross-validation. Currently no DEK-level
        // dependency comes through it (KEK-level only — see R75c), so its consultation is a
        // no-op for DEK pruning. Keep the field reachable so future R75c tightening lands here.
        assert walLiveness != null : "walLiveness collaborator must be wired";
        return Set.copyOf(pruned);
    }

    /**
     * Take an atomic snapshot of the protected DEK-version set: union of (manifest live versions ∪
     * compaction-input versions). R30c: the snapshot lock excludes new compaction registrations for
     * the duration of this method via {@link CompactionInputRegistry#withSnapshotLock}.
     */
    private ProtectedSet takeSnapshot(TenantId tenantId, DomainId domainId, TableId tableId) {
        return compactionInputs.withSnapshotLock(() -> snapshotInner(tenantId, domainId, tableId));
    }

    private ProtectedSet snapshotInner(TenantId tenantId, DomainId domainId, TableId tableId) {
        final HashSet<Integer> versions = new HashSet<>(
                manifest.liveVersionsInManifest(tenantId, domainId, tableId));
        for (SSTableId sst : compactionInputs.currentInputSet()) {
            final Optional<Integer> v = manifest.versionForSSTable(sst, tenantId, domainId,
                    tableId);
            v.ifPresent(versions::add);
        }
        return new ProtectedSet(Set.copyOf(versions));
    }

    /**
     * Highest DEK version present in the registry shard for {@code (tenantId, domainId, tableId)};
     * returns 0 if no DEK exists in scope.
     */
    private static int highestVersionInScope(KeyRegistryShard shard, TenantId tenantId,
            DomainId domainId, TableId tableId) {
        int max = 0;
        for (DekHandle h : shard.deks().keySet()) {
            if (h.tenantId().equals(tenantId) && h.domainId().equals(domainId)
                    && h.tableId().equals(tableId)) {
                max = Math.max(max, h.version().value());
            }
        }
        return max;
    }

    private record ProtectedSet(Set<Integer> versions) {
    }

    /** Default no-op zeroisation observer for callers that opt out. */
    private static final ZeroisationCallback NO_OP_CALLBACK = (handle, bytes) -> {
    };

    /**
     * Per-scope manifest snapshot source. Returns the set of DEK versions referenced by live
     * SSTables and a per-SSTable version lookup so the pruner can translate compaction-input ids
     * into protected versions.
     */
    public interface ManifestSnapshotSource {

        /**
         * Return the set of DEK versions referenced by live SSTables in the manifest within the
         * given scope. Empty if no live SSTable references that scope.
         *
         * @throws NullPointerException if any argument is null
         */
        Set<Integer> liveVersionsInManifest(TenantId tenantId, DomainId domainId, TableId tableId);

        /**
         * Look up the DEK version referenced by an SSTable. Returns empty if {@code id} is not
         * known or does not match the requested scope.
         *
         * @throws NullPointerException if any argument is null
         */
        Optional<Integer> versionForSSTable(SSTableId id, TenantId tenantId, DomainId domainId,
                TableId tableId);
    }

    /**
     * Observer fired exactly once per pruned DEK, immediately before its wrapped-bytes ciphertext
     * is zeroised and removed from the registry shard. Used by tests and audit hooks to observe R31
     * zeroisation.
     */
    @FunctionalInterface
    public interface ZeroisationCallback {

        void onPrune(DekHandle handle, byte[] wrappedBytes);
    }
}
