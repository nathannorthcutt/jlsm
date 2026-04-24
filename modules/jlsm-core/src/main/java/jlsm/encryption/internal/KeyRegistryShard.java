package jlsm.encryption.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Immutable per-tenant key registry shard. Holds all wrapped key material for a tenant (DEKs +
 * domain KEKs) along with the tenant's active KEK reference and the HKDF salt (R10b, which must
 * persist to avoid silent derivation mismatches on reload).
 *
 * <p>
 * The shard is value-immutable: mutations produce new {@code KeyRegistryShard} instances via
 * {@link #withDek}, {@link #withDomainKek}, and {@link #withTenantKekRef}.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R19, R10b.
 *
 * @param tenantId owning tenant
 * @param deks wrapped DEKs keyed by handle (defensively copied)
 * @param domainKeks wrapped tier-2 KEKs keyed by domain (defensively copied)
 * @param activeTenantKekRef currently active tier-1 KEK reference; nullable on first-write (no KEK
 *            activated yet)
 * @param hkdfSalt HKDF salt bytes (defensively copied; see accessor note)
 */
public record KeyRegistryShard(TenantId tenantId, Map<DekHandle, WrappedDek> deks,
        Map<DomainId, WrappedDomainKek> domainKeks, KekRef activeTenantKekRef, byte[] hkdfSalt) {

    /**
     * @throws NullPointerException if any non-nullable reference is null
     */
    public KeyRegistryShard {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(deks, "deks must not be null");
        Objects.requireNonNull(domainKeks, "domainKeks must not be null");
        Objects.requireNonNull(hkdfSalt, "hkdfSalt must not be null");
        // activeTenantKekRef may be null (first-write case)
        // Per-entry null checks ahead of Map.copyOf so operators see which field and
        // which key produced the null when a corrupt shard is rejected. Map.copyOf's
        // own NPE names neither — which defeats triage when the NPE is wrapped as a
        // "malformed shard file" IOException downstream.
        for (var entry : deks.entrySet()) {
            if (entry.getKey() == null) {
                throw new NullPointerException(
                        "deks contains a null key — corrupt shard or programmer error");
            }
            if (entry.getValue() == null) {
                throw new NullPointerException(
                        "deks contains a null value for key " + entry.getKey());
            }
        }
        for (var entry : domainKeks.entrySet()) {
            if (entry.getKey() == null) {
                throw new NullPointerException(
                        "domainKeks contains a null key — corrupt shard or programmer error");
            }
            if (entry.getValue() == null) {
                throw new NullPointerException(
                        "domainKeks contains a null value for key " + entry.getKey());
            }
        }
        deks = Map.copyOf(deks);
        domainKeks = Map.copyOf(domainKeks);
        hkdfSalt = hkdfSalt.clone();
    }

    /**
     * Returns a fresh clone of the HKDF salt. The component accessor defensively copies because the
     * salt is a persisted secret and unexpected mutation would silently corrupt derivations across
     * the entire tenant.
     */
    @Override
    public byte[] hkdfSalt() {
        return hkdfSalt.clone();
    }

    /**
     * Best-effort zeroization of the authoritative (internal) salt byte[] backing this shard.
     * Intended for shutdown / close paths where the shard is about to be dropped and R69 requires
     * the salt bytes to be scrubbed from memory before GC reclaims them.
     *
     * <p>
     * Package-private on purpose: external callers must not be able to corrupt a live shard. After
     * this call, any subsequent {@link #hkdfSalt()} invocation returns a clone of zero-filled bytes
     * and any HKDF derivation using this shard will silently produce wrong keys — so call only when
     * the shard is unreachable to functional consumers.
     */
    void zeroizeSalt() {
        Arrays.fill(hkdfSalt, (byte) 0);
    }

    /**
     * Produce a new shard with {@code newDek} added (or replacing any prior entry with the same
     * handle).
     *
     * @throws NullPointerException if {@code newDek} is null
     */
    public KeyRegistryShard withDek(WrappedDek newDek) {
        Objects.requireNonNull(newDek, "newDek must not be null");
        final java.util.Map<DekHandle, WrappedDek> next = new java.util.HashMap<>(deks);
        next.put(newDek.handle(), newDek);
        return new KeyRegistryShard(tenantId, next, domainKeks, activeTenantKekRef, hkdfSalt);
    }

    /**
     * Produce a new shard with {@code newDomainKek} added (or replacing the prior entry for the
     * same domain).
     *
     * @throws NullPointerException if {@code newDomainKek} is null
     */
    public KeyRegistryShard withDomainKek(WrappedDomainKek newDomainKek) {
        Objects.requireNonNull(newDomainKek, "newDomainKek must not be null");
        final java.util.Map<DomainId, WrappedDomainKek> next = new java.util.HashMap<>(domainKeks);
        next.put(newDomainKek.domainId(), newDomainKek);
        return new KeyRegistryShard(tenantId, deks, next, activeTenantKekRef, hkdfSalt);
    }

    /**
     * Produce a new shard with the tenant's active KEK reference replaced.
     *
     * @throws NullPointerException if {@code newRef} is null
     */
    public KeyRegistryShard withTenantKekRef(KekRef newRef) {
        Objects.requireNonNull(newRef, "newRef must not be null");
        return new KeyRegistryShard(tenantId, deks, domainKeks, newRef, hkdfSalt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KeyRegistryShard other)) {
            return false;
        }
        return tenantId.equals(other.tenantId) && deks.equals(other.deks)
                && domainKeks.equals(other.domainKeks)
                && Objects.equals(activeTenantKekRef, other.activeTenantKekRef)
                && Arrays.equals(hkdfSalt, other.hkdfSalt);
    }

    @Override
    public int hashCode() {
        int result = tenantId.hashCode();
        result = 31 * result + deks.hashCode();
        result = 31 * result + domainKeks.hashCode();
        result = 31 * result + Objects.hashCode(activeTenantKekRef);
        result = 31 * result + Arrays.hashCode(hkdfSalt);
        return result;
    }

    @Override
    public String toString() {
        return "KeyRegistryShard[tenantId=" + tenantId + ", deks=<" + deks.size()
                + " entries>, domainKeks=<" + domainKeks.size() + " entries>, activeTenantKekRef="
                + activeTenantKekRef + ", hkdfSalt=<" + hkdfSalt.length + " bytes>]";
    }
}
