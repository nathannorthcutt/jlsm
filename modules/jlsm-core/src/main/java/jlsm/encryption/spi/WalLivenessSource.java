package jlsm.encryption.spi;

import java.util.Objects;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;

/**
 * SPI: per-tenant count of unreplayed WAL segments whose wrapping chain depends on a given
 * {@link KekRef}. Consulted by the rekey grace-period gate (R75c) and the on-disk liveness witness
 * (R78e) so a retired KekRef is not garbage-collected while WAL replay still depends on it.
 *
 * <p>
 * The WAL module supplies an implementation; the encryption layer treats it as opaque and consults
 * it during rekey progress and KEK retirement.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R75c, R78e); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R75c
 * @spec encryption.primitives-lifecycle R78e
 */
public interface WalLivenessSource {

    /**
     * Return the count of unreplayed WAL segments for {@code tenantId} whose wrapping chain depends
     * on {@code kekRef}. Zero means the KekRef is no longer holding back WAL replay.
     *
     * @throws NullPointerException if any argument is null
     */
    long dependsOnKekRef(TenantId tenantId, KekRef kekRef);

    default void assertNonNullArgs(TenantId tenantId, KekRef kekRef) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kekRef, "kekRef");
    }
}
