package jlsm.encryption.internal;

import java.time.Instant;
import java.util.Objects;

import jlsm.encryption.KekRef;

/**
 * Atomic shard marker recording that a rekey to {@code completedKekRef} completed. Written into the
 * {@link KeyRegistryShard} when the rekey loop finishes its terminal pass, alongside the registry
 * mutation that commits the new KekRef as active. R78f.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R78f).
 *
 * @spec encryption.primitives-lifecycle R78f
 *
 * @param completedKekRef the KekRef the rekey completed to
 * @param timestamp completion instant
 */
public record RekeyCompleteMarker(KekRef completedKekRef, Instant timestamp) {

    public RekeyCompleteMarker {
        Objects.requireNonNull(completedKekRef, "completedKekRef");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
