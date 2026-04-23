package jlsm.encryption;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Persisted record of a wrapped DEK. Stored inside a tenant's key registry shard
 * (R19). The wrapped bytes are produced by AES-GCM wrapping the DEK under a specific
 * version of the tenant's domain KEK ({@code domainKekVersion}), which was itself
 * wrapped under the tenant's KMS KEK ({@code tenantKekRef}) at the time of the
 * outer wrap.
 *
 * <p>Defensive copies are made for {@code wrappedBytes} on both construction and
 * accessor to keep the on-disk record immutable from the perspective of any caller
 * holding a reference.
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R19.
 *
 * @param handle the scope this DEK is bound to
 * @param wrappedBytes opaque ciphertext (defensively copied)
 * @param domainKekVersion version of the wrapping domain KEK (positive)
 * @param tenantKekRef the KMS KEK version that wrapped the domain KEK at the time of
 *                     this DEK's wrap
 * @param createdAt wall-clock timestamp when this wrap was produced
 */
public record WrappedDek(
        DekHandle handle,
        byte[] wrappedBytes,
        int domainKekVersion,
        KekRef tenantKekRef,
        Instant createdAt) {

    /**
     * @throws NullPointerException if any reference is null
     * @throws IllegalArgumentException if {@code domainKekVersion} is not positive
     */
    public WrappedDek {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(tenantKekRef, "tenantKekRef must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (domainKekVersion <= 0) {
            throw new IllegalArgumentException(
                    "domainKekVersion must be positive, got " + domainKekVersion);
        }
        wrappedBytes = wrappedBytes.clone();
    }

    /**
     * Returns a fresh copy of the wrapped bytes. Callers must not assume two
     * invocations return the same array instance.
     */
    @Override
    public byte[] wrappedBytes() {
        return wrappedBytes.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WrappedDek other)) {
            return false;
        }
        return domainKekVersion == other.domainKekVersion
                && handle.equals(other.handle)
                && Arrays.equals(wrappedBytes, other.wrappedBytes)
                && tenantKekRef.equals(other.tenantKekRef)
                && createdAt.equals(other.createdAt);
    }

    @Override
    public int hashCode() {
        int result = handle.hashCode();
        result = 31 * result + Arrays.hashCode(wrappedBytes);
        result = 31 * result + Integer.hashCode(domainKekVersion);
        result = 31 * result + tenantKekRef.hashCode();
        result = 31 * result + createdAt.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "WrappedDek[handle="
                + handle
                + ", wrappedBytes=<"
                + wrappedBytes.length
                + " bytes>, domainKekVersion="
                + domainKekVersion
                + ", tenantKekRef="
                + tenantKekRef
                + ", createdAt="
                + createdAt
                + "]";
    }
}
