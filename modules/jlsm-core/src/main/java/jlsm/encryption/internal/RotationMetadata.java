package jlsm.encryption.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jlsm.encryption.TableScope;

/**
 * Pinned rotation metadata. Captures the rotation parameters at start time so that dynamic config
 * changes during the rotation cannot reclassify in-flight rotations (R37b-1 P4-4).
 *
 * <p>
 * Pinned in the shard at rotation start; consulted by the convergence machinery and the rekey loop.
 * Stored alongside the {@code KeyRegistryShard} mutation that begins rotation.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R37a, R37b-1); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R37a
 * @spec encryption.primitives-lifecycle R37b-1
 *
 * @param scope the table scope under rotation
 * @param oldDekVersion the DEK version superseded by this rotation
 * @param startedAt timestamp at which the rotation was initiated
 * @param r37aBoundAtStart the R37a convergence bound captured at rotation start (does not change if
 *            config is updated mid-rotation)
 */
public record RotationMetadata(TableScope scope, int oldDekVersion, Instant startedAt,
        Duration r37aBoundAtStart) {

    public RotationMetadata {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(r37aBoundAtStart, "r37aBoundAtStart");
        if (oldDekVersion <= 0) {
            throw new IllegalArgumentException(
                    "oldDekVersion must be positive, got " + oldDekVersion);
        }
        if (r37aBoundAtStart.isZero() || r37aBoundAtStart.isNegative()) {
            throw new IllegalArgumentException(
                    "r37aBoundAtStart must be positive, got " + r37aBoundAtStart);
        }
    }
}
