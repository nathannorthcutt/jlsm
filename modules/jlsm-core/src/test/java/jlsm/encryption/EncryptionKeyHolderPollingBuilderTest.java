package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;

/**
 * Tests for the WU-8 {@link EncryptionKeyHolder.Builder} extensions
 * ({@link EncryptionKeyHolder.Builder#deployerInstanceId} and
 * {@link EncryptionKeyHolder.Builder#pollingEnabled}). These are the configuration knobs deployers
 * use to enable/disable the per-tenant polling loop and supply the deterministic-jitter secret
 * required by R79c-1.
 *
 * @spec encryption.primitives-lifecycle R79c-1
 * @spec encryption.primitives-lifecycle R79d
 */
class EncryptionKeyHolderPollingBuilderTest {

    private static byte[] secret() {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) (i + 1);
        }
        return s;
    }

    private static EncryptionKeyHolder.Builder baseBuilder(@TempDir Path tmp) throws IOException {
        return EncryptionKeyHolder.builder().kmsClient(new NoopKmsClient())
                .registry(new TenantShardRegistry(new ShardStorage(tmp)))
                .activeTenantKekRef(new KekRef("kek"));
    }

    @Test
    void deployerInstanceIdNullRejected(@TempDir Path tmp) throws IOException {
        final EncryptionKeyHolder.Builder b = baseBuilder(tmp);
        assertThrows(NullPointerException.class, () -> b.deployerInstanceId(null));
    }

    @Test
    void deployerInstanceIdAccepted(@TempDir Path tmp) throws IOException {
        final EncryptionKeyHolder.Builder b = baseBuilder(tmp);
        final EncryptionKeyHolder holder = b.deployerInstanceId(new DeployerInstanceId(secret()))
                .build();
        try {
            assertNotNull(holder);
        } finally {
            holder.close();
        }
    }

    @Test
    void pollingEnabledTrueAccepted(@TempDir Path tmp) throws IOException {
        final EncryptionKeyHolder holder = baseBuilder(tmp)
                .deployerInstanceId(new DeployerInstanceId(secret())).pollingEnabled(true).build();
        try {
            assertNotNull(holder);
        } finally {
            holder.close();
        }
    }

    @Test
    void pollingEnabledFalseAccepted(@TempDir Path tmp) throws IOException {
        // R79d allows opt-out — must compile and build; the runtime emits a WARN log to
        // jlsm.encryption.config (covered indirectly by R79d narrative; no test of log emission
        // here because the spec does not pin the exact log format).
        final EncryptionKeyHolder holder = baseBuilder(tmp).pollingEnabled(false).build();
        try {
            assertNotNull(holder);
        } finally {
            holder.close();
        }
    }

    @Test
    void buildWithoutDeployerInstanceIdSucceedsWhenPollingDisabled(@TempDir Path tmp)
            throws IOException {
        // Backward-compatibility: pre-WD-03 callers did not set deployerInstanceId. Build must
        // still succeed when polling is explicitly disabled — only enabling polling without an
        // instance id should fail (the runtime needs the secret to compute jitter).
        final EncryptionKeyHolder holder = baseBuilder(tmp).pollingEnabled(false).build();
        try {
            assertNotNull(holder);
        } finally {
            holder.close();
        }
    }

    /** Minimal no-op KmsClient stub for builder tests that never exercise wrap/unwrap/probe. */
    private static final class NoopKmsClient implements KmsClient {

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) {
            throw new UnsupportedOperationException("not used in builder tests");
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) {
            throw new UnsupportedOperationException("not used in builder tests");
        }

        @Override
        public boolean isUsable(KekRef kekRef) {
            return true;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
