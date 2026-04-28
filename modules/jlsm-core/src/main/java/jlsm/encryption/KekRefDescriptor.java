package jlsm.encryption;

import java.util.Objects;
import java.util.Optional;

/**
 * Privacy-preserving descriptor of a {@link KekRef}. Carries a 16-byte HMAC-SHA256 prefix of the
 * canonical KekRef (keyed by the deployer secret per R83i-1), the provider class name, and an
 * optional region tag. Used in observability payloads (R83h) and tenant-visible exception messages
 * so support engineers can correlate without ever seeing the raw KekRef value.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83h, R83i, R83i-1).
 *
 * @spec encryption.primitives-lifecycle R83h
 * @spec encryption.primitives-lifecycle R83i-1
 *
 * @param hashPrefix 16-byte HMAC-SHA256 prefix; defensively copied
 * @param providerClass non-null KMS-provider class name
 * @param regionTag optional region tag (e.g., {@code "us-east-1"})
 */
public record KekRefDescriptor(byte[] hashPrefix, String providerClass,
        Optional<String> regionTag) {

    /** Required hash-prefix length in bytes. */
    public static final int HASH_PREFIX_BYTES = 16;

    public KekRefDescriptor {
        Objects.requireNonNull(hashPrefix, "hashPrefix");
        Objects.requireNonNull(providerClass, "providerClass");
        Objects.requireNonNull(regionTag, "regionTag");
        if (hashPrefix.length != HASH_PREFIX_BYTES) {
            throw new IllegalArgumentException(
                    "hashPrefix must be " + HASH_PREFIX_BYTES + " bytes, got " + hashPrefix.length);
        }
        if (providerClass.isEmpty()) {
            throw new IllegalArgumentException("providerClass must not be empty");
        }
        hashPrefix = hashPrefix.clone();
    }

    /** Defensive copy on read so callers cannot mutate the descriptor's internal state. */
    @Override
    public byte[] hashPrefix() {
        return hashPrefix.clone();
    }
}
