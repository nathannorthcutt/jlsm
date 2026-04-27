package jlsm.encryption;

import java.util.Objects;

/**
 * Per-deployer-instance secret used to derive jitter offsets in
 * {@link jlsm.encryption.internal.PollingScheduler}. Stored in a deployer-managed secret context
 * co-located with the R83i-2 {@link KekRefDescriptor} secret. Must carry ≥256 bits (32 bytes) of
 * entropy per R79c-1 P4-12; raw UUIDs (16 bytes) are explicitly rejected.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R79c-1).
 *
 * @spec encryption.primitives-lifecycle R79c-1
 *
 * @param secret deployer-instance secret bytes; defensively copied; must be ≥32 bytes
 */
public record DeployerInstanceId(byte[] secret) {

    /** Minimum entropy in bytes per R79c-1 P4-12. */
    public static final int MIN_SECRET_BYTES = 32;

    public DeployerInstanceId {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("secret must be at least " + MIN_SECRET_BYTES
                    + " bytes (R79c-1 P4-12 — raw UUIDs are rejected); got " + secret.length);
        }
        secret = secret.clone();
    }

    /** Defensive copy on accessor read. */
    @Override
    public byte[] secret() {
        return secret.clone();
    }
}
