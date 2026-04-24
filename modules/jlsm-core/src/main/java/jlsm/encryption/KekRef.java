package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque KMS key reference. The string form is provider-dependent (an AWS KMS ARN, a GCP KMS
 * resource name, a HashiCorp Vault path, a local filesystem UUID, etc.). The encryption layer
 * treats this as opaque and passes it through to the {@link KmsClient} SPI.
 *
 * <p>
 * Governed by: {@code .decisions/kms-integration-model/adr.md}; spec
 * {@code encryption.primitives-lifecycle} R78, R80.
 *
 * @param value non-null, non-empty KMS reference string
 */
public record KekRef(String value) {

    /**
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public KekRef {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("KekRef value must not be empty");
        }
    }
}
