package jlsm.encryption.internal;

import java.nio.file.Path;

import jlsm.encryption.TenantId;

/**
 * Deterministic derivation of per-tenant shard paths within the key registry root.
 * Documented for operational tooling so ops can locate a given tenant's shard file
 * without consulting internal data structures (R82b).
 *
 * <p>The encoding of {@link TenantId#value()} into a filesystem-safe path component
 * is chosen in WU-3 TDD (URL-safe base32 of the UTF-8 bytes is the current
 * candidate). Whatever the choice, {@link #shardPath} must be pure: same input,
 * same output, no I/O.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R82, R82b.
 */
public final class ShardPathResolver {

    private ShardPathResolver() {}

    /**
     * Resolve the shard file for a tenant within a registry root.
     *
     * @throws NullPointerException if either argument is null
     */
    public static Path shardPath(Path registryRoot, TenantId tenantId) {
        throw new UnsupportedOperationException("ShardPathResolver.shardPath stub — WU-3 scope");
    }

    /**
     * Resolve a temp path alongside {@code shardPath} used during atomic commit.
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code suffix} is empty
     */
    public static Path tempPath(Path shardPath, String suffix) {
        throw new UnsupportedOperationException("ShardPathResolver.tempPath stub — WU-3 scope");
    }
}
