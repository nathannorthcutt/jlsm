package jlsm.encryption.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        Map<DomainId, WrappedDomainKek> domainKeks, KekRef activeTenantKekRef, byte[] hkdfSalt,
        RetiredReferences retiredReferences, Optional<RekeyCompleteMarker> rekeyCompleteMarker,
        PermanentlyRevokedDeksSet permanentlyRevokedDeks) {

    /**
     * @throws NullPointerException if any non-nullable reference is null
     */
    public KeyRegistryShard {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(deks, "deks must not be null");
        Objects.requireNonNull(domainKeks, "domainKeks must not be null");
        Objects.requireNonNull(hkdfSalt, "hkdfSalt must not be null");
        Objects.requireNonNull(retiredReferences, "retiredReferences must not be null");
        Objects.requireNonNull(rekeyCompleteMarker, "rekeyCompleteMarker must not be null");
        Objects.requireNonNull(permanentlyRevokedDeks, "permanentlyRevokedDeks must not be null");
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
     * Backward-compatible 5-arg constructor for callers that don't carry WD-03 v2 extension state.
     * The new components default to empty / absent. Used by initial-load synthesis and v1 shard
     * deserialization.
     */
    public KeyRegistryShard(TenantId tenantId, Map<DekHandle, WrappedDek> deks,
            Map<DomainId, WrappedDomainKek> domainKeks, KekRef activeTenantKekRef,
            byte[] hkdfSalt) {
        this(tenantId, deks, domainKeks, activeTenantKekRef, hkdfSalt, RetiredReferences.empty(),
                Optional.empty(), PermanentlyRevokedDeksSet.empty());
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
        return new KeyRegistryShard(tenantId, next, domainKeks, activeTenantKekRef, hkdfSalt,
                retiredReferences, rekeyCompleteMarker, permanentlyRevokedDeks);
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
        return new KeyRegistryShard(tenantId, deks, next, activeTenantKekRef, hkdfSalt,
                retiredReferences, rekeyCompleteMarker, permanentlyRevokedDeks);
    }

    /**
     * Produce a new shard with the tenant's active KEK reference replaced.
     *
     * @throws NullPointerException if {@code newRef} is null
     */
    public KeyRegistryShard withTenantKekRef(KekRef newRef) {
        Objects.requireNonNull(newRef, "newRef must not be null");
        return new KeyRegistryShard(tenantId, deks, domainKeks, newRef, hkdfSalt, retiredReferences,
                rekeyCompleteMarker, permanentlyRevokedDeks);
    }

    // --- WD-03 stubs ----------------------------------------------------
    // The shard is extended with four new logical fields delivered by WD-03:
    // - retired : RetiredReferences
    // - rekeyComplete : Optional<RekeyCompleteMarker>
    // - permanentlyRevoked : PermanentlyRevokedDeksSet
    // - dualRefDeks : Map<DekHandle, DualWrappedDek> // during in-flight rekey
    // The canonical record components are NOT yet bumped — adding components is a breaking
    // change to existing serialized form and to every KeyRegistryShard constructor site
    // (TenantShardRegistry, ShardStorage, EncryptionKeyHolder). The implementation pipeline
    // bumps the record signature and ShardStorage FORMAT_VERSION (1 → 2) atomically.

    /**
     * Produce a new shard with {@code retired} replacing the prior retired-references container.
     *
     * @spec encryption.primitives-lifecycle R33
     */
    public KeyRegistryShard withRetiredReferences(RetiredReferences retired) {
        Objects.requireNonNull(retired, "retired must not be null");
        return new KeyRegistryShard(tenantId, deks, domainKeks, activeTenantKekRef, hkdfSalt,
                retired, rekeyCompleteMarker, permanentlyRevokedDeks);
    }

    /**
     * Produce a new shard with {@code marker} replacing the prior rekey-complete marker (or
     * installing one for the first time).
     *
     * @spec encryption.primitives-lifecycle R78f
     */
    public KeyRegistryShard withRekeyCompleteMarker(RekeyCompleteMarker marker) {
        Objects.requireNonNull(marker, "marker must not be null");
        return new KeyRegistryShard(tenantId, deks, domainKeks, activeTenantKekRef, hkdfSalt,
                retiredReferences, Optional.of(marker), permanentlyRevokedDeks);
    }

    /**
     * Produce a new shard with {@code revoked} replacing the prior permanently-revoked DEKs set.
     *
     * @spec encryption.primitives-lifecycle R83g
     */
    public KeyRegistryShard withPermanentlyRevokedDeks(PermanentlyRevokedDeksSet revoked) {
        Objects.requireNonNull(revoked, "revoked must not be null");
        return new KeyRegistryShard(tenantId, deks, domainKeks, activeTenantKekRef, hkdfSalt,
                retiredReferences, rekeyCompleteMarker, revoked);
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
                && Arrays.equals(hkdfSalt, other.hkdfSalt)
                && retiredReferences.equals(other.retiredReferences)
                && rekeyCompleteMarker.equals(other.rekeyCompleteMarker)
                && permanentlyRevokedDeks.equals(other.permanentlyRevokedDeks);
    }

    @Override
    public int hashCode() {
        int result = tenantId.hashCode();
        result = 31 * result + deks.hashCode();
        result = 31 * result + domainKeks.hashCode();
        result = 31 * result + Objects.hashCode(activeTenantKekRef);
        result = 31 * result + Arrays.hashCode(hkdfSalt);
        result = 31 * result + retiredReferences.hashCode();
        result = 31 * result + rekeyCompleteMarker.hashCode();
        result = 31 * result + permanentlyRevokedDeks.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "KeyRegistryShard[tenantId=" + tenantId + ", deks=<" + deks.size()
                + " entries>, domainKeks=<" + domainKeks.size() + " entries>, activeTenantKekRef="
                + activeTenantKekRef + ", hkdfSalt=<" + hkdfSalt.length + " bytes>, retired=<"
                + retiredReferences.entries().size() + " entries>, rekeyComplete="
                + rekeyCompleteMarker.isPresent() + ", revokedDeks=<"
                + permanentlyRevokedDeks.handles().size() + " entries>]";
    }
}
