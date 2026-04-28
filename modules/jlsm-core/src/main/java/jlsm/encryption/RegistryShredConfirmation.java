package jlsm.encryption;

import java.time.Instant;
import java.util.Objects;

/**
 * Single-use confirmation token that authorises an operator-initiated registry shred for a
 * tenant/domain. Carries a 16-byte nonce that is consumed exactly once; replay produces a
 * {@link RegistryStateException} with code {@code JLSM-ENC-83B2-TOKEN-REPLAY}.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R83b-2 token-replay enforcement).
 *
 * @spec encryption.primitives-lifecycle R83b-2
 *
 * @param tenantId target tenant
 * @param domainId target domain
 * @param generatedAt instant the token was minted
 * @param nonce16 16 random bytes; defensively copied
 */
public record RegistryShredConfirmation(TenantId tenantId, DomainId domainId, Instant generatedAt,
        byte[] nonce16) {

    /** Required nonce length in bytes. */
    public static final int NONCE_BYTES = 16;

    public RegistryShredConfirmation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(domainId, "domainId");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(nonce16, "nonce16");
        if (nonce16.length != NONCE_BYTES) {
            throw new IllegalArgumentException(
                    "nonce16 must be " + NONCE_BYTES + " bytes, got " + nonce16.length);
        }
        nonce16 = nonce16.clone();
    }

    /** Defensive copy on accessor read. */
    @Override
    public byte[] nonce16() {
        return nonce16.clone();
    }
}
