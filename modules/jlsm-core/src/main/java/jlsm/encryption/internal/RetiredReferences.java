package jlsm.encryption.internal;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;

import jlsm.encryption.KekRef;

/**
 * Container of retired KEK references with retention-until timestamps. Stored in the
 * {@link KeyRegistryShard}. The R33a grace-period invariant is enforced via consultation with
 * {@link LivenessWitness}: a retired ref is GC-eligible only when both retention has elapsed and
 * its liveness count has reached zero.
 *
 * <p>
 * <b>Governed by:</b> spec encryption.primitives-lifecycle (R33, R33a); ADR
 * encryption-three-tier-key-hierarchy.
 *
 * @spec encryption.primitives-lifecycle R33
 * @spec encryption.primitives-lifecycle R33a
 *
 * @param entries immutable map of retired KekRef → retention-until instant (defensively copied)
 */
public record RetiredReferences(Map<KekRef, Instant> entries) {

    /** Empty retired-refs container. */
    public static RetiredReferences empty() {
        return new RetiredReferences(Map.of());
    }

    public RetiredReferences {
        Objects.requireNonNull(entries, "entries");
        for (var e : entries.entrySet()) {
            Objects.requireNonNull(e.getKey(), "retired ref key must not be null");
            Objects.requireNonNull(e.getValue(), "retention-until value must not be null");
        }
        entries = Map.copyOf(entries);
    }

    /**
     * Mark {@code ref} as retired with retention-until {@code retentionUntil}. Returns a new
     * container; this record is immutable. If {@code ref} is already retired, the prior
     * retention-until is overwritten.
     */
    public RetiredReferences markRetired(KekRef ref, Instant retentionUntil) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(retentionUntil, "retentionUntil");
        // Build a fresh map: copy existing entries (excluding ref if present), put the new
        // (ref, retentionUntil) pair, freeze via Map.copyOf in the compact constructor.
        final java.util.HashMap<KekRef, Instant> next = new java.util.HashMap<>(entries);
        next.put(ref, retentionUntil);
        return new RetiredReferences(next);
    }

    /**
     * Return the subset of retired refs whose retention-until is on or before {@code now} and whose
     * liveness count is zero per {@code livenessProbe}. The probe is consulted with each candidate
     * {@link KekRef}; returning a non-zero value (typically backed by {@link LivenessWitness} in
     * production) excludes that ref from GC eligibility per the R33a grace-period invariant.
     *
     * @param now wall-clock instant against which retention-until is compared
     * @param livenessProbe maps a retired ref to its current liveness count (0 = unreferenced)
     * @throws NullPointerException if any argument is null
     */
    public Set<KekRef> eligibleForGc(Instant now, ToLongFunction<KekRef> livenessProbe) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(livenessProbe, "livenessProbe");
        if (entries.isEmpty()) {
            // Canonical-empty fast path so callers can rely on Set.of() identity semantics.
            return Set.of();
        }
        final java.util.HashSet<KekRef> eligible = new java.util.HashSet<>();
        for (Map.Entry<KekRef, Instant> e : entries.entrySet()) {
            // Retention "on or before now" — equal counts as elapsed.
            if (e.getValue().isAfter(now)) {
                continue;
            }
            // R33a grace-period invariant: liveness witness must report zero before GC eligible.
            if (livenessProbe.applyAsLong(e.getKey()) > 0L) {
                continue;
            }
            eligible.add(e.getKey());
        }
        return Set.copyOf(eligible);
    }
}
