package jlsm.encryption;

import java.util.Arrays;
import java.util.Objects;

/**
 * Persisted record of a wrapped tier-2 domain KEK. Stored inside a tenant's key registry shard
 * alongside the DEKs that descend from it (R19, R17 tier-2). The wrapped bytes are produced by
 * AES-KWP wrapping the domain KEK plaintext under the tenant's KMS KEK ({@code tenantKekRef}).
 *
 * <p>
 * Defensive copies are made for {@code wrappedBytes} on both construction and accessor.
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R19, R17 (tier-2).
 *
 * @param domainId the tier-2 data domain this KEK belongs to
 * @param version monotonic domain-KEK version (positive)
 * @param wrappedBytes opaque ciphertext (defensively copied)
 * @param tenantKekRef the KMS KEK reference that produced the wrap
 */
public record WrappedDomainKek(DomainId domainId, int version, byte[] wrappedBytes,
        KekRef tenantKekRef) {

    /**
     * @throws NullPointerException if any reference is null
     * @throws IllegalArgumentException if {@code version} is not positive or {@code wrappedBytes}
     *             is zero-length (a wrapped ciphertext cannot be empty — AES-KWP minimum is 24
     *             bytes)
     */
    public WrappedDomainKek {
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(wrappedBytes, "wrappedBytes must not be null");
        Objects.requireNonNull(tenantKekRef, "tenantKekRef must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive, got " + version);
        }
        if (wrappedBytes.length == 0) {
            throw new IllegalArgumentException(
                    "wrappedBytes must not be empty — a wrapped ciphertext cannot be zero-length");
        }
        wrappedBytes = wrappedBytes.clone();
    }

    /**
     * Returns a fresh copy of the wrapped bytes.
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
        if (!(o instanceof WrappedDomainKek other)) {
            return false;
        }
        return version == other.version && domainId.equals(other.domainId)
                && Arrays.equals(wrappedBytes, other.wrappedBytes)
                && tenantKekRef.equals(other.tenantKekRef);
    }

    @Override
    public int hashCode() {
        int result = domainId.hashCode();
        result = 31 * result + Integer.hashCode(version);
        result = 31 * result + Arrays.hashCode(wrappedBytes);
        result = 31 * result + tenantKekRef.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "WrappedDomainKek[domainId=" + domainId + ", version=" + version + ", wrappedBytes=<"
                + wrappedBytes.length + " bytes>, tenantKekRef=" + tenantKekRef + "]";
    }
}
