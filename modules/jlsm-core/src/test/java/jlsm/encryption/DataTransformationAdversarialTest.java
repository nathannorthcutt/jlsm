package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.KeyRegistryShard;
import jlsm.encryption.internal.ShardStorage;

/**
 * Adversarial tests targeting data-transformation fidelity in the encryption facade.
 *
 * <p>
 * Each test exercises a specific finding from the data_transformation lens: byte encoding/decoding
 * correctness, lossy Instant ↔ epoch-millis conversions, and canonicalization hazards.
 */
class DataTransformationAdversarialTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("local-master");

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static byte[] dummyWrappedBytes() {
        // Minimum valid wrapped payload: IV (12B) + tag (16B) = 28 bytes. Content doesn't
        // matter here — we are testing persistence fidelity, not decryption.
        final byte[] b = new byte[28];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    private static byte[] dummySalt() {
        final byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (0xA0 + i);
        }
        return b;
    }

    // ── F-R1.data_transformation.1.01 ────────────────────────────────────────────

    // Finding: F-R1.data_transformation.1.01
    // Bug: ShardStorage.serialize writes WrappedDek.createdAt via Instant.toEpochMilli(),
    // which truncates sub-millisecond nanoseconds. deserialize reconstructs via
    // Instant.ofEpochMilli, producing an Instant whose nanos are rounded down to the
    // nearest millisecond boundary. The reloaded WrappedDek is record-unequal to the
    // original even though it represents the same persisted state.
    // Correct behavior: createdAt must round-trip with sub-millisecond fidelity. The
    // persisted representation must preserve enough information to reconstruct the
    // original Instant exactly (e.g., epoch-seconds + nanos-of-second, or epoch-nanos).
    // Fix location: ShardStorage.serialize (writes createdAt) and ShardStorage.deserialize
    // (reads createdAt). Both sides must agree on a lossless encoding and estimateSize
    // must budget the correct number of bytes per entry.
    // Regression watch: old files with 8-byte millisecond encoding must either be rejected
    // with a clear error, or the format must be versioned so the new layout is only
    // applied at the current FORMAT_VERSION. estimateSize must stay in sync with the
    // on-wire layout or serialize() will under-allocate and throw BufferOverflowException.
    @Test
    void test_shardStorage_wrappedDekCreatedAt_preservesNanosecondPrecisionAcrossRoundTrip(
            @TempDir Path tempDir) throws IOException {
        // A timestamp with sub-millisecond nanoseconds — the common case on JDK 9+ where
        // Clock.systemUTC() returns microsecond precision. toEpochMilli() would truncate
        // the trailing 789_123 nanos (789 microseconds + 123 nanoseconds) down to zero.
        final Instant originalCreatedAt = Instant.ofEpochSecond(1_700_000_000L, 123_789_123L);
        // Sanity: the last 6 digits of the nanos field are non-zero, so any millisecond-
        // truncating persistence path will observably lose information.
        assert originalCreatedAt.getNano() % 1_000_000 != 0;

        final DekHandle handle = new DekHandle(TENANT, DOMAIN, TABLE, DekVersion.FIRST);
        final WrappedDek original = new WrappedDek(handle, dummyWrappedBytes(), 1, KEK_REF,
                originalCreatedAt);
        final KeyRegistryShard shard = new KeyRegistryShard(TENANT, Map.of(handle, original),
                Map.of(), KEK_REF, dummySalt());

        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        storage.writeShard(TENANT, shard);

        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        final WrappedDek reloaded = loaded.orElseThrow().deks().get(handle);

        // Per R19 transformation fidelity: the persisted record must round-trip exactly.
        // WrappedDek.equals uses Instant.equals which compares nanos — so millisecond
        // truncation is directly observable.
        assertEquals(originalCreatedAt, reloaded.createdAt(),
                "WrappedDek.createdAt must round-trip with nanosecond precision — "
                        + "Instant.toEpochMilli()/ofEpochMilli() truncates to millisecond "
                        + "boundaries, breaking record-equality across save/load");
        assertEquals(original, reloaded,
                "WrappedDek record-equality must be preserved across a serialize/deserialize "
                        + "round-trip (lossy Instant persistence is a data_transformation defect)");
    }

    // ── F-R1.data_transformation.1.04 ────────────────────────────────────────────

    // Finding: F-R1.data_transformation.1.04
    // Bug: ShardStorage.readLpString uses `new String(bytes, StandardCharsets.UTF_8)`, which
    // silently substitutes U+FFFD for malformed UTF-8 input. A CRC-valid shard whose
    // tenantId/domainId/tableId/KekRef bytes contain invalid UTF-8 (e.g., a lone 0x80
    // continuation byte) is accepted without error; the resulting identifier is silently
    // corrupted, orphaning DEKs and exposing identity ambiguity across tenants.
    // Correct behavior: deserialize must reject malformed UTF-8 with an IOException. Use a
    // strict CharsetDecoder (CodingErrorAction.REPORT on both malformedInput and
    // unmappableCharacter) so that invalid byte sequences surface as explicit failures.
    // Fix location: ShardStorage.readLpString (and the inline duplicate near line 643 that
    // decodes the activeTenantKekRef bytes) — both must route through a strict decoder.
    // Regression watch: well-formed UTF-8 (ASCII, multi-byte BMP, supplementary-plane
    // code points) must continue to round-trip unchanged — the strict decoder must only
    // reject genuinely malformed sequences.
    @Test
    void test_shardStorage_readLpString_rejectsMalformedUtf8InTenantId(@TempDir Path tempDir)
            throws IOException {
        // Write a valid shard, then patch its on-disk bytes to inject an invalid UTF-8
        // byte sequence into the tenantId field and recompute the CRC. The resulting file
        // is CRC-valid but semantically corrupt — a strict decoder is the only defense.
        final TenantId tenant = new TenantId("AA"); // 2-byte ASCII tenantId
        final KeyRegistryShard shard = new KeyRegistryShard(tenant, Map.of(), Map.of(), null,
                dummySalt());

        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        storage.writeShard(tenant, shard);

        // Locate the shard file by scanning the shards/<base32-tenant>/shard.bin directory.
        final Path shardsRoot = registryRoot.resolve("shards");
        Path shardFile = null;
        try (var tenants = Files.newDirectoryStream(shardsRoot)) {
            for (Path t : tenants) {
                final Path candidate = t.resolve("shard.bin");
                if (Files.isRegularFile(candidate)) {
                    shardFile = candidate;
                    break;
                }
            }
        }
        assert shardFile != null : "shard file must exist after writeShard";

        final byte[] bytes = Files.readAllBytes(shardFile);
        // Layout: [MAGIC 4B "KRSH"][version 2B][tenantId 4B len + 2 bytes "AA"] ...
        // tenantId payload begins at offset 4 + 2 + 4 = 10. Overwrite the first tenantId
        // byte with 0x80 — a lone continuation byte that is invalid UTF-8.
        final int tenantIdPayloadOffset = 4 + 2 + 4;
        bytes[tenantIdPayloadOffset] = (byte) 0x80;

        // Recompute the CRC-32C over the payload (all bytes except the trailing 4-byte CRC)
        // so the corruption passes R19a integrity checks and reaches readLpString.
        final int payloadLen = bytes.length - 4;
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, payloadLen);
        ByteBuffer.wrap(bytes, payloadLen, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) crc.getValue());

        Files.write(shardFile, bytes);

        // Correct behavior: strict UTF-8 decoding throws, loadShard surfaces IOException.
        // Buggy behavior: `new String(bytes, UTF_8)` silently replaces 0x80 with U+FFFD and
        // returns a corrupted TenantId wrapped in an Optional, with no error reported.
        assertThrows(IOException.class, () -> storage.loadShard(tenant),
                "Malformed UTF-8 in a length-prefixed string region must be rejected with "
                        + "IOException rather than silently replaced with U+FFFD");
    }

    // Finding: F-R1.data_transformation.1.04 (regression guard)
    // Bug: A strict decoder must not reject well-formed multi-byte UTF-8 sequences.
    // Correct behavior: Tenant/domain/table identifiers containing non-ASCII UTF-8
    // (BMP multi-byte, supplementary plane) must round-trip unchanged.
    // Fix location: ShardStorage.readLpString — confirm the fix decodes valid UTF-8.
    // Regression watch: an over-eager strict decoder that rejects any non-ASCII input would
    // break legitimate internationalized identifiers.
    @Test
    void test_shardStorage_readLpString_acceptsWellFormedMultiByteUtf8(@TempDir Path tempDir)
            throws IOException {
        // "💡" is U+1F4A1 (LIGHT BULB), a supplementary-plane code point that
        // encodes as 4 UTF-8 bytes (F0 9F 92 A1). Including it in a tenantId ensures the
        // strict decoder correctly handles non-ASCII multi-byte sequences.
        final TenantId tenant = new TenantId("tenant-é-💡");
        final KeyRegistryShard shard = new KeyRegistryShard(tenant, Map.of(), Map.of(), null,
                dummySalt());

        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        storage.writeShard(tenant, shard);

        final Optional<KeyRegistryShard> loaded = storage.loadShard(tenant);
        assertEquals(tenant, loaded.orElseThrow().tenantId(),
                "well-formed multi-byte UTF-8 tenantId must round-trip unchanged");
    }
}
