/**
 * Reference implementation of {@link jlsm.encryption.KmsClient} backed by a local
 * filesystem-resident master key. Intended for tests and development only — not production (see
 * {@link jlsm.encryption.local.LocalKmsClient} class Javadoc).
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R71, R71b.
 */
package jlsm.encryption.local;
