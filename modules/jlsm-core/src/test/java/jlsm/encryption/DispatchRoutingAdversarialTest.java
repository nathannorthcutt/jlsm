package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.KeyRegistryShard;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Adversarial tests targeting dispatch-routing violations in the encryption facade.
 *
 * <p>
 * Each test exercises a specific finding from the dispatch_routing lens: missing routing-key
 * validation, purpose-closed-set bypasses, and other discriminant-integrity defects.
 */
class DispatchRoutingAdversarialTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("local-master");

    private static byte[] salt32() {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) (0x20 + i);
        }
        return s;
    }

    private static KeyRegistryShard emptyShard(TenantId tenantId) {
        return new KeyRegistryShard(tenantId, Map.of(), Map.of(), null, salt32());
    }

    private static Path writeMasterKey(Path dir) throws IOException {
        final Path keyFile = dir.resolve("master.key");
        final byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) (i + 1);
        }
        Files.write(keyFile, bytes);
        if (Files.getFileAttributeView(keyFile.getParent(), PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        return keyFile;
    }

    // Finding: F-R1.dispatch_routing.1.01
    // Bug: TenantShardRegistry.updateShard does not validate that the mutator-returned shard's
    // tenantId matches the external routing key tenantId. A mutator can return a shard
    // whose internal tenantId is TENANT_B while the registry routes and persists it under
    // TENANT_A, violating R82a per-tenant isolation.
    // Correct behavior: updateShard must reject (with IllegalStateException) a mutator whose
    // returned shard has a tenantId that differs from the routing key tenantId, before
    // the shard is persisted to disk or published to the volatile snapshot.
    // Fix location: TenantShardRegistry.updateShard (after mutator.apply, before writeShard)
    // Regression watch: must not break the normal same-tenant updateShard path; must fail fast
    // before the durable write so neither the on-disk file nor the cached snapshot is
    // corrupted by a wrong-tenant shard.
    @Test
    void test_tenantShardRegistry_updateShard_rejectsMutatorReturningWrongTenantShard(
            @TempDir Path tmp) throws IOException {
        try (TenantShardRegistry reg = new TenantShardRegistry(new ShardStorage(tmp))) {
            // Mutator returns a shard whose internal tenantId is TENANT_B even though the
            // external routing key is TENANT_A. This is the adversarial input from the finding.
            assertThrows(IllegalStateException.class,
                    () -> reg.updateShard(TENANT_A,
                            current -> new TenantShardRegistry.ShardUpdate<>(emptyShard(TENANT_B),
                                    "done")));
        }
    }

    // Finding: F-R1.dispatch_routing.1.02
    // Bug: LocalKmsClient.wrapKek/unwrapKek null-checks EncryptionContext but never inspects
    // context.purpose(). Per spec R80a, KmsClient wrap/unwrap is valid only for
    // Purpose.DOMAIN_KEK, Purpose.REKEY_SENTINEL, or Purpose.HEALTH_CHECK. A caller that
    // mistakenly passes a Purpose.DEK context (DEK wraps go through AES-GCM under the
    // domain KEK, not through KmsClient) has its call silently accepted, bypassing R80a's
    // closed-set routing discriminant at the KMS dispatch boundary.
    // Correct behavior: wrapKek and unwrapKek must reject (IllegalArgumentException) any
    // EncryptionContext whose purpose() is not one of the KmsClient-valid set
    // {DOMAIN_KEK, REKEY_SENTINEL, HEALTH_CHECK} — specifically, Purpose.DEK is invalid.
    // Fix location: LocalKmsClient.wrapKek (lines 118-139) and LocalKmsClient.unwrapKek
    // (lines 141-183) — add a purpose check after the null guards.
    // Regression watch: must continue to accept DOMAIN_KEK, REKEY_SENTINEL, and HEALTH_CHECK
    // purposes (all three are legitimate KmsClient operations per R80a).
    @Test
    void test_localKmsClient_wrapKek_rejectsDekPurpose(@TempDir Path tmp) throws IOException {
        final Path keyFile = writeMasterKey(tmp);
        try (LocalKmsClient client = new LocalKmsClient(keyFile);
                Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = arena.allocate(32);
            for (int i = 0; i < 32; i++) {
                plaintext.set(ValueLayout.JAVA_BYTE, i, (byte) (i + 100));
            }
            // Adversarial input per the finding: purpose=DEK is not a valid KmsClient
            // operation (DEKs are wrapped under the domain KEK via AES-GCM, not via KmsClient).
            final EncryptionContext dekCtx = EncryptionContext.forDek(TENANT_A, DOMAIN, TABLE,
                    new DekVersion(1));
            assertThrows(IllegalArgumentException.class,
                    () -> client.wrapKek(plaintext, KEK_REF, dekCtx));
        }
    }

    @Test
    void test_localKmsClient_unwrapKek_rejectsDekPurpose(@TempDir Path tmp) throws IOException {
        final Path keyFile = writeMasterKey(tmp);
        try (LocalKmsClient client = new LocalKmsClient(keyFile)) {
            // Any non-empty wrapped bytes are fine — the purpose check must fire before the
            // bytes are interpreted.
            final ByteBuffer wrappedBytes = ByteBuffer.allocate(40);
            final EncryptionContext dekCtx = EncryptionContext.forDek(TENANT_A, DOMAIN, TABLE,
                    new DekVersion(1));
            assertThrows(IllegalArgumentException.class,
                    () -> client.unwrapKek(wrappedBytes, KEK_REF, dekCtx));
        }
    }
}
