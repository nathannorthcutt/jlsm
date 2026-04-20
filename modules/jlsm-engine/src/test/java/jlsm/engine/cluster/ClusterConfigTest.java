package jlsm.engine.cluster;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClusterConfig} record validation and builder defaults.
 */
class ClusterConfigTest {

    @Test
    void builderDefaults() {
        var config = ClusterConfig.builder().build();
        assertEquals(ClusterConfig.DEFAULT_GRACE_PERIOD, config.gracePeriod());
        assertEquals(ClusterConfig.DEFAULT_PROTOCOL_PERIOD, config.protocolPeriod());
        assertEquals(ClusterConfig.DEFAULT_PING_TIMEOUT, config.pingTimeout());
        assertEquals(ClusterConfig.DEFAULT_INDIRECT_PROBES, config.indirectProbes());
        assertEquals(ClusterConfig.DEFAULT_PHI_THRESHOLD, config.phiThreshold());
        assertEquals(ClusterConfig.DEFAULT_CONSENSUS_QUORUM_PERCENT,
                config.consensusQuorumPercent());
    }

    @Test
    void builderOverrides() {
        var config = ClusterConfig.builder().gracePeriod(Duration.ofMinutes(5))
                .protocolPeriod(Duration.ofSeconds(2)).pingTimeout(Duration.ofMillis(250))
                .indirectProbes(5).phiThreshold(10.0).consensusQuorumPercent(50).build();
        assertEquals(Duration.ofMinutes(5), config.gracePeriod());
        assertEquals(Duration.ofSeconds(2), config.protocolPeriod());
        assertEquals(Duration.ofMillis(250), config.pingTimeout());
        assertEquals(5, config.indirectProbes());
        assertEquals(10.0, config.phiThreshold());
        assertEquals(50, config.consensusQuorumPercent());
    }

    @Test
    void nullGracePeriodThrows() {
        assertThrows(NullPointerException.class, () -> ClusterConfig.builder().gracePeriod(null));
    }

    @Test
    void nullProtocolPeriodThrows() {
        assertThrows(NullPointerException.class,
                () -> ClusterConfig.builder().protocolPeriod(null));
    }

    @Test
    void nullPingTimeoutThrows() {
        assertThrows(NullPointerException.class, () -> ClusterConfig.builder().pingTimeout(null));
    }

    @Test
    void zeroGracePeriodThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().gracePeriod(Duration.ZERO).build());
    }

    @Test
    void negativeGracePeriodThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().gracePeriod(Duration.ofSeconds(-1)).build());
    }

    @Test
    void negativeIndirectProbesThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().indirectProbes(-1).build());
    }

    @Test
    void zeroPhiThresholdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().phiThreshold(0.0).build());
    }

    @Test
    void negativePhiThresholdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().phiThreshold(-1.0).build());
    }

    @Test
    void quorumPercentZeroThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().consensusQuorumPercent(0).build());
    }

    @Test
    void quorumPercent101Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().consensusQuorumPercent(101).build());
    }

    @Test
    void quorumPercentBoundary1() {
        var config = ClusterConfig.builder().consensusQuorumPercent(1).build();
        assertEquals(1, config.consensusQuorumPercent());
    }

    @Test
    void quorumPercentBoundary100() {
        var config = ClusterConfig.builder().consensusQuorumPercent(100).build();
        assertEquals(100, config.consensusQuorumPercent());
    }

    @Test
    void indirectProbesZeroAllowed() {
        var config = ClusterConfig.builder().indirectProbes(0).build();
        assertEquals(0, config.indirectProbes());
    }

    // --- New WU-4 fields: consensus round + expander graph + cut detector ---

    @Test
    void defaultConsensusRoundTimeoutIsTwoSeconds() {
        var config = ClusterConfig.builder().build();
        assertEquals(Duration.ofSeconds(2), config.consensusRoundTimeout());
    }

    @Test
    void defaultExpanderGraphDegreeIsAuto() {
        var config = ClusterConfig.builder().build();
        assertEquals(0, config.expanderGraphDegree(),
                "default expander graph degree must be 0 (auto)");
    }

    @Test
    void defaultCutDetectorLowWatermarkIsTwo() {
        var config = ClusterConfig.builder().build();
        assertEquals(2, config.cutDetectorLowWatermark());
    }

    @Test
    void defaultCutDetectorHighWatermarkIsAuto() {
        var config = ClusterConfig.builder().build();
        assertEquals(0, config.cutDetectorHighWatermark(),
                "default cut detector high watermark must be 0 (auto)");
    }

    @Test
    void builderOverridesNewFields() {
        var config = ClusterConfig.builder().consensusRoundTimeout(Duration.ofSeconds(5))
                .expanderGraphDegree(7).cutDetectorLowWatermark(3).cutDetectorHighWatermark(9)
                .build();
        assertEquals(Duration.ofSeconds(5), config.consensusRoundTimeout());
        assertEquals(7, config.expanderGraphDegree());
        assertEquals(3, config.cutDetectorLowWatermark());
        assertEquals(9, config.cutDetectorHighWatermark());
    }

    @Test
    void nullConsensusRoundTimeoutThrows() {
        assertThrows(NullPointerException.class,
                () -> ClusterConfig.builder().consensusRoundTimeout(null));
    }

    @Test
    void zeroConsensusRoundTimeoutThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().consensusRoundTimeout(Duration.ZERO).build());
    }

    @Test
    void negativeConsensusRoundTimeoutThrows() {
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.builder()
                .consensusRoundTimeout(Duration.ofSeconds(-1)).build());
    }

    @Test
    void negativeExpanderGraphDegreeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().expanderGraphDegree(-1).build());
    }

    @Test
    void zeroCutDetectorLowWatermarkThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().cutDetectorLowWatermark(0).build());
    }

    @Test
    void negativeCutDetectorLowWatermarkThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().cutDetectorLowWatermark(-1).build());
    }

    @Test
    void negativeCutDetectorHighWatermarkThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ClusterConfig.builder().cutDetectorHighWatermark(-1).build());
    }

    @Test
    void highWatermarkBelowLowWatermarkThrows() {
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.builder()
                .cutDetectorLowWatermark(5).cutDetectorHighWatermark(3).build());
    }

    @Test
    void highWatermarkEqualToLowWatermarkThrows() {
        assertThrows(IllegalArgumentException.class, () -> ClusterConfig.builder()
                .cutDetectorLowWatermark(5).cutDetectorHighWatermark(5).build());
    }

    @Test
    void autoHighWatermarkCompatibleWithAnyLowWatermark() {
        // When cutDetectorHighWatermark == 0 (auto), low watermark constraint does not apply
        var config = ClusterConfig.builder().cutDetectorLowWatermark(10).cutDetectorHighWatermark(0)
                .build();
        assertEquals(10, config.cutDetectorLowWatermark());
        assertEquals(0, config.cutDetectorHighWatermark());
    }
}
