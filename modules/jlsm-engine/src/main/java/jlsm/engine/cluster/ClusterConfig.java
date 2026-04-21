package jlsm.engine.cluster;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for cluster behavior including membership protocol tuning and timeouts.
 *
 * <p>
 * Contract: Immutable configuration value created via {@link Builder}. All durations must be
 * positive. The {@code consensusQuorumPercent} must be in range [1, 100]. Sensible defaults are
 * provided by the builder.
 *
 * <p>
 * Governed by: {@code .decisions/cluster-membership-protocol/adr.md},
 * {@code .decisions/rebalancing-grace-period-strategy/adr.md}
 *
 * @param gracePeriod time to wait before treating a departed node as permanent
 * @param protocolPeriod interval between membership protocol rounds
 * @param pingTimeout timeout for a single ping/ack exchange
 * @param indirectProbes number of indirect probe paths for failure detection
 * @param phiThreshold phi accrual failure detector threshold
 * @param consensusQuorumPercent percentage of nodes required for view-change consensus
 * @param consensusRoundTimeout maximum duration a RAPID consensus round may remain pending before
 *            being abandoned
 * @param expanderGraphDegree out-degree of the expander-graph monitoring overlay (0 = auto, derived
 *            from the live-member count at rebuild time)
 * @param cutDetectorLowWatermark minimum number of independent observer reports required before a
 *            cut detector aggregates their suspicions into a single round
 * @param cutDetectorHighWatermark upper bound on observer reports considered for a single
 *            aggregated round (0 = auto, derived from the degree at rebuild time)
 *
 * @spec engine.clustering.R2 — builder with documented defaults for grace period, protocol period,
 *       ping timeout, indirect probe count, phi threshold, and consensus quorum percent
 * @spec engine.clustering.R3 — reject zero/negative gracePeriod with IllegalArgumentException
 * @spec engine.clustering.R4 — reject consensusQuorumPercent outside (0, 100] with IAE
 * @spec engine.clustering.R5 — reject non-finite or non-positive phiThreshold with IAE
 * @spec engine.clustering.R6 — reject zero/negative pingTimeout with IAE
 * @spec engine.clustering.R7 — reject negative indirectProbes with IAE; zero is valid
 */
public record ClusterConfig(Duration gracePeriod, Duration protocolPeriod, Duration pingTimeout,
        int indirectProbes, double phiThreshold, int consensusQuorumPercent,
        Duration consensusRoundTimeout, int expanderGraphDegree, int cutDetectorLowWatermark,
        int cutDetectorHighWatermark) {

    /** Default grace period: 2 minutes. */
    public static final Duration DEFAULT_GRACE_PERIOD = Duration.ofMinutes(2);

    /** Default protocol period: 1 second. */
    public static final Duration DEFAULT_PROTOCOL_PERIOD = Duration.ofSeconds(1);

    /** Default ping timeout: 500 milliseconds. */
    public static final Duration DEFAULT_PING_TIMEOUT = Duration.ofMillis(500);

    /** Default indirect probes: 3. */
    public static final int DEFAULT_INDIRECT_PROBES = 3;

    /** Default phi threshold: 8.0. */
    public static final double DEFAULT_PHI_THRESHOLD = 8.0;

    /** Default consensus quorum: 75%. */
    public static final int DEFAULT_CONSENSUS_QUORUM_PERCENT = 75;

    /** Default consensus round timeout: 2 seconds. */
    public static final Duration DEFAULT_CONSENSUS_ROUND_TIMEOUT = Duration.ofSeconds(2);

    /** Default expander-graph degree (0 = auto — derived from live-member count at rebuild). */
    public static final int DEFAULT_EXPANDER_GRAPH_DEGREE = 0;

    /** Default cut-detector low watermark (minimum observer reports before aggregation). */
    public static final int DEFAULT_CUT_DETECTOR_LOW_WATERMARK = 2;

    /** Default cut-detector high watermark (0 = auto — derived from degree at rebuild). */
    public static final int DEFAULT_CUT_DETECTOR_HIGH_WATERMARK = 0;

    public ClusterConfig {
        Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        Objects.requireNonNull(protocolPeriod, "protocolPeriod must not be null");
        Objects.requireNonNull(pingTimeout, "pingTimeout must not be null");
        Objects.requireNonNull(consensusRoundTimeout, "consensusRoundTimeout must not be null");
        if (gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("gracePeriod must be positive");
        }
        if (protocolPeriod.isNegative() || protocolPeriod.isZero()) {
            throw new IllegalArgumentException("protocolPeriod must be positive");
        }
        if (pingTimeout.isNegative() || pingTimeout.isZero()) {
            throw new IllegalArgumentException("pingTimeout must be positive");
        }
        if (indirectProbes < 0) {
            throw new IllegalArgumentException(
                    "indirectProbes must be non-negative, got: " + indirectProbes);
        }
        if (!(phiThreshold > 0.0) || !Double.isFinite(phiThreshold)) {
            throw new IllegalArgumentException(
                    "phiThreshold must be a finite positive value, got: " + phiThreshold);
        }
        if (consensusQuorumPercent < 1 || consensusQuorumPercent > 100) {
            throw new IllegalArgumentException(
                    "consensusQuorumPercent must be in [1, 100], got: " + consensusQuorumPercent);
        }
        if (consensusRoundTimeout.isNegative() || consensusRoundTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "consensusRoundTimeout must be positive, got: " + consensusRoundTimeout);
        }
        if (expanderGraphDegree < 0) {
            throw new IllegalArgumentException(
                    "expanderGraphDegree must be non-negative, got: " + expanderGraphDegree);
        }
        if (cutDetectorLowWatermark < 1) {
            throw new IllegalArgumentException(
                    "cutDetectorLowWatermark must be >= 1, got: " + cutDetectorLowWatermark);
        }
        if (cutDetectorHighWatermark < 0) {
            throw new IllegalArgumentException(
                    "cutDetectorHighWatermark must be >= 0, got: " + cutDetectorHighWatermark);
        }
        if (cutDetectorHighWatermark > 0 && cutDetectorHighWatermark <= cutDetectorLowWatermark) {
            throw new IllegalArgumentException(
                    "cutDetectorHighWatermark must be 0 (auto) or > cutDetectorLowWatermark, got: "
                            + cutDetectorHighWatermark + " (low=" + cutDetectorLowWatermark + ")");
        }
    }

    /**
     * Returns a new builder with all defaults pre-populated.
     *
     * @return a new builder; never null
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ClusterConfig} with sensible defaults.
     */
    public static final class Builder {

        private Duration gracePeriod = DEFAULT_GRACE_PERIOD;
        private Duration protocolPeriod = DEFAULT_PROTOCOL_PERIOD;
        private Duration pingTimeout = DEFAULT_PING_TIMEOUT;
        private int indirectProbes = DEFAULT_INDIRECT_PROBES;
        private double phiThreshold = DEFAULT_PHI_THRESHOLD;
        private int consensusQuorumPercent = DEFAULT_CONSENSUS_QUORUM_PERCENT;
        private Duration consensusRoundTimeout = DEFAULT_CONSENSUS_ROUND_TIMEOUT;
        private int expanderGraphDegree = DEFAULT_EXPANDER_GRAPH_DEGREE;
        private int cutDetectorLowWatermark = DEFAULT_CUT_DETECTOR_LOW_WATERMARK;
        private int cutDetectorHighWatermark = DEFAULT_CUT_DETECTOR_HIGH_WATERMARK;

        private Builder() {
        }

        public Builder gracePeriod(Duration gracePeriod) {
            this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
            return this;
        }

        public Builder protocolPeriod(Duration protocolPeriod) {
            this.protocolPeriod = Objects.requireNonNull(protocolPeriod,
                    "protocolPeriod must not be null");
            return this;
        }

        public Builder pingTimeout(Duration pingTimeout) {
            this.pingTimeout = Objects.requireNonNull(pingTimeout, "pingTimeout must not be null");
            return this;
        }

        public Builder indirectProbes(int indirectProbes) {
            this.indirectProbes = indirectProbes;
            return this;
        }

        public Builder phiThreshold(double phiThreshold) {
            this.phiThreshold = phiThreshold;
            return this;
        }

        public Builder consensusQuorumPercent(int consensusQuorumPercent) {
            this.consensusQuorumPercent = consensusQuorumPercent;
            return this;
        }

        public Builder consensusRoundTimeout(Duration consensusRoundTimeout) {
            this.consensusRoundTimeout = Objects.requireNonNull(consensusRoundTimeout,
                    "consensusRoundTimeout must not be null");
            return this;
        }

        public Builder expanderGraphDegree(int expanderGraphDegree) {
            this.expanderGraphDegree = expanderGraphDegree;
            return this;
        }

        public Builder cutDetectorLowWatermark(int cutDetectorLowWatermark) {
            this.cutDetectorLowWatermark = cutDetectorLowWatermark;
            return this;
        }

        public Builder cutDetectorHighWatermark(int cutDetectorHighWatermark) {
            this.cutDetectorHighWatermark = cutDetectorHighWatermark;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return a new immutable {@link ClusterConfig}; never null
         */
        public ClusterConfig build() {
            return new ClusterConfig(gracePeriod, protocolPeriod, pingTimeout, indirectProbes,
                    phiThreshold, consensusQuorumPercent, consensusRoundTimeout,
                    expanderGraphDegree, cutDetectorLowWatermark, cutDetectorHighWatermark);
        }
    }
}
