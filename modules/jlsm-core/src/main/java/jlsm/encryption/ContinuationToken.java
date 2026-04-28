package jlsm.encryption;

import java.util.Objects;

/**
 * Opaque rekey continuation token. Carries everything the next call to
 * {@link EncryptionKeyHolder#rekey} needs to resume a paused rekey loop: the old/new KEK
 * references, the next shard index to process, the rekey epoch, and the kind of continuation
 * (shard-batch advance vs. waiting for the on-disk liveness witness).
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R78b, R78e).
 *
 * @spec encryption.primitives-lifecycle R78b
 * @spec encryption.primitives-lifecycle R78e
 *
 * @param tenantId tenant under rekey
 * @param oldKekRef KekRef being retired
 * @param newKekRef KekRef being rotated in
 * @param nextShardIndex zero-based index of the next shard to process
 * @param rekeyEpoch monotonic rekey-epoch counter (used for stale-token detection)
 * @param kind continuation kind
 */
public record ContinuationToken(TenantId tenantId, KekRef oldKekRef, KekRef newKekRef,
        int nextShardIndex, long rekeyEpoch, ContinuationKind kind) {

    public ContinuationToken {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(oldKekRef, "oldKekRef");
        Objects.requireNonNull(newKekRef, "newKekRef");
        Objects.requireNonNull(kind, "kind");
        if (nextShardIndex < 0) {
            throw new IllegalArgumentException(
                    "nextShardIndex must be non-negative, got " + nextShardIndex);
        }
        if (rekeyEpoch < 0) {
            throw new IllegalArgumentException(
                    "rekeyEpoch must be non-negative, got " + rekeyEpoch);
        }
    }

    /** Continuation kind. */
    public enum ContinuationKind {
        /** Resume on the next shard batch. */
        SHARD_BATCH,
        /** Pause until the on-disk liveness witness reaches zero on {@code oldKekRef}. */
        AWAITING_LIVENESS_WITNESS;
    }
}
