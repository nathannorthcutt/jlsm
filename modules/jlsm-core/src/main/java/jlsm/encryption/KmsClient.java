package jlsm.encryption;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Service Provider Interface (SPI) the encryption layer consumes to wrap and unwrap
 * tier-2 domain KEKs under tier-1 tenant KEKs. The default reference implementation
 * is {@code LocalKmsClient} in {@code jlsm.encryption.local}; production deployments
 * typically plug in an AWS KMS, GCP KMS, or Vault Transit adapter.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Classify failures into the {@link KmsException} hierarchy so callers can
 *       distinguish retryable from non-retryable errors (R76a).</li>
 *   <li>Treat the provided {@link EncryptionContext} as Additional Authenticated
 *       Data binding the wrap to its purpose and scope (R80, R80a).</li>
 *   <li>Return a non-null {@link WrapResult#kekRef} recording the concrete KEK
 *       version used (R80b), even if the input {@link KekRef} was an alias.</li>
 * </ul>
 *
 * <p>Governed by: spec {@code encryption.primitives-lifecycle} R80, R80a, R80b;
 * ADR {@code .decisions/kms-integration-model/adr.md}.
 */
public interface KmsClient extends AutoCloseable {

    /**
     * Wrap a plaintext KEK under the named KMS KEK.
     *
     * @param plaintextKek the tier-2 KEK plaintext to protect
     * @param kekRef the tier-1 KMS key to wrap under
     * @param context AAD binding this wrap to a {@link Purpose} and scope
     * @return the wrapped bytes and the concrete KEK version used
     * @throws KmsException on transient or permanent KMS failures
     */
    WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef, EncryptionContext context)
            throws KmsException;

    /**
     * Unwrap previously-wrapped bytes under the named KMS KEK.
     *
     * @param wrappedBytes the opaque wrapped bytes produced by a prior {@code wrapKek}
     * @param kekRef the tier-1 KMS key to unwrap under
     * @param context AAD that must match the wrap-time context
     * @return a segment holding the plaintext and an arena owning its lifetime
     * @throws KmsException on transient or permanent KMS failures
     */
    UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef, EncryptionContext context)
            throws KmsException;

    /**
     * Liveness/availability probe for a KMS KEK. Implementations SHOULD perform a
     * lightweight call (e.g., {@code DescribeKey} on AWS KMS) rather than a real
     * wrap/unwrap.
     *
     * @param kekRef the key to probe
     * @return {@code true} if the key is reachable and usable
     * @throws KmsException on transient or permanent KMS failures
     */
    boolean isUsable(KekRef kekRef) throws KmsException;

    /**
     * Release underlying resources. Implementations SHOULD be idempotent. Never
     * propagates a checked exception (matches {@link AutoCloseable} contract as
     * narrowed).
     */
    @Override
    void close();
}
