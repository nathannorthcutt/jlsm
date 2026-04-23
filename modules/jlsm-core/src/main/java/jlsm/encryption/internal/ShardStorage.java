package jlsm.encryption.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import jlsm.encryption.TenantId;

/**
 * Durable per-tenant shard storage. Each tenant's {@link KeyRegistryShard} is
 * persisted to a single file under {@code registryRoot}. Reads verify a CRC-32C
 * trailer (R19a); writes go through a temp-file + fsync + rename sequence (R20) so
 * that a crash mid-write either preserves the previous shard or leaves a recoverable
 * temp file. Orphan temp files are swept by {@link #recoverOrphanTemps} (R20a).
 *
 * <p>POSIX permissions on both the temp file and the final shard are 0600 (owner
 * read/write only, R70, R70a). On non-POSIX filesystems the implementation falls
 * back to {@code Files.setAttribute} best-effort.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R19a, R20, R20a,
 * R70, R70a.
 */
public final class ShardStorage {

    private final Path registryRoot;

    /**
     * @throws NullPointerException if {@code registryRoot} is null
     */
    public ShardStorage(Path registryRoot) {
        this.registryRoot = Objects.requireNonNull(registryRoot, "registryRoot must not be null");
    }

    /**
     * Load a tenant's shard, verifying its CRC-32C trailer.
     *
     * @return empty if no shard file exists for the tenant
     * @throws NullPointerException if {@code tenantId} is null
     * @throws IOException on I/O error or CRC-32C mismatch
     */
    public Optional<KeyRegistryShard> loadShard(TenantId tenantId) throws IOException {
        throw new UnsupportedOperationException("ShardStorage.loadShard stub — WU-3 scope");
    }

    /**
     * Atomically persist {@code shard} for {@code tenantId}: temp write, fsync,
     * POSIX 0600, rename-over.
     *
     * @throws NullPointerException if either argument is null
     * @throws IOException on I/O failure
     */
    public void writeShard(TenantId tenantId, KeyRegistryShard shard) throws IOException {
        throw new UnsupportedOperationException("ShardStorage.writeShard stub — WU-3 scope");
    }

    /**
     * Recover orphan temp files left by a prior crashed write: verify CRC, compare
     * against existing shard, promote if newer and valid, delete if invalid or older
     * than existing shard (R20a).
     *
     * @throws IOException on I/O failure
     */
    public void recoverOrphanTemps() throws IOException {
        throw new UnsupportedOperationException(
                "ShardStorage.recoverOrphanTemps stub — WU-3 scope");
    }

    Path registryRoot() {
        return registryRoot;
    }
}
