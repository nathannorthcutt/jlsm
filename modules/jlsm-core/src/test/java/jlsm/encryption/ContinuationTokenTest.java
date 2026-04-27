package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import jlsm.encryption.ContinuationToken.ContinuationKind;

/**
 * Tests for {@link ContinuationToken} — opaque rekey continuation token carrying tenant, old/new
 * KEK refs, next-shard index, rekey epoch, and continuation kind.
 *
 * @spec encryption.primitives-lifecycle R78b
 * @spec encryption.primitives-lifecycle R78e
 */
class ContinuationTokenTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final KekRef OLD_REF = new KekRef("kek/v1");
    private static final KekRef NEW_REF = new KekRef("kek/v2");

    @Test
    void construct_validArgs_succeeds() {
        final ContinuationToken token = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 5, 1L,
                ContinuationKind.SHARD_BATCH);
        assertEquals(TENANT, token.tenantId());
        assertEquals(OLD_REF, token.oldKekRef());
        assertEquals(NEW_REF, token.newKekRef());
        assertEquals(5, token.nextShardIndex());
        assertEquals(1L, token.rekeyEpoch());
        assertEquals(ContinuationKind.SHARD_BATCH, token.kind());
    }

    @Test
    void construct_nullTenantId_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new ContinuationToken(null, OLD_REF, NEW_REF,
                0, 1L, ContinuationKind.SHARD_BATCH));
    }

    @Test
    void construct_nullOldKekRef_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new ContinuationToken(TENANT, null, NEW_REF,
                0, 1L, ContinuationKind.SHARD_BATCH));
    }

    @Test
    void construct_nullNewKekRef_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new ContinuationToken(TENANT, OLD_REF, null,
                0, 1L, ContinuationKind.SHARD_BATCH));
    }

    @Test
    void construct_nullKind_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> new ContinuationToken(TENANT, OLD_REF, NEW_REF, 0, 1L, null));
    }

    @Test
    void construct_negativeShardIndex_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ContinuationToken(TENANT, OLD_REF,
                NEW_REF, -1, 1L, ContinuationKind.SHARD_BATCH));
    }

    @Test
    void construct_negativeRekeyEpoch_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> new ContinuationToken(TENANT, OLD_REF,
                NEW_REF, 0, -1L, ContinuationKind.SHARD_BATCH));
    }

    @Test
    void construct_zeroValues_succeeds() {
        // R78b — fresh token with shardIndex=0 and rekeyEpoch=0 must be valid.
        final ContinuationToken token = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 0, 0L,
                ContinuationKind.SHARD_BATCH);
        assertNotNull(token);
        assertEquals(0, token.nextShardIndex());
        assertEquals(0L, token.rekeyEpoch());
    }

    @Test
    void awaitingLivenessWitnessKind_isValidContinuation() {
        // R78e — AWAITING_LIVENESS_WITNESS kind signals a paused rekey awaiting on-disk
        // witness drainage to zero before completion.
        final ContinuationToken token = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 100, 1L,
                ContinuationKind.AWAITING_LIVENESS_WITNESS);
        assertEquals(ContinuationKind.AWAITING_LIVENESS_WITNESS, token.kind());
    }

    @Test
    void equals_sameValues_isTrue() {
        final ContinuationToken a = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 5, 1L,
                ContinuationKind.SHARD_BATCH);
        final ContinuationToken b = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 5, 1L,
                ContinuationKind.SHARD_BATCH);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentEpoch_isFalse() {
        final ContinuationToken a = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 5, 1L,
                ContinuationKind.SHARD_BATCH);
        final ContinuationToken b = new ContinuationToken(TENANT, OLD_REF, NEW_REF, 5, 2L,
                ContinuationKind.SHARD_BATCH);
        assertNotEquals(a, b);
    }

    @Test
    void continuationKind_hasBothMembers() {
        // Defensive: the enum exposes the two cases the spec defines (R78b shard-batch,
        // R78e awaiting-witness) and nothing else.
        assertEquals(2, ContinuationKind.values().length);
        assertEquals(ContinuationKind.SHARD_BATCH, ContinuationKind.valueOf("SHARD_BATCH"));
        assertEquals(ContinuationKind.AWAITING_LIVENESS_WITNESS,
                ContinuationKind.valueOf("AWAITING_LIVENESS_WITNESS"));
    }
}
