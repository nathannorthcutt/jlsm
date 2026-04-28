package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Tests for the WD-03 v1 → v2 wire-format extension in {@link ShardStorage}. The v2 format adds:
 * <ul>
 * <li>retired-refs section</li>
 * <li>rekey-complete marker (optional)</li>
 * <li>permanently-revoked DEKs set</li>
 * </ul>
 * Existing v1 shards must continue to load (synthesizing empty new sections).
 *
 * @spec encryption.primitives-lifecycle R33
 * @spec encryption.primitives-lifecycle R78d
 * @spec encryption.primitives-lifecycle R78f
 * @spec encryption.primitives-lifecycle R83g
 */
class ShardStorageV2WireFormatTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef REF = new KekRef("kek/v1");

    private static byte[] dummyWrap() {
        final byte[] b = new byte[28];
        b[0] = 0x42;
        return b;
    }

    private static KeyRegistryShard buildShard() {
        final DekHandle handle = new DekHandle(TENANT, DOMAIN, TABLE, new DekVersion(1));
        final WrappedDek dek = new WrappedDek(handle, dummyWrap(), 1, REF, Instant.EPOCH);
        final WrappedDomainKek dkek = new WrappedDomainKek(DOMAIN, 1, dummyWrap(), REF);
        return new KeyRegistryShard(TENANT, Map.of(handle, dek), Map.of(DOMAIN, dkek), REF,
                new byte[32]);
    }

    @Test
    void roundTrip_emptyExtensions_writesAndReads(@TempDir Path tempDir) throws IOException {
        // The v2 writer must round-trip a shard that has no retired-refs and no rekey-complete
        // marker. The new sections serialize as empty placeholders.
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final KeyRegistryShard shard = buildShard();
        storage.writeShard(TENANT, shard);
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent());
        assertEquals(shard, loaded.get());
    }

    @Test
    void writtenShard_carriesV2FormatVersion(@TempDir Path tempDir) throws IOException {
        // After WD-03 lands, fresh writes must use the bumped format version (2). Inspect the raw
        // bytes: bytes [4..6] are the 2-byte big-endian version field after the 4-byte MAGIC.
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        storage.writeShard(TENANT, buildShard());
        final Path shardFile = ShardPathResolver.shardPath(tempDir.resolve("registry"), TENANT);
        assertTrue(Files.exists(shardFile));
        final byte[] raw = Files.readAllBytes(shardFile);
        assertNotNull(raw);
        assertTrue(raw.length > 6);
        // MAGIC = 'K','R','S','H'
        assertEquals('K', (char) raw[0]);
        assertEquals('R', (char) raw[1]);
        assertEquals('S', (char) raw[2]);
        assertEquals('H', (char) raw[3]);
        final short version = ByteBuffer.wrap(raw, 4, 2).order(ByteOrder.BIG_ENDIAN).getShort();
        assertEquals((short) 2, version, "fresh writes must use FORMAT_VERSION = 2 after WD-03");
    }

    @Test
    void v1Shard_loadsTransparently(@TempDir Path tempDir) throws IOException {
        // Backward-read: a previously-written v1 shard must load and produce a shard whose
        // new-section accessors return empty defaults. We synthesize a v1 byte stream by hand
        // because the writer no longer emits v1 after WD-03.
        final Path shardFile = ShardPathResolver.shardPath(tempDir.resolve("registry"), TENANT);
        Files.createDirectories(shardFile.getParent());
        final byte[] v1Bytes = synthesizeV1Bytes(buildShard());
        Files.write(shardFile, v1Bytes);

        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent(), "v1 shard must load via v1 → v2 migration path");
        // Logical contents preserved. New sections synthesize empty.
        final KeyRegistryShard rs = loaded.get();
        assertEquals(TENANT, rs.tenantId());
        assertEquals(REF, rs.activeTenantKekRef());
        assertEquals(1, rs.deks().size());
        assertEquals(1, rs.domainKeks().size());
    }

    @Test
    void v1Shard_thenWrite_promotesToV2(@TempDir Path tempDir) throws IOException {
        // After loading a v1 shard and re-writing, the on-disk file is v2.
        final Path shardFile = ShardPathResolver.shardPath(tempDir.resolve("registry"), TENANT);
        Files.createDirectories(shardFile.getParent());
        Files.write(shardFile, synthesizeV1Bytes(buildShard()));

        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final KeyRegistryShard loaded = storage.loadShard(TENANT).orElseThrow();
        storage.writeShard(TENANT, loaded);

        final byte[] raw = Files.readAllBytes(shardFile);
        final short version = ByteBuffer.wrap(raw, 4, 2).order(ByteOrder.BIG_ENDIAN).getShort();
        assertEquals((short) 2, version, "rewrite must promote to v2");
    }

    @Test
    void unsupportedVersion_throws(@TempDir Path tempDir) throws IOException {
        // A future-version shard (v3) must be rejected with IOException.
        final Path shardFile = ShardPathResolver.shardPath(tempDir.resolve("registry"), TENANT);
        Files.createDirectories(shardFile.getParent());
        final byte[] v1Bytes = synthesizeV1Bytes(buildShard());
        // Tamper version field at bytes [4..6] to v3.
        v1Bytes[4] = 0;
        v1Bytes[5] = 3;
        // Recompute CRC32C trailer over the mutated payload.
        final int payloadLen = v1Bytes.length - 4;
        final CRC32C crc = new CRC32C();
        crc.update(v1Bytes, 0, payloadLen);
        ByteBuffer.wrap(v1Bytes, payloadLen, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) crc.getValue());
        Files.write(shardFile, v1Bytes);

        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final IOException ex = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> storage.loadShard(TENANT));
        assertTrue(ex.getMessage().contains("unsupported"),
                "v3 must be rejected as unsupported, got: " + ex.getMessage());
    }

    @Test
    void roundTrip_withRetiredReferences_persists(@TempDir Path tempDir) throws IOException {
        // A shard carrying retired refs must serialize and deserialize them losslessly under v2.
        final RetiredReferences retired = RetiredReferences.empty()
                .markRetired(new KekRef("kek/v0-old"), Instant.parse("2026-05-01T00:00:00Z"));
        final KeyRegistryShard withRetired = buildShard().withRetiredReferences(retired);
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        storage.writeShard(TENANT, withRetired);

        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent());
        assertEquals(retired, loaded.get().retiredReferences());
    }

    @Test
    void newShard_hasEmptyExtensionsByDefault(@TempDir Path tempDir) throws IOException {
        // R20a: a fresh shard with no rotation history serializes empty defaults for the v2
        // extension fields and reads them back as such.
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        storage.writeShard(TENANT, buildShard());
        final KeyRegistryShard loaded = storage.loadShard(TENANT).orElseThrow();
        assertEquals(RetiredReferences.empty(), loaded.retiredReferences());
        assertFalse(loaded.rekeyCompleteMarker().isPresent());
        assertTrue(loaded.permanentlyRevokedDeks().handles().isEmpty());
    }

    /**
     * Synthesize the on-wire v1 byte stream for {@code shard}. Mirrors the pre-WD-03 ShardStorage
     * serializer exactly so we can prove the v1→v2 migration path without depending on a relic v1
     * writer.
     */
    private static byte[] synthesizeV1Bytes(KeyRegistryShard shard) {
        // Rough upper bound — small fixture.
        final ByteBuffer buf = ByteBuffer.allocate(8 * 1024).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'K').put((byte) 'R').put((byte) 'S').put((byte) 'H');
        buf.putShort((short) 1);
        putLpString(buf, shard.tenantId().value());
        putLpBytes(buf, shard.hkdfSalt());
        if (shard.activeTenantKekRef() == null) {
            buf.putInt(-1);
        } else {
            putLpString(buf, shard.activeTenantKekRef().value());
        }
        // domain KEKs in lexicographic order
        final java.util.List<Map.Entry<DomainId, WrappedDomainKek>> dKeks = new java.util.ArrayList<>(
                shard.domainKeks().entrySet());
        dKeks.sort(java.util.Comparator.comparing(e -> e.getKey().value()));
        buf.putInt(dKeks.size());
        for (var e : dKeks) {
            final WrappedDomainKek dk = e.getValue();
            putLpString(buf, dk.domainId().value());
            buf.putInt(dk.version());
            putLpBytes(buf, dk.wrappedBytes());
            putLpString(buf, dk.tenantKekRef().value());
        }
        // DEKs
        final java.util.List<Map.Entry<DekHandle, WrappedDek>> deks = new java.util.ArrayList<>(
                shard.deks().entrySet());
        deks.sort(java.util.Comparator.<Map.Entry<DekHandle, WrappedDek>, String>comparing(
                e -> e.getKey().tenantId().value())
                .thenComparing(e -> e.getKey().domainId().value())
                .thenComparing(e -> e.getKey().tableId().value())
                .thenComparingInt(e -> e.getKey().version().value()));
        buf.putInt(deks.size());
        for (var e : deks) {
            final WrappedDek d = e.getValue();
            putLpString(buf, d.handle().tenantId().value());
            putLpString(buf, d.handle().domainId().value());
            putLpString(buf, d.handle().tableId().value());
            buf.putInt(d.handle().version().value());
            putLpBytes(buf, d.wrappedBytes());
            buf.putInt(d.domainKekVersion());
            putLpString(buf, d.tenantKekRef().value());
            buf.putLong(d.createdAt().getEpochSecond());
            buf.putInt(d.createdAt().getNano());
        }
        buf.flip();
        final byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        // Append CRC32C trailer
        final CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        final byte[] full = new byte[payload.length + 4];
        System.arraycopy(payload, 0, full, 0, payload.length);
        ByteBuffer.wrap(full, payload.length, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) crc.getValue());
        return full;
    }

    private static void putLpString(ByteBuffer buf, String s) {
        final byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.putInt(b.length);
        buf.put(b);
    }

    private static void putLpBytes(ByteBuffer buf, byte[] b) {
        buf.putInt(b.length);
        buf.put(b);
    }
}
